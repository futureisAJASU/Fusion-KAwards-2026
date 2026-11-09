package com.projectnuke.fusion.projects

data class ProjectWorkPlanRequest(
    val goal: String,
    val desiredOutput: String,
    val constraintText: String,
)

object ProjectWorkPlanPromptPolicy {
    const val MAX_REQUEST_PROMPT_CHARS = 4_800
    private const val REQUEST_MARKER = "[작업 계획 제안 요청]"

    fun normalizeRequest(request: ProjectWorkPlanRequest): ProjectWorkPlanRequest? {
        val goal = clean(request.goal, ProjectWorkPlanProposalPolicy.MAX_GOAL_CHARS)
        val output = clean(request.desiredOutput, ProjectWorkPlanProposalPolicy.MAX_DESIRED_OUTPUT_CHARS)
        val constraint = clean(request.constraintText, ProjectWorkPlanProposalPolicy.MAX_CONSTRAINT_CHARS)
        if (goal.isBlank()) return null
        return ProjectWorkPlanRequest(goal, output, constraint)
    }

    fun buildPrompt(request: ProjectWorkPlanRequest): String? {
        val normalized = normalizeRequest(request) ?: return null
        val allowedTypes = ProjectWorkPlanStepType.entries.joinToString(" / ") { it.label }
        return buildString {
            appendLine(REQUEST_MARKER)
            appendLine("목표: ${normalized.goal}")
            appendLine("원하는 결과: ${normalized.desiredOutput.ifBlank { "(지정하지 않음)" }}")
            appendLine("제약: ${normalized.constraintText.ifBlank { "(지정하지 않음)" }}")
            appendLine()
            appendLine("현재 Project에 연결된 자료와 위 요청만 바탕으로, 사용자가 검토할 작업 순서를 제안해 주세요.")
            appendLine("이 요청은 계획 제안만 생성합니다. 단계를 실행하거나, 대화에 자동 전송하거나, 할 일·결정을 만들거나, 자료를 업로드하지 마세요.")
            appendLine("허용 단계 유형: $allowedTypes")
            appendLine("4~6단계를 권장하며 최대 8단계로 작성하세요. 각 단계의 제목과 구체적인 사용자 지시를 작성하세요.")
            appendLine("직접 수행할 수 없는 작업은 사용자가 자료를 확인하거나 결정할 수 있는 단계로 제안하세요.")
            appendLine("숨겨진 추론이나 chain-of-thought는 포함하지 마세요. 임의 명령, 스크립트 실행, 임의 URL 열기, 자동 네트워크 동작을 제안하지 마세요.")
            appendLine("아래 JSON 객체만 반환하세요. 설명이나 마크다운 코드 펜스는 붙이지 마세요.")
            appendLine("{\"title\":\"계획 제목\",\"goal\":\"요청 목표\",\"steps\":[{\"type\":\"허용 단계 유형 중 하나\",\"title\":\"단계 제목\",\"instruction\":\"단계별 지시\"}]}")
        }.trim().takeIf { it.length <= MAX_REQUEST_PROMPT_CHARS }
    }

    /** The user's composer draft is the authority for the goal during import. */
    fun parseRequestFromComposer(raw: String): ProjectWorkPlanRequest? {
        val marker = raw.indexOf(REQUEST_MARKER)
        if (marker < 0 || raw.length > MAX_REQUEST_PROMPT_CHARS) return null
        val lines = raw.substring(marker + REQUEST_MARKER.length).lineSequence().take(4).toList()
        fun value(prefix: String): String? = lines.firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)?.trim()?.takeIf(String::isNotBlank)
        val goal = value("목표:") ?: return null
        val output = value("원하는 결과:").orEmpty().removePlaceholder()
        val constraint = value("제약:").orEmpty().removePlaceholder()
        return normalizeRequest(ProjectWorkPlanRequest(goal, output, constraint))
    }

    private fun String.removePlaceholder(): String = takeUnless { it == "(지정하지 않음)" }.orEmpty()

    private fun clean(raw: String, maxChars: Int): String =
        raw.trim().replace(Regex("\\s+"), " ").take(maxChars)
}
