package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedMemoryPackingTest {
    @Test
    fun `oversized high priority candidate is skipped and later candidate survives`() {
        val packed = packSavedMemoryTexts(listOf("A".repeat(100), "B"), maxChars = 15, headerLength = 2)
        assertEquals(listOf("B"), packed.texts)
        assertTrue(packed.trimmed)
    }

    @Test
    fun `eleventh candidate is considered after ten oversized candidates`() {
        val packed = packSavedMemoryTexts(
            texts = (1..10).map { "x".repeat(2000) } + "small",
            maxChars = 100,
            headerLength = 10,
            maxItems = 10,
        )
        assertEquals(listOf("small"), packed.texts)
        assertTrue(packed.trimmed)
    }

    @Test
    fun `ten fitting items remain selectable after oversized candidates`() {
        val packed = packSavedMemoryTexts(
            texts = (1..5).map { "fit-$it" } +
                (1..10).map { "x".repeat(2000) } +
                (6..10).map { "fit-$it" },
            maxChars = 1500,
            headerLength = 10,
            maxItems = 10,
        )
        assertEquals((1..10).map { "fit-$it" }, packed.texts)
        assertTrue(packed.trimmed)
    }
}
