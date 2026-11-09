package com.projectnuke.fusion.chat

/**
 * Explicit chat interaction mode (v1): presentation/session state only.
 *
 * NORMAL is ordinary chat. STUDY is a dedicated Study Mode surface over the
 * SAME conversation, model/runtime, TrustRouter, Search, Local RAG, Project
 * context, submission ownership and response-version architecture: it changes
 * only presentation/interaction tools plus request-time model behavior
 * (Phase 2), never the generation pipeline.
 *
 * The mode is intentionally ephemeral:
 * - never persisted (no Room field, no preferences, no SavedStateHandle),
 * - never stored per message,
 * - scoped to exactly one (conversationId, newChatEpoch) pair,
 * - a conversation switch or a same-id Fresh New Chat epoch change resets to
 *   NORMAL (defense-in-depth alongside the UI remember(scope) reset),
 * - process death resets to NORMAL by construction (default constructor).
 *
 * Android-free and host-testable. Mode switching is a pure presentation
 * switch: it mutates no composer text, no messages, no attachments, and
 * performs no generation or retrieval. Callers disable the switch while a
 * critical operation owns the composer and pass that gate in as
 * [ChatInteractionModePolicy.switch]'s controlsEnabled flag.
 *
 * The enum itself is public because the public [GenerationRequestSnapshot]
 * freezes it per request; the state/policy types stay internal.
 */
enum class ChatInteractionMode {
    NORMAL,
    STUDY,
    RESEARCH,
}

/** Ephemeral mode binding. Never persisted. */
internal data class ChatInteractionModeState(
    val mode: ChatInteractionMode = ChatInteractionMode.NORMAL,
    val conversationId: Long = 0L,
    val newChatEpoch: Long = 0L,
)

/**
 * Current UI scope a mode switch applies to. Fusion reuses
 * conversationId = 0 for every fresh New Chat; the monotonic epoch
 * distinguishes them, so the mode belongs to exactly one pair.
 */
internal data class ChatInteractionModeScope(
    val conversationId: Long,
    val newChatEpoch: Long,
)

internal object ChatInteractionModePolicy {
    fun currentMode(
        state: ChatInteractionModeState,
        conversationId: Long,
        newChatEpoch: Long,
    ): ChatInteractionMode {
        if (state.mode == ChatInteractionMode.NORMAL) return ChatInteractionMode.NORMAL
        if (state.conversationId != conversationId || state.newChatEpoch != newChatEpoch) {
            return ChatInteractionMode.NORMAL
        }
        return state.mode
    }

    fun currentMode(
        state: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
    ): ChatInteractionMode = currentMode(state, scope.conversationId, scope.newChatEpoch)

    fun isStudyMode(
        state: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
    ): Boolean = currentMode(state, scope) == ChatInteractionMode.STUDY

    /**
     * Pure presentation switch. Rules:
     * - disabled controls reject the switch (state returned unchanged);
     * - a stale scope cannot mutate the mode: a STUDY binding owned by
     *   another scope is never rebound or cleared from here (the UI
     *   remember(scope) reset owns scope transitions);
     * - switching to the already-current mode is idempotent (same instance);
     * - otherwise rebinds to [scope] with [targetMode].
     *
     * Touches no composer text, messages, attachments, retrieval or
     * generation by construction: there is no callback parameter on this API.
     */
    fun switch(
        state: ChatInteractionModeState,
        scope: ChatInteractionModeScope,
        targetMode: ChatInteractionMode,
        controlsEnabled: Boolean,
    ): ChatInteractionModeState {
        if (!controlsEnabled) return state
        if (currentMode(state, scope) == targetMode) return state
        if (state.mode != ChatInteractionMode.NORMAL &&
            (state.conversationId != scope.conversationId || state.newChatEpoch != scope.newChatEpoch)
        ) {
            return state
        }
        return ChatInteractionModeState(
            mode = targetMode,
            conversationId = scope.conversationId,
            newChatEpoch = scope.newChatEpoch,
        )
    }

    /**
     * Generic Quick Prompt surface availability: the dedicated Study tools
     * are the contextual action surface while STUDY, so the generic
     * "빠른 입력" expansion stays closed. Attachment, Tune, microphone,
     * Voice Mode and Send semantics are unaffected.
     */
    fun allowsQuickPrompt(mode: ChatInteractionMode): Boolean =
        mode == ChatInteractionMode.NORMAL

    /**
     * Composer placeholder per mode. Entering STUDY inserts no text; only
     * the hint changes so an empty New Chat invites a learning topic.
     */
    fun composerPlaceholder(mode: ChatInteractionMode): String =
        when (mode) {
            ChatInteractionMode.NORMAL -> "입력하여 채팅"
            ChatInteractionMode.STUDY -> "배울 내용을 입력하세요"
            ChatInteractionMode.RESEARCH -> "조사할 질문을 입력하세요"
        }
}

/** Pure dismissal rules for the transient interaction-mode selector popup. */
internal object ModeSelectorPresentationPolicy {
    fun shouldDismiss(
        previousScope: ChatInteractionModeScope,
        currentScope: ChatInteractionModeScope,
        previousEnabled: Boolean,
        currentEnabled: Boolean,
        previousMode: ChatInteractionMode,
        currentMode: ChatInteractionMode,
    ): Boolean = previousScope != currentScope ||
        previousMode != currentMode ||
        (previousEnabled && !currentEnabled)
}
