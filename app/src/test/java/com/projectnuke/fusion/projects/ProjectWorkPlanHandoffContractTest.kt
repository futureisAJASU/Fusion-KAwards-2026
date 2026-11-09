package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.SelectionContextPurpose
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.chat.SelectionSnapshotResult
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.chat.SelectionSourceSnapshot
import com.projectnuke.fusion.chat.SourceComparisonRequestPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectWorkPlanHandoffContractTest {
    @Test
    fun `every specialized destination is prepared as one identified immutable action`() {
        val expectedDestinations = mapOf(
            ProjectWorkPlanStepType.RESEARCH to ProjectWorkPlanDestination.RESEARCH_CHAT,
            ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT to ProjectWorkPlanDestination.PROJECT_CONTEXT_CHAT,
            ProjectWorkPlanStepType.WRITE_DRAFT to ProjectWorkPlanDestination.DRAFT_COMPOSER,
            ProjectWorkPlanStepType.COMPARE_SOURCES to ProjectWorkPlanDestination.SOURCE_SELECTION_COMPARE,
            ProjectWorkPlanStepType.STRUCTURED_EXTRACT to ProjectWorkPlanDestination.SOURCE_SELECTION_EXTRACTION,
            ProjectWorkPlanStepType.REVIEW_KNOWLEDGE to ProjectWorkPlanDestination.KNOWLEDGE_SELECTION,
        )
        expectedDestinations.forEach { (type, destination) ->
            val preparation = ProjectWorkPlanHandoffPolicy.prepare(attempt(type), projectContext())
            assertTrue(preparation is ProjectWorkPlanHandoffPreparation.Ready)
            val action = (preparation as ProjectWorkPlanHandoffPreparation.Ready).action
            assertEquals(destination, action.destination)
            assertEquals("plan-1", action.planId)
            assertEquals("step-2", action.stepId)
            assertEquals("attempt-3", action.attemptId)
            assertEquals(9L, action.projectId)
            assertEquals(type, action.stepType)
            assertTrue(ProjectWorkPlanHandoffPolicy.needsUserSend(action.destination))
            assertNull(action.frozenSelection)
            assertTrue(action.requestSnapshot.isValid())
            assertFalse(action.requestSnapshot.webSearchEnabled)
            if (type == ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT) {
                assertNotNull(action.frozenProjectContext)
                assertEquals(listOf(11L, 12L), action.requestSnapshot.projectSourceIds)
            } else {
                assertNull(action.frozenProjectContext)
                assertEquals(emptyList<Long>(), action.requestSnapshot.projectSourceIds)
            }
        }
    }

    @Test
    fun `ask preparation fails closed without frozen project authority`() {
        assertTrue(
            ProjectWorkPlanHandoffPolicy.prepare(attempt(ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT))
                is ProjectWorkPlanHandoffPreparation.InvalidAttempt,
        )
        assertTrue(
            ProjectWorkPlanHandoffPolicy.prepare(
                attempt(ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT),
                projectContext().copy(projectId = 77L),
            ) is ProjectWorkPlanHandoffPreparation.InvalidAttempt,
        )
    }

    @Test
    fun `research preparation never enables web search or an automatic egress state`() {
        val action = ready(ProjectWorkPlanStepType.RESEARCH)
        assertEquals("RESEARCH", action.requestSnapshot.interactionMode)
        assertEquals("DEFERRED", action.requestSnapshot.egressState)
        assertFalse(action.requestSnapshot.webSearchEnabled)
        assertEquals(emptyList<Long>(), action.requestSnapshot.projectSourceIds)
        assertTrue(action.requestSnapshot.isValid())
        assertFalse(
            action.requestSnapshot.copy(webSearchEnabled = true, egressState = "AUTO_ENABLE_WEB").isValid(),
        )
    }

    @Test
    fun `comparison freeze is bounded immutable and keeps the existing comparison text`() {
        val action = ready(ProjectWorkPlanStepType.COMPARE_SOURCES)
        // Below the existing 2..4 comparison bound fails closed.
        assertNull(ProjectWorkPlanHandoffPolicy.prepareCompare(action, selection(1)))

        val frozen = ProjectWorkPlanHandoffPolicy.prepareCompare(action, selection(3))
        assertNotNull(frozen)
        assertEquals(3, frozen!!.frozenSelection!!.sources.size)
        assertTrue(frozen.seedText.startsWith(action.attempt.instruction.trim()))
        assertTrue(frozen.seedText.contains(SourceComparisonRequestPolicy.COMPOSER_TEXT))
        // A frozen selection can never be widened or replaced afterwards.
        assertNull(ProjectWorkPlanHandoffPolicy.prepareCompare(frozen, selection(4)))
        // Wrong destination fails closed.
        assertNull(
            ProjectWorkPlanHandoffPolicy.prepareCompare(ready(ProjectWorkPlanStepType.RESEARCH), selection(3)),
        )
    }

    @Test
    fun `extraction freeze requires exactly one explicitly chosen source`() {
        val action = ready(ProjectWorkPlanStepType.STRUCTURED_EXTRACT)
        assertNull(ProjectWorkPlanHandoffPolicy.prepareExtraction(action, selection(2)))
        assertNull(
            ProjectWorkPlanHandoffPolicy.prepareExtraction(ready(ProjectWorkPlanStepType.RESEARCH), selection(1)),
        )

        val single = ProjectWorkPlanHandoffPolicy.prepareExtraction(action, selection(1))
        assertNotNull(single)
        assertEquals("Source 1", single!!.title)
        assertTrue(single.identity.isWellFormed())
    }

    @Test
    fun `knowledge question freeze keeps the frozen instruction and bounded selection`() {
        val action = ready(ProjectWorkPlanStepType.REVIEW_KNOWLEDGE)
        assertNull(
            ProjectWorkPlanHandoffPolicy.prepareKnowledgeQuestion(
                ready(ProjectWorkPlanStepType.STRUCTURED_EXTRACT),
                selection(2),
            ),
        )

        val frozen = ProjectWorkPlanHandoffPolicy.prepareKnowledgeQuestion(action, selection(2))
        assertNotNull(frozen)
        assertEquals(2, frozen!!.frozenSelection!!.sources.size)
        assertEquals(action.attempt.instruction.trim(), frozen.seedText)
        // Idempotence: a second freeze cannot replace the frozen snapshot.
        assertNull(ProjectWorkPlanHandoffPolicy.prepareKnowledgeQuestion(frozen, selection(3)))
    }

    @Test
    fun `extraction result id is the durable source identity plus the explicit type`() {
        val identity = SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = "note-7")
        val id = ProjectWorkPlanHandoffPolicy.extractionResultId(identity, "KEY_NUMBERS")
        assertNotNull(id)
        assertTrue(id!!.startsWith("x:") && id.endsWith("#KEY_NUMBERS"))
        assertTrue(id.length <= ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS)
        assertNull(ProjectWorkPlanHandoffPolicy.extractionResultId(identity, " "))
        assertNull(
            ProjectWorkPlanHandoffPolicy.extractionResultId(
                SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE),
                "KEY_NUMBERS",
            ),
        )
        // A long stable key stays inside the durable column bound.
        val longIdentity = SelectionSourceIdentity(
            SelectionSourceKind.ATTACHED_DOCUMENT,
            conversationId = 1L,
            managedPath = "documents/" + "x".repeat(300),
        )
        val bounded = ProjectWorkPlanHandoffPolicy.extractionResultId(longIdentity, "SPECIFICATIONS")
        assertTrue(bounded != null && bounded.length <= ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS)
    }

    @Test
    fun `selection result id replaces the process-local snapshot uuid`() {
        val identities = listOf(
            SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = 1L),
            SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = "note-7"),
        )
        val id = ProjectWorkPlanHandoffPolicy.selectionResultId(identities)
        assertNotNull(id)
        assertTrue(id!!.startsWith("s:"))
        assertTrue(id.length <= ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS)
        assertEquals(id, ProjectWorkPlanHandoffPolicy.selectionResultId(identities))
        assertNull(ProjectWorkPlanHandoffPolicy.selectionResultId(emptyList()))
    }

    @Test
    fun `message receipts carry the durable assistant message as the result id`() {
        val receipt = ProjectWorkPlanResultReceipt(
            planId = "plan-1",
            stepId = "step-2",
            attemptId = "attempt-3",
            projectId = 9L,
            stepType = ProjectWorkPlanStepType.RESEARCH,
            resultKind = ProjectWorkPlanResultReceipt.KIND_MESSAGE,
            resultId = "12",
            conversationId = 4L,
            userMessageId = 11L,
        )
        assertEquals(12L, receipt.resultId.toLong())
        assertTrue(receipt.resultId.toLong() > (receipt.userMessageId ?: 0L))
        assertEquals(ProjectWorkPlanResultReceipt.KIND_MESSAGE, receipt.resultKind)
        assertEquals(
            setOf(
                ProjectWorkPlanResultReceipt.KIND_MESSAGE,
                ProjectWorkPlanResultReceipt.KIND_SELECTION,
                ProjectWorkPlanResultReceipt.KIND_EXTRACTION,
            ),
            ProjectWorkPlanResultReceipt.KINDS,
        )
    }

    private fun ready(type: ProjectWorkPlanStepType): ProjectWorkPlanPreparedAction {
        val preparation = ProjectWorkPlanHandoffPolicy.prepare(attempt(type), projectContext())
        assertTrue(preparation is ProjectWorkPlanHandoffPreparation.Ready)
        return (preparation as ProjectWorkPlanHandoffPreparation.Ready).action
    }

    private fun projectContext(): com.projectnuke.fusion.chat.ProjectContextSnapshot =
        com.projectnuke.fusion.chat.ProjectContextSnapshot(
            projectId = 9L,
            instructions = "Follow the frozen instructions",
            files = listOf(
                com.projectnuke.fusion.chat.ProjectFileSnapshot(11L, "spec.md", "text/markdown", "/managed/spec.md"),
                com.projectnuke.fusion.chat.ProjectFileSnapshot(12L, "plan.md", "text/markdown", "/managed/plan.md"),
            ),
        )

    private fun attempt(
        type: ProjectWorkPlanStepType,
        instruction: String = "Review the Project sources",
    ) = ProjectWorkPlanAttempt(
        planId = "plan-1",
        stepId = "step-2",
        attemptId = "attempt-3",
        projectId = 9L,
        type = type,
        title = "Check sources",
        instruction = instruction,
        planTitle = "Application",
        goal = "Prepare a submission",
        desiredOutput = "Evidence summary",
        constraintText = "Use saved Project material",
    )

    private fun selection(count: Int): SelectionContextSnapshot {
        val sources = (1..count).map { index ->
            SelectionSourceSnapshot(
                SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = index.toLong()),
                "Source $index",
                "excerpt $index",
            )
        }
        val result = SelectionContextSnapshot.create(
            sources = sources,
            createdAt = 1L,
            purpose = SelectionContextPurpose.QUESTION,
        )
        assertTrue(result is SelectionSnapshotResult.Valid)
        return (result as SelectionSnapshotResult.Valid).snapshot
    }
}
