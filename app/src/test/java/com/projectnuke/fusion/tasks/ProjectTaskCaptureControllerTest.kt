package com.projectnuke.fusion.tasks

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTaskCaptureControllerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `answer task preview is not durable until user accepts and only once`() = runTest {
        var writes = 0
        var stored: ProjectTaskProposal? = null
        val writer = ProjectTaskWriter { proposal, _ ->
            writes++
            stored = proposal
            ProjectTaskWriteResult.Stored
        }
        val controller = ProjectTaskCaptureController(writer, backgroundScope)
        controller.begin(ProjectTaskPolicy.fromAnswer(7, "P", "Task title\nreviewed note", 10, 12), false)
        assertEquals(0, writes)
        assertFalse(controller.state.value.saved)

        controller.save()
        runCurrent()
        controller.save()
        runCurrent()
        assertEquals(1, writes)
        assertTrue(controller.state.value.saved)
        assertEquals("Task title", stored?.title)
        assertEquals("reviewed note", stored?.note)
        controller.dismiss()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `durable failure stays visible and does not claim task was added`() = runTest {
        val writer = ProjectTaskWriter { _, _ -> ProjectTaskWriteResult.Failed }
        val controller = ProjectTaskCaptureController(writer, backgroundScope)
        controller.begin(ProjectTaskPolicy.fromAnswer(7, "P", "Task title", 10, 12), false)
        controller.save()
        runCurrent()
        assertFalse(controller.state.value.saved)
        assertEquals("할 일을 저장하지 못했습니다. 다시 시도해 주세요.", controller.state.value.error)
        controller.dismiss()
    }
}
