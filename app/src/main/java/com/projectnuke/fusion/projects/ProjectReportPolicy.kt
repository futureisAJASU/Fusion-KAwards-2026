package com.projectnuke.fusion.projects

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded deterministic Markdown projection over already durable Project data. */
object ProjectReportPolicy {
    const val MAX_REPORT_CHARS = 18_000
    const val MAX_DECISIONS = 6
    const val MAX_TASKS = 14
    const val MAX_KNOWLEDGE = 5
    const val MAX_RESEARCH = 4
    const val MAX_FILES = 6
    const val MAX_CHANGES = 8
    const val MAX_PLANS = 4
    const val MAX_TEXT_CHARS = 900
    private const val MAX_REPORT_TITLE_CHARS = 160
    private const val MAX_DECISION_REASON_CHARS = 220
    private const val MAX_KNOWLEDGE_EXCERPT_CHARS = 240
    private const val MAX_RESEARCH_SUMMARY_CHARS = 520
    private val markdownEscapes = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '>', '<', ':', '/', '?', '@')

    data class Decision(val title: String, val rationale: String)
    data class Task(val title: String, val state: String)
    data class Knowledge(val title: String, val excerpt: String)
    data class Research(val conversationId: Long, val title: String, val summary: String)
    data class SourceFile(val displayName: String, val mimeType: String, val summary: String = "")
    data class Change(val kind: String, val title: String)
    data class PlanStep(val title: String, val state: String)
    data class Plan(val title: String, val status: String, val steps: List<PlanStep>, val currentStepPosition: Int)

    data class Input(
        val projectName: String,
        val instructions: String,
        val activeDecisions: List<Decision> = emptyList(),
        val tasks: List<Task> = emptyList(),
        val savedKnowledge: List<Knowledge> = emptyList(),
        val recentResearch: List<Research> = emptyList(),
        val sourceFiles: List<SourceFile> = emptyList(),
        val plans: List<Plan> = emptyList(),
        val recentChanges: List<Change> = emptyList(),
        val generatedAt: Long,
    )

    fun build(input: Input): String {
        val sections = mutableListOf<String>()
        sections += "# ${clean(input.projectName).ifBlank { "Project" }}"

        val goal = clean(input.instructions)
        sections += if (goal.isBlank()) {
            "## 목표\nProject 지침에 목표가 기록되어 있지 않습니다."
        } else "## 목표\n${goal.take(MAX_TEXT_CHARS)}"

        val openTasks = input.tasks.filter { it.state != "DONE" }.take(MAX_TASKS)
        val doneTasks = input.tasks.filter { it.state == "DONE" }.take(MAX_TASKS)
        val openTaskCount = input.tasks.count { it.state != "DONE" }
        val doneTaskCount = input.tasks.count { it.state == "DONE" }
        val activePlan = input.plans.firstOrNull { it.status == "ACTIVE" }
            ?: input.plans.firstOrNull { it.status == "PAUSED" }
        val stateLines = buildList {
            add("열린 할 일 ${openTaskCount}개, 완료한 할 일 ${doneTaskCount}개")
            activePlan?.let { plan ->
                val settled = plan.steps.count { it.state == "COMPLETED" || it.state == "SKIPPED" }
                val label = if (plan.status == "PAUSED") "일시 중지된 계획" else "진행 중인 계획"
                add("$label ‘${clean(plan.title)}’ (${settled} / ${plan.steps.size} 단계 정리)")
            }
        }
        sections += "## 현재 상태\n" + stateLines.joinToString("\n") { "- $it" }

        input.activeDecisions.take(MAX_DECISIONS).takeIf { it.isNotEmpty() }?.let { rows ->
            sections += "## 주요 결정\n" + rows.joinToString("\n") { row ->
                val rationale = clean(row.rationale).take(MAX_DECISION_REASON_CHARS)
                "- ${clean(row.title).take(MAX_REPORT_TITLE_CHARS)}" + if (rationale.isNotBlank()) " — $rationale" else ""
            }
        }
        doneTasks.takeIf { it.isNotEmpty() }?.let { rows ->
            sections += "## 완료한 작업\n" + rows.joinToString("\n") { "- ${clean(it.title).take(MAX_REPORT_TITLE_CHARS)}" } +
                if (doneTaskCount > rows.size) "\n- 외 ${doneTaskCount - rows.size}건" else ""
        }
        openTasks.takeIf { it.isNotEmpty() }?.let { rows ->
            sections += "## 열린 작업\n" + rows.joinToString("\n") { "- ${clean(it.title).take(MAX_REPORT_TITLE_CHARS)}" } +
                if (openTaskCount > rows.size) "\n- 외 ${openTaskCount - rows.size}건" else ""
        }

        val evidence = buildList {
            input.savedKnowledge.take(MAX_KNOWLEDGE).forEach { item ->
                val excerpt = clean(item.excerpt).take(MAX_KNOWLEDGE_EXCERPT_CHARS)
                add("- **${clean(item.title).take(MAX_REPORT_TITLE_CHARS)}**" + if (excerpt.isNotBlank()) ": $excerpt" else "")
            }
            input.recentResearch.take(MAX_RESEARCH).forEach { item ->
                val summary = clean(item.summary).take(MAX_RESEARCH_SUMMARY_CHARS)
                add("- **${clean(item.title).take(MAX_REPORT_TITLE_CHARS)}** (대화 ${item.conversationId})" + if (summary.isNotBlank()) ": $summary" else "")
            }
            input.sourceFiles.take(MAX_FILES).forEach {
                val summary = clean(it.summary).take(MAX_RESEARCH_SUMMARY_CHARS)
                add("- ${clean(it.displayName).take(MAX_REPORT_TITLE_CHARS)} (${clean(it.mimeType).take(80)})" +
                    if (summary.isNotBlank()) ": $summary" else "")
            }
        }
        if (evidence.isNotEmpty()) sections += "## 핵심 근거/자료\n" + evidence.joinToString("\n")

        val risks = buildList {
            activePlan?.steps?.filter { it.state !in setOf("COMPLETED", "SKIPPED") }?.take(8)
                ?.forEach { add("- 계획 단계 확인 필요: ${clean(it.title)}") }
            openTasks.take(8).forEach { add("- 미완료 작업: ${clean(it.title)}") }
        }
        if (risks.isNotEmpty()) sections += "## 남은 리스크\n" + risks.distinct().take(12).joinToString("\n")

        input.plans.take(MAX_PLANS).takeIf { it.isNotEmpty() }?.let { plans ->
            sections += "## 작업 계획\n" + plans.joinToString("\n") { plan ->
                val settled = plan.steps.count { it.state == "COMPLETED" || it.state == "SKIPPED" }
                "- ${clean(plan.title).take(MAX_REPORT_TITLE_CHARS)} · ${planStatusLabel(plan.status)} · $settled / ${plan.steps.size} 단계 정리"
            }
        }

        val next = activePlan?.steps?.getOrNull(activePlan.currentStepPosition)
            ?.takeIf { it.state !in setOf("COMPLETED", "SKIPPED") }
            ?.let { clean(it.title).take(MAX_REPORT_TITLE_CHARS) }
            ?: openTasks.firstOrNull()?.let { clean(it.title).take(MAX_REPORT_TITLE_CHARS) }
        if (next != null) sections += "## 다음 단계\n- $next"

        input.recentChanges.take(MAX_CHANGES).takeIf { it.isNotEmpty() }?.let { changes ->
            sections += "## 최근 프로젝트 변경\n" + changes.joinToString("\n") { "- ${clean(it.kind).take(48)}: ${clean(it.title).take(MAX_REPORT_TITLE_CHARS)}" }
        }
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(input.generatedAt))
        sections += "_이 보고서는 $generatedAt 시점에 Project에 남아 있는 자료를 바탕으로 만든 결정적 요약입니다._"
        val markdown = sections.joinToString("\n\n")
        if (markdown.length <= MAX_REPORT_CHARS) return markdown
        val truncatedNotice = "\n\n_보고서 표시 한도에 맞춰 뒤쪽 일부 항목을 생략했습니다._"
        val safeEnd = (MAX_REPORT_CHARS - truncatedNotice.length).coerceAtLeast(0)
        val boundedPrefix = markdown.take(safeEnd).substringBeforeLast("\n\n", missingDelimiterValue = markdown.take(safeEnd))
        return boundedPrefix + truncatedNotice
    }

    private fun clean(value: String): String = buildString {
        value.replace('\u0000', ' ')
            .replace(Regex("[\\t\\r\\n ]+"), " ")
            .trim()
            .forEach { character ->
                if (character in markdownEscapes) append('\\')
                append(character)
            }
    }

    private fun planStatusLabel(status: String): String = when (status) {
        "ACTIVE" -> "진행 중"
        "COMPLETED" -> "완료"
        "PAUSED" -> "일시 중지"
        "CANCELLED" -> "취소됨"
        else -> "상태 확인 필요"
    }
}
