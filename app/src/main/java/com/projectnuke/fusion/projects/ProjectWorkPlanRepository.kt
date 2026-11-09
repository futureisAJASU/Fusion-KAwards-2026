package com.projectnuke.fusion.projects

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectDecisionWriteResult
import com.projectnuke.fusion.data.ProjectTaskInsertResult
import com.projectnuke.fusion.tasks.ProjectTaskDraft
import com.projectnuke.fusion.tasks.ProjectTaskPolicy
import com.projectnuke.fusion.tasks.ProjectTaskPreparation
import com.projectnuke.fusion.tasks.ProjectTaskProposal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ProjectWorkPlanWithSteps(
    val plan: ProjectWorkPlanEntity,
    val steps: List<ProjectWorkPlanStepEntity>,
)

data class ProjectWorkPlanAttempt(
    val planId: String,
    val stepId: String,
    val attemptId: String,
    val projectId: Long,
    val type: ProjectWorkPlanStepType,
    val title: String,
    val instruction: String,
    val planTitle: String,
    val goal: String,
    val desiredOutput: String,
    val constraintText: String,
)

sealed interface ProjectWorkPlanCreateResult {
    data class Created(val planId: String) : ProjectWorkPlanCreateResult
    data object InvalidProposal : ProjectWorkPlanCreateResult
    data object ProjectUnavailable : ProjectWorkPlanCreateResult
    data object ActivePlanAlreadyExists : ProjectWorkPlanCreateResult
}

enum class ProjectWorkPlanAttemptFailureKind { GENERATION, STALE_SOURCE, ACCESS, PERSISTENCE }

enum class ProjectWorkPlanAttemptResult { APPLIED, STALE, ATTEMPT_LIMIT, INVALID }

sealed interface ProjectWorkPlanTaskCreateResult {
    data class Created(val taskId: Long) : ProjectWorkPlanTaskCreateResult
    data object Invalid : ProjectWorkPlanTaskCreateResult
    data object SensitiveContent : ProjectWorkPlanTaskCreateResult
    data object CapacityReached : ProjectWorkPlanTaskCreateResult
    data object StaleAttempt : ProjectWorkPlanTaskCreateResult
}

sealed interface ProjectWorkPlanDecisionCreateResult {
    data class Created(val decisionId: String) : ProjectWorkPlanDecisionCreateResult
    data object Invalid : ProjectWorkPlanDecisionCreateResult
    data object CapacityReached : ProjectWorkPlanDecisionCreateResult
    data object ProjectUnavailable : ProjectWorkPlanDecisionCreateResult
    data object StaleAttempt : ProjectWorkPlanDecisionCreateResult
}

class ProjectWorkPlanRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /**
     * Issue 5: managed-attachment root for the canonical attachment
     * contract at settlement. UI-owned construction sites pass the app
     * attachment directory; null falls back to a direct existence check
     * while reference liveness stays enforced at the coordinator gate.
     */
    private val attachmentRoot: java.io.File? = null,
) {
    private val dao get() = database.projectWorkPlanDao()

    suspend fun create(
        projectId: Long,
        proposal: ProjectWorkPlanProposal,
        planId: String = newId(),
        stepIds: List<String> = proposal.steps.map { newId() },
        now: Long = clock(),
    ): ProjectWorkPlanCreateResult = withContext(Dispatchers.IO) {
        if (projectId <= 0L || planId.isBlank() || !ProjectWorkPlanProposalPolicy.validateEditedProposal(proposal) ||
            stepIds.size != proposal.steps.size || stepIds.any(String::isBlank) || stepIds.distinct().size != stepIds.size
        ) return@withContext ProjectWorkPlanCreateResult.InvalidProposal
        if (database.projectDao().getProjectById(projectId) == null) {
            return@withContext ProjectWorkPlanCreateResult.ProjectUnavailable
        }
        val plan = ProjectWorkPlanEntity(
            id = planId,
            projectId = projectId,
            title = proposal.title.trim(),
            goal = proposal.goal.trim(),
            desiredOutput = proposal.desiredOutput.trim(),
            constraintText = proposal.constraintText.trim(),
            status = ProjectWorkPlanStatus.ACTIVE.name,
            currentStepPosition = 0,
            archivedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        val steps = proposal.steps.mapIndexed { index, step ->
            ProjectWorkPlanStepEntity(
                id = stepIds[index],
                planId = planId,
                position = index,
                title = step.title.trim(),
                instruction = step.instruction.trim(),
                type = step.type.name,
                state = if (index == 0) ProjectWorkPlanStepState.READY.name else ProjectWorkPlanStepState.PENDING.name,
                createdAt = now,
                updatedAt = now,
            )
        }
        database.withTransaction {
            if (database.projectDao().getProjectById(projectId) == null) {
                return@withTransaction ProjectWorkPlanCreateResult.ProjectUnavailable
            }
            if (dao.getActivePlan(projectId) != null) {
                return@withTransaction ProjectWorkPlanCreateResult.ActivePlanAlreadyExists
            }
            dao.insertPlanWithSteps(plan, steps)
            ProjectWorkPlanCreateResult.Created(planId)
        }
    }

    suspend fun plans(projectId: Long, includeArchived: Boolean = false, limit: Int = MAX_PLANS): List<ProjectWorkPlanEntity> =
        withContext(Dispatchers.IO) {
            require(projectId > 0L)
            val bounded = limit.coerceIn(1, MAX_PLANS)
            if (includeArchived) {
                dao.getPlans(projectId, bounded)
            } else {
                // Archive filtering occurs in SQL before LIMIT so archived rows
                // never occupy the bounded result set (C06).
                dao.getPlansExcludingArchived(projectId, bounded)
            }
        }

    /** Observational read: never releases a live RUNNING attempt (C05). */
    suspend fun activePlan(projectId: Long): ProjectWorkPlanWithSteps? = withContext(Dispatchers.IO) {
        require(projectId > 0L)
        val plan = dao.getActivePlan(projectId) ?: return@withContext null
        ProjectWorkPlanWithSteps(plan = dao.getPlan(plan.id, projectId) ?: return@withContext null, steps = dao.getSteps(plan.id))
    }

    /**
     * Explicit stale-attempt recovery for process-interruption startup only.
     * Reads must use [load]/[activePlan]; this is the single ownership
     * transition that may release RUNNING steps, and callers must hold
     * evidence that no live attempt owner remains.
     */
    suspend fun recoverPlan(planId: String, projectId: Long): ProjectWorkPlanWithSteps? =
        withContext(Dispatchers.IO) {
            require(planId.isNotBlank() && projectId > 0L)
            var plan = dao.getPlan(planId, projectId) ?: return@withContext null
            dao.recoverRunningSteps(planId, clock())
            plan = dao.getPlan(planId, projectId) ?: return@withContext null
            ProjectWorkPlanWithSteps(plan, dao.getSteps(planId))
        }

    /** Observational read by default; recovery requires [recoverPlan] (C05). */
    suspend fun load(planId: String, projectId: Long, recover: Boolean = false): ProjectWorkPlanWithSteps? =
        withContext(Dispatchers.IO) {
            require(planId.isNotBlank() && projectId > 0L)
            var plan = dao.getPlan(planId, projectId) ?: return@withContext null
            if (recover) {
                dao.recoverRunningSteps(planId, clock())
                plan = dao.getPlan(planId, projectId) ?: return@withContext null
            }
            ProjectWorkPlanWithSteps(plan, dao.getSteps(planId))
        }

    suspend fun updatePlanText(
        planId: String,
        projectId: Long,
        title: String,
        goal: String,
        desiredOutput: String,
        constraintText: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanedTitle = title.trim().replace(Regex("\\s+"), " ")
        val cleanedGoal = goal.trim()
        if (cleanedTitle.isBlank() || cleanedTitle.length > ProjectWorkPlanProposalPolicy.MAX_PLAN_TITLE_CHARS ||
            cleanedGoal.isBlank() || cleanedGoal.length > ProjectWorkPlanProposalPolicy.MAX_GOAL_CHARS ||
            desiredOutput.length > ProjectWorkPlanProposalPolicy.MAX_DESIRED_OUTPUT_CHARS ||
            constraintText.length > ProjectWorkPlanProposalPolicy.MAX_CONSTRAINT_CHARS
        ) return@withContext false
        database.withTransaction {
            if (dao.getSteps(planId).any { it.state == ProjectWorkPlanStepState.RUNNING.name || it.activeAttemptId != null }) {
                return@withTransaction false
            }
            dao.updatePlanText(planId, projectId, cleanedTitle, cleanedGoal, desiredOutput.trim(), constraintText.trim(), clock()) == 1
        }
    }

    suspend fun updateStepText(planId: String, stepId: String, title: String, instruction: String): Boolean =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.trim().replace(Regex("\\s+"), " ")
            val cleanInstruction = instruction.trim()
            if (cleanTitle.isBlank() || cleanTitle.length > ProjectWorkPlanProposalPolicy.MAX_STEP_TITLE_CHARS ||
                cleanInstruction.isBlank() || cleanInstruction.length > ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS
            ) return@withContext false
            database.withTransaction {
                val plan = dao.getPlanById(planId) ?: return@withTransaction false
                if (plan.status !in setOf(ProjectWorkPlanStatus.ACTIVE.name, ProjectWorkPlanStatus.PAUSED.name) || plan.archivedAt != null) {
                    return@withTransaction false
                }
                dao.updateStepText(planId, stepId, cleanTitle, cleanInstruction, clock()) == 1
            }
        }

    suspend fun reorder(planId: String, projectId: Long, orderedStepIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction false
            if (plan.status !in setOf(ProjectWorkPlanStatus.ACTIVE.name, ProjectWorkPlanStatus.PAUSED.name) || plan.archivedAt != null) return@withTransaction false
            val steps = dao.getSteps(planId)
            if (steps.size != orderedStepIds.size || steps.map { it.id }.toSet() != orderedStepIds.toSet() ||
                steps.any { it.state !in setOf(ProjectWorkPlanStepState.PENDING.name, ProjectWorkPlanStepState.READY.name, ProjectWorkPlanStepState.FAILED.name) || it.activeAttemptId != null }
            ) return@withTransaction false
            val now = clock()
            orderedStepIds.forEachIndexed { index, id -> dao.updatePosition(planId, id, -(index + 1), now) }
            orderedStepIds.forEachIndexed { index, id ->
                dao.updatePosition(planId, id, index, now)
                dao.updateStepState(planId, id, if (index == 0) ProjectWorkPlanStepState.READY.name else ProjectWorkPlanStepState.PENDING.name, now)
            }
            dao.updateProgress(
                planId,
                projectId,
                0,
                if (plan.status == ProjectWorkPlanStatus.PAUSED.name) ProjectWorkPlanStatus.PAUSED.name
                    else ProjectWorkPlanStatus.ACTIVE.name,
                now,
            )
            true
        }
    }

    suspend fun startStep(planId: String, stepId: String, projectId: Long): ProjectWorkPlanAttempt? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction null
            if (plan.status != ProjectWorkPlanStatus.ACTIVE.name || plan.archivedAt != null) return@withTransaction null
            val step = dao.getStep(stepId, planId) ?: return@withTransaction null
            val type = ProjectWorkPlanStepType.fromModelValue(step.type) ?: return@withTransaction null
            if (step.position != plan.currentStepPosition || step.attemptCount >= MAX_STEP_ATTEMPTS || step.linkedTaskId != null) return@withTransaction null
            if (step.state !in setOf(ProjectWorkPlanStepState.READY.name, ProjectWorkPlanStepState.FAILED.name, ProjectWorkPlanStepState.REVIEW.name)) return@withTransaction null
            val attemptId = newId()
            val now = clock()
            if (dao.acquireStep(planId, stepId, plan.currentStepPosition, attemptId, now) != 1) return@withTransaction null
            // Issue 1: a retry supersedes prior attempts' generation
            // witnesses so a stale request can never settle afterwards.
            database.projectWorkPlanGenerationWitnessDao().deleteByStep(planId, stepId)
            ProjectWorkPlanAttempt(
                planId = planId,
                stepId = stepId,
                attemptId = attemptId,
                projectId = projectId,
                type = type,
                title = step.title,
                instruction = step.instruction,
                planTitle = plan.title,
                goal = plan.goal,
                desiredOutput = plan.desiredOutput,
                constraintText = plan.constraintText,
            )
        }
    }

    suspend fun finishAttempt(
        attempt: ProjectWorkPlanAttempt,
        resultKind: String? = null,
        resultId: String? = null,
    ): ProjectWorkPlanAttemptResult = withContext(Dispatchers.IO) {
        if ((resultKind == null) != (resultId == null) || resultKind?.length?.let { it !in 1..32 } == true ||
            resultId?.length?.let { it !in 1..180 } == true
        ) return@withContext ProjectWorkPlanAttemptResult.INVALID
        database.withTransaction {
            finishAttemptInTransaction(attempt.planId, attempt.stepId, attempt.attemptId, resultKind, resultId)
        }
    }

    suspend fun recordAssistantResult(
        attempt: ProjectWorkPlanAttempt,
        conversationId: Long,
        userMessageId: Long,
        assistantMessageId: Long,
        submissionToken: String? = null,
        requestId: String? = null,
    ): ProjectWorkPlanAttemptResult = settleSpecializedResult(
        ProjectWorkPlanResultReceipt(
            planId = attempt.planId,
            stepId = attempt.stepId,
            attemptId = attempt.attemptId,
            projectId = attempt.projectId,
            stepType = attempt.type,
            resultKind = ProjectWorkPlanResultReceipt.KIND_MESSAGE,
            resultId = assistantMessageId.toString(),
            conversationId = conversationId,
            userMessageId = userMessageId,
            submissionToken = submissionToken,
            requestId = requestId,
        ),
    )

    /**
     * The single settlement authority for specialized result receipts. The
     * [ProjectWorkPlanReceiptPolicy] validates plan/step/attempt/project
     * ownership against durable rows first, then the result-specific durable
     * identity (message authority for MESSAGE), and only then commits the
     * bounded result reference. Stale, cancelled, retried, duplicate or
     * archived-plan callbacks fail closed without mutating the step.
     */
    suspend fun settleSpecializedResult(
        receipt: ProjectWorkPlanResultReceipt,
    ): ProjectWorkPlanAttemptResult = withContext(Dispatchers.IO) {
        // Finding D: SELECTION/EXTRACTION never settle through the generic
        // path with an opaque id. They settle only with their durable
        // details row via settleSelectionResult/settleExtractionResult.
        if (receipt.resultKind == ProjectWorkPlanResultReceipt.KIND_SELECTION ||
            receipt.resultKind == ProjectWorkPlanResultReceipt.KIND_EXTRACTION
        ) {
            return@withContext ProjectWorkPlanAttemptResult.INVALID
        }
        val result = database.withTransaction {
            val plan = dao.getPlan(receipt.planId, receipt.projectId)
            val step = dao.getStep(receipt.stepId, receipt.planId)
            val verdict = ProjectWorkPlanReceiptPolicy.validate(
                receipt = receipt,
                plan = plan?.let {
                    ProjectWorkPlanReceiptPlanState(
                        projectId = it.projectId,
                        status = it.status,
                        archivedAt = it.archivedAt,
                    )
                },
                step = step?.let {
                    ProjectWorkPlanReceiptStepState(
                        planId = it.planId,
                        type = it.type,
                        state = it.state,
                        activeAttemptId = it.activeAttemptId,
                        attemptCount = it.attemptCount,
                        linkedTaskId = it.linkedTaskId,
                    )
                },
            )
            if (verdict !is ProjectWorkPlanReceiptVerdict.Accept) {
                val rejection = (verdict as ProjectWorkPlanReceiptVerdict.Reject).reason
                return@withTransaction ProjectWorkPlanReceiptPolicy.attemptResultFor(rejection)
            }
            if (receipt.resultKind == ProjectWorkPlanResultReceipt.KIND_MESSAGE &&
                !messageAuthorityHolds(receipt)
            ) {
                return@withTransaction ProjectWorkPlanAttemptResult.STALE
            }
            finishAttemptInTransaction(
                planId = receipt.planId,
                stepId = receipt.stepId,
                attemptId = receipt.attemptId,
                resultKind = receipt.resultKind,
                resultId = receipt.resultId,
            )
        }
        // Issue 1: the generation witness served its purpose once the
        // attempt settles terminally. STALE keeps it for a later owned
        // answer; terminal results clear it. Cancellation propagates.
        if (receipt.resultKind == ProjectWorkPlanResultReceipt.KIND_MESSAGE &&
            result != ProjectWorkPlanAttemptResult.STALE
        ) {
            try {
                database.projectWorkPlanGenerationWitnessDao().deleteByAttempt(receipt.attemptId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best-effort terminal cleanup; the row is superseded on
                // retry by the by-step cleanup in startStep.
            }
        }
        result
    }

    private suspend fun messageAuthorityHolds(receipt: ProjectWorkPlanResultReceipt): Boolean {
        val conversationId = receipt.conversationId ?: return false
        val userMessageId = receipt.userMessageId ?: return false
        val assistantMessageId = receipt.resultId.toLongOrNull() ?: return false
        if (database.projectDao().getProjectIdForConversation(conversationId) != receipt.projectId) return false
        val userMessage = database.chatDao().getMessageIdentityById(userMessageId)
        val assistantMessage = database.chatDao().getMessageIdentityById(assistantMessageId)
        if (userMessage?.conversationId != conversationId || userMessage.role != "user" ||
            assistantMessage?.conversationId != conversationId || assistantMessage.role != "assistant"
        ) {
            return false
        }
        // Finding B: the exact committed submission. When the witness row
        // is still present it must resolve to this exact user message;
        // the witness is deleted after successful reconcile, in which case
        // the durable origin proof below remains authoritative.
        receipt.submissionToken?.let { token ->
            val witness = database.submissionCommitWitnessDao().get(token)
            if (witness != null &&
                (witness.committedConversationId != conversationId || witness.userMessageId != userMessageId)
            ) {
                return false
            }
        }
        // Issue 1: exact generation-request proof. The durable generation
        // witness must link this attempt to the receipt's requestId and to
        // exactly this assistant. An answer from a retry/regeneration under
        // another requestId — even for the same user message — or a forged
        // requestId is rejected here, independently of any caller claim.
        // The version group proves user-turn ownership, never request
        // identity, so it is not consulted for this decision.
        val generationWitness = database.projectWorkPlanGenerationWitnessDao().getByAttempt(receipt.attemptId)
        if (!ProjectWorkPlanRequestWitnessPolicy.receiptMatchesWitness(
                attemptId = receipt.attemptId,
                requestId = receipt.requestId,
                conversationId = conversationId,
                userMessageId = userMessageId,
                assistantMessageId = assistantMessageId,
                witness = generationWitness?.let {
                    ProjectWorkPlanRequestWitnessPolicy.GenerationWitness(
                        attemptId = it.attemptId,
                        requestId = it.requestId,
                        conversationId = it.conversationId,
                        userMessageId = it.userMessageId,
                        assistantMessageId = it.assistantMessageId,
                    )
                },
            )
        ) {
            return false
        }
        // Finding B: direct response ownership as defense in depth. A
        // recorded version group owner is authoritative; otherwise the
        // nearest preceding user message on the durable timeline must be
        // exactly the committed Work Plan message.
        val groupOwner = database.responseVersionCommitWitnessDao()
            .getByMessageId(assistantMessageId)?.groupId
        val timeline = database.chatDao().getMessagesForConversation(conversationId).map {
            ProjectWorkPlanMessageOwnership.TimelineRef(it.id, it.role)
        }
        return ProjectWorkPlanMessageOwnership.isOwnedAssistant(
            timeline = timeline,
            groupOwnerByAssistantId = if (groupOwner == null) emptyMap() else mapOf(assistantMessageId to groupOwner),
            assistantMessageId = assistantMessageId,
            userMessageId = userMessageId,
        )
    }

    /**
     * Issue 1: records the exact generation request created for the
     * committed Work Plan submission. First wins: an existing binding for
     * this attempt is kept (idempotent when identical), and a different
     * request never replaces it. Returns false when no binding was (or
     * could be) established; only a SQLite conflict for another request or
     * an invalid shape fails — coroutine cancellation propagates.
     */
    suspend fun noteWorkPlanGenerationAttempt(
        attempt: ProjectWorkPlanAttempt,
        conversationId: Long,
        userMessageId: Long,
        requestId: String,
        now: Long = clock(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (attempt.planId.isBlank() || attempt.stepId.isBlank() || attempt.attemptId.isBlank() ||
            attempt.projectId <= 0L || conversationId <= 0L || userMessageId <= 0L || requestId.isBlank()
        ) {
            return@withContext false
        }
        try {
            database.projectWorkPlanGenerationWitnessDao().insert(
                com.projectnuke.fusion.data.ProjectWorkPlanGenerationWitnessEntity(
                    attemptId = attempt.attemptId,
                    requestId = requestId,
                    planId = attempt.planId,
                    stepId = attempt.stepId,
                    projectId = attempt.projectId,
                    conversationId = conversationId,
                    userMessageId = userMessageId,
                    assistantMessageId = null,
                    createdAt = now,
                ),
            )
            true
        } catch (conflict: android.database.sqlite.SQLiteConstraintException) {
            val existing = database.projectWorkPlanGenerationWitnessDao().getByAttempt(attempt.attemptId)
            existing != null && existing.requestId == requestId &&
                existing.conversationId == conversationId && existing.userMessageId == userMessageId
        }
    }

    /**
     * Issue 1: links the exact assistant persisted by the bound request.
     * Only the bound request's first persisted answer links; retries under
     * other requests and forged pairs change nothing.
     */
    suspend fun noteWorkPlanAssistantPersisted(
        attemptId: String,
        requestId: String,
        assistantMessageId: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (attemptId.isBlank() || requestId.isBlank() || assistantMessageId <= 0L) return@withContext false
        database.projectWorkPlanGenerationWitnessDao()
            .linkAssistant(attemptId, requestId, assistantMessageId) == 1
    }

    /**
     * Finding D: durable SELECTION settlement. The receipt id must be the
     * hash over the exact entries; every identity is revalidated against
     * its durable owner inside this transaction. Inaccessible sources fail
     * closed (STALE, retryable) and never settle. The details row and the
     * step transition commit atomically.
     */
    suspend fun settleSelectionResult(
        receipt: ProjectWorkPlanResultReceipt,
        entries: List<ProjectWorkPlanResultDetails.SourceEntry>,
        now: Long = clock(),
    ): ProjectWorkPlanAttemptResult = withContext(Dispatchers.IO) {
        if (receipt.resultKind != ProjectWorkPlanResultReceipt.KIND_SELECTION) {
            return@withContext ProjectWorkPlanAttemptResult.INVALID
        }
        val expected = ProjectWorkPlanResultDetails.selectionResultId(entries.map { it.identity })
        if (expected == null || expected != receipt.resultId) {
            return@withContext ProjectWorkPlanAttemptResult.INVALID
        }
        val encoded = ProjectWorkPlanResultDetails.encodeSources(entries)
            ?: return@withContext ProjectWorkPlanAttemptResult.INVALID
        database.withTransaction {
            val plan = dao.getPlan(receipt.planId, receipt.projectId)
            val step = dao.getStep(receipt.stepId, receipt.planId)
            val verdict = ProjectWorkPlanReceiptPolicy.validate(
                receipt = receipt,
                plan = plan?.let {
                    ProjectWorkPlanReceiptPlanState(it.projectId, it.status, it.archivedAt)
                },
                step = step?.let {
                    ProjectWorkPlanReceiptStepState(it.planId, it.type, it.state, it.activeAttemptId, it.attemptCount, it.linkedTaskId)
                },
            )
            if (verdict !is ProjectWorkPlanReceiptVerdict.Accept) {
                return@withTransaction ProjectWorkPlanReceiptPolicy.attemptResultFor(
                    (verdict as ProjectWorkPlanReceiptVerdict.Reject).reason,
                )
            }
            if (!entries.all { sourceAccessible(it.identity) }) {
                return@withTransaction ProjectWorkPlanAttemptResult.STALE
            }
            try {
                database.projectWorkPlanResultSourceDao().insert(
                    com.projectnuke.fusion.data.ProjectWorkPlanResultSourceEntity(
                        attemptId = receipt.attemptId,
                        resultId = receipt.resultId,
                        planId = receipt.planId,
                        stepId = receipt.stepId,
                        projectId = receipt.projectId,
                        stepType = receipt.stepType.name,
                        resultKind = receipt.resultKind,
                        extractionType = null,
                        sourcesJson = encoded,
                        createdAt = now,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withTransaction ProjectWorkPlanAttemptResult.STALE
            }
            finishAttemptInTransaction(
                planId = receipt.planId,
                stepId = receipt.stepId,
                attemptId = receipt.attemptId,
                resultKind = receipt.resultKind,
                resultId = receipt.resultId,
            )
        }
    }

    /**
     * Findings D + F: durable EXTRACTION settlement. The receipt id must be
     * the hash over the exact identity + type; source access is revalidated
     * again here before anything settles. A disappeared source never settles.
     */
    suspend fun settleExtractionResult(
        receipt: ProjectWorkPlanResultReceipt,
        entry: ProjectWorkPlanResultDetails.SourceEntry,
        extractionType: String,
        now: Long = clock(),
    ): ProjectWorkPlanAttemptResult = withContext(Dispatchers.IO) {
        if (receipt.resultKind != ProjectWorkPlanResultReceipt.KIND_EXTRACTION) {
            return@withContext ProjectWorkPlanAttemptResult.INVALID
        }
        val expected = ProjectWorkPlanResultDetails.extractionResultId(entry.identity, extractionType)
        if (expected == null || expected != receipt.resultId) {
            return@withContext ProjectWorkPlanAttemptResult.INVALID
        }
        val encoded = ProjectWorkPlanResultDetails.encodeSources(listOf(entry))
            ?: return@withContext ProjectWorkPlanAttemptResult.INVALID
        database.withTransaction {
            val plan = dao.getPlan(receipt.planId, receipt.projectId)
            val step = dao.getStep(receipt.stepId, receipt.planId)
            val verdict = ProjectWorkPlanReceiptPolicy.validate(
                receipt = receipt,
                plan = plan?.let {
                    ProjectWorkPlanReceiptPlanState(it.projectId, it.status, it.archivedAt)
                },
                step = step?.let {
                    ProjectWorkPlanReceiptStepState(it.planId, it.type, it.state, it.activeAttemptId, it.attemptCount, it.linkedTaskId)
                },
            )
            if (verdict !is ProjectWorkPlanReceiptVerdict.Accept) {
                return@withTransaction ProjectWorkPlanReceiptPolicy.attemptResultFor(
                    (verdict as ProjectWorkPlanReceiptVerdict.Reject).reason,
                )
            }
            if (!sourceAccessible(entry.identity)) {
                return@withTransaction ProjectWorkPlanAttemptResult.STALE
            }
            try {
                database.projectWorkPlanResultSourceDao().insert(
                    com.projectnuke.fusion.data.ProjectWorkPlanResultSourceEntity(
                        attemptId = receipt.attemptId,
                        resultId = receipt.resultId,
                        planId = receipt.planId,
                        stepId = receipt.stepId,
                        projectId = receipt.projectId,
                        stepType = receipt.stepType.name,
                        resultKind = receipt.resultKind,
                        extractionType = extractionType.trim().take(32),
                        sourcesJson = encoded,
                        createdAt = now,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withTransaction ProjectWorkPlanAttemptResult.STALE
            }
            finishAttemptInTransaction(
                planId = receipt.planId,
                stepId = receipt.stepId,
                attemptId = receipt.attemptId,
                resultKind = receipt.resultKind,
                resultId = receipt.resultId,
            )
        }
    }

    /**
     * Finding D: durable provenance read for plan reopen. Returns the
     * stored entries (identities + display titles) for the latest row
     * carrying this fingerprint, or null when no durable row exists.
     * Callers resolve accessibility per identity and render deleted
     * sources truthfully as unavailable.
     */
    suspend fun loadResultSources(resultId: String): List<ProjectWorkPlanResultDetails.SourceEntry>? =
        withContext(Dispatchers.IO) {
            if (resultId.isBlank()) return@withContext null
            val row = database.projectWorkPlanResultSourceDao().getByResultId(resultId, 1).firstOrNull()
                ?: return@withContext null
            ProjectWorkPlanResultDetails.decodeSources(row.sourcesJson)
        }

    suspend fun loadStepResultSources(planId: String, stepId: String): List<ProjectWorkPlanResultDetails.SourceEntry> =
        withContext(Dispatchers.IO) {
            if (planId.isBlank() || stepId.isBlank()) return@withContext emptyList()
            database.projectWorkPlanResultSourceDao().getByStep(planId, stepId)
                .firstNotNullOfOrNull { ProjectWorkPlanResultDetails.decodeSources(it.sourcesJson) }
                .orEmpty()
        }

    /**
     * Issue 5: durable source-access gate shared by settlement. The four
     * database-governed kinds reuse the shared [SourceAccessAuthority]
     * with archive-agnostic existence (the lock is a session/display
     * boundary, enforced transiently at the coordinator gate and at
     * render). Managed attachments use the canonical managed contract
     * when the attachment root is available. MEMORY record liveness has no
     * durable store without a Context, so well-formedness is required here
     * while liveness is enforced at the coordinator gate and display.
     */
    private suspend fun sourceAccessible(identity: com.projectnuke.fusion.chat.SelectionSourceIdentity): Boolean {
        if (!identity.isWellFormed()) return false
        if (identity.kind == com.projectnuke.fusion.chat.SelectionSourceKind.MEMORY) return true
        val root = attachmentRoot
        val authority = com.projectnuke.fusion.knowledge.SourceAccessAuthority(
            archiveAccessIncluded = { true },
            lookups = repositoryAccessLookups(root),
        )
        return authority.existsDurably(identity)
    }

    private fun repositoryAccessLookups(root: java.io.File?) =
        object : com.projectnuke.fusion.knowledge.SourceAccessLookups {
            override suspend fun conversation(conversationId: Long) =
                database.chatDao().getConversationById(conversationId)?.let {
                    com.projectnuke.fusion.knowledge.SourceAccessLookups.ConversationState(it.id, it.isArchived)
                }

            override suspend fun attachmentReferenced(conversationId: Long, managedPath: String): Boolean {
                val attachmentRoot = root ?: return true
                return database.knowledgeSearchDao().getSearchMessages(listOf(conversationId), 96).any { message ->
                    com.projectnuke.fusion.util.AttachmentMessageCodec.parseTrustedAttachmentMessage(
                        message.content,
                        attachmentRoot,
                    ).records.any { it.localPath == managedPath }
                }
            }

            override suspend fun managedAttachmentResolvable(managedPath: String): Boolean =
                resolveManaged(managedPath) != null

            override suspend fun managedAttachmentExists(managedPath: String): Boolean =
                resolveManaged(managedPath) != null

            private fun resolveManaged(managedPath: String): java.io.File? {
                val attachmentRoot = root ?: return try {
                    java.io.File(managedPath).takeIf { it.exists() }
                } catch (_: SecurityException) {
                    null
                }
                return try {
                    com.projectnuke.fusion.util.AttachmentStorageManager.resolveManagedAttachment(
                        attachmentRoot,
                        managedPath,
                    )
                } catch (_: SecurityException) {
                    null
                }
            }

            override suspend fun projectExists(projectId: Long): Boolean =
                database.projectDao().getProjectById(projectId) != null

            override suspend fun projectFileResolvable(projectId: Long, fileId: Long): Boolean =
                projectFileOwned(projectId, fileId)

            override suspend fun projectFileOwned(projectId: Long, fileId: Long): Boolean =
                database.projectDao().getProjectFileById(fileId)?.projectId == projectId

            override suspend fun memory(memoryId: String): com.projectnuke.fusion.knowledge.SourceAccessLookups.MemoryState? =
                null

            override suspend fun memoryRecorded(memoryId: String): Boolean = memoryId.isNotBlank()

            override suspend fun savedKnowledge(savedKnowledgeId: String) =
                database.savedKnowledgeDao().getById(savedKnowledgeId)?.let {
                    com.projectnuke.fusion.knowledge.SourceAccessLookups.SavedKnowledgeState(
                        it.sourceConversationId,
                        it.provenanceType,
                    )
                }

            override suspend fun savedKnowledgeExists(savedKnowledgeId: String): Boolean =
                database.savedKnowledgeDao().getById(savedKnowledgeId) != null

            override suspend fun noteSourceArchivedStates(noteId: String): List<Boolean> = emptyList()
        }

    private suspend fun finishAttemptInTransaction(
        planId: String,
        stepId: String,
        attemptId: String,
        resultKind: String?,
        resultId: String?,
    ): ProjectWorkPlanAttemptResult {
        val step = dao.getStep(stepId, planId) ?: return ProjectWorkPlanAttemptResult.STALE
        if (step.activeAttemptId != attemptId || step.state != ProjectWorkPlanStepState.RUNNING.name) {
            return ProjectWorkPlanAttemptResult.STALE
        }
        val previous = priorReferences(step)
        if (!step.resultKind.isNullOrBlank() && !step.resultId.isNullOrBlank()) {
            previous.add(0, ProjectWorkPlanResultReference(step.resultKind, step.resultId))
        }
        val encoded = encodeReferences(previous.take(MAX_PRIOR_RESULT_REFERENCES))
        val applied = dao.finishStepAttempt(
            planId, stepId, attemptId, resultKind, resultId,
            encoded, MAX_STEP_ATTEMPTS, clock(),
        )
        return if (applied == 1) ProjectWorkPlanAttemptResult.APPLIED
        else if (step.attemptCount >= MAX_STEP_ATTEMPTS) ProjectWorkPlanAttemptResult.ATTEMPT_LIMIT
        else ProjectWorkPlanAttemptResult.STALE
    }

    suspend fun failAttempt(
        attempt: ProjectWorkPlanAttempt,
        failureKind: ProjectWorkPlanAttemptFailureKind,
    ): ProjectWorkPlanAttemptResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val step = dao.getStep(attempt.stepId, attempt.planId) ?: return@withTransaction ProjectWorkPlanAttemptResult.STALE
            if (step.activeAttemptId != attempt.attemptId || step.state != ProjectWorkPlanStepState.RUNNING.name) return@withTransaction ProjectWorkPlanAttemptResult.STALE
            val updated = dao.failStepAttempt(
                attempt.planId, attempt.stepId, attempt.attemptId, failureKind.name, MAX_STEP_ATTEMPTS, clock(),
            )
            // Issue 1: a failed attempt's witness can never settle.
            database.projectWorkPlanGenerationWitnessDao().deleteByAttempt(attempt.attemptId)
            if (updated == 1) ProjectWorkPlanAttemptResult.APPLIED
            else if (step.attemptCount >= MAX_STEP_ATTEMPTS) ProjectWorkPlanAttemptResult.ATTEMPT_LIMIT
            else ProjectWorkPlanAttemptResult.STALE
        }
    }

    suspend fun cancelAttempt(attempt: ProjectWorkPlanAttempt): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            // Issue 1: dismissal stays retryable and its witness dies with it.
            database.projectWorkPlanGenerationWitnessDao().deleteByAttempt(attempt.attemptId)
            dao.cancelStepAttempt(attempt.planId, attempt.stepId, attempt.attemptId, clock()) == 1
        }
    }

    suspend fun completeManualStep(planId: String, stepId: String, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction false
            if (plan.status != ProjectWorkPlanStatus.ACTIVE.name) return@withTransaction false
            val now = clock()
            if (dao.completeManualStep(planId, stepId, plan.currentStepPosition, now) != 1) return@withTransaction false
            advance(planId, projectId, now)
            true
        }
    }

    suspend fun completeReviewedStep(planId: String, stepId: String, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction false
            if (plan.status != ProjectWorkPlanStatus.ACTIVE.name) return@withTransaction false
            val step = dao.getStep(stepId, planId) ?: return@withTransaction false
            if (step.position != plan.currentStepPosition) return@withTransaction false
            if (step.resultKind.isNullOrBlank() || step.resultId.isNullOrBlank()) return@withTransaction false
            val now = clock()
            if (dao.completeReviewedStep(planId, stepId, now) != 1) return@withTransaction false
            advance(planId, projectId, now)
            true
        }
    }

    suspend fun skipStep(planId: String, stepId: String, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction false
            if (plan.status != ProjectWorkPlanStatus.ACTIVE.name) return@withTransaction false
            val now = clock()
            if (dao.skipStep(planId, stepId, plan.currentStepPosition, now) != 1) return@withTransaction false
            advance(planId, projectId, now)
            true
        }
    }

    suspend fun pause(planId: String, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction false
            if (plan.archivedAt != null || dao.getSteps(planId).any { it.state == ProjectWorkPlanStepState.RUNNING.name }) return@withTransaction false
            dao.updateStatus(planId, projectId, ProjectWorkPlanStatus.PAUSED.name, clock()) == 1
        }
    }

    suspend fun resume(planId: String, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction false
            if (plan.status != ProjectWorkPlanStatus.PAUSED.name || plan.archivedAt != null) return@withTransaction false
            if (dao.getActivePlan(projectId) != null) return@withTransaction false
            val steps = dao.getSteps(planId)
            val current = steps.getOrNull(plan.currentStepPosition)
            val now = clock()
            if (current?.state == ProjectWorkPlanStepState.PENDING.name) dao.updateStepState(planId, current.id, ProjectWorkPlanStepState.READY.name, now)
            dao.updateStatus(planId, projectId, ProjectWorkPlanStatus.ACTIVE.name, now) == 1
        }
    }

    suspend fun cancelPlan(planId: String, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val plan = dao.getPlan(planId, projectId) ?: return@withTransaction false
            if (plan.archivedAt != null) return@withTransaction false
            val now = clock()
            dao.releaseRunningSteps(planId, now)
            dao.updateStatus(planId, projectId, ProjectWorkPlanStatus.CANCELLED.name, now) == 1
        }
    }

    suspend fun archive(planId: String, projectId: Long): Boolean = withContext(Dispatchers.IO) {
        dao.archivePlan(planId, projectId, clock(), clock()) == 1
    }

    suspend fun linkConfirmedTask(
        attempt: ProjectWorkPlanAttempt,
        taskId: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (attempt.type != ProjectWorkPlanStepType.CREATE_TASK || taskId <= 0L) return@withContext false
        database.withTransaction {
            val task = database.projectTaskDao().get(taskId, attempt.projectId) ?: return@withTransaction false
            val step = dao.getStep(attempt.stepId, attempt.planId) ?: return@withTransaction false
            val prior = priorReferences(step)
            if (!step.resultKind.isNullOrBlank() && !step.resultId.isNullOrBlank()) {
                prior.add(0, ProjectWorkPlanResultReference(step.resultKind, step.resultId))
            }
            if (dao.linkTask(
                    attempt.planId, attempt.stepId, taskId, taskId.toString(), attempt.attemptId,
                    encodeReferences(prior.take(MAX_PRIOR_RESULT_REFERENCES)), MAX_STEP_ATTEMPTS, clock(),
                ) != 1
            ) return@withTransaction false
            true
        }
    }

    /** User-confirmed Task creation and step linkage commit in one Room transaction. */
    suspend fun createConfirmedTask(
        attempt: ProjectWorkPlanAttempt,
        title: String,
        note: String,
    ): ProjectWorkPlanTaskCreateResult = withContext(Dispatchers.IO) {
        val prepared = ProjectTaskPolicy.prepare(
            ProjectTaskDraft(attempt.projectId, "", title, note),
            title,
            note,
            clock(),
        )
        val proposal = when (prepared) {
            ProjectTaskPreparation.Invalid -> return@withContext ProjectWorkPlanTaskCreateResult.Invalid
            ProjectTaskPreparation.SensitiveContent -> return@withContext ProjectWorkPlanTaskCreateResult.SensitiveContent
            is ProjectTaskPreparation.Ready -> prepared.proposal
        }
        database.withTransaction {
            val step = dao.getStep(attempt.stepId, attempt.planId)
                ?: return@withTransaction ProjectWorkPlanTaskCreateResult.StaleAttempt
            if (step.state != ProjectWorkPlanStepState.RUNNING.name || step.activeAttemptId != attempt.attemptId ||
                step.position != dao.getPlan(attempt.planId, attempt.projectId)?.currentStepPosition
            ) return@withTransaction ProjectWorkPlanTaskCreateResult.StaleAttempt
            val inserted = database.projectTaskDao().insertBounded(ProjectTaskPolicy.entity(proposal))
            val taskId = when (inserted) {
                is ProjectTaskInsertResult.Inserted -> inserted.id
                ProjectTaskInsertResult.CapacityReached -> return@withTransaction ProjectWorkPlanTaskCreateResult.CapacityReached
                ProjectTaskInsertResult.ProjectUnavailable -> return@withTransaction ProjectWorkPlanTaskCreateResult.StaleAttempt
            }
            val prior = priorReferences(step)
            if (!step.resultKind.isNullOrBlank() && !step.resultId.isNullOrBlank()) {
                prior.add(0, ProjectWorkPlanResultReference(step.resultKind, step.resultId))
            }
            val linked = dao.linkTask(
                attempt.planId, attempt.stepId, taskId, taskId.toString(), attempt.attemptId,
                encodeReferences(prior.take(MAX_PRIOR_RESULT_REFERENCES)), MAX_STEP_ATTEMPTS, clock(),
            )
            check(linked == 1) { "Work plan step ownership changed during confirmed task write" }
            ProjectWorkPlanTaskCreateResult.Created(taskId)
        }
    }

    /** User-confirmed Decision creation and its step provenance commit together. */
    suspend fun createConfirmedDecision(
        attempt: ProjectWorkPlanAttempt,
        title: String,
        rationale: String,
    ): ProjectWorkPlanDecisionCreateResult = withContext(Dispatchers.IO) {
        if (attempt.type != ProjectWorkPlanStepType.PROPOSE_DECISION) {
            return@withContext ProjectWorkPlanDecisionCreateResult.Invalid
        }
        val draft = ProjectDecisionDraft(title, rationale)
        val validated = ProjectDecisionPolicy.validate(draft)
            ?: return@withContext ProjectWorkPlanDecisionCreateResult.Invalid
        val id = newId()
        database.withTransaction {
            val plan = dao.getPlan(attempt.planId, attempt.projectId)
                ?: return@withTransaction ProjectWorkPlanDecisionCreateResult.ProjectUnavailable
            val step = dao.getStep(attempt.stepId, attempt.planId)
                ?: return@withTransaction ProjectWorkPlanDecisionCreateResult.StaleAttempt
            if (step.state != ProjectWorkPlanStepState.RUNNING.name || step.activeAttemptId != attempt.attemptId ||
                step.position != plan.currentStepPosition
            ) return@withTransaction ProjectWorkPlanDecisionCreateResult.StaleAttempt
            when (database.projectDecisionDao().insertDecision(
                ProjectDecisionEntity(
                    id = id,
                    projectId = attempt.projectId,
                    title = validated.title,
                    rationale = validated.rationale,
                    status = "ACTIVE",
                    sourceConversationId = null,
                    sourceMessageId = null,
                    createdAt = clock(),
                    updatedAt = clock(),
                ),
            )) {
                is ProjectDecisionWriteResult.Inserted -> Unit
                ProjectDecisionWriteResult.CapacityReached -> return@withTransaction ProjectWorkPlanDecisionCreateResult.CapacityReached
                ProjectDecisionWriteResult.ProjectMissing -> return@withTransaction ProjectWorkPlanDecisionCreateResult.ProjectUnavailable
                ProjectDecisionWriteResult.SupersededDecisionMissing,
                ProjectDecisionWriteResult.SupersededDecisionInactive -> return@withTransaction ProjectWorkPlanDecisionCreateResult.Invalid
            }
            val prior = priorReferences(step)
            if (!step.resultKind.isNullOrBlank() && !step.resultId.isNullOrBlank()) {
                prior.add(0, ProjectWorkPlanResultReference(step.resultKind, step.resultId))
            }
            val linked = dao.attachDecisionResult(
                attempt.planId,
                attempt.stepId,
                attempt.attemptId,
                id,
                encodeReferences(prior.take(MAX_PRIOR_RESULT_REFERENCES)),
                MAX_STEP_ATTEMPTS,
                clock(),
            )
            check(linked == 1) { "Work plan step ownership changed during confirmed decision write" }
            ProjectWorkPlanDecisionCreateResult.Created(id)
        }
    }

    suspend fun taskCompleted(taskId: Long): Boolean = withContext(Dispatchers.IO) {
        if (taskId <= 0L) return@withContext false
        database.withTransaction { taskCompletedInTransaction(taskId) }
    }

    /** Called by the owning Task transaction so Task and linked step settle atomically. */
    suspend fun taskCompletedInTransaction(taskId: Long): Boolean {
        if (taskId <= 0L) return false
        val step = dao.getStepForTask(taskId) ?: return false
        val plan = dao.getPlanById(step.planId) ?: return false
        if (plan.status !in setOf(ProjectWorkPlanStatus.ACTIVE.name, ProjectWorkPlanStatus.PAUSED.name) || plan.archivedAt != null) {
            return false
        }
        if (dao.completeLinkedTask(step.planId, taskId, clock()) != 1) return false
        advance(step.planId, plan.projectId, clock(), preservePaused = plan.status == ProjectWorkPlanStatus.PAUSED.name)
        return true
    }

    private suspend fun advance(planId: String, projectId: Long, now: Long, preservePaused: Boolean = false) {
        val steps = dao.getSteps(planId)
        val completed = steps.all {
            it.state == ProjectWorkPlanStepState.COMPLETED.name || it.state == ProjectWorkPlanStepState.SKIPPED.name
        }
        if (completed) {
            dao.updateProgress(planId, projectId, steps.size, ProjectWorkPlanStatus.COMPLETED.name, now)
            return
        }
        val current = steps.firstOrNull {
            it.state !in setOf(ProjectWorkPlanStepState.COMPLETED.name, ProjectWorkPlanStepState.SKIPPED.name)
        } ?: return
        if (current.state == ProjectWorkPlanStepState.PENDING.name) {
            dao.readyNextStep(planId, current.position, now)
        }
        dao.updateProgress(
            planId,
            projectId,
            current.position,
            if (preservePaused) ProjectWorkPlanStatus.PAUSED.name else ProjectWorkPlanStatus.ACTIVE.name,
            now,
        )
    }

    private fun priorReferences(step: ProjectWorkPlanStepEntity): MutableList<ProjectWorkPlanResultReference> = try {
        val json = JSONArray(step.previousResultRefs)
        buildList {
            for (index in 0 until json.length().coerceAtMost(MAX_PRIOR_RESULT_REFERENCES)) {
                val item = json.optJSONObject(index) ?: continue
                val kind = item.optString("kind").take(32)
                val id = item.optString("id").take(180)
                if (kind.isNotBlank() && id.isNotBlank()) add(ProjectWorkPlanResultReference(kind, id))
            }
        }.toMutableList()
    } catch (_: JSONException) {
        mutableListOf()
    }

    private fun encodeReferences(items: List<ProjectWorkPlanResultReference>): String = JSONArray().apply {
        items.take(MAX_PRIOR_RESULT_REFERENCES).forEach { item ->
            put(JSONObject().put("kind", item.kind.take(32)).put("id", item.id.take(180)))
        }
    }.toString()

    companion object {
        const val MAX_PLANS = 64
        const val MAX_STEP_ATTEMPTS = 5
        const val MAX_PRIOR_RESULT_REFERENCES = 3
    }
}

data class ProjectWorkPlanResultReference(val kind: String, val id: String)
