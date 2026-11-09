package com.projectnuke.fusion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * On-device voice dictation (editable dictation into the normal composer).
 *
 * Architecture: microphone -> on-device speech recognition -> plain text
 * transcript -> normal Fusion composer -> existing text-only LLM pipeline.
 * The LLM never receives audio; no audio is persisted, no cloud STT
 * fallback exists, and no model call occurs during recognition.
 *
 * The platform engine is created lazily on the first explicit mic action —
 * never at startup — through [VoiceRecognitionEnginePort] so unit tests run
 * without Android speech services.
 *
 * Draft safety: recognition only proposes text. Each proposal carries the
 * controller-owned draft ([VoiceDictationController.expectedDraft]); the UI
 * applies it with an atomic compare-and-set
 * ([com.projectnuke.fusion.chat.ChatViewModel.applyVoiceDraftText]). A user
 * edit that lands first fails the CAS, which supersedes voice ownership
 * instead of overwriting the user's edit. Stale sessions can never write.
 */

internal enum class DictationState {
    IDLE,
    REQUESTING_PERMISSION,
    LISTENING,
    PROCESSING,
    ERROR,
}

internal data class VoiceTextEvent(
    val sessionId: Long,
    val candidate: String,
    val isFinal: Boolean,
    /**
     * Originating conversation bound at recognition start. The application
     * path must mutate exactly this conversation — never the visible
     * conversation at callback time — so a chat switch mid-dictation can
     * neither write into nor auto-submit another chat.
     */
    val conversationId: Long = 0L,
    /**
     * Immutable controller-owned draft snapshot taken when the event was
     * emitted. Mirrors the retained pending-final ownership so the async UI
     * apply can CAS from exactly this value.
     */
    val expectedDraft: String = "",
)

internal interface DictationEngineListener {
    fun onPartial(sessionToken: Long, transcript: String)
    fun onFinal(sessionToken: Long, transcript: String)
    fun onEngineError(sessionToken: Long, message: String)
}

internal interface VoiceRecognitionEnginePort {
    fun setListener(listener: DictationEngineListener?)
    /**
     * Starts recognition for the controller-owned [sessionToken]. The
     * controller is the single authority for session identity: the engine
     * MUST report all callbacks of this start with exactly [sessionToken]
     * and MUST NOT mint its own replacement token. Internal invalidation
     * may only drop stale callbacks, never renumber live ones.
     */
    fun start(sessionToken: Long, languageTag: String): Boolean
    fun stop()
    fun cancel()
    fun destroy()
}

/** Pure mapping of recognizer failures to bounded Korean messages. */
internal object DictationErrorPolicy {
    // Mirrors android.speech.SpeechRecognizer error codes without requiring
    // the framework at call time.
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    fun messageFor(code: Int): String = when (code) {
        ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다. 설정에서 허용해 주세요."
        ERROR_RECOGNIZER_BUSY -> "음성 인식기가 사용 중입니다. 잠시 후 다시 시도해 주세요."
        ERROR_SPEECH_TIMEOUT,
        ERROR_NO_MATCH -> "음성이 인식되지 않았습니다. 다시 시도해 주세요."
        ERROR_NETWORK -> "네트워크 오류로 음성 인식을 중단했습니다."
        else -> "음성 인식 중 오류가 발생했습니다."
    }
}

/**
 * Merges a recognition transcript onto the captured composer base with
 * natural spacing. Pure and total: empty transcript keeps the base, empty
 * base yields the transcript trimmed.
 */
internal fun mergeVoiceSegment(baseText: String, transcript: String): String {
    val base = baseText.trimEnd()
    val said = transcript.trim()
    if (said.isEmpty()) return baseText
    if (base.isEmpty()) return said
    return "$base $said"
}

