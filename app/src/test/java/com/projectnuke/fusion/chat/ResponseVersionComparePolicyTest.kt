package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseVersionComparePolicyTest {

    private fun entry(id: Long, ordinal: Int, answer: String = "답변 $ordinal") =
        ResponseVersionCompareEntry(messageId = id, ordinal = ordinal, visibleAnswer = answer)

    @Test
    fun `two versions active second defaults comparison to first`() {
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1), entry(12L, 2)),
            activeMessageId = 12L,
            totalCount = 2,
        )

        assertNotNull(snapshot)
        assertEquals(12L, snapshot!!.activeMessageId)
        assertEquals(11L, snapshot.comparisonMessageId)
        assertEquals(2, snapshot.totalCount)
    }

    @Test
    fun `three versions active first defaults comparison to second`() {
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1), entry(12L, 2), entry(13L, 3)),
            activeMessageId = 11L,
            totalCount = 3,
        )

        assertNotNull(snapshot)
        assertEquals(12L, snapshot!!.comparisonMessageId)
    }

    @Test
    fun `arbitrary valid non-active comparison selection`() {
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1), entry(12L, 2), entry(13L, 3), entry(14L, 4)),
            activeMessageId = 13L,
            totalCount = 4,
        )!!
        // Default is the previous usable version.
        assertEquals(12L, snapshot.comparisonMessageId)

        val selected = ResponseVersionComparePolicy.select(snapshot, 11L)
        assertNotNull(selected)
        assertEquals(11L, selected!!.comparisonMessageId)
        assertEquals(13L, selected.activeMessageId)
        // Input snapshot is never mutated by selection.
        assertEquals(12L, snapshot.comparisonMessageId)
    }

    @Test
    fun `active cannot become comparison target`() {
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1), entry(12L, 2)),
            activeMessageId = 12L,
            totalCount = 2,
        )!!

        assertNull(ResponseVersionComparePolicy.select(snapshot, 12L))
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertNull(
            ResponseVersionComparePolicy.snapshot(
                listOf(entry(11L, 1), entry(11L, 2)),
                activeMessageId = 11L,
                totalCount = 2,
            ),
        )
    }

    @Test
    fun `malformed active member is rejected`() {
        assertNull(
            ResponseVersionComparePolicy.snapshot(
                listOf(entry(11L, 1), entry(12L, 2)),
                activeMessageId = 99L,
                totalCount = 2,
            ),
        )
        assertNull(
            ResponseVersionComparePolicy.snapshot(
                emptyList(),
                activeMessageId = 11L,
                totalCount = 0,
            ),
        )
    }

    @Test
    fun `one usable version is unavailable`() {
        assertNull(
            ResponseVersionComparePolicy.snapshot(
                listOf(entry(11L, 1)),
                activeMessageId = 11L,
                totalCount = 1,
            ),
        )
    }

    @Test
    fun `blanks are filtered and fewer than two usable is unavailable`() {
        // Three stored versions, two blank: only one usable answer.
        assertNull(
            ResponseVersionComparePolicy.snapshot(
                listOf(entry(11L, 1, "   "), entry(12L, 2, "유효 답변"), entry(13L, 3, "")),
                activeMessageId = 12L,
                totalCount = 3,
            ),
        )
        // Two usable answers survive filtering: available, ordinals kept.
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1, "  "), entry(12L, 2, "첫 유효"), entry(13L, 3, "둘 유효")),
            activeMessageId = 13L,
            totalCount = 3,
        )
        assertNotNull(snapshot)
        assertEquals(listOf(12L, 13L), snapshot!!.entries.map { it.messageId })
        assertEquals(12L, snapshot.comparisonMessageId)
        assertEquals(3, snapshot.totalCount)
    }

    @Test
    fun `unknown comparison selection is rejected`() {
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1), entry(12L, 2)),
            activeMessageId = 12L,
            totalCount = 2,
        )!!

        assertNull(ResponseVersionComparePolicy.select(snapshot, 99L))
    }

    @Test
    fun `policy exposes zero response-state mutation callbacks`() {
        // Open/select/close are data-only: no Room, store, coordinator or
        // persistence callbacks exist to invoke, so the dialog cannot write.
        val methods = ResponseVersionComparePolicy.javaClass.declaredMethods
        assertTrue(methods.none { m -> m.parameterTypes.any { it.name.contains("Function") } })
        val names = methods.map { it.name }.joinToString(",")
        for (banned in listOf("save", "move", "mutat", "persist", "update", "write", "room", "dao")) {
            assertTrue(
                "compare policy must not expose $banned",
                !names.contains(banned, ignoreCase = true),
            )
        }
    }

    @Test
    fun `snapshot does not alias the caller list`() {
        val input = mutableListOf(entry(11L, 1), entry(12L, 2))
        val snapshot = ResponseVersionComparePolicy.snapshot(input, 12L, totalCount = 2)!!
        input.clear()

        // Frozen at capture: later version-group changes cannot rewrite it.
        assertEquals(2, snapshot.entries.size)
        assertEquals(11L, snapshot.comparisonMessageId)
    }

    @Test
    fun `select allocates a new snapshot and leaves the frozen one intact`() {
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1), entry(12L, 2), entry(13L, 3)),
            activeMessageId = 12L,
            totalCount = 3,
        )!!
        val selected = ResponseVersionComparePolicy.select(snapshot, 13L)!!

        assertNotSame(snapshot, selected)
        assertEquals(11L, snapshot.comparisonMessageId)
        assertEquals(13L, selected.comparisonMessageId)
        assertEquals(snapshot.entries, selected.entries)
        assertEquals(snapshot.activeMessageId, selected.activeMessageId)
    }

    @Test
    fun `entry accessors resolve exact members`() {
        val snapshot = ResponseVersionComparePolicy.snapshot(
            listOf(entry(11L, 1, "첫째"), entry(12L, 2, "둘째")),
            activeMessageId = 12L,
            totalCount = 2,
        )!!

        assertEquals("둘째", ResponseVersionComparePolicy.activeEntry(snapshot)?.visibleAnswer)
        assertEquals("첫째", ResponseVersionComparePolicy.comparisonEntry(snapshot)?.visibleAnswer)
    }
}
