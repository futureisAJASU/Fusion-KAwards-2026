package com.projectnuke.fusion.ui

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSegmentedControlTest {

    @Test
    fun `selected and unselected options reserve the same leading slot and typography`() {
        val unselected = stableSegmentedOptionModel("GPU", selected = false)
        val selected = stableSegmentedOptionModel("GPU", selected = true)

        assertEquals(unselected.leadingSlotWidthDp, selected.leadingSlotWidthDp)
        assertEquals(unselected.textSizeSp, selected.textSizeSp)
        assertEquals(FontWeight.Normal, unselected.fontWeight)
        assertEquals(unselected.fontWeight, selected.fontWeight)
        assertTrue(selected.selected)
    }

    @Test
    fun `shared content model keeps long settings labels state stable`() {
        val unselected = stableSegmentedOptionModel("모든 로컬 모델", selected = false)
        val selected = stableSegmentedOptionModel("모든 로컬 모델", selected = true)

        assertEquals(unselected.label, selected.label)
        assertEquals(StableSegmentedLeadingSlotWidthDp, selected.leadingSlotWidthDp)
        assertEquals(StableSegmentedTextSizeSp, selected.textSizeSp)
    }
}
