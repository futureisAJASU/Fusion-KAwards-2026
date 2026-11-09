package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebClipFreshnessTest {
    @Test fun labelsFactualRetrievalAgeWithoutStaleClassification() {
        val now = 2_000_000_000_000L
        assertEquals("가져옴 · 오늘", webClipFreshnessLabel(now - 1_000L, now))
        assertEquals("가져옴 · 어제", webClipFreshnessLabel(now - 24L * 60 * 60 * 1000, now))
        assertEquals("가져옴 · 12일 전", webClipFreshnessLabel(now - 12L * 24 * 60 * 60 * 1000, now))
        assertNull(webClipFreshnessLabel(null, now))
        assertNull(webClipFreshnessLabel(-1L, now))
    }
}
