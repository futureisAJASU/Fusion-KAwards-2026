package com.projectnuke.fusion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserVisibleNamePolicyTest {
    @Test
    fun `collapses whitespace and strips control and bidi characters`() {
        val result = normalizeUserVisibleName(
            "  report\u0000 \n\t  final\u202E.exe  ",
            fallback = "file",
            maxCodePoints = 80,
        )
        assertEquals("report final.exe", result)
        assertFalse(result.contains('\u202E'))
    }

    @Test
    fun `caps by code point without splitting supplementary character`() {
        assertEquals(
            "A😀",
            normalizeUserVisibleName("A😀B", fallback = "file", maxCodePoints = 2),
        )
        assertEquals(
            "fallback",
            normalizeUserVisibleName("\u0000\u202E", fallback = "fallback", maxCodePoints = 20),
        )
    }
}
