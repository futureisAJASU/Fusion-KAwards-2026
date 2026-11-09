package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.EvidenceDao
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageEvidenceEntity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry
import com.projectnuke.fusion.util.normalizeUserVisibleName
import kotlinx.coroutines.CancellationException

/**
 * Product conversation branch ("여기서 새 흐름 시작").
 *
 * From one assistant message, creates a NEW conversation containing exactly
 * the active-timeline prefix through that message (U1 A1 .. Un An), using
 * the ACTIVE response-version branch only. Hidden inactive regenerated
 * alternatives are never copied.
 *
 * Copied rules:
 *  - exact stored raw message content (envelopes/metadata preserved as-is),
 *    with NEW message IDs
 *  - durable evidence rows re-keyed to the new message IDs with all fields
 *    preserved (no re-verification, no fabricated rows)
 *  - managed attachment envelopes keep referencing the same app-managed
 *    files (no physical duplication); existing reference-scanning cleanup
 *    keeps shared files alive while any conversation references them
 *  - bounded semantic title = the source conversation's title (branch
 *    relationship is product metadata, never a " · 가지" suffix)
 *
 * Never copied: later turns, composer draft, pending attachments, generation
 * state, regeneration ownership, summaries, memory candidates. No model
 * generation begins; navigation to the new conversation is the caller's job
 * after [BranchOutcome.Success].
 *
 * The conversation row, message rows and evidence rows commit in ONE caller-
 * provided transaction ([runInTransaction]); a DB failure leaves no partial
 * branch and never mutates the source.
 */
internal enum class BranchFailure {
    SOURCE_MISSING,
    UNKNOWN_MESSAGE,
    INACTIVE_VERSION,
    EMPTY_PREFIX,
    DB_ERROR,
    SOURCE_DELETING,
    PROJECT_SCOPE_CHANGED,
}

internal sealed interface BranchOutcome {
    data class Success(val newConversationId: Long) : BranchOutcome
    data class Failure(val reason: BranchFailure) : BranchOutcome
}

