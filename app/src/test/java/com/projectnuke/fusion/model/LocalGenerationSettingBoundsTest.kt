package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalGenerationSettingBoundsTest {
    @Test
    fun `topK uses the complete persisted compatibility domain`() {
        assertEquals(1, LocalGenerationSettingBounds.clampTopK(1))
        assertEquals(100, LocalGenerationSettingBounds.clampTopK(100))
        assertEquals(1, LocalGenerationSettingBounds.clampTopK(-10))
        assertEquals(100, LocalGenerationSettingBounds.clampTopK(999))
    }

    @Test
    fun `reasoning budget uses the complete persisted compatibility domain`() {
        assertEquals(1, LocalGenerationSettingBounds.clampReasoningBudget(1))
        assertEquals(8192, LocalGenerationSettingBounds.clampReasoningBudget(8192))
        assertEquals(1, LocalGenerationSettingBounds.clampReasoningBudget(0))
        assertEquals(8192, LocalGenerationSettingBounds.clampReasoningBudget(99999))
    }

    @Test
    fun `advanced editor parsing preserves valid low values and clamps invalid input`() {
        assertEquals(1, LocalGenerationSettingBounds.parseTopK("1", 64))
        assertEquals(2, LocalGenerationSettingBounds.parseTopK("2", 64))
        assertEquals(3, LocalGenerationSettingBounds.parseTopK("3", 64))
        assertEquals(4, LocalGenerationSettingBounds.parseTopK("4", 64))
        assertEquals(1, LocalGenerationSettingBounds.parseReasoningBudget("1", 512))
        assertEquals(32, LocalGenerationSettingBounds.parseReasoningBudget("32", 512))
        assertEquals(64, LocalGenerationSettingBounds.parseReasoningBudget("64", 512))
        assertEquals(127, LocalGenerationSettingBounds.parseReasoningBudget("127", 512))
        assertEquals(1, LocalGenerationSettingBounds.parseTopK("-10", 64))
        assertEquals(100, LocalGenerationSettingBounds.parseTopK("999", 64))
        assertEquals(1, LocalGenerationSettingBounds.parseReasoningBudget("0", 512))
        assertEquals(8192, LocalGenerationSettingBounds.parseReasoningBudget("99999", 512))
        assertEquals(2, LocalGenerationSettingBounds.parseTopK("not-a-number", 2))
        assertEquals(64, LocalGenerationSettingBounds.parseReasoningBudget("not-a-number", 64))
    }
}
