package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind

/**
 * Issue 5: the single shared knowledge-access authority.
 *
 * `ProjectWorkPlanRepository.sourceAccessible()` duplicated and weakened
 * `FusionKnowledgeSearchRepository.accessible()` (archive-blind
 * conversations, no saved-knowledge archive policy, MEMORY always true, a
 * separate attachment file rule). There is now exactly one rule
 * implementation; call sites differ only in their lookups and in the
 * archive authorization they pass:
 *
 * - Search, Work Plan selection/extraction gates, and result display use
 *   `isAccessible` with the current session archive authorization;
 * - durable repository settlement uses `existsDurably` (archive-agnostic
 *   existence: the lock is a session/display boundary, not a settlement
 *   poison).
 *
 * Lookups are injected so the policy is unit-testable without Android and
 * so production can wire Room/Context while tests use fakes. The memory
 * scope names mirror `MemoryScope`; the saved-knowledge visibility reuses
 * [SavedKnowledgeNoteArchivePolicy].
 */
class SourceAccessAuthority(
    private val archiveAccessIncluded: () -> Boolean,
    private val lookups: SourceAccessLookups,
) {
    suspend fun isAccessible(identity: SelectionSourceIdentity): Boolean {
        if (!identity.isWellFormed()) return false
        val archiveAllowed = archiveAccessIncluded()
        return when (identity.kind) {
            SelectionSourceKind.CONVERSATION -> {
                val conversation = lookups.conversation(requireNotNull(identity.conversationId))
                    ?: return false
                if (conversation.isArchived && !archiveAllowed) return false
                true
            }
            SelectionSourceKind.PROJECT ->
                lookups.projectExists(requireNotNull(identity.projectId))
            SelectionSourceKind.PROJECT_DOCUMENT ->
                lookups.projectFileResolvable(
                    requireNotNull(identity.projectId),
                    requireNotNull(identity.fileId),
                )
            SelectionSourceKind.ATTACHED_DOCUMENT -> {
                val conversationId = requireNotNull(identity.conversationId)
                val managedPath = requireNotNull(identity.managedPath)
                val conversation = lookups.conversation(conversationId) ?: return false
                if (conversation.isArchived && !archiveAllowed) return false
                lookups.managedAttachmentResolvable(managedPath) &&
                    lookups.attachmentReferenced(conversationId, managedPath)
            }
            SelectionSourceKind.MEMORY ->
                memoryVisible(requireNotNull(identity.memoryId), archiveAllowed)
            SelectionSourceKind.SAVED_KNOWLEDGE ->
                savedKnowledgeVisible(requireNotNull(identity.savedKnowledgeId), archiveAllowed)
        }
    }

    /**
     * Archive-agnostic durable existence for settlement. The archive lock
     * gates presentation and the transient pre-settlement check, never the
     * durability of an already-authorized selection.
     */
    suspend fun existsDurably(identity: SelectionSourceIdentity): Boolean {
        if (!identity.isWellFormed()) return false
        return when (identity.kind) {
            SelectionSourceKind.CONVERSATION ->
                lookups.conversation(requireNotNull(identity.conversationId)) != null
            SelectionSourceKind.PROJECT ->
                lookups.projectExists(requireNotNull(identity.projectId))
            SelectionSourceKind.PROJECT_DOCUMENT ->
                lookups.projectFileOwned(
                    requireNotNull(identity.projectId),
                    requireNotNull(identity.fileId),
                )
            SelectionSourceKind.ATTACHED_DOCUMENT -> {
                val conversationId = requireNotNull(identity.conversationId)
                val managedPath = requireNotNull(identity.managedPath)
                lookups.conversation(conversationId) != null &&
                    lookups.managedAttachmentExists(managedPath)
            }
            SelectionSourceKind.MEMORY ->
                lookups.memoryRecorded(requireNotNull(identity.memoryId))
            SelectionSourceKind.SAVED_KNOWLEDGE ->
                lookups.savedKnowledgeExists(requireNotNull(identity.savedKnowledgeId))
        }
    }

    private suspend fun memoryVisible(memoryId: String, archiveAllowed: Boolean): Boolean {
        val record = lookups.memory(memoryId) ?: return false
        if (!record.enabled || !record.savedByUser || record.scopeName == "DISABLED") return false
        if (record.scopeName == "GLOBAL" || record.scopeName == "MODEL_ONLY") return true
        val conversation = lookups.conversation(record.conversationId) ?: return false
        return !conversation.isArchived || archiveAllowed
    }

    private suspend fun savedKnowledgeVisible(savedKnowledgeId: String, archiveAllowed: Boolean): Boolean {
        val saved = lookups.savedKnowledge(savedKnowledgeId) ?: return false
        val ownSourceArchived = saved.sourceConversationId?.let { sourceId ->
            lookups.conversation(sourceId)?.isArchived
        } == true
        val linkedStates = if (saved.provenanceType == "USER_NOTE") {
            lookups.noteSourceArchivedStates(savedKnowledgeId)
        } else {
            emptyList()
        }
        return SavedKnowledgeNoteArchivePolicy.isVisible(archiveAllowed, ownSourceArchived, linkedStates)
    }
}

/** Narrow durable reads behind the shared authority; no UI dependency. */
interface SourceAccessLookups {
    data class ConversationState(val id: Long, val isArchived: Boolean)

    data class MemoryState(
        val enabled: Boolean,
        val savedByUser: Boolean,
        val scopeName: String,
        val conversationId: Long,
    )

    data class SavedKnowledgeState(
        val sourceConversationId: Long?,
        val provenanceType: String,
    )

    suspend fun conversation(conversationId: Long): ConversationState?
    suspend fun attachmentReferenced(conversationId: Long, managedPath: String): Boolean
    suspend fun managedAttachmentResolvable(managedPath: String): Boolean
    suspend fun managedAttachmentExists(managedPath: String): Boolean
    suspend fun projectExists(projectId: Long): Boolean
    suspend fun projectFileResolvable(projectId: Long, fileId: Long): Boolean
    suspend fun projectFileOwned(projectId: Long, fileId: Long): Boolean
    suspend fun memory(memoryId: String): MemoryState?
    suspend fun memoryRecorded(memoryId: String): Boolean
    suspend fun savedKnowledge(savedKnowledgeId: String): SavedKnowledgeState?
    suspend fun savedKnowledgeExists(savedKnowledgeId: String): Boolean
    suspend fun noteSourceArchivedStates(noteId: String): List<Boolean>
}
