package com.projectnuke.fusion.knowledge

/** Project Knowledge Health v1 — deterministic read-only provenance/access review.
 *
 * Never claims factual correctness or deletes knowledge. No network calls.
 * No numeric health score. Categories are informational only.
 */
internal object ProjectKnowledgeHealthPolicy {
    const val MAX_KNOWLEDGE_HEALTH_SCAN = 64
    const val MAX_KNOWLEDGE_HEALTH_VISIBLE = 20
    const val MAX_NOTE_SOURCES_PER_ITEM = 8
    const val MAX_KNOWLEDGE_LINEAGE = 8

    enum class ProjectKnowledgeHealthKind(val displayLabel: String) {
        SOURCE_UNAVAILABLE("확인 필요"),
        ARCHIVE_RESTRICTED("접근 제한"),
        WEB_SOURCE_MISSING("확인 필요"),
        WEB_SOURCE_INVALID("확인 필요"),
        DOCUMENT_SOURCE_UNAVAILABLE("확인 필요"),
        NOTE_LINK_UNAVAILABLE("확인 필요"),
        INDEPENDENT_COPY("정보"),
    }

    enum class AssistantStatus { AVAILABLE, ARCHIVE_RESTRICTED, MISSING }

    fun assistantStatus(
        row: com.projectnuke.fusion.data.SavedKnowledgeEntity,
        proof: ProjectKnowledgeAssistantSourceProof,
        archiveAllowed: Boolean,
    ): AssistantStatus {
        val conversationId = row.sourceConversationId ?: return AssistantStatus.MISSING
        val messageId = row.sourceMessageId ?: return AssistantStatus.MISSING
        val exactMessage = proof.conversationId == conversationId && proof.messageId == messageId &&
            proof.messageConversationId == conversationId && proof.messageRole == "assistant"
        if (!exactMessage) return AssistantStatus.MISSING
        return if (proof.conversationArchived && !archiveAllowed) AssistantStatus.ARCHIVE_RESTRICTED else AssistantStatus.AVAILABLE
    }

    fun documentSourceAvailable(
        expectedProjectId: Long,
        proof: ProjectKnowledgeDocumentSourceProof?,
    ): Boolean = proof != null && proof.sourceProjectId == expectedProjectId &&
        proof.sourceFileId > 0L && proof.fileProjectId == proof.sourceProjectId &&
        proof.projectExists && proof.fileAvailable

    data class HealthObservation(
        val kind: ProjectKnowledgeHealthKind,
        val savedKnowledgeId: String,
        val knowledgeTitle: String,
        val statement: String,
        /** True when the title originated from a source protected by archive access. */
        val archiveProtected: Boolean = false,
    )
}

internal sealed interface ProjectKnowledgeHealthLoadState {
    data object Loading : ProjectKnowledgeHealthLoadState
    data class Ready(
        val observations: List<ProjectKnowledgeHealthPolicy.HealthObservation>,
        val scanLimitReached: Boolean = false,
        val findingsTruncated: Boolean = false,
    ) : ProjectKnowledgeHealthLoadState
    data object Failed : ProjectKnowledgeHealthLoadState
}

internal data class ProjectKnowledgeHealthScanResult(
    val observations: List<ProjectKnowledgeHealthPolicy.HealthObservation>,
    val scanLimitReached: Boolean,
    val findingsTruncated: Boolean,
)
