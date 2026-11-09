package com.projectnuke.fusion.chat

/**
 * Lifecycle gate for opportunistic background recommendation work.
 *
 * Dynamic semantic follow-up inference is UI enhancement, not foreground
 * work: it runs only while the chat surface lifecycle is appropriately
 * active (at least STARTED). When the surface goes inactive, publication is
 * retired. A started native Conversation remains alive until its Flow
 * terminal callback, then its result is discarded; work that has not started
 * is cancelled. This never touches the foreground
 * [GenerationSessionRegistry], never converts backgrounding into USER_STOP,
 * and never surfaces a toast or error. The deterministic
 * [NextActionSuggestionPolicy] row remains a valid fallback.
 *
 * Pure and host-testable; host tests prove this gating policy, not Android
 * process scheduling.
 */
internal object DynamicFollowUpLifecyclePolicy {
    fun shouldGenerate(isSurfaceActive: Boolean): Boolean = isSurfaceActive
}
