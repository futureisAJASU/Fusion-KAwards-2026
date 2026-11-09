package com.projectnuke.fusion.util

import android.content.ComponentCallbacks2

/**
 * ComponentCallbacks2 levels are categories, not one ordered severity scale.
 * In particular, UI_HIDDEN means the app is no longer visible; it is not a
 * stronger form of RUNNING_CRITICAL.
 */
internal enum class MemoryTrimAction {
    CLEAR_LIGHTWEIGHT,
    RELEASE_IDLE_RUNTIME,
    RELEASE_ALL_IDLE_RESOURCES,
}

internal fun resolveMemoryTrimAction(level: Int): MemoryTrimAction = when (level) {
    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryTrimAction.CLEAR_LIGHTWEIGHT
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryTrimAction.RELEASE_IDLE_RUNTIME
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryTrimAction.RELEASE_ALL_IDLE_RESOURCES
    else -> MemoryTrimAction.CLEAR_LIGHTWEIGHT
}
