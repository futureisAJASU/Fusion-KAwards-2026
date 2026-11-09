package com.projectnuke.fusion.knowledge

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedKnowledgeCaptureControllerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `answer note is only written after explicit confirmation`() = runTest {
        var writes = 0
        var accepted: SavedKnowledgeProposal? = null
        val writer = SavedKnowledgeWriter { proposal, _ ->
            writes++
            accepted = proposal
            SavedKnowledgeWriteResult.Stored
        }
        val controller = SavedKnowledgeCaptureController(writer, backgroundScope)
        controller.begin(
            SavedKnowledgeDraft("suggested", "visible answer", 10L, 12L, 7L, "Project"),
            archiveAccessIncluded = false,
        )

        assertEquals(0, writes)
        assertFalse(controller.state.value.saved)
        assertEquals("visible answer", controller.state.value.draft?.visibleText)

        controller.updateTitle("Reviewed note")
        controller.updateScope(SavedKnowledgeScope.PROJECT)
        controller.save()
        runCurrent()

        assertEquals(1, writes)
        assertTrue(controller.state.value.saved)
        assertEquals("Reviewed note", accepted?.title)
        assertEquals(7L, accepted?.projectId)
        controller.dismiss()
    }
}
