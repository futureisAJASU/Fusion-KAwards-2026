package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.projects.ProjectWorkPlanAttempt
import com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult
import com.projectnuke.fusion.projects.ProjectWorkPlanDestination
import com.projectnuke.fusion.projects.ProjectWorkPlanExtractionContentPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanHandoffPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction
import com.projectnuke.fusion.projects.ProjectWorkPlanRepository
import com.projectnuke.fusion.projects.ProjectWorkPlanResultDetails
import com.projectnuke.fusion.projects.ProjectWorkPlanResultReceipt
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.visibleMessageContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finding H: non-visual Work Plan handoff orchestration.
 *
 * Compose keeps transient UI/navigation state, but receipt settlement,
 * attempt cancellation, typed destination transitions, source/result
 * authority, and provenance validation live here:
 *
 * Compose UI → typed user event → coordinator → existing specialized
 * owner → validated receipt → [ProjectWorkPlanRepository].
 *
 * The coordinator is stateless and suspend-based (the Composable supplies
 * the scope); selection/extraction/review outcomes are typed so the UI
 * only renders and routes.
 */
internal class ProjectWorkPlanHandoffCoordinator(
    private val gateway: WorkPlanSettlementGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface SelectionRoute {
        /** COMPARE path: dispatch the frozen comparison action to chat. */
        data class DispatchPrepared(val action: ProjectWorkPlanPreparedAction) : SelectionRoute

        /** EXTRACTION path: canonical source resolved, open extraction. */
        data class ExtractionReady(val source: StructuredExtractionSource) : SelectionRoute

        /** REVIEW path: snapshot held for the explicit confirm dialog. */
        data object ReviewPending : SelectionRoute

        /** Fail closed: the attempt stays RUNNING for retry/reselect. */
        data class Rejected(val message: String) : SelectionRoute
    }

    sealed interface SettleOutcome {
        data object Applied : SettleOutcome
        data object Stale : SettleOutcome
        data class Rejected(val message: String) : SettleOutcome
    }

    suspend fun settleMessage(receipt: ProjectWorkPlanResultReceipt): ProjectWorkPlanAttemptResult =
        gateway.settleMessage(receipt)

    suspend fun cancel(attempt: ProjectWorkPlanAttempt): Boolean =
        gateway.cancel(attempt)

    /**
     * REVIEW_KNOWLEDGE ask-path: freezes the validated knowledge selection
     * onto a prepared chat action. Null fails closed (the attempt stays
     * RUNNING for retry/reselect).
     */
    fun prepareReviewQuestion(
        action: ProjectWorkPlanPreparedAction,
        snapshot: com.projectnuke.fusion.chat.SelectionContextSnapshot,
    ): ProjectWorkPlanPreparedAction? =
        ProjectWorkPlanHandoffPolicy.prepareKnowledgeQuestion(action, snapshot)

    suspend fun routeSelection(
        action: ProjectWorkPlanPreparedAction,
        snapshot: com.projectnuke.fusion.chat.SelectionContextSnapshot,
        isAccessible: suspend (SelectionSourceIdentity) -> Boolean,
        reader: ProjectWorkPlanExtractionContentPolicy.CanonicalReader,
    ): SelectionRoute = when (action.destination) {
        ProjectWorkPlanDestination.SOURCE_SELECTION_COMPARE -> {
            val prepared = ProjectWorkPlanHandoffPolicy.prepareCompare(action, snapshot)
                ?: return SelectionRoute.Rejected("비교에는 자료를 2~4개 선택해 주세요.")
            SelectionRoute.DispatchPrepared(prepared)
        }
        ProjectWorkPlanDestination.SOURCE_SELECTION_EXTRACTION -> {
            val single = ProjectWorkPlanHandoffPolicy.prepareExtraction(action, snapshot)
                ?: return SelectionRoute.Rejected("추출에는 자료를 1개 선택해 주세요.")
            when (val resolution = ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
                identities = listOf(single.identity),
                isAccessible = isAccessible,
                reader = reader,
            )) {
                is ProjectWorkPlanExtractionContentPolicy.Resolution.Ready ->
                    SelectionRoute.ExtractionReady(
                        StructuredExtractionSource(
                            title = single.title,
                            kind = sourceKindLabel(single.identity.kind),
                            reference = single.identity.stableKey(),
                            text = resolution.text,
                            durableIdentity = single.identity,
                        ),
                    )
                ProjectWorkPlanExtractionContentPolicy.Resolution.Inaccessible ->
                    SelectionRoute.Rejected("선택한 자료가 삭제되었거나 지금 열 수 없습니다. 검색에서 다시 선택해 주세요.")
                ProjectWorkPlanExtractionContentPolicy.Resolution.Unresolvable,
                ProjectWorkPlanExtractionContentPolicy.Resolution.Empty,
                -> SelectionRoute.Rejected("이 자료의 본문을 읽을 수 없어 추출하지 않았습니다. 발췌가 전체 문서를 대신하지 않습니다.")
            }
        }
        ProjectWorkPlanDestination.KNOWLEDGE_SELECTION -> {
            if (ProjectWorkPlanHandoffPolicy.prepareKnowledgeQuestion(action, snapshot) == null) {
                SelectionRoute.Rejected("선택한 자료로 질문을 준비하지 못했습니다.")
            } else {
                SelectionRoute.ReviewPending
            }
        }
        else -> SelectionRoute.Rejected("이 단계에서는 자료를 선택하지 않습니다.")
    }

    /**
     * REVIEW_KNOWLEDGE confirm: persists the durable source identities and
     * settles only when every source revalidates. Deleted sources fail
     * closed without mutating the step.
     */
    suspend fun settleReviewSelection(
        action: ProjectWorkPlanPreparedAction,
        snapshot: com.projectnuke.fusion.chat.SelectionContextSnapshot,
        isAccessible: suspend (SelectionSourceIdentity) -> Boolean,
    ): SettleOutcome {
        val entries = snapshot.sources.map {
            ProjectWorkPlanResultDetails.SourceEntry(it.identity, it.title)
        }
        if (!ProjectWorkPlanResultDetails.entriesValid(entries)) {
            return SettleOutcome.Rejected("선택한 자료를 확인하지 못했습니다. 검색에서 다시 선택해 주세요.")
        }
        val resultId = ProjectWorkPlanResultDetails.selectionResultId(entries.map { it.identity })
            ?: return SettleOutcome.Rejected("선택한 자료를 확인하지 못했습니다. 검색에서 다시 선택해 주세요.")
        for (entry in entries) {
            val accessible = try {
                isAccessible(entry.identity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!accessible) {
                return SettleOutcome.Rejected("선택한 자료 중 열 수 없는 항목이 있습니다. 검색에서 다시 선택해 주세요.")
            }
        }
        val receipt = ProjectWorkPlanResultReceipt(
            planId = action.planId,
            stepId = action.stepId,
            attemptId = action.attemptId,
            projectId = action.projectId,
            stepType = action.stepType,
            resultKind = ProjectWorkPlanResultReceipt.KIND_SELECTION,
            resultId = resultId,
        )
        return when (gateway.settleSelection(receipt, entries, clock())) {
            ProjectWorkPlanAttemptResult.APPLIED -> SettleOutcome.Applied
            ProjectWorkPlanAttemptResult.INVALID -> SettleOutcome.Rejected("선택한 자료를 단계 결과로 사용할 수 없습니다. 계획에서 다시 시작할 수 있습니다.")
            else -> SettleOutcome.Stale
        }
    }

    /**
     * STRUCTURED_EXTRACT accept: revalidates source access again before
     * settlement. A disappeared source never settles the step.
     */
    suspend fun settleExtraction(
        action: ProjectWorkPlanPreparedAction,
        result: StructuredExtractionResult,
        isAccessible: suspend (SelectionSourceIdentity) -> Boolean,
    ): SettleOutcome {
        val identity = result.durableIdentity
            ?: return SettleOutcome.Rejected("이 추출 결과를 단계 결과로 사용할 수 없습니다. 계획에서 다시 시작할 수 있습니다.")
        val accessible = try {
            isAccessible(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!accessible) {
            return SettleOutcome.Rejected("원본 자료가 삭제되었거나 지금 열 수 없어 추출 결과를 기록하지 않았습니다.")
        }
        val resultId = ProjectWorkPlanResultDetails.extractionResultId(identity, result.type.name)
            ?: return SettleOutcome.Rejected("이 추출 결과를 단계 결과로 사용할 수 없습니다. 계획에서 다시 시작할 수 있습니다.")
        val receipt = ProjectWorkPlanResultReceipt(
            planId = action.planId,
            stepId = action.stepId,
            attemptId = action.attemptId,
            projectId = action.projectId,
            stepType = action.stepType,
            resultKind = ProjectWorkPlanResultReceipt.KIND_EXTRACTION,
            resultId = resultId,
        )
        val entry = ProjectWorkPlanResultDetails.SourceEntry(identity, result.sourceTitle)
        return when (gateway.settleExtraction(receipt, entry, result.type.name, clock())) {
            ProjectWorkPlanAttemptResult.APPLIED -> SettleOutcome.Applied
            ProjectWorkPlanAttemptResult.INVALID -> SettleOutcome.Rejected("이 추출 결과를 단계 결과로 사용할 수 없습니다. 계획에서 다시 시작할 수 있습니다.")
            else -> SettleOutcome.Stale
        }
    }

    companion object {
        fun sourceKindLabel(kind: SelectionSourceKind): String = when (kind) {
            SelectionSourceKind.CONVERSATION -> "대화"
            SelectionSourceKind.PROJECT -> "Project"
            SelectionSourceKind.PROJECT_DOCUMENT -> "Project 문서"
            SelectionSourceKind.ATTACHED_DOCUMENT -> "첨부 문서"
            SelectionSourceKind.MEMORY -> "기억"
            SelectionSourceKind.SAVED_KNOWLEDGE -> "저장 지식"
        }
    }
}

/** Settlement authority behind the coordinator; the repository is the real owner. */
internal interface WorkPlanSettlementGateway {
    suspend fun settleMessage(receipt: ProjectWorkPlanResultReceipt): ProjectWorkPlanAttemptResult
    suspend fun settleSelection(
        receipt: ProjectWorkPlanResultReceipt,
        entries: List<ProjectWorkPlanResultDetails.SourceEntry>,
        now: Long,
    ): ProjectWorkPlanAttemptResult
    suspend fun settleExtraction(
        receipt: ProjectWorkPlanResultReceipt,
        entry: ProjectWorkPlanResultDetails.SourceEntry,
        extractionType: String,
        now: Long,
    ): ProjectWorkPlanAttemptResult
    suspend fun cancel(attempt: ProjectWorkPlanAttempt): Boolean
}

internal class ProjectWorkPlanRepositoryGateway(
    private val repository: ProjectWorkPlanRepository,
) : WorkPlanSettlementGateway {
    override suspend fun settleMessage(receipt: ProjectWorkPlanResultReceipt) =
        repository.settleSpecializedResult(receipt)

    override suspend fun settleSelection(
        receipt: ProjectWorkPlanResultReceipt,
        entries: List<ProjectWorkPlanResultDetails.SourceEntry>,
        now: Long,
    ) = repository.settleSelectionResult(receipt, entries, now)

    override suspend fun settleExtraction(
        receipt: ProjectWorkPlanResultReceipt,
        entry: ProjectWorkPlanResultDetails.SourceEntry,
        extractionType: String,
        now: Long,
    ) = repository.settleExtractionResult(receipt, entry, extractionType, now)

    override suspend fun cancel(attempt: ProjectWorkPlanAttempt) =
        repository.cancelAttempt(attempt)
}

/**
 * Finding F: canonical bounded source content resolved through the owning
 * source — the same owners the real Structured Extraction feature uses —
 * never the bounded Search excerpt.
 */
internal class WorkPlanCanonicalContentReader(
    private val context: Context,
    private val database: AppDatabase,
) : ProjectWorkPlanExtractionContentPolicy.CanonicalReader {
    override suspend fun readCanonicalText(identity: SelectionSourceIdentity): String? =
        withContext(Dispatchers.IO) {
            try {
                when (identity.kind) {
                    SelectionSourceKind.PROJECT_DOCUMENT -> readProjectDocument(identity)
                    SelectionSourceKind.ATTACHED_DOCUMENT -> readAttachedDocument(identity)
                    SelectionSourceKind.CONVERSATION -> readConversation(identity)
                    SelectionSourceKind.PROJECT -> readProject(identity)
                    SelectionSourceKind.SAVED_KNOWLEDGE -> readSavedKnowledge(identity)
                    SelectionSourceKind.MEMORY -> readMemory(identity)
                }?.takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun readProjectDocument(identity: SelectionSourceIdentity): String? {
        val projectId = identity.projectId ?: return null
        val fileId = identity.fileId ?: return null
        val file = database.projectDao().getProjectFileById(fileId)
            ?.takeIf { it.projectId == projectId } ?: return null
        // Same owner as the real extraction entry point: bounded passages.
        return ProjectKnowledgeController(context, database).inspectPassages(file)
            .asSequence().map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
            .takeIf { it.isNotBlank() }
    }

    private suspend fun readAttachedDocument(identity: SelectionSourceIdentity): String? {
        val conversationId = identity.conversationId ?: return null
        val managedPath = identity.managedPath?.takeIf { it.isNotBlank() } ?: return null
        if (database.chatDao().getConversationById(conversationId) == null) return null
        val source = AttachmentStorageManager.resolveManagedAttachment(context, managedPath) ?: return null
        val stub = ProjectFileEntity(
            id = 0, projectId = 0, displayName = source.name ?: managedPath.substringAfterLast('/'),
            mimeType = "", localPath = managedPath, addedAt = 0,
        )
        return ProjectKnowledgeController(context, database).inspectPassages(stub)
            .asSequence().map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
            .takeIf { it.isNotBlank() }
    }

    private suspend fun readConversation(identity: SelectionSourceIdentity): String? {
        val conversationId = identity.conversationId ?: return null
        // Bounded read: latest messages only, never the whole history.
        return database.chatDao().getMessagesLatestLimited(conversationId, 200)
            .asSequence().map { visibleMessageContent(it.content).trim() }
            .filter { it.isNotBlank() }.joinToString("\n\n")
            .takeIf { it.isNotBlank() }
    }

    private suspend fun readProject(identity: SelectionSourceIdentity): String? {
        val projectId = identity.projectId ?: return null
        val project = database.projectDao().getProjectById(projectId) ?: return null
        val files = database.projectDao().getProjectFilesBounded(projectId, 32)
        return buildString {
            appendLine("프로젝트: ${project.name}")
            if (project.instructions.isNotBlank()) appendLine("지침: ${project.instructions}")
            appendLine("자료 ${files.size}개")
            files.forEach { appendLine("- ${it.displayName}") }
        }.trim().takeIf { it.isNotBlank() }
    }

    private suspend fun readSavedKnowledge(identity: SelectionSourceIdentity): String? {
        val id = identity.savedKnowledgeId?.takeIf { it.isNotBlank() } ?: return null
        val item = database.savedKnowledgeDao().getById(id) ?: return null
        val note = if (item.provenanceType == "DOCUMENT_HIGHLIGHT") {
            database.documentHighlightSourceDao().getByKnowledgeId(id)?.userNote.orEmpty()
        } else {
            ""
        }
        return (item.savedText + "\n" + note).trim().takeIf { it.isNotBlank() }
    }

    private suspend fun readMemory(identity: SelectionSourceIdentity): String? {
        val id = identity.memoryId?.takeIf { it.isNotBlank() } ?: return null
        return try {
            loadAllConversationMemoryCandidates(context).firstOrNull { it.id == id }?.text
                ?.trim()?.takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
