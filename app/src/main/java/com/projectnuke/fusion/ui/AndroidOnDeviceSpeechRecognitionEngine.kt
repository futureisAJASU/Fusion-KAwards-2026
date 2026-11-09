package com.projectnuke.fusion.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Controller-owned session gate for the platform recognizer.
 *
 * Session identity belongs to [VoiceDictationController]: [onStart] records
 * exactly the externally supplied token, and [onCancel] only invalidates the
 * live token so stale platform callbacks are dropped. The gate never mints
 * its own token, so repeated cancel/start cycles cannot drift the callback
 * identity away from the controller's session.
 */
internal class RecognitionSessionGate {
    private var liveToken = 0L

    fun onStart(externalToken: Long) {
        liveToken = externalToken
    }

    fun onCancel() {
        liveToken = 0L
    }

    fun accepts(token: Long): Boolean = token != 0L && token == liveToken

    fun current(): Long = liveToken
}

/** Minimal platform-recognizer surface, so start ownership is testable. */
internal interface SpeechRecognizerHandle {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

internal fun SpeechRecognizer.asRecognizerHandle(): SpeechRecognizerHandle =
    object : SpeechRecognizerHandle {
        override fun setRecognitionListener(listener: RecognitionListener) =
            this@asRecognizerHandle.setRecognitionListener(listener)

        override fun startListening(intent: Intent) =
            this@asRecognizerHandle.startListening(intent)

        override fun stopListening() = this@asRecognizerHandle.stopListening()

        override fun cancel() = this@asRecognizerHandle.cancel()

        override fun destroy() = this@asRecognizerHandle.destroy()
    }

internal fun firstTranscript(results: Bundle?): String =
    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

internal fun recognitionIntent(languageTag: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

internal fun recognitionListener(
    token: Long,
    sessionGate: RecognitionSessionGate,
    listenerProvider: () -> DictationEngineListener?,
): RecognitionListener =
    object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!sessionGate.accepts(token)) return
            val text = firstTranscript(partialResults)
            if (text.isNotBlank()) listenerProvider()?.onPartial(token, text)
        }

        override fun onResults(results: Bundle?) {
            if (!sessionGate.accepts(token)) return
            listenerProvider()?.onFinal(token, firstTranscript(results))
        }

        override fun onError(error: Int) {
            if (!sessionGate.accepts(token)) return
            listenerProvider()?.onEngineError(token, DictationErrorPolicy.messageFor(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

/**
 * Transactional start ownership for the platform recognizer.
 *
 * Success:
 *     one live platform recognizer
 *     one matching controller-issued session token
 *     listener installed, startListening accepted, handle published.
 *
 * Every failure path (creation, listener installation, gate establishment,
 * startListening) rolls back ALL partially established ownership: the
 * session gate is invalidated, the candidate is cancelled/destroyed exactly
 * once, and nothing stays published — there is no half-started state and no
 * leaked SpeechRecognizer. A stale failure never destroys a newer
 * recognizer: rollback only touches the candidate it created. The
 * controller remains the sole token authority; the lifecycle never mints a
 * replacement token.
 *
 * Pure seam (no Android Context) so the ownership contract is unit-testable
 * without instrumentation.
 */
internal class PlatformRecognizerLifecycle(
    private val sessionGate: RecognitionSessionGate,
    private val recognizerFactory: () -> SpeechRecognizerHandle,
    private val intentFactory: (languageTag: String) -> Intent,
    private val listenerFor: (token: Long) -> RecognitionListener,
) {
    private var recognizer: SpeechRecognizerHandle? = null

    @Volatile
    private var destroyed = false

    fun start(sessionToken: Long, languageTag: String): Boolean {
        if (destroyed) return false
        if (sessionToken == 0L) return false
        // Invalidate any previous recognizer first (best-effort restart).
        invalidateCurrent()
        return try {
            val candidate = recognizerFactory()
            try {
                candidate.setRecognitionListener(listenerFor(sessionToken))
                // Ownership must be visible to late callbacks before
                // startListening; a failure below rolls it back.
                sessionGate.onStart(sessionToken)
                recognizer = candidate
                candidate.startListening(intentFactory(languageTag))
                true
            } catch (_: Exception) {
                rollbackFailedStart(candidate)
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            // Best-effort; error callbacks report terminal failures.
        }
    }

    fun cancel() {
        try {
            sessionGate.onCancel()
            recognizer?.cancel()
        } catch (_: Exception) {
            // Best-effort; state is cleared by the caller.
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        sessionGate.onCancel()
        dispose(recognizer)
        recognizer = null
    }

    private fun invalidateCurrent() {
        // Invalidate the gate BEFORE releasing the old handle: a replacement
        // start must never observe (or outlive) the previous session token.
        sessionGate.onCancel()
        val current = recognizer ?: return
        recognizer = null
        dispose(current)
    }

    private fun rollbackFailedStart(candidate: SpeechRecognizerHandle) {
        // Session identity stays controller-owned: the gate is invalidated,
        // never renumbered, so late callbacks from the failed candidate are
        // fenced. A newer recognizer is never touched by a stale failure.
        sessionGate.onCancel()
        if (recognizer === candidate) {
            recognizer = null
        }
        dispose(candidate)
    }

    /**
     * Best-effort release of one platform handle. `cancel` and `destroy` each
     * run in their own exception boundary, so a failing cancel can never
     * suppress the destroy attempt.
     */
    private fun dispose(handle: SpeechRecognizerHandle?) {
        if (handle == null) return
        try {
            handle.cancel()
        } catch (_: Exception) {
        }
        try {
            handle.destroy()
        } catch (_: Exception) {
        }
    }
}

/**
 * Platform [VoiceRecognitionEnginePort] backed by Android's ON-DEVICE speech
 * recognizer.
 *
 * No cloud fallback: [start] uses
 * [SpeechRecognizer.isOnDeviceRecognitionAvailable] and
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer] only, and returns false
 * when on-device recognition is unavailable so the controller shows the
 * bounded offline message. Free-form recognition with partial results; the
 * device/app locale is preferred. No audio is persisted by Fusion — the
 * recognizer streams to the system service and only text transcripts leave
 * through [DictationEngineListener].
 *
 * Session identity is controller-owned: [start] echoes the supplied token on
 * every callback verbatim. Cancellation only invalidates the live token via
 * [RecognitionSessionGate]; it never renumbers the next session. Start is
 * transactional through [PlatformRecognizerLifecycle]: a failed start leaves
 * no live token, no published recognizer, and no leaked platform resource.
 */
internal class AndroidOnDeviceSpeechRecognitionEngine(
    private val appContext: Context,
) : VoiceRecognitionEnginePort {

    private var listener: DictationEngineListener? = null
    private val sessionGate = RecognitionSessionGate()
    private val lifecycle = PlatformRecognizerLifecycle(
        sessionGate = sessionGate,
        recognizerFactory = {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).asRecognizerHandle()
        },
        intentFactory = { languageTag -> recognitionIntent(languageTag) },
        listenerFor = { token -> recognitionListener(token, sessionGate) { listener } },
    )

    override fun setListener(listener: DictationEngineListener?) {
        this.listener = listener
    }

    override fun start(sessionToken: Long, languageTag: String): Boolean {
        if (sessionToken == 0L) return false
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return false
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) return false
        return lifecycle.start(sessionToken, languageTag)
    }

    override fun stop() = lifecycle.stop()

    override fun cancel() = lifecycle.cancel()

    override fun destroy() {
        lifecycle.destroy()
        listener = null
    }
}
