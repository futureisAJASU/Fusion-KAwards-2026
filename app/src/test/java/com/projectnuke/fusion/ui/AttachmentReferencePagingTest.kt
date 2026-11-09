package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.MessageContentPageRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentReferencePagingTest {
    @Test
    fun `reference after more than 96 rows is still found by bounded pages`() = runBlocking {
        val rows = (1L..129L).map { id ->
            MessageContentPageRow(id, if (id == 129L) "recent:managed-path" else "message-$id")
        }
        var loadedPages = 0

        val found = scanConversationAttachmentReference(
            conversationId = 7L,
            managedPath = "managed-path",
            pageSize = 64,
            loadPage = { conversationId, afterId, limit ->
                assertTrue(conversationId == 7L)
                loadedPages++
                rows.asSequence().filter { it.id > afterId }.take(limit).toList()
            },
            matches = { content, path -> content.contains(path) },
        )

        assertTrue(found)
        assertEquals(3, loadedPages)
    }

    @Test
    fun `empty scan does not keep paging after the last message`() = runBlocking {
        var loadedPages = 0
        val found = scanConversationAttachmentReference(
            conversationId = 8L,
            managedPath = "missing",
            pageSize = 2,
            loadPage = { _, afterId, limit ->
                loadedPages++
                (1L..3L).filter { it > afterId }.take(limit).map { MessageContentPageRow(it, "body-$it") }
            },
            matches = { content, path -> content.contains(path) },
        )

        assertFalse(found)
        assertEquals(3, loadedPages)
    }
}
