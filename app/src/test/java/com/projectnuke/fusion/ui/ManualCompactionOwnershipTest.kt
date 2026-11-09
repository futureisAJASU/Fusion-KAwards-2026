package com.projectnuke.fusion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompactionOwnershipTest {
    @Test
    fun `conversation switch cannot publish old manual request`() {
        val owner = ManualCompactionUiOwner(conversationId = 1L, requestId = "request-a")

        assertTrue(ownsManualCompactionUi(owner, visibleConversationId = 1L, requestId = "request-a"))
        assertFalse(ownsManualCompactionUi(owner, visibleConversationId = 2L, requestId = "request-a"))
        assertFalse(ownsManualCompactionUi(owner, visibleConversationId = 1L, requestId = "request-b"))
    }

    @Test
    fun `fatal manual wrapper errors escape`() {
        assertThrows(OutOfMemoryError::class.java) {
            rethrowManualCompactionFatal(OutOfMemoryError("injected"))
        }
        assertThrows(LinkageError::class.java) {
            rethrowManualCompactionFatal(LinkageError("injected"))
        }
    }
}