internal class VoiceDictationController(
    private val engineProvider: () -> VoiceRecognitionEnginePort?,
    private val notify: (String) -> Unit,
    var onVoiceText: ((VoiceTextEvent) -> Unit)? = null,
) {
    var state by mutableStateOf(DictationState.IDLE)
        private set

    val activeSessionId: Long get() = activeToken

    private var engine: VoiceRecognitionEnginePort? = null
    private var sessionCounter = 0L
    private var activeToken = 0L
    private var activeConversationId = 0L
    private var baseText = ""
    private var lastVoiceDraft: String? = null
    /**
     * Terminal pending-apply ownership for the last final transcript. The
     * final [VoiceTextEvent] is emitted while this state is retained, so the
     * async UI coroutine can still resolve [expectedDraft] and CAS the
     * composer. A late PARTIAL acknowledgement of this same session advances
     * the expected value (never consumes it); only the FINAL-phase
     * acknowledgement ([noteApplied]/[noteApplyFailed]) settles it. A newer
     * [start] supersedes it.
     */
    private var pendingFinalToken = 0L
    private var pendingFinalConversationId = 0L
    private var pendingFinalExpected: String? = null

    private val engineListener = object : DictationEngineListener {
        override fun onPartial(sessionToken: Long, transcript: String) {
            if (pendingFinalToken != 0L) return
            if (sessionToken != activeToken || activeToken == 0L) return
            val expected = lastVoiceDraft ?: return
            onVoiceText?.invoke(
                VoiceTextEvent(
                    sessionId = sessionToken,
                    candidate = mergeVoiceSegment(baseText, transcript),
                    isFinal = false,
                    conversationId = activeConversationId,
                    expectedDraft = expected,
                ),
            )
        }

        override fun onFinal(sessionToken: Long, transcript: String) {
            if (sessionToken != activeToken || activeToken == 0L) return
            val token = activeToken
            val expected = lastVoiceDraft ?: baseText
            val origin = activeConversationId
            // Enter the terminal pending-apply state BEFORE emitting: draft
            // ownership survives until the UI acknowledges application.
            pendingFinalToken = token
            pendingFinalExpected = expected
            pendingFinalConversationId = origin
            activeToken = 0L
            activeConversationId = 0L
            state = DictationState.IDLE
            onVoiceText?.invoke(
                VoiceTextEvent(
                    sessionId = token,
                    candidate = mergeVoiceSegment(baseText, transcript),
                    isFinal = true,
                    conversationId = origin,
                    expectedDraft = expected,
                ),
            )
        }

        override fun onEngineError(sessionToken: Long, message: String) {
            // A settled final is terminal: late errors for it are stale.
            if (sessionToken == pendingFinalToken && pendingFinalToken != 0L) return
            if (sessionToken != activeToken || activeToken == 0L) return
            clearSessionLocked()
            lastVoiceDraft = null
            state = DictationState.ERROR
            notify(message)
        }
    }

    fun notePermissionRequest() {
        if (state == DictationState.IDLE) state = DictationState.REQUESTING_PERMISSION
    }

    fun notePermissionResult(granted: Boolean) {
        if (state != DictationState.REQUESTING_PERMISSION) return
        if (granted) {
            state = DictationState.IDLE
        } else {
            state = DictationState.IDLE
            notify(DictationErrorPolicy.messageFor(DictationErrorPolicy.ERROR_INSUFFICIENT_PERMISSIONS))
        }
    }

    /**
     * Starts a session capturing [baseComposerText] and binding the session
     * to [conversationId]. A previous session is cancelled first (single
     * session only); a pending final acknowledgement is superseded. Returns
     * false with a bounded message when no on-device recognizer is available
     * — never falls back to a network recognizer.
     *
     * The session token is controller-owned and handed to the engine; the
     * engine must echo it back unchanged on every callback.
     */
    fun start(baseComposerText: String, conversationId: Long): Boolean {
        if (activeToken != 0L || pendingFinalToken != 0L) cancelEngineSession()
        clearSessionLocked()
        val engine = getOrCreateEngine()
        if (engine == null) {
            state = DictationState.ERROR
            notify(UNAVAILABLE_MESSAGE)
            return false
        }
        val token = ++sessionCounter
        baseText = baseComposerText
        lastVoiceDraft = baseComposerText
        activeToken = token
        activeConversationId = conversationId
        state = DictationState.LISTENING
        if (!engine.start(token, Locale.getDefault().toLanguageTag())) {
            clearSessionLocked()
            lastVoiceDraft = null
            state = DictationState.ERROR
            notify(UNAVAILABLE_MESSAGE)
            return false
        }
        return true
    }

    /** Mic tapped while listening: end input, apply the final transcript. */
    fun stopAndFinalize() {
        if (state != DictationState.LISTENING || activeToken == 0L) return
        state = DictationState.PROCESSING
        try {
            engine?.stop()
        } catch (_: Exception) {
            cancel()
        }
    }

    /** Aborts the session with no further text. Idempotent. */
    fun cancel() {
        if (activeToken == 0L && pendingFinalToken == 0L && state == DictationState.IDLE) return
        cancelEngineSession()
        clearSessionLocked()
        lastVoiceDraft = null
        state = DictationState.IDLE
    }

    /**
     * Supersedes dictation started in another conversation: navigating away
     * cancels a live (listening/processing) session so its late callbacks
     * can never write into the newly visible chat. A final transcript that
     * already entered the terminal pending-apply state is NOT cancelled —
     * its acknowledgement still mutates only its originating conversation,
     * and auto-submit is additionally gated on the visible conversation.
     */
    fun cancelIfConversationChanged(currentConversationId: Long) {
        if (activeToken != 0L && activeConversationId != currentConversationId) {
            cancel()
        }
    }

    /**
     * The controller-owned draft for [sessionId], or null when the session
     * is stale. Survives the final transcript until the explicit
     * application acknowledgement settles it. The UI must CAS the composer
     * from this exact value.
     */
    fun expectedDraft(sessionId: Long): String? =
        if (sessionId == activeToken && activeToken != 0L) {
            lastVoiceDraft
        } else if (sessionId == pendingFinalToken && pendingFinalToken != 0L) {
            pendingFinalExpected
        } else {
            null
        }

    /** Originating conversation bound at start, or null when stale. */
    fun sessionConversation(sessionId: Long): Long? =
        if (sessionId == activeToken && activeToken != 0L) {
            activeConversationId
        } else if (sessionId == pendingFinalToken && pendingFinalToken != 0L) {
            pendingFinalConversationId
        } else {
            null
        }

    /**
     * Records a successful CAS for the acknowledged event phase.
     *
     * Partial phase: chains the live session's draft. A late partial
     * acknowledgement for a session that already entered the terminal
     * pending-final state never CONSUMES final ownership — but its CAS did
     * commit a controller-owned draft, so the pending final's expected
     * advances to it and the actual final can still CAS from the composer's
     * real current value.
     *
     * Final phase: the application acknowledgement for the pending final —
     * the session settles exactly once and late duplicates are ignored.
     */
    fun noteApplied(sessionId: Long, candidate: String, isFinal: Boolean) {
        if (isFinal) {
            if (sessionId == pendingFinalToken && pendingFinalToken != 0L) {
                pendingFinalToken = 0L
                pendingFinalConversationId = 0L
                pendingFinalExpected = null
                lastVoiceDraft = null
                if (state != DictationState.IDLE) state = DictationState.IDLE
            }
            return
        }
        if (sessionId == activeToken && activeToken != 0L) {
            lastVoiceDraft = candidate
        } else if (sessionId == pendingFinalToken && pendingFinalToken != 0L) {
            pendingFinalExpected = candidate
            lastVoiceDraft = candidate
        }
    }

    /**
     * A failed CAS means the user edited the draft (or ownership moved):
     * supersede voice ownership rather than overwriting the user's edit.
     *
     * Partial phase on a live session: cancels the session and notifies.
     * Partial phase on an already-terminal session (pending final set):
     * records nothing — the final event's own application settles or reports
     * the outcome, so a partial miss can never consume final ownership.
     *
     * Final phase: settles the pending session without resurrecting any
     * stale ownership.
     */
    fun noteApplyFailed(sessionId: Long, isFinal: Boolean) {
        if (isFinal) {
            if (sessionId == pendingFinalToken && pendingFinalToken != 0L) {
                cancelEngineSession()
                pendingFinalToken = 0L
                pendingFinalConversationId = 0L
                pendingFinalExpected = null
                lastVoiceDraft = null
                state = DictationState.IDLE
                notify("직접 입력한 내용이 있어 음성 입력을 중단했습니다.")
            }
            return
        }
        if (sessionId == activeToken && activeToken != 0L) {
            cancelEngineSession()
            clearSessionLocked()
            lastVoiceDraft = null
            state = DictationState.IDLE
            notify("직접 입력한 내용이 있어 음성 입력을 중단했습니다.")
        }
    }

    /** Idempotent release: no leaked recognizer, safe to call repeatedly. */
    fun destroy() {
        val current = engine
        engine = null
        clearSessionLocked()
        lastVoiceDraft = null
        baseText = ""
        if (state != DictationState.IDLE) state = DictationState.IDLE
        if (current != null) {
            current.setListener(null)
            try {
                current.destroy()
            } catch (_: Exception) {
                // Release is best-effort; state is already cleared above.
            }
        }
    }

    private fun getOrCreateEngine(): VoiceRecognitionEnginePort? {
        engine?.let { return it }
        val created = try {
            engineProvider()
        } catch (_: Exception) {
            null
        } ?: return null
        created.setListener(engineListener)
        engine = created
        return created
    }

    private fun cancelEngineSession() {
        try {
            engine?.cancel()
        } catch (_: Exception) {
            // Best-effort; state is cleared by the caller.
        }
    }

    private fun clearSessionLocked() {
        activeToken = 0L
        activeConversationId = 0L
        pendingFinalToken = 0L
        pendingFinalConversationId = 0L
        pendingFinalExpected = null
    }

    companion object {
        const val UNAVAILABLE_MESSAGE = "기기에서 사용할 수 있는 오프라인 음성 인식이 없습니다."
    }
}
