package com.projectnuke.fusion.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class FusionPopupPlacementTest {
    private fun place(anchor: IntRect, size: IntSize = IntSize(280, 200), rtl: Boolean = false,
        bottomInset: Int = 0) = FusionPopupPlacement.position(anchor, IntSize(400, 800), size, 12, 24, bottomInset, rtl)

    @Test fun upperTriggerOpensBelowWithClearance() {
        assertEquals(IntOffset(100, 160), place(IntRect(100, 100, 148, 148)))
    }
    @Test fun lowerTriggerFlipsAboveInsteadOfDockingToBottom() {
        assertEquals(IntOffset(100, 528), place(IntRect(100, 740, 148, 788)))
    }
    @Test fun rightEdgeClampsWithinWindow() {
        assertEquals(108, place(IntRect(350, 100, 398, 148)).x)
    }
    @Test fun rtlAlignsThePopupToTheTriggersTrailingEdge() {
        assertEquals(48, place(IntRect(280, 100, 328, 148), rtl = true).x)
    }
    @Test fun keyboardReducesAvailableSpaceAndForcesUpwardPlacement() {
        val p = place(IntRect(100, 300, 148, 348), bottomInset = 300)
        assertEquals(88, p.y)
        assertTrue(p.y + 200 <= 500 - 12)
    }
    @Test fun oversizedMenuHasStableNonNegativePlacement() {
        val p = place(IntRect(0, 0, 48, 48), IntSize(800, 1000))
        assertEquals(IntOffset(12, 36), p)
    }
    @Test fun expansionOriginUsesThePressedPointEvenOutsideThePopup() {
        val origin = FusionPopupPlacement.origin(IntRect(100, 100, 148, 148), Offset(40f, 10f), IntOffset(100, 160), IntSize(280, 200))
        assertEquals(40f / 280f, origin.pivotFractionX, .0001f)
        assertEquals(-.25f, origin.pivotFractionY, .0001f)
    }
    @Test fun keyboardActivationFallsBackToTriggerCenter() {
        val origin = FusionPopupPlacement.origin(IntRect(100, 100, 148, 148), null, IntOffset(100, 160), IntSize(280, 200))
        assertEquals(24f / 280f, origin.pivotFractionX, .0001f)
        assertEquals(-36f / 200f, origin.pivotFractionY, .0001f)
    }
}
