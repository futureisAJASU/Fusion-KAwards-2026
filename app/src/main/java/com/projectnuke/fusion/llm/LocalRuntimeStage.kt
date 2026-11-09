package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode

/**
 * Typed local-generation lifecycle stages emitted from the actual engine
 * lifecycle in [LiteRtLlmEngine.generateStreaming].
 *
 * The source of truth is the runtime itself, mirroring the existing
 * diagnostics:
 * - [LocalRuntimeStage.ENGINE_ACQUIRING] fires only when the loaded runtime
 *   is NOT reusable and `getOrCreateEngine()` is about to run (same gate as
 *   the `ENGINE_ACQUIRE_BEGIN` diagnostic). Warm/reusable runtimes never
 *   emit it, so the UI must not show a fake loading stage in that case.
 * - [LocalRuntimeStage.ENGINE_READY] fires after engine acquisition returns
 *   (same point as the `ENGINE_READY` diagnostic).
 * - [LocalRuntimeStage.GENERATION_STARTED] fires after the conversation is
 *   created (same point as the `GENERATION_BEGIN` diagnostic).
 * - [LocalRuntimeStage.FIRST_VISIBLE_TOKEN] fires on the first non-whitespace
 *   token (same point as the `FIRST_VISIBLE_TOKEN` diagnostic).
 *
 * Presentation mapping (stage -> Korean UI string) lives with the UI layer;
 * this type carries only runtime truth.
 */
enum class LocalRuntimeStage {
    ENGINE_ACQUIRING,
    ENGINE_READY,
    GENERATION_STARTED,
    FIRST_VISIBLE_TOKEN,
}

/**
 * A single runtime stage event.
 *
 * @param requestedAccelerator the accelerator requested by the engine profile
 * for this generation (known before acquisition starts).
 * @param actualBackend the loaded runtime's actual text backend once the
 * engine is acquired, null while acquisition is still in progress.
 */
data class LocalRuntimeStageEvent(
    val stage: LocalRuntimeStage,
    val requestedAccelerator: AcceleratorMode,
    val actualBackend: String?,
)
