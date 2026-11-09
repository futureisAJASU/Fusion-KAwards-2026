package com.projectnuke.fusion.chat

/** Small deterministic combinations of controls Fusion already owns. */
enum class FusionWorkflowPreset(
    val title: String,
    val summary: String,
    val guidance: String,
    val interactionMode: ChatInteractionMode,
    val webSearchEnabled: Boolean,
    val requiresProjectSources: Boolean = false,
) {
    PAPER_COMPARISON(
        title = "논문 비교",
        summary = "Research와 웹 검색",
        guidance = "자료별 주장·근거·한계와 서로 다른 점을 근거에 따라 비교해 주세요.",
        interactionMode = ChatInteractionMode.RESEARCH,
        webSearchEnabled = true,
    ),
    EXAM_PREPARATION(
        title = "시험 대비",
        summary = "Study 모드",
        guidance = "핵심 개념을 설명하고 마지막에 이해 확인 질문 3개를 내 주세요.",
        interactionMode = ChatInteractionMode.STUDY,
        webSearchEnabled = false,
    ),
    PROJECT_REVIEW(
        title = "프로젝트 점검",
        summary = "현재 Project 자료 범위",
        guidance = "현재 Project 자료를 근거로 결정, 미완료 작업, 남은 자료의 빈틈을 간결하게 점검해 주세요.",
        interactionMode = ChatInteractionMode.NORMAL,
        webSearchEnabled = false,
        requiresProjectSources = true,
    ),
}

data class FusionWorkflowConfiguration(
    val preset: FusionWorkflowPreset,
    val interactionMode: ChatInteractionMode,
    val webSearchEnabled: Boolean,
    val sourceScope: ProjectSourceScope,
)

object FusionWorkflowPolicy {
    fun configure(
        preset: FusionWorkflowPreset,
        projectId: Long?,
        projectFileCount: Int,
    ): FusionWorkflowConfiguration? {
        if (preset.requiresProjectSources && (projectId == null || projectFileCount <= 0)) return null
        val sourceScope = if (projectId != null && projectFileCount > 0) {
            ProjectSourceScope(ProjectSourceScopeKind.PROJECT, projectId)
        } else {
            ProjectSourceScope(ProjectSourceScopeKind.AVAILABLE)
        }
        return FusionWorkflowConfiguration(
            preset = preset,
            interactionMode = preset.interactionMode,
            webSearchEnabled = preset.webSearchEnabled,
            sourceScope = sourceScope,
        )
    }

    /** Guidance remains ordinary, editable composer text and is never sent by selection alone. */
    fun applyGuidance(currentDraft: String, preset: FusionWorkflowPreset): String {
        val trimmed = currentDraft.trimEnd()
        if (trimmed.endsWith(preset.guidance)) return currentDraft
        val priorWorkflow = FusionWorkflowPreset.entries.firstOrNull { trimmed.endsWith(it.guidance) }
        val base = priorWorkflow?.let { trimmed.removeSuffix(it.guidance).trimEnd() } ?: trimmed
        return if (base.isEmpty()) preset.guidance else "$base\n\n${preset.guidance}"
    }

    fun matches(
        preset: FusionWorkflowPreset,
        projectId: Long?,
        projectFileCount: Int,
        interactionMode: ChatInteractionMode,
        webSearchEnabled: Boolean,
        sourceScope: ProjectSourceScope,
        composerDraft: String,
    ): Boolean {
        val configuration = configure(preset, projectId, projectFileCount) ?: return false
        return configuration.interactionMode == interactionMode &&
            configuration.webSearchEnabled == webSearchEnabled &&
            configuration.sourceScope == sourceScope && composerDraft.trimEnd().endsWith(preset.guidance)
    }
}
