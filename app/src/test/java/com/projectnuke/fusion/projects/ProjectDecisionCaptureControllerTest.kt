package com.projectnuke.fusion.projects

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDecisionCaptureControllerTest {
    private class RecordingGateway(
        var result: ProjectDecisionCaptureResult = ProjectDecisionCaptureResult.Created("decision-1"),
        var beforeSettle: suspend () -> Unit = {},
    ) : ProjectDecisionCaptureGateway {
        val settlements = mutableListOf<Pair<FrozenProjectDecisionSource, ProjectDecisionDraft>>()

        override suspend fun settle(
            source: FrozenProjectDecisionSource,
            draft: ProjectDecisionDraft,
        ): ProjectDecisionCaptureResult {
            beforeSettle()
            settlements += source to draft
            return result
        }
    }

    @Test
    fun openingEditingAndDismissingHaveZeroWrites() {
        val gateway = RecordingGateway()
        val controller = ProjectDecisionCaptureController(gateway)
        controller.begin(source(), "Draft", "Reason")

        assertTrue(gateway.settlements.isEmpty())
        controller.updateTitle("  Updated   title ")
        controller.updateRationale(" Updated rationale ")
        assertEquals("Updated title", controller.draftTitle)
        assertEquals("Updated rationale", controller.draftRationale)
        controller.dismiss()

        assertNull(controller.frozenSource)
        assertTrue(gateway.settlements.isEmpty())
    }

    @Test
    fun explicitSaveUsesEditedDraftAndCreatesOneDecision() = runBlocking {
        val gateway = RecordingGateway()
        val controller = ProjectDecisionCaptureController(gateway)
        controller.begin(source(), "Draft", "Reason")
        controller.updateTitle("Accepted decision")
        controller.updateRationale("Reviewed rationale")

        assertEquals(ProjectDecisionCaptureResult.Created("decision-1"), controller.save())
        assertEquals(1, gateway.settlements.size)
        assertEquals("Accepted decision", gateway.settlements.single().second.title)
        assertEquals("Reviewed rationale", gateway.settlements.single().second.rationale)
        assertTrue(controller.saved)
        assertEquals(ProjectDecisionCaptureResult.AlreadySaved, controller.save())
        assertEquals(1, gateway.settlements.size)
    }

    @Test
    fun concurrentSaveIsSingleFlight() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = RecordingGateway(beforeSettle = {
            entered.complete(Unit)
            release.await()
        })
        val controller = ProjectDecisionCaptureController(gateway).apply {
            begin(source(), "Draft", "Reason")
        }
        val first = async { controller.save() }
        entered.await()

        assertEquals(ProjectDecisionCaptureResult.SaveInFlight, controller.save())
        release.complete(Unit)
        assertEquals(ProjectDecisionCaptureResult.Created("decision-1"), first.await())
        assertEquals(1, gateway.settlements.size)
    }

    @Test
    fun activeSupersedeTargetIsSentAndInactiveResultIsPreserved() = runBlocking {
        val gateway = RecordingGateway()
        val controller = ProjectDecisionCaptureController(gateway)
        controller.begin(source(), "Draft", "Reason")
        controller.selectSupersede("active-1")
        assertEquals("active-1", controller.supersedesDecisionId)
        controller.selectSupersede("active-2")
        assertEquals("active-2", controller.supersedesDecisionId)
        assertEquals(ProjectDecisionCaptureResult.Created("decision-1"), controller.save())
        assertEquals("active-2", gateway.settlements.single().second.supersedesDecisionId)

        gateway.result = ProjectDecisionCaptureResult.SupersededDecisionInactive
        controller.begin(source(), "Draft", "Reason")
        controller.selectSupersede("now-inactive")
        assertEquals(ProjectDecisionCaptureResult.SupersededDecisionInactive, controller.save())
        controller.selectSupersede(null)
        assertNull(controller.supersedesDecisionId)
    }

    @Test
    fun capacityResultIsPreserved() = runBlocking {
        val gateway = RecordingGateway(result = ProjectDecisionCaptureResult.CapacityReached)
        val controller = ProjectDecisionCaptureController(gateway).apply { begin(source(), "Draft", "Reason") }
        assertEquals(ProjectDecisionCaptureResult.CapacityReached, controller.save())
        assertFalse(controller.saved)
    }

    @Test
    fun cancellationPropagatesFromGateway() = runBlocking {
        val gateway = RecordingGateway(beforeSettle = { throw kotlinx.coroutines.CancellationException("cancel") })
        val controller = ProjectDecisionCaptureController(gateway).apply { begin(source(), "Draft", "Reason") }
        var propagated = false
        try {
            controller.save()
        } catch (_: kotlinx.coroutines.CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertTrue(gateway.settlements.isEmpty())
    }

    companion object {
        fun source() = FrozenProjectDecisionSource(
            projectId = 3L,
            conversationId = 10L,
            assistantMessageId = 20L,
            assistantMessageCreatedAt = 30L,
            responseVersionGroupId = -20L,
            responseVersionMessageIds = listOf(20L),
            answerFingerprint = ProjectDecisionCapturePolicy.fingerprint("durable answer"),
        )
    }
}
