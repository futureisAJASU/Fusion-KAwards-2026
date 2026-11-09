package com.projectnuke.fusion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.projectnuke.fusion.chat.ChatViewModel

/**
 * Turn-based Voice Mode v1 (NOT a multimodal LLM feature).
 *
 * Text-mediated loop only:
 *
 *   on-device ASR -> text transcript -> existing normal submission path
 *   -> visible assistant text -> existing offline-only read-aloud.
 *
 * No audio ever reaches the LLM, external providers, web search or
 * Translation: only the recognized transcript (ordinary user text) enters
 * product flows, so generation/DataEgress/consent/attachment policies apply
 * exactly as for typed text. There is no second generation pipeline: voice
 * turns submit through the single [ChatScreen] submission entry point after
 * crossing the durable composer boundary, and replies are spoken with the
 * shared [LocalReadAloudController].
 *
 * Explicitly NOT hands-free: after a reply finishes, Voice Mode stays on
 * but the microphone never reopens by itself — the user taps for the next
 * turn. Leaving the mode cancels recognition and stops TTS but never stops
 * an already-running LLM generation (that stays behind the existing stop
 * action), and never touches the draft.
 */

internal class VoiceModeController(
    private val dictation: VoiceDictationController,
    private val readAloud: LocalReadAloudController,
) {
    var active by mutableStateOf(false)
        private set

    fun enter() {
        active = true
    }

    /**
     * Leaves Voice Mode: cancels recognition, stops TTS, preserves the
     * draft and never stops LLM generation. Idempotent.
     */
    fun exit() {
        active = false
        try {
            dictation.cancel()
        } catch (_: Exception) {
            // Best-effort; mode state is already cleared above.
        }
        try {
            readAloud.stop()
        } catch (_: Exception) {
            // Best-effort; mode state is already cleared above.
        }
    }
}

/**
 * Auto-submit rule for a voice-mode final transcript: only when the mode is
 * on, the event is final, the transcript is non-blank, and the draft CAS
 * succeeded. Never auto-sends manual dictation or blank results.
 */
internal fun shouldAutoSubmitVoiceFinal(
    voiceModeActive: Boolean,
    event: VoiceTextEvent,
    draftApplied: Boolean,
): Boolean = voiceModeActive && event.isFinal && draftApplied && event.candidate.isNotBlank()

/**
 * Single application path for voice dictation proposals (manual mic and
 * Voice Mode share it).
 *
 * The event is bound to its originating conversation: the composer CAS
 * targets exactly [VoiceTextEvent.conversationId], never the visible chat at
 * callback time, so a mid-dictation navigation can neither write into nor
 * auto-submit another conversation. Auto-submit additionally requires the
 * originating conversation to still be visible ([currentConversationId]).
 *
 * Returns true when exactly one auto-submit request was issued via
 * [onAutoSubmit]. Ownership acknowledgement ([noteApplied]/[noteApplyFailed])
 * settles the controller session; stale events are dropped.
 */
internal suspend fun applyVoiceTextEvent(
    event: VoiceTextEvent,
    voiceModeActive: Boolean,
    currentConversationId: Long,
    dictation: VoiceDictationController,
    chatViewModel: ChatViewModel,
    onAutoSubmit: suspend (conversationId: Long) -> Unit,
): Boolean {
    var expected = dictation.expectedDraft(event.sessionId) ?: return false
    var applied = chatViewModel.applyVoiceDraftText(event.conversationId, expected, event.candidate)
    if (!applied && event.isFinal) {
        // A controller-owned partial of this same live session may have
        // committed between the expected read and the CAS above, advancing
        // the controller's expected draft. Only acknowledged events from
        // this very session ever move that value — user edits, submissions
        // and imports change the composer but never the controller's
        // expected — so an advanced expected is proof of controller-owned
        // progress, and the final may CAS again from it. An unchanged or
        // gone expected means genuine loss of ownership: fail closed.
        var lastTried = expected
        while (!applied) {
            val advanced = dictation.expectedDraft(event.sessionId) ?: break
            if (advanced == lastTried) break
            lastTried = advanced
            applied = chatViewModel.applyVoiceDraftText(event.conversationId, advanced, event.candidate)
        }
    }
    if (!applied) {
        dictation.noteApplyFailed(event.sessionId, event.isFinal)
        return false
    }
    dictation.noteApplied(event.sessionId, event.candidate, event.isFinal)
    if (shouldAutoSubmitVoiceFinal(voiceModeActive, event, true) &&
        event.conversationId == currentConversationId
    ) {
        onAutoSubmit(event.conversationId)
        return true
    }
    return false
}

internal data class VoiceModeMessage(
    val id: Long,
    val role: String,
)

/**
 * Selects the assistant reply to speak back in Voice Mode: the latest
 * message must be a NEW (unspoken) assistant message in the SAME
 * conversation the voice turn was submitted to, generation must have
 * settled, and the mode must still be awaiting that reply. Anything else —
 * including a conversation switch mid-turn — yields null instead of
 * speaking the wrong chat.
 */
internal fun selectVoiceReplyToSpeak(
    voiceModeActive: Boolean,
    awaitingReply: Boolean,
    isGenerating: Boolean,
    messages: List<VoiceModeMessage>,
    submitSize: Int,
    submitConversationId: Long,
    currentConversationId: Long,
    lastSpokenId: Long?,
    awaitingUserMessageId: Long? = null,
): VoiceModeMessage? {
    if (!voiceModeActive || !awaitingReply || isGenerating) return null
    if (submitConversationId != currentConversationId) return null
    if (awaitingUserMessageId == null && messages.size <= submitSize) return null
    if (awaitingUserMessageId != null) {
        // The active message list is a sliding window, so its size and the
        // original insertion index can stop describing the submitted turn.
        // Bind to the durable user row ID instead. IDs are monotonic database
        // row IDs: if the source has aged out, any newer user row still proves
        // that another turn intervened.
        val submittedIndex = messages.indexOfFirst { it.id == awaitingUserMessageId }
        if (submittedIndex >= 0) {
            if (messages[submittedIndex].role != "user") return null
            if (messages.drop(submittedIndex + 1).any { it.role == "user" }) return null
        } else if (messages.any { it.role == "user" && it.id > awaitingUserMessageId }) {
            return null
        }
    }
    val last = messages.lastOrNull() ?: return null
    if (awaitingUserMessageId != null && last.id <= awaitingUserMessageId) return null
    if (last.role != "assistant" || last.id == lastSpokenId) return null
    return last
}
