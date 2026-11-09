package com.projectnuke.fusion.projects

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ProjectWorkPlanStatus { ACTIVE, COMPLETED, PAUSED, CANCELLED }

enum class ProjectWorkPlanStepState { PENDING, READY, RUNNING, REVIEW, COMPLETED, SKIPPED, FAILED }

/** Internal allowlist. The UI uses [label] and never exposes enum identifiers. */
enum class ProjectWorkPlanStepType(val label: String) {
    RESEARCH("근거 조사"),
    ASK_WITH_PROJECT_CONTEXT("Project 대화"),
    COMPARE_SOURCES("자료 비교"),
    STRUCTURED_EXTRACT("핵심 정보 추출"),
    REVIEW_KNOWLEDGE("자료 확인"),
    CREATE_TASK("할 일 제안"),
    PROPOSE_DECISION("결정 제안"),
    WRITE_DRAFT("초안 작성"),
    MANUAL("직접 수행"),
    ;

    companion object {
        fun fromModelValue(value: String): ProjectWorkPlanStepType? =
            entries.firstOrNull { it.label == value.trim() || it.name == value.trim() }
    }
}

data class ProjectWorkPlanProposalStep(
    val type: ProjectWorkPlanStepType,
    val title: String,
    val instruction: String,
)

data class ProjectWorkPlanProposal(
    val title: String,
    val goal: String,
    val desiredOutput: String,
    val constraintText: String,
    val steps: List<ProjectWorkPlanProposalStep>,
)

sealed interface ProjectWorkPlanProposalParseResult {
    data class Valid(val proposal: ProjectWorkPlanProposal) : ProjectWorkPlanProposalParseResult
    data object InvalidJson : ProjectWorkPlanProposalParseResult
    data object InvalidShape : ProjectWorkPlanProposalParseResult
    data object InvalidStepType : ProjectWorkPlanProposalParseResult
    data object TooManySteps : ProjectWorkPlanProposalParseResult
    data object UnsafeInstruction : ProjectWorkPlanProposalParseResult
}

object ProjectWorkPlanProposalPolicy {
    const val MAX_STEPS = 8
    const val MAX_PLAN_TITLE_CHARS = 120
    const val MAX_GOAL_CHARS = 2_400
    const val MAX_DESIRED_OUTPUT_CHARS = 600
    const val MAX_CONSTRAINT_CHARS = 800
    const val MAX_STEP_TITLE_CHARS = 120
    const val MAX_STEP_INSTRUCTION_CHARS = 1_000
    const val MAX_RESPONSE_CHARS = 24_000

    private val unsafeInstruction = Regex(
        "(?i)(\\brm\\s+-rf\\b|\\b(del|format)\\s+[a-z]:|\\b(powershell|cmd\\.exe|adb|curl|wget)\\b|" +
            "\\b(run|execute|invoke)\\s+(a\\s+)?(shell|terminal|script|arbitrary\\s+command|tool)\\b|" +
            "\\b(create|make|generate)\\s+(another|a\\s+new|recursive|a\\s+recursive)\\s+(work\\s+)?plan\\b|" +
            "open\\s+arbitrary\\s+(url|link)|https?://|DELETE\\s+FROM|DROP\\s+TABLE)",
    )

