package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import java.util.UUID

enum class SavedKnowledgeScope { PERSONAL, PROJECT }
enum class SavedKnowledgeProvenance { ASSISTANT_ANSWER, INBOX_CAPTURE, SHARE_CAPTURE, WEB_CLIP, DOCUMENT_HIGHLIGHT, PORTABLE_SNAPSHOT, PORTABLE_SNAPSHOT_SUMMARY, USER_NOTE }

data class SavedKnowledgeDraft(
    val title: String,
    val visibleText: String,
    val sourceConversationId: Long,
    val sourceMessageId: Long,
    val projectId: Long?,
    val projectName: String?,
)

data class SavedKnowledgeProposal(
    val id: String,
    val scope: SavedKnowledgeScope,
    val projectId: Long?,
    val title: String,
    val visibleText: String,
    val sourceConversationId: Long,
    val sourceMessageId: Long,
    val createdAt: Long,
)

sealed interface SavedKnowledgePreparation {
    data class Ready(val proposal: SavedKnowledgeProposal) : SavedKnowledgePreparation
    data object Invalid : SavedKnowledgePreparation
}

/** Bounds and strips the durable snapshot before it reaches the repository. */
object SavedKnowledgePolicy {
    const val MAX_TITLE_CHARS = 160
    const val MAX_TEXT_CHARS = 12_000
    private val secretPattern = Regex(
        "(?i)(?:\\b(?:sk-[a-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,})\\b|\\b(?:key|api[_-]?key|access[_-]?token|secret)\\s*[:=]\\s*\\S{12,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)",
    )
    private val internalPathPattern = Regex("(?i)(?:[a-z]:\\\\(?:users|program files|windows)\\\\|/(?:data/user|data/data|storage/emulated/0/android/data)/)")

    fun prepare(
        draft: SavedKnowledgeDraft,
        title: String,
        scope: SavedKnowledgeScope,
        now: Long,
        id: String = UUID.randomUUID().toString(),
    ): SavedKnowledgePreparation {
        if (now < 0L || id.isBlank() || id.length > 80 ||
            draft.sourceConversationId <= 0L || draft.sourceMessageId <= 0L
        ) return SavedKnowledgePreparation.Invalid
        val projectId = when (scope) {
            SavedKnowledgeScope.PERSONAL -> null
            SavedKnowledgeScope.PROJECT -> draft.projectId?.takeIf { it > 0L }
                ?: return SavedKnowledgePreparation.Invalid
        }
        val boundedTitle = title.trim().take(MAX_TITLE_CHARS)
        val visibleText = visibleMessageContentPreservingWhitespace(draft.visibleText).take(MAX_TEXT_CHARS)
        if (boundedTitle.isBlank() || visibleText.isBlank()) return SavedKnowledgePreparation.Invalid
        return SavedKnowledgePreparation.Ready(
            SavedKnowledgeProposal(
                id = id,
                scope = scope,
                projectId = projectId,
                title = boundedTitle,
                visibleText = visibleText,
                sourceConversationId = draft.sourceConversationId,
                sourceMessageId = draft.sourceMessageId,
                createdAt = now,
            ),
        )
    }

    fun toEntity(proposal: SavedKnowledgeProposal): SavedKnowledgeEntity = SavedKnowledgeEntity(
        id = proposal.id,
        scope = proposal.scope.name,
        projectId = proposal.projectId,
        title = proposal.title,
        savedText = proposal.visibleText,
        provenanceType = SavedKnowledgeProvenance.ASSISTANT_ANSWER.name,
        sourceConversationId = proposal.sourceConversationId,
        sourceMessageId = proposal.sourceMessageId,
        createdAt = proposal.createdAt,
        updatedAt = proposal.createdAt,
    )

    fun containsSensitiveMaterial(visibleText: String): Boolean =
        secretPattern.containsMatchIn(visibleText) || internalPathPattern.containsMatchIn(visibleText)
}
