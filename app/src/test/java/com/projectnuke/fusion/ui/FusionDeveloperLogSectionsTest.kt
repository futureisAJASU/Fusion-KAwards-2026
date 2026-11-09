package com.projectnuke.fusion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionDeveloperLogSectionsTest {
    @Test
    fun recentErrorsOnlyContainsErrorEventsAndUsesTheTwentyEventBound() {
        val events = buildList {
            add(FusionDeveloperLogEvent(1L, "memory", "not-an-error"))
            repeat(21) { index ->
                add(FusionDeveloperLogEvent(index + 2L, "runtime_error", "error-$index"))
            }
        }

        val section = formatRecentDeveloperErrors(events)

        assertTrue(section.contains("error-0"))
        assertTrue(section.contains("error-19"))
        assertFalse(section.contains("error-20"))
        assertFalse(section.contains("not-an-error"))
        assertFalse(section.contains("저장된 메모리"))
        assertFalse(section.contains("A/B 테스트"))
    }

    @Test
    fun recentErrorsHasAnExplicitEmptyState() {
        assertTrue(formatRecentDeveloperErrors(emptyList()).contains("최근 오류가 없습니다."))
        assertTrue(formatRecentDeveloperErrors(listOf(FusionDeveloperLogEvent(1L, "info", "not an error")))
            .contains("최근 오류가 없습니다."))
    }
}