internal class ConversationBranchCoordinator(
    private val chatDao: ChatDao,
    private val evidenceDao: EvidenceDao,
    private val projectDao: ProjectDao? = null,
    private val runInTransaction: suspend (suspend () -> Long) -> Long,
    private val loadVersions: (Long) -> ResponseVersionState = { ResponseVersionState() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class ProjectScopeExpectation(val frozen: Boolean, val projectId: Long?)

    private class ProjectScopeChangedException : Exception()

    private data class SourceSnapshot(
        val source: ConversationEntity,
        val messages: List<MessageEntity>,
        val state: ResponseVersionState,
        val evidenceByMessageId: Map<Long, MessageEvidenceEntity>,
    )

    suspend fun branch(
        sourceConversationId: Long,
        branchAssistantMessageId: Long,
        researchRefreshFrom: Pair<Long, Long>? = null,
        expectedProjectScope: ProjectScopeExpectation? = null,
    ): BranchOutcome {
        if (researchRefreshFrom != null &&
            (researchRefreshFrom.first != sourceConversationId || researchRefreshFrom.second != branchAssistantMessageId ||
                researchRefreshFrom.first <= 0L || researchRefreshFrom.second <= 0L)
        ) return BranchOutcome.Failure(BranchFailure.UNKNOWN_MESSAGE)
        val lease = ConversationStructuralMutationRegistry.tryAcquire(sourceConversationId)
            ?: return BranchOutcome.Failure(BranchFailure.SOURCE_DELETING)
        return try {
            // Capture the source timeline under the same per-conversation
            // version lock used by assistant persistence. The structural
            // lease remains held after this lock is released through branch
            // transaction commit.
            val snapshot = ResponseVersionMutationCoordinator.withVersionLockUnderLease(lease) {
                val source = chatDao.getConversationById(sourceConversationId)
                    ?: return@withVersionLockUnderLease null
                val messages = chatDao.getMessagesForConversation(sourceConversationId)
                val state = loadVersions(sourceConversationId)
                val evidenceByMessageId = evidenceDao
                    .getEvidenceForConversation(sourceConversationId)
                    .associateBy { it.messageId }
                SourceSnapshot(source, messages, state, evidenceByMessageId)
            } ?: return BranchOutcome.Failure(BranchFailure.SOURCE_MISSING)
            val source = snapshot.source
            val messages = snapshot.messages
            val state = snapshot.state
            val active = activeTimelineMessages(messages, state)
            val index = active.indexOfFirst { it.id == branchAssistantMessageId }
            if (index < 0) {
                val raw = messages.firstOrNull { it.id == branchAssistantMessageId }
                return BranchOutcome.Failure(
                    if (raw != null) BranchFailure.INACTIVE_VERSION
                    else BranchFailure.UNKNOWN_MESSAGE,
                )
            }
            val anchor = active[index]
            if (anchor.role != "assistant") {
                return BranchOutcome.Failure(BranchFailure.UNKNOWN_MESSAGE)
            }
            val prefix = active.take(index + 1)
            if (prefix.isEmpty()) {
                return BranchOutcome.Failure(BranchFailure.EMPTY_PREFIX)
            }
            val evidenceByMessageId = snapshot.evidenceByMessageId
            val now = clock()
            val title = branchTitle(source.title)
            try {
                val newId = runInTransaction {
                    // Membership is mutable. Read it only after entering the
                    // transaction that creates the branch so the inherited
                    // relation cannot be stale at commit time.
                    val sourceProjectId = projectDao?.getProjectIdForConversation(sourceConversationId)
                    if (expectedProjectScope?.frozen == true && sourceProjectId != expectedProjectScope.projectId) {
                        throw ProjectScopeChangedException()
                    }
                    val createdId = chatDao.insertConversation(
                        ConversationEntity(
                            title = title,
                            createdAt = now,
                            updatedAt = now,
                            branchedFromConversationId = sourceConversationId,
                            branchedFromMessageId = branchAssistantMessageId,
                            refreshedFromConversationId = researchRefreshFrom?.first,
                            refreshedFromMessageId = researchRefreshFrom?.second,
                        ),
                    )
                    val oldToNew = HashMap<Long, Long>(prefix.size)
                    for (message in prefix) {
                        val newMessageId = chatDao.insertMessage(
                            MessageEntity(
                                conversationId = createdId,
                                role = message.role,
                                content = message.content,
                                createdAt = message.createdAt,
                            ),
                        )
                        oldToNew[message.id] = newMessageId
                    }
                    for ((oldId, newId) in oldToNew) {
                        val row = evidenceByMessageId[oldId] ?: continue
                        evidenceDao.insertEvidence(row.copy(messageId = newId))
                    }
                    if (sourceProjectId != null) {
                        projectDao?.assignConversation(
                            ProjectConversationEntity(
                                conversationId = createdId,
                                projectId = sourceProjectId,
                                assignedAt = clock(),
                            ),
                        )
                    }
                    createdId
                }
                BranchOutcome.Success(newId)
            } catch (_: ProjectScopeChangedException) {
                BranchOutcome.Failure(BranchFailure.PROJECT_SCOPE_CHANGED)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                BranchOutcome.Failure(BranchFailure.DB_ERROR)
            }
        } finally {
            lease.release()
        }
    }

    companion object {
        /**
         * Title for a newly branched conversation.
         *
         * The conversation TITLE remains the semantic title — the source
         * conversation's title, bounded to the 80-code-point product bound.
         * Branch relationship is metadata and must not pollute the title with
         * any suffix (previously " · 가지"). Arbitrary user-created titles are
         * never mutated by this function (and no path strips the literal word
         * "가지" from user text).
         */
        fun branchTitle(sourceTitle: String): String =
            normalizeUserVisibleName(sourceTitle.trim().ifBlank { null }, "새 대화", 80)
    }
}
