package com.projectnuke.fusion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.BreakIterator
import java.util.Locale

/**
 * Local-only read-aloud for assistant answers (NOT a voice assistant: no
 * recognition, no microphone, no wake word, no conversation mode).
 *
 * Offline truth: synthesis must use an installed voice with
 * networkRequired == false. Network voices are rejected ([ReadAloudVoicePolicy]);
 * with no suitable local voice the UI shows a bounded Korean message and
 * never falls back to network synthesis.
 *
 * Spoken content is always the user-visible answer (the same
 * [assistantVisibleAnswerForHandoff] boundary as the Translation handoff):
 * thinking, metrics, envelopes and metadata are never spoken.
 *
 * The platform engine is created lazily on the first explicit toggle —
 * never at startup — through [ReadAloudEnginePort] so unit tests run
 * without Android TTS. Platform TTS initializes asynchronously: a first
 * request that arrives while initialization is still pending is QUEUED and
 * resolved exactly once when the engine reports ready (or fails with a
 * bounded message when init fails). The queue has a single owner — this
 * controller — so a cancelled request can never speak late.
 */

internal data class InstalledVoice(
    val name: String,
    val locale: Locale,
    val networkRequired: Boolean,
)

internal object ReadAloudVoicePolicy {
    /**
     * Selects an installed OFFLINE voice for [locale]. Network-required
     * voices are always rejected; the choice is deterministic (by name) so
     * repeated reads behave identically.
     */
    fun selectOfflineVoice(voices: Collection<InstalledVoice>, locale: Locale): InstalledVoice? =
        voices
            .filter { !it.networkRequired && it.locale.language == locale.language }
            .minByOrNull { it.name }
}

internal object ReadAloudLocalePolicy {
    /**
     * Deterministic lightweight locale preference: Korean-dominant text
     * prefers Korean, English-dominant prefers English; mixed/unknown falls
     * back deterministically (ko, then en, then lowest language tag). Null
     * when no offline voice exists at all. No LLM involved.
     */
    fun preferredLocale(text: String, offlineLocales: Set<Locale>): Locale? {
        if (offlineLocales.isEmpty()) return null
        var hangul = 0
        var latin = 0
        for (character in text) {
            if (character in '\uAC00'..'\uD7A3' ||
                character in '\u1100'..'\u11FF' ||
                character in '\u3130'..'\u318F'
            ) {
                hangul++
            } else if (character in 'A'..'Z' || character in 'a'..'z') {
                latin++
            }
        }
        val wanted = if (hangul > latin) "ko" else if (latin > hangul) "en" else null
        wanted?.let { language ->
            offlineLocales.firstOrNull { it.language == language }
        }?.let { return it }
        return offlineLocales.firstOrNull { it.language == "ko" }
            ?: offlineLocales.firstOrNull { it.language == "en" }
            ?: offlineLocales.minByOrNull { it.toLanguageTag() }
    }
}

internal interface ReadAloudEngineListener {
    fun onReady()
    fun onInitFailed()
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)
}

internal enum class ReadAloudSpeakOutcome {
    STARTED,
    QUEUED_PENDING_INIT,
    NO_OFFLINE_VOICE,
    UNAVAILABLE,
}

internal interface ReadAloudEnginePort {
    fun setListener(listener: ReadAloudEngineListener?)
    val isReady: Boolean
    val initFailed: Boolean
    val maxSpeechInputLength: Int get() = Int.MAX_VALUE
    fun offlineLocales(): Set<Locale>
    fun speak(text: String, locale: Locale, utteranceId: String): ReadAloudSpeakOutcome
    fun stop()
    fun shutdown()
}

internal data class PendingReadAloud(
    val messageId: Long,
    val visibleText: String,
)

