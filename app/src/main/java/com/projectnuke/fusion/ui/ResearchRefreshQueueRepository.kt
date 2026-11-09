package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ProjectSourceScope
import com.projectnuke.fusion.chat.ResearchRefreshPolicy
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.ResearchRefreshRepository
import com.projectnuke.fusion.util.visibleMessageContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

internal const val RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION = 128
private const val RESEARCH_REFRESH_MAX_MESSAGE_CHARS = 250_000

data class ResearchRefreshMessageWindow(
    val messages: List<MessageEntity>,
    val hasOlderMessages: Boolean = false,
)

data class ResearchRefreshQueueCandidate(
    val sourceConversationId: Long,
    val sourceMessageId: Long,
    val sourceTimestamp: Long,
    val conversationTitle: String,
    val question: String,
    val visiblePreviousAnswer: String,
    val receipt: AnswerContextReceipt,
    val frozenScope: ProjectSourceScope,
    val ageDays: Int,
) {
    val stableId: String get() = "research:$sourceConversationId:$sourceMessageId"
}

enum class ResearchRefreshQueueCoverage {
    COMPLETE,
    INCOMPLETE,
    FAILED,
}

data class ResearchRefreshQueueLoadResult(
    val candidates: List<ResearchRefreshQueueCandidate>,
    val coverage: ResearchRefreshQueueCoverage,
    val visibleLimitReached: Boolean = false,
    val requestedTargetStatus: ResearchRefreshQueueTargetStatus = ResearchRefreshQueueTargetStatus.NOT_REQUESTED,
) {
    val coverageIncomplete: Boolean get() =
        coverage != ResearchRefreshQueueCoverage.COMPLETE || visibleLimitReached
}

enum class ResearchRefreshQueueTargetStatus { NOT_REQUESTED, ELIGIBLE, INELIGIBLE, NOT_EXAMINED, FAILED }

sealed interface ResearchRefreshQueueTargetInspection {
    data object Missing : ResearchRefreshQueueTargetInspection
    data object NotExamined : ResearchRefreshQueueTargetInspection
    data object Ineligible : ResearchRefreshQueueTargetInspection
    data object AuthorityUnavailable : ResearchRefreshQueueTargetInspection
    data class Source(val value: ResearchRefreshQueueSource) : ResearchRefreshQueueTargetInspection
}

data class ResearchRefreshQueueSelection(
    val candidates: List<ResearchRefreshQueueCandidate>,
    val visibleLimitReached: Boolean,
)

data class ResearchRefreshQueueSource(
    val sourceConversationId: Long,
    val sourceMessageId: Long,
    val sourceTimestamp: Long,
    val conversationTitle: String,
    val question: String,
    val visiblePreviousAnswer: String,
    val receipt: AnswerContextReceipt?,
    val frozenScope: ProjectSourceScope?,
    val accessible: Boolean,
    val hasNewerRefresh: Boolean,
)

object ResearchRefreshQueuePolicy {
    const val DEFAULT_REFRESH_AGE_DAYS = 14L
    const val MAX_CANDIDATES = 16
    // Hard relationship-inspection budget. Load results disclose when raw
    // candidates remain beyond this bounded prefix.
    const val MAX_SOURCE_QUESTIONS_SCANNED = 128
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun isOldEnough(sourceTimestamp: Long, now: Long): Boolean =
        sourceTimestamp <= now - DEFAULT_REFRESH_AGE_DAYS * DAY_MS

    fun select(
        sources: List<ResearchRefreshQueueSource>,
        now: Long,
    ): List<ResearchRefreshQueueCandidate> = selectWithCoverage(sources, now).candidates

