package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Motion fallback resolution for the Fluid Surface motion contract.
 *
 * Host tests prove the pure policy only: system animation-scale zero yields
 * MINIMAL, an active accessibility service yields REDUCED, and the default is
 * FULL. Android service wiring (reading Settings.Global /
 * AccessibilityManager) is intentionally NOT claimed by host tests.
 */
class FluidMotionPolicyTest {
    @Test
    fun `zero animator scale maps to minimal`() {
        assertEquals(FluidMotionMode.MINIMAL, FluidMotionPolicy.resolve(animatorDurationScale = 0f, accessibilityServiceActive = false))
        assertEquals(FluidMotionMode.MINIMAL, FluidMotionPolicy.resolve(animatorDurationScale = -1f, accessibilityServiceActive = true))
    }

    @Test
    fun `active accessibility service maps to reduced`() {
        assertEquals(FluidMotionMode.REDUCED, FluidMotionPolicy.resolve(animatorDurationScale = 1f, accessibilityServiceActive = true))
    }

    @Test
    fun `default maps to full`() {
        assertEquals(FluidMotionMode.FULL, FluidMotionPolicy.resolve(animatorDurationScale = 1f, accessibilityServiceActive = false))
    }
}