    fun parseModelProposal(
        raw: String,
        requestedGoal: String,
        desiredOutput: String = "",
        constraintText: String = "",
    ): ProjectWorkPlanProposalParseResult {
        if (raw.length > MAX_RESPONSE_CHARS) return ProjectWorkPlanProposalParseResult.InvalidShape
        val jsonText = unwrapJson(raw.trim()) ?: return ProjectWorkPlanProposalParseResult.InvalidJson
        val root = try {
            JSONObject(jsonText)
        } catch (_: JSONException) {
            return ProjectWorkPlanProposalParseResult.InvalidJson
        }
        val title = root.string("title")?.clean(MAX_PLAN_TITLE_CHARS)
            ?: return ProjectWorkPlanProposalParseResult.InvalidShape
        val stepsJson = root.opt("steps") as? JSONArray
            ?: return ProjectWorkPlanProposalParseResult.InvalidShape
        if (stepsJson.length() !in 1..MAX_STEPS) {
            return if (stepsJson.length() > MAX_STEPS) ProjectWorkPlanProposalParseResult.TooManySteps
            else ProjectWorkPlanProposalParseResult.InvalidShape
        }
        val steps = ArrayList<ProjectWorkPlanProposalStep>(stepsJson.length())
        for (index in 0 until stepsJson.length()) {
            val step = stepsJson.optJSONObject(index) ?: return ProjectWorkPlanProposalParseResult.InvalidShape
            val typeRaw = step.string("type")?.trim()
                ?: return ProjectWorkPlanProposalParseResult.InvalidShape
            val type = ProjectWorkPlanStepType.fromModelValue(typeRaw)
                ?: return ProjectWorkPlanProposalParseResult.InvalidStepType
            val stepTitle = step.string("title")?.clean(MAX_STEP_TITLE_CHARS)
                ?: return ProjectWorkPlanProposalParseResult.InvalidShape
            val instruction = step.string("instruction")?.clean(MAX_STEP_INSTRUCTION_CHARS)
                ?: return ProjectWorkPlanProposalParseResult.InvalidShape
            if (unsafeInstruction.containsMatchIn(instruction)) {
                return ProjectWorkPlanProposalParseResult.UnsafeInstruction
            }
            steps += ProjectWorkPlanProposalStep(type, stepTitle, instruction)
        }
        val userGoal = requestedGoal.clean(MAX_GOAL_CHARS)
        val output = desiredOutput.clean(MAX_DESIRED_OUTPUT_CHARS)
        val constraint = constraintText.clean(MAX_CONSTRAINT_CHARS)
        if (userGoal.isBlank() || title.isBlank() || steps.any { it.title.isBlank() || it.instruction.isBlank() }) {
            return ProjectWorkPlanProposalParseResult.InvalidShape
        }
        return ProjectWorkPlanProposalParseResult.Valid(
            ProjectWorkPlanProposal(
                title = title,
                // The user request is authoritative. Model text cannot silently
                // replace or broaden the goal supplied in the Project flow.
                goal = userGoal,
                desiredOutput = output,
                constraintText = constraint,
                steps = steps,
            ),
        )
    }

    fun validateEditedProposal(proposal: ProjectWorkPlanProposal): Boolean =
        proposal.title.clean(MAX_PLAN_TITLE_CHARS).isNotBlank() &&
            proposal.title.length <= MAX_PLAN_TITLE_CHARS &&
            proposal.goal.clean(MAX_GOAL_CHARS).isNotBlank() &&
            proposal.goal.length <= MAX_GOAL_CHARS &&
            proposal.desiredOutput.length <= MAX_DESIRED_OUTPUT_CHARS &&
            proposal.constraintText.length <= MAX_CONSTRAINT_CHARS &&
            proposal.steps.size in 1..MAX_STEPS &&
            proposal.steps.all { step ->
                step.type in ProjectWorkPlanStepType.entries &&
                    step.title.clean(MAX_STEP_TITLE_CHARS).isNotBlank() &&
                    step.instruction.clean(MAX_STEP_INSTRUCTION_CHARS).isNotBlank() &&
                    !unsafeInstruction.containsMatchIn(step.instruction)
            }

    private fun JSONObject.string(key: String): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun String.clean(maxChars: Int): String = trim().replace(Regex("\\s+"), " ").take(maxChars)

    private fun unwrapJson(raw: String): String? {
        if (raw.isBlank()) return null
        val fenced = Regex("(?s)^```(?:json)?\\s*(\\{.*?})\\s*```$").matchEntire(raw)
            ?.groupValues?.getOrNull(1)
        if (fenced != null) return fenced
        return raw.takeIf { it.startsWith("{") && it.endsWith("}") }
    }
}

data class ProjectWorkPlanActionSnapshot(
    val planId: String,
    val stepId: String,
    val attemptId: String,
    val projectId: Long,
    val stepType: ProjectWorkPlanStepType,
    val instruction: String,
    val projectSourceIds: List<Long>,
    val interactionMode: String,
    val workflowPreset: String?,
    val webSearchEnabled: Boolean,
    val egressState: String,
) {
    fun isValid(): Boolean =
        planId.isNotBlank() && stepId.isNotBlank() && attemptId.isNotBlank() && projectId > 0L &&
            instruction.isNotBlank() && instruction.length <= ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS &&
            projectSourceIds.size <= MAX_SNAPSHOT_SOURCE_IDS && projectSourceIds.all { it > 0L } &&
            interactionMode in ALLOWED_INTERACTION_MODES && egressState in ALLOWED_EGRESS_STATES &&
            (workflowPreset == null || workflowPreset in ALLOWED_WORKFLOW_PRESETS)

    companion object {
        const val MAX_SNAPSHOT_SOURCE_IDS = 32
        private val ALLOWED_INTERACTION_MODES = setOf("CHAT", "RESEARCH", "STUDY", "CUSTOM")
        private val ALLOWED_EGRESS_STATES = setOf("LOCAL_ONLY", "APPROVED", "BLOCKED", "DEFERRED")
        private val ALLOWED_WORKFLOW_PRESETS = setOf("RESEARCH", "COMPARE", "SUMMARIZE", "DRAFT", "REVIEW")
    }
}
