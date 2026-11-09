package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ConversationEntity
import kotlinx.coroutines.CancellationException

/** The archive lock is an access boundary shared by every entry point. */
internal object ArchiveAccessPolicy {
    fun accessibleConversations(
        conversations: List<ConversationEntity>,
        archiveLockEnabled: Boolean,
        archiveUnlockedForSession: Boolean,
    ): List<ConversationEntity> = conversations.filter {
        canAccessConversation(it.isArchived, archiveLockEnabled, archiveUnlockedForSession)
    }

    fun accessibleSummaries(
        summaries: List<ConversationSummaryMemory>,
        accessibleConversationIds: Set<Long>,
    ): List<ConversationSummaryMemory> = summaries.filter { it.conversationId in accessibleConversationIds }

    /** Explicitly promoted memories remain shared; their source title does not. */
    fun accessibleMemories(
        memories: List<ConversationMemoryCandidate>,
        accessibleConversationIds: Set<Long>,
    ): List<ConversationMemoryCandidate> = memories.mapNotNull { candidate ->
        when {
            candidate.conversationId in accessibleConversationIds -> candidate
            candidate.scope == MemoryScope.GLOBAL || candidate.scope == MemoryScope.MODEL_ONLY ->
                candidate.copy(conversationTitle = null)
            else -> null
        }
    }

    fun canAccessConversation(
        isArchived: Boolean,
        archiveLockEnabled: Boolean,
        archiveUnlockedForSession: Boolean,
    ): Boolean = !isArchived || !archiveLockEnabled || archiveUnlockedForSession

    fun canRenderArchive(
        archiveLockEnabled: Boolean,
        archiveUnlockedForSession: Boolean,
    ): Boolean = canAccessConversation(
        isArchived = true,
        archiveLockEnabled = archiveLockEnabled,
        archiveUnlockedForSession = archiveUnlockedForSession,
    )

    fun canIncludeArchivedInExport(
        archiveLockEnabled: Boolean,
        archiveUnlockedForSession: Boolean,
    ): Boolean = canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
}

internal const val ArchiveLockPreferenceKey = "archive_lock_enabled"

/** Final durable lookup: stale list rows never authorize selection. */
internal suspend fun archiveConversationAdmission(
    lookup: suspend () -> ConversationEntity?,
    archiveLockEnabled: () -> Boolean,
    archiveUnlockedForSession: Boolean,
): Boolean = try {
    val conversation = lookup()
    conversation != null && ArchiveAccessPolicy.canAccessConversation(
        conversation.isArchived, archiveLockEnabled(), archiveUnlockedForSession,
    )
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    false
}
