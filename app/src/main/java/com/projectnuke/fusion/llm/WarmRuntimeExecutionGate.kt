package com.projectnuke.fusion.llm

/**
 * Result of an opportunistic generation that must never create or replace a
 * native runtime. The reuse check is performed only after [withRuntimeLock]
 * grants exclusive access, so it cannot rely on a stale pre-lock snapshot.
 */
internal sealed interface WarmRuntimeExecution<out T> {
    data object NotReusable : WarmRuntimeExecution<Nothing>
    data class Executed<T>(val value: T) : WarmRuntimeExecution<T>
}

internal suspend fun <T> executeOnlyWithReusableRuntime(
    withRuntimeLock: suspend (suspend () -> WarmRuntimeExecution<T>) -> WarmRuntimeExecution<T>,
    isRuntimeReusable: () -> Boolean,
    generate: suspend () -> T,
): WarmRuntimeExecution<T> = withRuntimeLock {
    if (!isRuntimeReusable()) {
        WarmRuntimeExecution.NotReusable
    } else {
        WarmRuntimeExecution.Executed(generate())
    }
}
