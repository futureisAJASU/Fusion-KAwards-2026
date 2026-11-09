package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactionMemoryStateTest {

    private fun memory(boundary: Long, summary: String) = AutoCompactMemory(
        conversationId = 1L,
        summary = summary,
        compactedThroughMessageId = boundary,
        updatedAt = boundary,
        sourceTurnCount = 2,
    )

    private fun preparation(
        memory: AutoCompactMemory?,
        compactUsed: Boolean,
        fallbackTrimmed: Boolean = false,
    ) = PreparedLocalAutoCompaction(
        promptMessages = emptyList(),
        memory = memory,
        compactConsidered = compactUsed || fallbackTrimmed,
        compactUsed = compactUsed,
        fallbackTrimmed = fallbackTrimmed,
    )

    @Test
    fun `first successful compact appears in the live divider memory without reopening`() {
        val first = memory(150L, "summary A")
        val visible = updateVisibleAutoCompactMemory(
            current = null,
            preparation = preparation(first, compactUsed = true),
        )

        assertSame(first, visible)
        assertSame(first, compactMemoryForDivider(visible))
    }

    @Test
    fun `incremental successful compact advances the live divider from A to B`() {
        val first = memory(150L, "summary A")
        val second = memory(200L, "summary B")
        val afterFirst = updateVisibleAutoCompactMemory(
            current = null,
            preparation = preparation(first, compactUsed = true),
        )
        val afterSecond = updateVisibleAutoCompactMemory(
            current = afterFirst,
            preparation = preparation(second, compactUsed = true),
        )

        assertEquals(150L, afterFirst?.compactedThroughMessageId)
        assertEquals(200L, afterSecond?.compactedThroughMessageId)
        assertEquals("summary B", afterSecond?.summary)
    }

    @Test
    fun `failed compact retains the last visible memory`() {
        val first = memory(150L, "summary A")
        val failed = updateVisibleAutoCompactMemory(
            current = first,
            preparation = preparation(first, compactUsed = false, fallbackTrimmed = true),
        )

        assertSame(first, failed)
        assertEquals(150L, failed?.compactedThroughMessageId)
    }

    @Test
    fun `disabled compact keeps retained persisted memory available without deleting it`() {
        val persisted = memory(150L, "summary A")

        assertSame(persisted, compactMemoryForDivider(persisted))
        assertTrue(persisted.summary.isNotBlank())
    }
}