    fun selectWithCoverage(
        sources: List<ResearchRefreshQueueSource>,
        now: Long,
        preferredTarget: Pair<Long, Long>? = null,
    ): ResearchRefreshQueueSelection {
        val eligible = sources.asSequence()
        .filter { source ->
            val receipt = source.receipt ?: return@filter false
            val restoredScope = ResearchRefreshPolicy.restoreSourceScope(receipt) ?: return@filter false
            source.sourceConversationId > 0L && source.sourceMessageId > 0L &&
                source.accessible && isOldEnough(source.sourceTimestamp, now) &&
                !source.hasNewerRefresh && receipt.interactionMode == ChatInteractionMode.RESEARCH.name &&
                source.frozenScope?.frozen() == restoredScope && source.question.isNotBlank() &&
                source.question.length <= ResearchRefreshPolicy.MAX_QUESTION_CHARS &&
                source.visiblePreviousAnswer.isNotBlank()
        }
        .sortedWith(
            compareBy<ResearchRefreshQueueSource> { it.sourceTimestamp }
                .thenBy { it.sourceConversationId }
                .thenBy { it.sourceMessageId },
        )
        .map { source ->
            ResearchRefreshQueueCandidate(
                sourceConversationId = source.sourceConversationId,
                sourceMessageId = source.sourceMessageId,
                sourceTimestamp = source.sourceTimestamp,
                conversationTitle = source.conversationTitle.ifBlank { "연구 확인" },
                question = source.question,
                visiblePreviousAnswer = source.visiblePreviousAnswer,
                receipt = requireNotNull(source.receipt),
                frozenScope = requireNotNull(source.frozenScope).frozen(),
                ageDays = ((now - source.sourceTimestamp).coerceAtLeast(0L) / DAY_MS)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        .toList()
        val visibleLimitReached = eligible.size > MAX_CANDIDATES
        var selected = eligible.take(MAX_CANDIDATES)
        val preferred = preferredTarget?.let { (conversationId, messageId) ->
            eligible.firstOrNull {
                it.sourceConversationId == conversationId && it.sourceMessageId == messageId
            }
        }
        if (preferred != null && selected.none { it.stableId == preferred.stableId }) {
            selected = (selected.take((MAX_CANDIDATES - 1).coerceAtLeast(0)) + preferred)
                .sortedWith(compareBy<ResearchRefreshQueueCandidate> { it.sourceTimestamp }
                    .thenBy { it.sourceConversationId }
                    .thenBy { it.sourceMessageId })
        }
        return ResearchRefreshQueueSelection(selected, visibleLimitReached)
    }

    fun prepare(candidate: ResearchRefreshQueueCandidate) = ResearchRefreshPolicy.prepare(
        receipt = candidate.receipt,
        sourceConversationId = candidate.sourceConversationId,
        sourceMessageId = candidate.sourceMessageId,
        question = candidate.question,
        visiblePreviousAnswer = candidate.visiblePreviousAnswer,
    )
}

interface ResearchRefreshQueueDataSource {
    suspend fun conversations(includeArchived: Boolean): List<ConversationEntity>
    suspend fun projectConversations(projectId: Long, includeArchived: Boolean): List<ConversationEntity> =
        conversations(includeArchived)
    suspend fun messages(conversationId: Long): ResearchRefreshMessageWindow
    suspend fun activeAssistantMessageIds(conversationId: Long, messages: List<MessageEntity>): Set<Long>?
    suspend fun hasNewerRefresh(sourceConversationId: Long, sourceMessageId: Long): Boolean
    suspend fun projectForConversation(conversationId: Long): Long? = null
    suspend fun inspectRequestedTarget(
        sourceConversationId: Long,
        sourceMessageId: Long,
        includeArchived: Boolean,
    ): ResearchRefreshQueueTargetInspection = ResearchRefreshQueueTargetInspection.NotExamined
}

internal class RoomResearchRefreshQueueDataSource(
    private val database: AppDatabase,
    private val context: android.content.Context,
) : ResearchRefreshQueueDataSource {
    private val refreshRepository = ResearchRefreshRepository(database.researchRefreshDao())

    override suspend fun conversations(includeArchived: Boolean): List<ConversationEntity> {
        val active = database.chatDao()
            .observeConversationsLimited(MAX_CONVERSATIONS + 1)
            .firstOrNull().orEmpty()
        if (!includeArchived) return active
        val archived = database.chatDao()
            .observeArchivedConversationsLimited(MAX_CONVERSATIONS + 1)
            .firstOrNull().orEmpty()
        return (active + archived).distinctBy { it.id }
            .sortedWith(compareByDescending<ConversationEntity> { it.updatedAt }.thenByDescending { it.id })
            .take(MAX_CONVERSATIONS + 1)
    }

    override suspend fun projectConversations(projectId: Long, includeArchived: Boolean): List<ConversationEntity> =
        if (includeArchived) {
            database.projectDao().getConversationsForProjectBounded(projectId, MAX_CONVERSATIONS + 1)
        } else {
            database.projectDao().getActiveConversationsForProjectBounded(projectId, MAX_CONVERSATIONS + 1)
        }

    override suspend fun messages(conversationId: Long): ResearchRefreshMessageWindow {
        val fetched = database.chatDao().getMessagesLatestLimited(conversationId, MAX_MESSAGES_PER_CONVERSATION + 1)
        return ResearchRefreshMessageWindow(
            messages = fetched.takeLast(MAX_MESSAGES_PER_CONVERSATION),
            hasOlderMessages = fetched.size > MAX_MESSAGES_PER_CONVERSATION,
        )
    }

    override suspend fun activeAssistantMessageIds(
        conversationId: Long,
        messages: List<MessageEntity>,
    ): Set<Long>? {
        val state = when (val loaded = loadResponseVersionStateResult(context, conversationId)) {
            ResponseVersionLoad.Missing -> ResponseVersionState()
            is ResponseVersionLoad.Valid -> loaded.state
            ResponseVersionLoad.Corrupt,
            ResponseVersionLoad.Oversized -> return null
        }
        return activeTimelineMessages(messages, state)
            .filter { it.role == "assistant" }
            .mapTo(linkedSetOf()) { it.id }
    }

    override suspend fun hasNewerRefresh(sourceConversationId: Long, sourceMessageId: Long): Boolean =
        refreshRepository.targetsFrom(
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            includeArchived = true,
            limit = 1,
        ).isNotEmpty()

    override suspend fun projectForConversation(conversationId: Long): Long? =
        database.projectDao().getProjectIdForConversation(conversationId)

    override suspend fun inspectRequestedTarget(
        sourceConversationId: Long,
        sourceMessageId: Long,
        includeArchived: Boolean,
    ): ResearchRefreshQueueTargetInspection {
        val conversation = database.chatDao().getConversationById(sourceConversationId)
            ?: return ResearchRefreshQueueTargetInspection.Missing
        if (conversation.isArchived && !includeArchived) {
            return ResearchRefreshQueueTargetInspection.Ineligible
        }
        val target = database.chatDao().getMessageById(sourceMessageId)
            ?: return ResearchRefreshQueueTargetInspection.Missing
        if (target.conversationId != sourceConversationId) return ResearchRefreshQueueTargetInspection.Ineligible

        val window = messages(sourceConversationId)
        val index = window.messages.indexOfFirst { it.id == sourceMessageId }
        if (index < 0) return ResearchRefreshQueueTargetInspection.NotExamined
        val activeIds = activeAssistantMessageIds(sourceConversationId, window.messages)
            ?: return ResearchRefreshQueueTargetInspection.AuthorityUnavailable
        if (target.role != "assistant" || target.id !in activeIds || target.content.length > RESEARCH_REFRESH_MAX_MESSAGE_CHARS) {
            return ResearchRefreshQueueTargetInspection.Ineligible
        }
        val receipt = AnswerContextReceipt.parse(target.content)
            ?.takeIf { it.interactionMode == ChatInteractionMode.RESEARCH.name }
            ?: return ResearchRefreshQueueTargetInspection.Ineligible
        val scope = ResearchRefreshPolicy.restoreSourceScope(receipt)
            ?: return ResearchRefreshQueueTargetInspection.Ineligible
        val origin = window.messages.subList(0, index).lastOrNull { it.role == "user" }
            ?: return if (window.hasOlderMessages) ResearchRefreshQueueTargetInspection.NotExamined
            else ResearchRefreshQueueTargetInspection.Ineligible
        val question = visibleMessageContent(origin.content).trim()
            .take(ResearchRefreshPolicy.MAX_QUESTION_CHARS + 1)
        val answer = assistantVisibleAnswerForHandoff(
            content = target.content,
            reasoningEnabled = true,
            generationModeKey = null,
        ).take(ResearchRefreshPolicy.MAX_PREVIOUS_ANSWER_CHARS + 1)
        if (question.isBlank() || question.length > ResearchRefreshPolicy.MAX_QUESTION_CHARS || answer.isBlank()) {
            return ResearchRefreshQueueTargetInspection.Ineligible
        }
        return ResearchRefreshQueueTargetInspection.Source(
            ResearchRefreshQueueSource(
                sourceConversationId = sourceConversationId,
                sourceMessageId = sourceMessageId,
                sourceTimestamp = target.createdAt,
                conversationTitle = conversation.title,
                question = question,
                visiblePreviousAnswer = answer,
                receipt = receipt,
                frozenScope = scope,
                accessible = !conversation.isArchived || includeArchived,
                hasNewerRefresh = false,
            ),
        )
    }

    companion object {
        internal const val MAX_CONVERSATIONS = 64
        private const val MAX_MESSAGES_PER_CONVERSATION = RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION
    }
}

/** Shared candidate owner consumed by both Action Center and the queue screen. */
class ResearchRefreshQueueRepository(
    private val dataSource: ResearchRefreshQueueDataSource,
) {
    suspend fun load(
        now: Long = System.currentTimeMillis(),
        includeArchived: Boolean = false,
        projectId: Long? = null,
        requestedTarget: Pair<Long, Long>? = null,
    ): ResearchRefreshQueueLoadResult = withContext(Dispatchers.IO) {
        try {
            require(projectId == null || projectId > 0L)
            var coverage = ResearchRefreshQueueCoverage.COMPLETE
            val rawConversations = if (projectId == null) {
                dataSource.conversations(includeArchived)
            } else {
                dataSource.projectConversations(projectId, includeArchived)
            }
            if (rawConversations.size > RoomResearchRefreshQueueDataSource.MAX_CONVERSATIONS) {
                coverage = ResearchRefreshQueueCoverage.INCOMPLETE
            }
            val conversations = rawConversations
                .take(RoomResearchRefreshQueueDataSource.MAX_CONVERSATIONS)
                .asSequence()
                .filter { !it.isArchived || includeArchived }
                .sortedWith(compareBy<ConversationEntity> { it.id })
                .toList()
            val candidateSources = mutableListOf<ResearchRefreshQueueSource>()
            val assignedProjects = mutableMapOf<Long, Long?>()
            for (conversation in conversations) {
                val messageWindow = try {
                    dataSource.messages(conversation.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    coverage = ResearchRefreshQueueCoverage.INCOMPLETE
                    continue
                }
                if (messageWindow.hasOlderMessages || messageWindow.messages.size > RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION) {
                    coverage = ResearchRefreshQueueCoverage.INCOMPLETE
                }
                val messages = messageWindow.messages.takeLast(RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION)
                val activeMessageIds = try {
                    dataSource.activeAssistantMessageIds(conversation.id, messages)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    coverage = ResearchRefreshQueueCoverage.INCOMPLETE
                    null
                }
                if (activeMessageIds == null) {
                    coverage = ResearchRefreshQueueCoverage.INCOMPLETE
                    continue
                }
                messages.forEachIndexed { index, message ->
                    if (message.id !in activeMessageIds || message.role != "assistant" || message.content.length > RESEARCH_REFRESH_MAX_MESSAGE_CHARS ||
                        !ResearchRefreshQueuePolicy.isOldEnough(message.createdAt, now)
                    ) return@forEachIndexed
                    val receipt = AnswerContextReceipt.parse(message.content)
                        ?.takeIf { it.interactionMode == ChatInteractionMode.RESEARCH.name }
                        ?: return@forEachIndexed
                    val scope = ResearchRefreshPolicy.restoreSourceScope(receipt)
                        ?: return@forEachIndexed
                    if (projectId != null) {
                        if (scope.projectId != projectId || receipt.projectContextId != projectId) {
                            return@forEachIndexed
                        }
                        val assignedProjectId = if (assignedProjects.containsKey(conversation.id)) {
                            assignedProjects[conversation.id]
                        } else {
                            val resolved = try {
                                dataSource.projectForConversation(conversation.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                coverage = ResearchRefreshQueueCoverage.INCOMPLETE
                                null
                            }
                            assignedProjects[conversation.id] = resolved
                            resolved
                        }
                        if (assignedProjectId != projectId) return@forEachIndexed
                    }
                    val origin = messages.subList(0, index).lastOrNull { it.role == "user" }
                        ?: return@forEachIndexed
                    val question = visibleMessageContent(origin.content).trim()
                        .take(ResearchRefreshPolicy.MAX_QUESTION_CHARS + 1)
                    val visibleAnswer = assistantVisibleAnswerForHandoff(
                        content = message.content,
                        reasoningEnabled = true,
                        generationModeKey = null,
                    ).take(ResearchRefreshPolicy.MAX_PREVIOUS_ANSWER_CHARS + 1)
                    if (question.isBlank() || question.length > ResearchRefreshPolicy.MAX_QUESTION_CHARS ||
                        visibleAnswer.isBlank()
                    ) return@forEachIndexed
                    candidateSources += ResearchRefreshQueueSource(
                        sourceConversationId = conversation.id,
                        sourceMessageId = message.id,
                        sourceTimestamp = message.createdAt,
                        conversationTitle = conversation.title,
                        question = question,
                        visiblePreviousAnswer = visibleAnswer,
                        receipt = receipt,
                        frozenScope = scope,
                        accessible = !conversation.isArchived || includeArchived,
                        hasNewerRefresh = false,
                    )
                }
            }
            val eligibleForRelationshipCheck = candidateSources
                .sortedWith(
                    compareBy<ResearchRefreshQueueSource> { it.sourceTimestamp }
                        .thenBy { it.sourceConversationId }
                        .thenBy { it.sourceMessageId },
                )
                .take(ResearchRefreshQueuePolicy.MAX_SOURCE_QUESTIONS_SCANNED)
            if (candidateSources.size > eligibleForRelationshipCheck.size) {
                coverage = ResearchRefreshQueueCoverage.INCOMPLETE
            }
            val settled = mutableListOf<ResearchRefreshQueueSource>()
            for (source in eligibleForRelationshipCheck) {
                val hasNewer = try {
                    dataSource.hasNewerRefresh(source.sourceConversationId, source.sourceMessageId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Relationship authority unavailable: do not expose a possibly obsolete source.
                    coverage = ResearchRefreshQueueCoverage.INCOMPLETE
                    continue
                }
                settled += source.copy(hasNewerRefresh = hasNewer)
            }
            var requestedTargetStatus = if (requestedTarget == null) {
                ResearchRefreshQueueTargetStatus.NOT_REQUESTED
            } else {
                ResearchRefreshQueueTargetStatus.NOT_EXAMINED
            }
            if (requestedTarget != null) {
                val (targetConversationId, targetMessageId) = requestedTarget
                if (targetConversationId <= 0L || targetMessageId <= 0L) {
                    requestedTargetStatus = ResearchRefreshQueueTargetStatus.INELIGIBLE
                } else {
                    val alreadySettled = settled.firstOrNull {
                        it.sourceConversationId == targetConversationId && it.sourceMessageId == targetMessageId
                    }
                    val inspection = if (alreadySettled != null) {
                        ResearchRefreshQueueTargetInspection.Source(alreadySettled)
                    } else try {
                        dataSource.inspectRequestedTarget(targetConversationId, targetMessageId, includeArchived)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ResearchRefreshQueueTargetInspection.AuthorityUnavailable
                    }
                    when (inspection) {
                        ResearchRefreshQueueTargetInspection.Missing,
                        ResearchRefreshQueueTargetInspection.Ineligible,
                        -> requestedTargetStatus = ResearchRefreshQueueTargetStatus.INELIGIBLE
                        ResearchRefreshQueueTargetInspection.NotExamined ->
                            requestedTargetStatus = ResearchRefreshQueueTargetStatus.NOT_EXAMINED
                        ResearchRefreshQueueTargetInspection.AuthorityUnavailable ->
                            requestedTargetStatus = ResearchRefreshQueueTargetStatus.FAILED
                        is ResearchRefreshQueueTargetInspection.Source -> {
                            val source = inspection.value
                            if (source.sourceConversationId != targetConversationId || source.sourceMessageId != targetMessageId) {
                                requestedTargetStatus = ResearchRefreshQueueTargetStatus.INELIGIBLE
                            } else {
                                val frozenProjectId = source.frozenScope?.projectId
                                val ownershipMatches = if (frozenProjectId == null) true else try {
                                    dataSource.projectForConversation(targetConversationId) == frozenProjectId
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    requestedTargetStatus = ResearchRefreshQueueTargetStatus.FAILED
                                    false
                                }
                                if (ownershipMatches && requestedTargetStatus != ResearchRefreshQueueTargetStatus.FAILED) {
                                    val hasNewer = try {
                                        alreadySettled?.hasNewerRefresh ?: dataSource.hasNewerRefresh(targetConversationId, targetMessageId)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        requestedTargetStatus = ResearchRefreshQueueTargetStatus.FAILED
                                        false
                                    }
                                    val exactSource = source.copy(hasNewerRefresh = hasNewer)
                                    val exactCandidate = ResearchRefreshQueuePolicy.select(listOf(exactSource), now).singleOrNull()
                                    if (exactCandidate == null) {
                                        if (requestedTargetStatus != ResearchRefreshQueueTargetStatus.FAILED) {
                                            requestedTargetStatus = ResearchRefreshQueueTargetStatus.INELIGIBLE
                                        }
                                    } else if (requestedTargetStatus != ResearchRefreshQueueTargetStatus.FAILED) {
                                        requestedTargetStatus = ResearchRefreshQueueTargetStatus.ELIGIBLE
                                        if (settled.none {
                                                it.sourceConversationId == targetConversationId && it.sourceMessageId == targetMessageId
                                            }
                                        ) settled += exactSource
                                    }
                                } else if (requestedTargetStatus != ResearchRefreshQueueTargetStatus.FAILED) {
                                    requestedTargetStatus = ResearchRefreshQueueTargetStatus.INELIGIBLE
                                }
                            }
                        }
                    }
                }
            }
            if (requestedTarget != null && requestedTargetStatus != ResearchRefreshQueueTargetStatus.ELIGIBLE) {
                settled.removeAll {
                    it.sourceConversationId == requestedTarget.first && it.sourceMessageId == requestedTarget.second
                }
            }
            val selection = ResearchRefreshQueuePolicy.selectWithCoverage(settled, now, requestedTarget)
            ResearchRefreshQueueLoadResult(
                candidates = selection.candidates,
                coverage = coverage,
                visibleLimitReached = selection.visibleLimitReached,
                requestedTargetStatus = requestedTargetStatus,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ResearchRefreshQueueLoadResult(
                emptyList(),
                ResearchRefreshQueueCoverage.FAILED,
                requestedTargetStatus = if (requestedTarget == null) ResearchRefreshQueueTargetStatus.NOT_REQUESTED
                else ResearchRefreshQueueTargetStatus.FAILED,
            )
        }
    }

}
