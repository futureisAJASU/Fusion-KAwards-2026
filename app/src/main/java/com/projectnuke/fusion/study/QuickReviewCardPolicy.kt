package com.projectnuke.fusion.study

import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import java.util.UUID

enum class ReviewCardScope { PERSONAL, PROJECT }
enum class ReviewCardOrigin { STUDY_TOPIC, ASSISTANT_ANSWER, SAVED_KNOWLEDGE, PROJECT_DOCUMENT, USER_TEXT, SNAPSHOT_IMPORT }
enum class ReviewCardRating { AGAIN, HARD, UNDERSTOOD }

data class QuickReviewCardDraft(
    val front: String,
    val back: String,
    val scope: ReviewCardScope = ReviewCardScope.PERSONAL,
    val projectId: Long? = null,
    val sourceKind: ReviewCardOrigin = ReviewCardOrigin.USER_TEXT,
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val sourceSavedKnowledgeId: String? = null,
    val sourceProjectId: Long? = null,
    val sourceProjectFileId: Long? = null,
)

data class QuickReviewCardProposal(
    val id: String,
    val scope: ReviewCardScope,
    val projectId: Long?,
    val front: String,
    val back: String,
    val sourceKind: ReviewCardOrigin,
    val sourceConversationId: Long?,
    val sourceMessageId: Long?,
    val sourceSavedKnowledgeId: String?,
    val sourceProjectId: Long?,
    val sourceProjectFileId: Long?,
    val dueAt: Long,
)

sealed interface QuickReviewCardPreparation {
    data class Ready(val proposal: QuickReviewCardProposal) : QuickReviewCardPreparation
    data object Invalid : QuickReviewCardPreparation
    data object SensitiveContent : QuickReviewCardPreparation
}

sealed interface ReviewCardDecision {
    data class Updated(val card: StudyReviewCardEntity) : ReviewCardDecision
    data object InvalidTime : ReviewCardDecision
}

/** Bounds copied card text and provides a small, deterministic revisit schedule. */
object QuickReviewCardPolicy {
    const val MAX_FRONT_CHARS = 600
    const val MAX_BACK_CHARS = 12_000
    const val MAX_CARDS = 512
    const val MAX_QUEUE_CARDS = 32
    const val AGAIN_INTERVAL_MILLIS = 10L * 60L * 1_000L
    const val HARD_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    const val UNDERSTOOD_INTERVAL_MILLIS = 3L * 24L * 60L * 60L * 1_000L

