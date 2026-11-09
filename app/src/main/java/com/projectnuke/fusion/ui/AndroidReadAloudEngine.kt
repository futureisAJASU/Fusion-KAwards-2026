package com.projectnuke.fusion.ui

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Platform [ReadAloudEnginePort] backed by Android TextToSpeech.
 *
 * No third-party dependency, no speech recognition, no microphone. The
 * instance is created lazily by [LocalReadAloudController] on the first
 * explicit read-aloud action — never at app startup.
 *
 * Offline-only: [speak] re-validates the installed voices at call time,
 * selects only a voice with networkRequired == false via
 * [ReadAloudVoicePolicy], and assigns that validated voice object LAST
 * (after the locale) so the final selection can never be a network voice,
 * even if the installed set changed mid-flight. No network fallback exists.
 *
 * Async init: readiness is reported through [ReadAloudEngineListener.onReady]
 * / [onInitFailed]; the controller owns the single queued request, so this
 * adapter keeps no speech queue of its own.
 */
internal class AndroidReadAloudEngine(
    appContext: Context,
) : ReadAloudEnginePort {

    private var listener: ReadAloudEngineListener? = null
    private val callbacks = android.os.Handler(android.os.Looper.getMainLooper())
    private fun dispatch(callback: (ReadAloudEngineListener) -> Unit) {
        callbacks.post { if (!destroyed) listener?.let(callback) }
    }

    private val utteranceFence = UtteranceOwnershipFence()

    @Volatile
    override var isReady = false
        private set

    @Volatile
    override var initFailed = false
        private set

    override val maxSpeechInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()

    @Volatile
    private var destroyed = false

    private val tts = TextToSpeech(appContext) { status ->
        if (destroyed) return@TextToSpeech
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            dispatch { it.onReady() }
        } else {
            initFailed = true
            dispatch { it.onInitFailed() }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    // Only the currently owned utterance may settle the
                    // session; stale-generation callbacks stay fenced.
                    if (utteranceFence.accept(utteranceId)) dispatch { it.onDone(utteranceId!!) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceFence.accept(utteranceId)) dispatch { it.onError(utteranceId!!) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceFence.accept(utteranceId)) dispatch { it.onError(utteranceId!!) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
            },
        )
    }

    override fun setListener(listener: ReadAloudEngineListener?) {
        this.listener = listener
    }

    override fun offlineLocales(): Set<Locale> = try {
        tts.voices.orEmpty()
            .filter { !it.isNetworkConnectionRequired }
            .mapTo(LinkedHashSet()) { it.locale }
    } catch (_: Exception) {
        emptySet()
    }

    override fun speak(text: String, locale: Locale, utteranceId: String): ReadAloudSpeakOutcome {
        if (destroyed || initFailed) return ReadAloudSpeakOutcome.UNAVAILABLE
        if (!isReady) return ReadAloudSpeakOutcome.QUEUED_PENDING_INIT
        val installed = try {
            tts.voices.orEmpty().map { voice ->
                InstalledVoice(
                    name = voice.name,
                    locale = voice.locale,
                    networkRequired = voice.isNetworkConnectionRequired,
                )
            }
        } catch (_: Exception) {
            return ReadAloudSpeakOutcome.UNAVAILABLE
        }
        val selected = ReadAloudVoicePolicy.selectOfflineVoice(installed, locale)
            ?: return ReadAloudSpeakOutcome.NO_OFFLINE_VOICE
        return try {
            // FINAL assignment is the re-validated offline voice object:
            // re-resolve by stable name and re-check the network flag so a
            // changed voice set cannot swap in a network voice underneath.
            val voiceObject = tts.voices.firstOrNull {
                it.name == selected.name && !it.isNetworkConnectionRequired
            } ?: return ReadAloudSpeakOutcome.NO_OFFLINE_VOICE
            tts.language = voiceObject.locale
            tts.voice = voiceObject
            utteranceFence.begin(utteranceId)
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) == TextToSpeech.SUCCESS) {
                ReadAloudSpeakOutcome.STARTED
            } else {
                // Failed platform speak: do not leave a falsely active
                // ownership token behind.
                utteranceFence.invalidate()
                ReadAloudSpeakOutcome.UNAVAILABLE
            }
        } catch (_: Exception) {
            utteranceFence.invalidate()
            ReadAloudSpeakOutcome.UNAVAILABLE
        }
    }

    override fun stop() {
        // Drop ownership BEFORE flushing so any callback the stop triggers
        // is already fenced and cannot settle a newer session.
        utteranceFence.invalidate()
        try {
            tts.stop()
        } catch (_: Exception) {
            // Best-effort; the controller already cleared its state.
        }
    }

    override fun shutdown() {
        if (destroyed) return
        destroyed = true
        utteranceFence.invalidate()
        try {
            settleReadAloudEngine(stop = { tts.stop() }, shutdown = { tts.shutdown() })
        } finally {
            listener = null
        }
    }
}
