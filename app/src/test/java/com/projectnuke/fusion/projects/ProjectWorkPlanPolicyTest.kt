package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectWorkPlanPolicyTest {
    @Test
    fun `parses bounded allowlisted proposal and keeps user goal authoritative`() {
        val result = ProjectWorkPlanProposalPolicy.parseModelProposal(
            """{"title":"제출 준비","goal":"모델이 쓴 목표","steps":[{"type":"RESEARCH","title":"근거 확인","instruction":"공개 근거의 출처와 날짜를 확인한다."},{"type":"MANUAL","title":"최종 확인","instruction":"사용자가 제출 자료를 확인한다."}]}""",
            requestedGoal = "공모전 제출 준비",
            desiredOutput = "제출용 초안",
            constraintText = "Project 자료만 사용",
        )

        assertTrue(result is ProjectWorkPlanProposalParseResult.Valid)
        val proposal = (result as ProjectWorkPlanProposalParseResult.Valid).proposal
        assertEquals("공모전 제출 준비", proposal.goal)
        assertEquals("제출용 초안", proposal.desiredOutput)
        assertEquals(2, proposal.steps.size)
        assertEquals(ProjectWorkPlanStepType.RESEARCH, proposal.steps.first().type)
    }

    @Test
    fun `rejects malformed json without yielding a persistable proposal`() {
        assertEquals(
            ProjectWorkPlanProposalParseResult.InvalidJson,
            ProjectWorkPlanProposalPolicy.parseModelProposal("{not-json", requestedGoal = "goal"),
        )
    }

    @Test
    fun `rejects unknown and generic tool step types`() {
        listOf("TOOL", "SHELL", "CREATE_PLAN").forEach { type ->
            val result = ProjectWorkPlanProposalPolicy.parseModelProposal(
                """{"title":"title","goal":"goal","steps":[{"type":"$type","title":"step","instruction":"do something"}]}""",
                requestedGoal = "goal",
            )
            assertEquals(ProjectWorkPlanProposalParseResult.InvalidStepType, result)
        }
    }

    @Test
    fun `rejects more than eight steps`() {
        val steps = (1..9).joinToString(",") {
            """{"type":"MANUAL","title":"step $it","instruction":"manual work"}"""
        }
        val result = ProjectWorkPlanProposalPolicy.parseModelProposal(
            """{"title":"title","goal":"goal","steps":[$steps]}""",
            requestedGoal = "goal",
        )
        assertEquals(ProjectWorkPlanProposalParseResult.TooManySteps, result)
    }

    @Test
    fun `rejects empty instruction and arbitrary command execution`() {
        val empty = ProjectWorkPlanProposalPolicy.parseModelProposal(
            """{"title":"title","goal":"goal","steps":[{"type":"MANUAL","title":"step","instruction":" "}]}""",
            requestedGoal = "goal",
        )
        val command = ProjectWorkPlanProposalPolicy.parseModelProposal(
            """{"title":"title","goal":"goal","steps":[{"type":"RESEARCH","title":"step","instruction":"Run a shell command to fetch arbitrary URLs."}]}""",
            requestedGoal = "goal",
        )
        val recursive = ProjectWorkPlanProposalPolicy.parseModelProposal(
            """{"title":"title","goal":"goal","steps":[{"type":"MANUAL","title":"step","instruction":"Generate another plan for this step."}]}""",
            requestedGoal = "goal",
        )
        assertEquals(ProjectWorkPlanProposalParseResult.InvalidShape, empty)
        assertEquals(ProjectWorkPlanProposalParseResult.UnsafeInstruction, command)
        assertEquals(ProjectWorkPlanProposalParseResult.UnsafeInstruction, recursive)
    }

    @Test
    fun `validates edits and bounded frozen action snapshot`() {
        val proposal = ProjectWorkPlanProposal(
            title = "Plan",
            goal = "Goal",
            desiredOutput = "Draft",
            constraintText = "Local Project files",
            steps = listOf(ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.MANUAL, "Check", "Review the file")),
        )
        assertTrue(ProjectWorkPlanProposalPolicy.validateEditedProposal(proposal))
        assertFalse(ProjectWorkPlanProposalPolicy.validateEditedProposal(proposal.copy(steps = emptyList())))
        val snapshot = ProjectWorkPlanActionSnapshot(
            planId = "plan", stepId = "step", attemptId = "attempt", projectId = 3,
            stepType = ProjectWorkPlanStepType.RESEARCH, instruction = "Check sources",
            projectSourceIds = listOf(1, 2), interactionMode = "RESEARCH", workflowPreset = "RESEARCH",
            webSearchEnabled = false, egressState = "LOCAL_ONLY",
        )
        assertTrue(snapshot.isValid())
        assertFalse(snapshot.copy(projectSourceIds = (1..33).map(Int::toLong)).isValid())
        assertFalse(snapshot.copy(egressState = "AUTO_ENABLE_WEB").isValid())
    }

    @Test
    fun `plan prompt is bounded and request parsing preserves user authored fields`() {
        val request = ProjectWorkPlanRequest("공모전 제출 준비", "제출용 초안", "Project 안의 자료만 사용")
        val prompt = ProjectWorkPlanPromptPolicy.buildPrompt(request)

        assertTrue(prompt != null)
        assertTrue(prompt!!.length <= ProjectWorkPlanPromptPolicy.MAX_REQUEST_PROMPT_CHARS)
        assertFalse(prompt.contains("ASK_WITH_PROJECT_CONTEXT"))
        assertFalse(prompt.contains("RESEARCH"))
        assertEquals(request, ProjectWorkPlanPromptPolicy.parseRequestFromComposer(prompt))
        assertEquals(null, ProjectWorkPlanPromptPolicy.buildPrompt(ProjectWorkPlanRequest("", "", "")))
    }

    @Test
    fun `prepared handoff freezes attempt identity and defers send to the user`() {
        val attempt = ProjectWorkPlanAttempt(
            planId = "plan-1",
            stepId = "step-2",
            attemptId = "attempt-3",
            projectId = 9,
            type = ProjectWorkPlanStepType.RESEARCH,
            title = "Check sources",
            instruction = "Review the Project sources",
            planTitle = "Application",
            goal = "Prepare a submission",
            desiredOutput = "Evidence summary",
            constraintText = "Use saved Project material",
        )
        val preparation = ProjectWorkPlanHandoffPolicy.prepare(attempt)

        assertTrue(preparation is ProjectWorkPlanHandoffPreparation.Ready)
        val action = (preparation as ProjectWorkPlanHandoffPreparation.Ready).action
        assertEquals(ProjectWorkPlanDestination.RESEARCH_CHAT, action.destination)
        assertEquals("plan-1", action.planId)
        assertEquals("step-2", action.stepId)
        assertEquals("attempt-3", action.attemptId)
        assertEquals(9L, action.projectId)
        assertEquals(ProjectWorkPlanStepType.RESEARCH, action.stepType)
        assertEquals("Review the Project sources", action.seedText)
        assertTrue(action.seedText.length <= ProjectWorkPlanHandoffPolicy.MAX_SEED_CHARS)
        assertTrue(ProjectWorkPlanHandoffPolicy.needsUserSend(action.destination))
        assertEquals("plan-1", action.requestSnapshot.planId)
        assertEquals("step-2", action.requestSnapshot.stepId)
        assertEquals("attempt-3", action.requestSnapshot.attemptId)
        assertEquals(9L, action.requestSnapshot.projectId)
        assertEquals("RESEARCH", action.requestSnapshot.interactionMode)
        assertFalse(action.requestSnapshot.webSearchEnabled)
        assertEquals("DEFERRED", action.requestSnapshot.egressState)
        assertTrue(action.requestSnapshot.isValid())
        assertTrue(action.frozenSelection == null)
    }

    @Test
    fun `handoff prepare is bounded and unsupported step types fail closed`() {
        val longInstruction = "x".repeat(ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS + 500)
        val bounded = ProjectWorkPlanHandoffPolicy.prepare(
            attempt(type = ProjectWorkPlanStepType.WRITE_DRAFT, instruction = longInstruction),
        )
        assertTrue(bounded is ProjectWorkPlanHandoffPreparation.Ready)
        val boundedAction = (bounded as ProjectWorkPlanHandoffPreparation.Ready).action
        assertTrue(boundedAction.seedText.length <= ProjectWorkPlanHandoffPolicy.MAX_SEED_CHARS)
        assertTrue(boundedAction.requestSnapshot.instruction.length <= ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS)
        assertTrue(boundedAction.requestSnapshot.isValid())

        assertEquals(
            ProjectWorkPlanHandoffPreparation.UnsupportedStep,
            ProjectWorkPlanHandoffPolicy.prepare(attempt(type = ProjectWorkPlanStepType.MANUAL)),
        )
        assertEquals(
            ProjectWorkPlanHandoffPreparation.InvalidAttempt,
            ProjectWorkPlanHandoffPolicy.prepare(attempt(type = ProjectWorkPlanStepType.RESEARCH, instruction = "  ")),
        )
        assertEquals(
            ProjectWorkPlanHandoffPreparation.InvalidAttempt,
            ProjectWorkPlanHandoffPolicy.prepare(attempt(type = ProjectWorkPlanStepType.RESEARCH, projectId = 0L)),
        )
    }

    @Test
    fun `comparison seed is the frozen instruction plus the existing comparison text`() {
        val instruction = "Compare the two papers"
        val seed = ProjectWorkPlanHandoffPolicy.compareSeed(instruction)
        assertTrue(seed.startsWith(instruction))
        assertTrue(seed.contains(com.projectnuke.fusion.chat.SourceComparisonRequestPolicy.COMPOSER_TEXT))
        assertTrue(seed.length <= ProjectWorkPlanHandoffPolicy.MAX_SEED_CHARS)
    }

    private fun attempt(
        type: ProjectWorkPlanStepType,
        instruction: String = "Review the Project sources",
        projectId: Long = 9L,
    ) = ProjectWorkPlanAttempt(
        planId = "plan-1",
        stepId = "step-2",
        attemptId = "attempt-3",
        projectId = projectId,
        type = type,
        title = "Check sources",
        instruction = instruction,
        planTitle = "Application",
        goal = "Prepare a submission",
        desiredOutput = "Evidence summary",
        constraintText = "Use saved Project material",
    )
}
