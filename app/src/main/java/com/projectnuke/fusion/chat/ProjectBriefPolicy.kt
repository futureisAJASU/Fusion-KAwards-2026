package com.projectnuke.fusion.chat

const val MAX_RECENT_SUMMARIES = 5
const val MAX_TITLE_CHARS = 120
const val MAX_SUMMARY_PREVIEW_CHARS = 600
const val MAX_BRIEF_SUMMARY_CANDIDATES = 32
const val MAX_BRIEF_CHANGE_ROWS = 12

data class ProjectBriefConversationInput(
    val conversationId: Long,
    val title: String,
    val conversationUpdatedAt: Long,
    val summary: String?,
    val summaryUpdatedAt: Long?,
)

data class ProjectBriefFileInput(
    val displayName: String,
    val mimeType: String,
)

data class ProjectBriefInput(
    val projectId: Long,
    val projectName: String,
    val instructions: String,
    val conversations: List<ProjectBriefConversationInput>,
    val files: List<ProjectBriefFileInput>,
    /** IDs whose summary fields were actually inspected by the bounded loader. */
    val summaryCandidateConversationIds: Set<Long> = conversations.map { it.conversationId }.toSet(),
    val savedKnowledge: List<ProjectBriefKnowledgeInput> = emptyList(),
    val openTasks: List<ProjectBriefTaskInput> = emptyList(),
    val decisions: List<ProjectBriefDecisionInput> = emptyList(),
    val changeCandidates: List<ProjectBriefChangeInput> = emptyList(),
)

data class ProjectBriefKnowledgeInput(val id: String, val title: String, val updatedAt: Long, val retrievedAt: Long? = null)
data class ProjectBriefTaskInput(val id: Long, val title: String, val updatedAt: Long)
data class ProjectBriefDecisionInput(val id: String, val title: String, val status: String, val createdAt: Long, val rationale: String = "")
data class ProjectBriefChangeInput(val key: String, val kind: String, val title: String, val changedAt: Long, val conversationId: Long? = null)

data class ProjectBriefConversationItem(
    val conversationId: Long,
    val title: String,
    val summaryPreview: String,
    val updatedAt: Long,
)

data class ProjectBriefSnapshot(
    val projectId: Long,
    val projectName: String,
    val conversationCount: Int,
    val fileCount: Int,
    /** Count of usable summaries in [ProjectBriefInput.summaryCandidateConversationIds]. */
    val recentSummaryCount: Int,
    val recentSummaries: List<ProjectBriefConversationItem>,
    val recentKnowledge: List<ProjectBriefKnowledgeInput> = emptyList(),
    val openTasks: List<ProjectBriefTaskInput> = emptyList(),
    val recentDecisions: List<ProjectBriefDecisionInput> = emptyList(),
    val activeDecisions: List<ProjectBriefDecisionInput> = emptyList(),
    val changeCandidates: List<ProjectBriefChangeInput> = emptyList(),
)

sealed interface ProjectBriefLoadState {
    data object Loading : ProjectBriefLoadState
    data class Ready(val snapshot: ProjectBriefSnapshot) : ProjectBriefLoadState
    data object Unavailable : ProjectBriefLoadState
}

enum class ProjectBriefContentState {
    LOADING,
    READY,
    PROJECT_EMPTY,
    NO_SUMMARIES,
    UNAVAILABLE,
}

