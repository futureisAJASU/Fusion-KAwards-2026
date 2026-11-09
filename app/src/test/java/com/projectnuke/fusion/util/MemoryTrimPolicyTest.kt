package com.projectnuke.fusion.util

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTrimPolicyTest {
    @Test
    fun `ui hidden only clears lightweight caches`() {
        assertEquals(
            MemoryTrimAction.CLEAR_LIGHTWEIGHT,
            resolveMemoryTrimAction(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
    }

    @Test
    fun `running critical and complete release all idle resources`() {
        assertEquals(
            MemoryTrimAction.RELEASE_ALL_IDLE_RESOURCES,
            resolveMemoryTrimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
        assertEquals(
            MemoryTrimAction.RELEASE_ALL_IDLE_RESOURCES,
            resolveMemoryTrimAction(ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
        )
    }

    @Test
    fun `background and running moderate release only idle runtime`() {
        assertEquals(
            MemoryTrimAction.RELEASE_IDLE_RUNTIME,
            resolveMemoryTrimAction(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
        assertEquals(
            MemoryTrimAction.RELEASE_IDLE_RUNTIME,
            resolveMemoryTrimAction(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE),
        )
    }
}