    fun prepare(
        draft: QuickReviewCardDraft,
        front: String,
        back: String,
        now: Long,
        id: String = UUID.randomUUID().toString(),
    ): QuickReviewCardPreparation {
        if (now < 0L || id.isBlank() || id.length > 80) return QuickReviewCardPreparation.Invalid
        val targetProject = when (draft.scope) {
            ReviewCardScope.PERSONAL -> null
            ReviewCardScope.PROJECT -> draft.projectId?.takeIf { it > 0L }
                ?: return QuickReviewCardPreparation.Invalid
        }
        if (front.length > MAX_FRONT_CHARS * 4 || back.length > MAX_BACK_CHARS * 4) {
            return QuickReviewCardPreparation.Invalid
        }
        val cleanFront = visibleMessageContentPreservingWhitespace(front).trim()
        val cleanBack = visibleMessageContentPreservingWhitespace(back).trim()
        if (cleanFront.isBlank() || cleanBack.isBlank() ||
            cleanFront.length > MAX_FRONT_CHARS || cleanBack.length > MAX_BACK_CHARS ||
            draft.sourceConversationId?.let { it <= 0L } == true ||
            draft.sourceMessageId?.let { it <= 0L } == true ||
            draft.sourceProjectId?.let { it <= 0L } == true ||
            draft.sourceProjectFileId?.let { it <= 0L } == true ||
            draft.sourceSavedKnowledgeId?.let { it.isBlank() || it.length > 80 } == true
        ) return QuickReviewCardPreparation.Invalid
        val sourceValid = when (draft.sourceKind) {
            ReviewCardOrigin.STUDY_TOPIC -> draft.sourceConversationId != null && draft.sourceMessageId == null &&
                draft.sourceSavedKnowledgeId == null && draft.sourceProjectId == null && draft.sourceProjectFileId == null
            ReviewCardOrigin.ASSISTANT_ANSWER -> draft.sourceConversationId != null && draft.sourceMessageId != null &&
                draft.sourceSavedKnowledgeId == null && draft.sourceProjectId == null && draft.sourceProjectFileId == null
            ReviewCardOrigin.SAVED_KNOWLEDGE -> !draft.sourceSavedKnowledgeId.isNullOrBlank() &&
                draft.sourceConversationId == null && draft.sourceMessageId == null && draft.sourceProjectId == null && draft.sourceProjectFileId == null
            ReviewCardOrigin.PROJECT_DOCUMENT -> draft.sourceProjectId != null && draft.sourceProjectFileId != null &&
                draft.sourceConversationId == null && draft.sourceMessageId == null && draft.sourceSavedKnowledgeId == null
            ReviewCardOrigin.USER_TEXT -> draft.sourceConversationId == null && draft.sourceMessageId == null &&
                draft.sourceSavedKnowledgeId == null && draft.sourceProjectId == null && draft.sourceProjectFileId == null
            ReviewCardOrigin.SNAPSHOT_IMPORT -> draft.sourceConversationId == null && draft.sourceMessageId == null &&
                draft.sourceSavedKnowledgeId == null && draft.sourceProjectId == null && draft.sourceProjectFileId == null
        }
        if (!sourceValid) return QuickReviewCardPreparation.Invalid
        if (SavedKnowledgePolicy.containsSensitiveMaterial(cleanFront + "\n" + cleanBack)) {
            return QuickReviewCardPreparation.SensitiveContent
        }
        return QuickReviewCardPreparation.Ready(
            QuickReviewCardProposal(
                id, draft.scope, targetProject, cleanFront, cleanBack, draft.sourceKind,
                draft.sourceConversationId, draft.sourceMessageId, draft.sourceSavedKnowledgeId,
                draft.sourceProjectId, draft.sourceProjectFileId, now,
            ),
        )
    }

    fun toEntity(proposal: QuickReviewCardProposal): StudyReviewCardEntity = StudyReviewCardEntity(
        id = proposal.id,
        scope = proposal.scope.name,
        projectId = proposal.projectId,
        front = proposal.front,
        back = proposal.back,
        sourceKind = proposal.sourceKind.name,
        sourceConversationId = proposal.sourceConversationId,
        sourceMessageId = proposal.sourceMessageId,
        sourceSavedKnowledgeId = proposal.sourceSavedKnowledgeId,
        sourceProjectId = proposal.sourceProjectId,
        sourceProjectFileId = proposal.sourceProjectFileId,
        dueAt = proposal.dueAt,
        createdAt = proposal.dueAt,
        updatedAt = proposal.dueAt,
    )

    fun rate(card: StudyReviewCardEntity, rating: ReviewCardRating, now: Long): ReviewCardDecision {
        if (now < 0L || now > Long.MAX_VALUE - UNDERSTOOD_INTERVAL_MILLIS || card.reviewCount < 0) {
            return ReviewCardDecision.InvalidTime
        }
        val interval = when (rating) {
            ReviewCardRating.AGAIN -> AGAIN_INTERVAL_MILLIS
            ReviewCardRating.HARD -> HARD_INTERVAL_MILLIS
            ReviewCardRating.UNDERSTOOD -> UNDERSTOOD_INTERVAL_MILLIS
        }
        return ReviewCardDecision.Updated(
            card.copy(
                reviewCount = if (card.reviewCount >= 1_000_000) 1_000_000 else card.reviewCount + 1,
                lastRating = rating.name,
                dueAt = now + interval,
                lastReviewedAt = now,
                updatedAt = now,
            ),
        )
    }
}