/** Splits only at grapheme boundaries when possible and preserves every input character. */
internal object ReadAloudTextChunker {
    fun split(text: String, maxLength: Int, locale: Locale): List<String> {
        if (text.isEmpty()) return emptyList()
        require(maxLength > 0)
        if (text.length <= maxLength) return listOf(text)

        val boundaries = BreakIterator.getCharacterInstance(locale).apply { setText(text) }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + maxLength)
            if (end < text.length && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) {
                end--
            }
            if (!boundaries.isBoundary(end)) {
                val boundary = boundaries.preceding(end)
                if (boundary > start) end = boundary
            }
            if (end <= start) {
                end = minOf(text.length, start + Character.charCount(text.codePointAt(start)))
            }

            if (end < text.length) {
                val whitespace = (end - 1 downTo start).firstOrNull { text[it].isWhitespace() }
                if (whitespace != null && whitespace - start >= maxLength / 2) end = whitespace + 1
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}

internal class LocalReadAloudController(
    private val engineProvider: () -> ReadAloudEnginePort?,
    private val notify: (String) -> Unit,
) {
    var speakingMessageId by mutableStateOf<Long?>(null)
        private set

    private var engine: ReadAloudEnginePort? = null
    private var pending: PendingReadAloud? = null
    private var activeUtteranceId: String? = null
    private var activeLocale: Locale? = null
    private val remainingChunks = ArrayDeque<String>()
    private var queuedChunkAfterInit = false

    /**
     * Toggles read-aloud for one assistant message. Only one session at a
     * time: toggling the speaking (or queued) message cancels it; toggling
     * another stops the previous and speaks the new answer. The engine is
     * created lazily here — never before the first explicit action. While
     * platform init is pending, the request is queued and the indicator
     * stays on so a second tap cancels before anything speaks.
     */
    fun toggle(messageId: Long, visibleText: String) {
        if (speakingMessageId == messageId) {
            pending = null
            stopEngine()
            speakingMessageId = null
            return
        }
        if (speakingMessageId != null) {
            pending = null
            stopEngine()
            speakingMessageId = null
        }
        if (visibleText.isBlank()) {
            notify("읽을 수 있는 내용이 없습니다.")
            return
        }
        val engine = getOrCreateEngine()
            ?: run {
                notify(NO_OFFLINE_VOICE_MESSAGE)
                return
            }
        if (engine.initFailed) {
            discardFailedEngine(engine)
            notify(INIT_FAILED_MESSAGE)
            return
        }
        if (!engine.isReady) {
            pending = PendingReadAloud(messageId, visibleText)
            speakingMessageId = messageId
            return
        }
        startSpeaking(engine, messageId, visibleText)
    }

    fun stop() {
        if (pending == null && speakingMessageId == null) return
        pending = null
        stopEngine()
        speakingMessageId = null
    }

    /** Idempotent release: no leaked engine, safe to call repeatedly. */
    fun shutdown() {
        val current = engine
        engine = null
        pending = null
        remainingChunks.clear()
        queuedChunkAfterInit = false
        activeLocale = null
        speakingMessageId = null
        activeUtteranceId = null
        if (current != null) {
            current.setListener(null)
            try {
                current.shutdown()
            } catch (_: Exception) {
                // Release is best-effort; state is already cleared above.
            }
        }
    }

    private fun startSpeaking(engine: ReadAloudEnginePort, messageId: Long, visibleText: String) {
        val locale = ReadAloudLocalePolicy.preferredLocale(visibleText, engine.offlineLocales())
        if (locale == null) {
            pending = null
            speakingMessageId = null
            notify(NO_OFFLINE_VOICE_MESSAGE)
            return
        }
        remainingChunks.clear()
        remainingChunks.addAll(ReadAloudTextChunker.split(visibleText, engine.maxSpeechInputLength, locale))
        activeLocale = locale
        speakingMessageId = messageId
        speakNextChunk(engine)
    }

    private fun speakNextChunk(engine: ReadAloudEnginePort) {
        val text = remainingChunks.removeFirstOrNull()
        val locale = activeLocale
        if (text == null || locale == null) {
            activeUtteranceId = null
            pending = null
            queuedChunkAfterInit = false
            speakingMessageId = null
            return
        }
        val utteranceId = java.util.UUID.randomUUID().toString()
        when (engine.speak(text, locale, utteranceId)) {
            ReadAloudSpeakOutcome.STARTED -> {
                activeUtteranceId = utteranceId
                pending = null
                queuedChunkAfterInit = false
            }
            ReadAloudSpeakOutcome.QUEUED_PENDING_INIT -> {
                // Init raced closed between chunks. Preserve just the
                // unspoken chunk so completed speech is never replayed.
                activeUtteranceId = null
                remainingChunks.addFirst(text)
                queuedChunkAfterInit = true
            }
            ReadAloudSpeakOutcome.NO_OFFLINE_VOICE -> {
                activeUtteranceId = null
                remainingChunks.clear()
                activeLocale = null
                pending = null
                speakingMessageId = null
                notify(NO_OFFLINE_VOICE_MESSAGE)
            }
            ReadAloudSpeakOutcome.UNAVAILABLE -> {
                activeUtteranceId = null
                remainingChunks.clear()
                activeLocale = null
                pending = null
                speakingMessageId = null
                if (engine.initFailed) discardFailedEngine(engine)
                notify(INIT_FAILED_MESSAGE)
            }
        }
    }

    private fun getOrCreateEngine(): ReadAloudEnginePort? {
        engine?.let { return it }
        val created = try {
            engineProvider()
        } catch (_: Exception) {
            null
        } ?: return null
        engine = created
        created.setListener(listenerFor(created))
        return created
    }

    private fun listenerFor(source: ReadAloudEnginePort) = object : ReadAloudEngineListener {
        override fun onReady() {
            if (engine !== source) return
            val queued = pending
            pending = null
            if (queued != null) startSpeaking(source, queued.messageId, queued.visibleText)
            else if (queuedChunkAfterInit) speakNextChunk(source)
        }

        override fun onInitFailed() {
            if (engine !== source) return
            pending = null
            remainingChunks.clear()
            queuedChunkAfterInit = false
            activeLocale = null
            activeUtteranceId = null
            speakingMessageId = null
            discardFailedEngine(source)
            notify(INIT_FAILED_MESSAGE)
        }

        override fun onDone(utteranceId: String) {
            if (engine !== source || utteranceId != activeUtteranceId) return
            activeUtteranceId = null
            if (remainingChunks.isNotEmpty()) speakNextChunk(source)
            else {
                activeLocale = null
                pending = null
                speakingMessageId = null
            }
        }

        override fun onError(utteranceId: String) {
            if (engine !== source || utteranceId != activeUtteranceId) return
            activeUtteranceId = null
            remainingChunks.clear()
            activeLocale = null
            pending = null
            speakingMessageId = null
            notify("음성으로 읽지 못했습니다.")
        }
    }

    private fun discardFailedEngine(failed: ReadAloudEnginePort) {
        if (engine !== failed) return
        engine = null
        failed.setListener(null)
        try {
            failed.shutdown()
        } catch (_: Exception) {
            // The failed engine has been detached; the next explicit read can retry.
        }
    }

    private fun stopEngine() {
        activeUtteranceId = null
        activeLocale = null
        remainingChunks.clear()
        queuedChunkAfterInit = false
        try {
            engine?.stop()
        } catch (_: Exception) {
            // Stopping is best-effort; state is cleared by the caller.
        }
    }

    companion object {
        const val NO_OFFLINE_VOICE_MESSAGE = "기기에서 사용할 수 있는 오프라인 음성이 없습니다."
        const val INIT_FAILED_MESSAGE = "음성 엔진을 시작하지 못했습니다."
    }
}

/** Required release runs even after stop fails; fatal errors retain identity. */
internal fun settleReadAloudEngine(stop: () -> Unit, shutdown: () -> Unit) {
    try {
        try { stop() } catch (_: Exception) { }
    } finally {
        try { shutdown() } catch (_: Exception) { }
    }
}
