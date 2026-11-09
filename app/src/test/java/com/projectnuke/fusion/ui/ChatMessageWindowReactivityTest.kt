package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageWindowReactivityTest {
    @Test
    fun `search result lazy target accounts for the optional load older row`() {
        assertEquals(0, searchResultLazyListTargetIndex(0, hasLoadOlderMessages = false))
        assertEquals(4, searchResultLazyListTargetIndex(4, hasLoadOlderMessages = false))
        assertEquals(1, searchResultLazyListTargetIndex(0, hasLoadOlderMessages = true))
        assertEquals(5, searchResultLazyListTargetIndex(4, hasLoadOlderMessages = true))
        assertEquals(null, searchResultLazyListTargetIndex(-1, hasLoadOlderMessages = true))
    }


    @Test
    fun `room count remains authoritative as visible window stays capped`() {
        val snapshots = listOf(99, 100, 101, 150)

        snapshots.forEach { totalCount ->
            val allIdentities = (1L..totalCount.toLong()).map { id ->
                MessageIdentity(id, 7L, if (id % 2L == 1L) "user" else "assistant", id)
            }
            val visibleCount = minOf(100, totalCount)
            val visible = allIdentities.takeLast(visibleCount)
            val fullActiveIds = activeTimelineMessages(
                allIdentities.map { it.toEntity() },
                ResponseVersionState(),
            ).map(MessageEntity::id)

            assertEquals(totalCount, fullActiveIds.size)
            assertEquals(totalCount.toLong(), fullActiveIds.last())
            assertEquals(totalCount > visibleCount, hasHiddenOlderMessages(totalCount, visible.size))
            if (totalCount == 150) {
                assertEquals(51L, visible.first().id)
                assertEquals(150L, fullActiveIds.last())
                assertTrue(hasHiddenOlderMessages(totalCount, visible.size))
            }
        }
    }

    @Test
    fun `exactly one hundred loaded messages hides older button only at count one hundred`() {
        assertFalse(hasHiddenOlderMessages(99, 99))
        assertFalse(hasHiddenOlderMessages(100, 100))
        assertTrue(hasHiddenOlderMessages(101, 100))
        assertTrue(hasHiddenOlderMessages(150, 100))
    }

    private fun MessageIdentity.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = "",
        createdAt = createdAt,
    )
}