fun buildProjectBriefSnapshot(input: ProjectBriefInput): ProjectBriefSnapshot {
    val uniqueConversations = input.conversations.distinctBy { it.conversationId }
    val candidateIds = input.summaryCandidateConversationIds
    val summarizedItems = uniqueConversations
        .asSequence()
        .filter { it.conversationId in candidateIds }
        .mapNotNull { conversation ->
            val normalizedSummary = conversation.summary
                ?.let(::sanitizeFusionMemoryText)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ProjectBriefConversationItem(
                conversationId = conversation.conversationId,
                title = boundedProjectBriefTitle(conversation.title),
                summaryPreview = normalizedSummary.takeUtf16Safe(MAX_SUMMARY_PREVIEW_CHARS),
                updatedAt = maxOf(
                    conversation.conversationUpdatedAt,
                    conversation.summaryUpdatedAt ?: Long.MIN_VALUE,
                ),
            )
        }
        .sortedWith(
            compareByDescending<ProjectBriefConversationItem> { it.updatedAt }
                .thenByDescending { it.conversationId },
        )
        .toList()

    return ProjectBriefSnapshot(
        projectId = input.projectId,
        projectName = input.projectName,
        conversationCount = uniqueConversations.size,
        fileCount = input.files.size,
        recentSummaryCount = summarizedItems.size,
        recentSummaries = summarizedItems.take(MAX_RECENT_SUMMARIES),
        recentKnowledge = input.savedKnowledge.distinctBy { it.id }.sortedByDescending { it.updatedAt }.take(3),
        openTasks = input.openTasks.distinctBy { it.id }.filter { it.title.isNotBlank() }.sortedByDescending { it.updatedAt }.take(3),
        recentDecisions = input.decisions.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(3),
        activeDecisions = input.decisions.asSequence().filter { it.status == "ACTIVE" }
            .distinctBy { it.id }.sortedByDescending { it.createdAt }.take(3)
            .map { it.copy(title = it.title.takeUtf16Safe(200), rationale = it.rationale.takeUtf16Safe(240)) }.toList(),
        changeCandidates = input.changeCandidates.distinctBy { it.key }.sortedWith(
            compareByDescending<ProjectBriefChangeInput> { it.changedAt }.thenByDescending { it.key },
        ).take(64).map { it.copy(title = it.title.takeUtf16Safe(MAX_TITLE_CHARS)) },
    )
}

/** Most recent current-project conversations considered for summary IO. */
fun selectProjectBriefSummaryCandidates(
    conversations: List<ProjectBriefConversationInput>,
): List<ProjectBriefConversationInput> = conversations
    .distinctBy { it.conversationId }
    .sortedWith(
        compareByDescending<ProjectBriefConversationInput> { it.conversationUpdatedAt }
            .thenByDescending { it.conversationId },
    )
    .take(MAX_BRIEF_SUMMARY_CANDIDATES)

fun projectBriefRecentChanges(snapshot: ProjectBriefSnapshot, now: Long, days: Int): List<ProjectBriefChangeInput> {
    if (now < 0L || days !in setOf(7, 30)) return emptyList()
    val cutoff = now - days * 24L * 60L * 60L * 1000L
    return snapshot.changeCandidates.asSequence().filter { it.changedAt in cutoff..now }
        .sortedWith(compareByDescending<ProjectBriefChangeInput> { it.changedAt }.thenByDescending { it.key })
        .take(MAX_BRIEF_CHANGE_ROWS).toList()
}

fun projectBriefContentState(state: ProjectBriefLoadState): ProjectBriefContentState = when (state) {
    ProjectBriefLoadState.Loading -> ProjectBriefContentState.LOADING
    ProjectBriefLoadState.Unavailable -> ProjectBriefContentState.UNAVAILABLE
    is ProjectBriefLoadState.Ready -> when {
        state.snapshot.conversationCount == 0 && state.snapshot.fileCount == 0 &&
            state.snapshot.recentKnowledge.isEmpty() && state.snapshot.openTasks.isEmpty() &&
            state.snapshot.recentDecisions.isEmpty() -> ProjectBriefContentState.PROJECT_EMPTY
        state.snapshot.recentSummaries.isEmpty() && state.snapshot.recentKnowledge.isEmpty() &&
            state.snapshot.openTasks.isEmpty() && state.snapshot.recentDecisions.isEmpty() -> ProjectBriefContentState.NO_SUMMARIES
        else -> ProjectBriefContentState.READY
    }
}

private fun boundedProjectBriefTitle(raw: String): String =
    raw.trim().ifBlank { "새 대화" }.takeUtf16Safe(MAX_TITLE_CHARS)

private fun String.takeUtf16Safe(maxChars: Int): String {
    if (length <= maxChars) return this
    var end = maxChars.coerceAtLeast(0)
    if (end < length && end > 0 && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
        end -= 1
    }
    return substring(0, end)
}
