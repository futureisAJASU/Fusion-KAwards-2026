package com.projectnuke.fusion.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Local-only read-aloud: offline voice truth, deterministic locale policy,
// async-init queueing, single-session lifecycle — all without a real TTS
// engine.
class LocalReadAloudTest {

    private class FakeEngine(
        var locales: Set<Locale> = setOf(Locale.KOREAN, Locale.ENGLISH),
        var ready: Boolean = true,
        var failed: Boolean = false,
        var speakOutcome: ReadAloudSpeakOutcome = ReadAloudSpeakOutcome.STARTED,
        private val maxInputLength: Int = Int.MAX_VALUE,
    ) : ReadAloudEnginePort {
        val spoken = mutableListOf<Pair<String, Locale>>()
        var stopCalls = 0
        var shutdownCalls = 0
        var attachedListener: ReadAloudEngineListener? = null
        val terminalCallbacks = mutableListOf<() -> Unit>()

        override fun setListener(listener: ReadAloudEngineListener?) {
            this.attachedListener = listener
        }

        override val isReady: Boolean get() = ready
        override val initFailed: Boolean get() = failed
        override val maxSpeechInputLength: Int get() = maxInputLength

        override fun offlineLocales(): Set<Locale> = locales

        override fun speak(text: String, locale: Locale, utteranceId: String): ReadAloudSpeakOutcome {
            if (speakOutcome != ReadAloudSpeakOutcome.STARTED) return speakOutcome
            spoken += text to locale
            val captured = attachedListener
            terminalCallbacks += { captured?.onDone(utteranceId) }
            return ReadAloudSpeakOutcome.STARTED
        }

        override fun stop() {
            stopCalls++
        }

        override fun shutdown() {
            shutdownCalls++
        }
    }

    private fun controller(
        engine: FakeEngine,
        notices: MutableList<String>,
        providerCalls: IntArray = IntArray(1),
    ): LocalReadAloudController = LocalReadAloudController(
        engineProvider = {
            providerCalls[0]++
            engine
        },
        notify = { notices += it },
    )

    // ---- locale policy -------------------------------------------------------

    @Test fun `accepted old terminal delivered after new speech preserves current owner`() {
        val engine = FakeEngine()
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11, "first answer")
        val acceptedOldTerminal = engine.terminalCallbacks.single()
        readAloud.toggle(22, "second answer")
        acceptedOldTerminal()
        assertEquals(22L, readAloud.speakingMessageId)
    }

    @Test fun `blank replacement stops previous speech and clears its indicator`() {
        val engine = FakeEngine()
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)

        readAloud.toggle(11L, "first answer")
        readAloud.toggle(22L, " \n ")

        assertNull(readAloud.speakingMessageId)
        assertEquals(1, engine.stopCalls)
        assertEquals(listOf("읽을 수 있는 내용이 없습니다."), notices)
    }

    @Test
    fun `korean-dominant text prefers korean`() {
        val locale = ReadAloudLocalePolicy.preferredLocale(
            "HBM은 고대역폭 메모리입니다. 정말 유용해요.",
            setOf(Locale.KOREAN, Locale.ENGLISH),
        )
        assertEquals("ko", locale?.language)
    }

    @Test
    fun `english-dominant text prefers english`() {
        val locale = ReadAloudLocalePolicy.preferredLocale(
            "High bandwidth memory is really useful for AI accelerators.",
            setOf(Locale.KOREAN, Locale.ENGLISH),
        )
        assertEquals("en", locale?.language)
    }

    @Test
    fun `mixed unknown falls back deterministically`() {
        val available = setOf(Locale.ENGLISH, Locale.JAPANESE)
        val first = ReadAloudLocalePolicy.preferredLocale("12345 !!!", available)
        val second = ReadAloudLocalePolicy.preferredLocale("12345 !!!", available)
        assertEquals(first, second)
        assertTrue(first == Locale.ENGLISH || first == Locale.JAPANESE)
    }

    @Test
    fun `no offline voice yields null`() {
        assertNull(ReadAloudLocalePolicy.preferredLocale("안녕하세요", emptySet()))
    }

    // ---- offline voice selection ----------------------------------------------

    @Test
    fun `network-required voices rejected offline voice accepted`() {
        val voices = listOf(
            InstalledVoice("net-ko", Locale.KOREAN, networkRequired = true),
            InstalledVoice("local-ko", Locale.KOREAN, networkRequired = false),
        )
        assertEquals("local-ko", ReadAloudVoicePolicy.selectOfflineVoice(voices, Locale.KOREAN)?.name)
    }

    @Test
    fun `all network voices means no selection`() {
        val voices = listOf(
            InstalledVoice("net-ko", Locale.KOREAN, networkRequired = true),
        )
        assertNull(ReadAloudVoicePolicy.selectOfflineVoice(voices, Locale.KOREAN))
    }

    @Test
    fun `offline selection is deterministic across same-language voices`() {
        val voices = listOf(
            InstalledVoice("b-voice", Locale.KOREAN, networkRequired = false),
            InstalledVoice("a-voice", Locale.KOREAN, networkRequired = false),
        )
        assertEquals("a-voice", ReadAloudVoicePolicy.selectOfflineVoice(voices, Locale.KOREAN)?.name)
        assertEquals("a-voice", ReadAloudVoicePolicy.selectOfflineVoice(voices, Locale.KOREAN)?.name)
    }

    // ---- controller lifecycle --------------------------------------------------

    @Test
    fun `no engine initialization before explicit action`() {
        val engine = FakeEngine()
        val notices = mutableListOf<String>()
        val calls = IntArray(1)
        val readAloud = controller(engine, notices, calls)
        readAloud.stop()
        readAloud.shutdown()
        assertEquals(0, calls[0])
        assertNull(readAloud.speakingMessageId)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `toggle speaks the exact visible text`() {
        val engine = FakeEngine()
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11L, "보이는 답변입니다.")
        assertEquals(11L, readAloud.speakingMessageId)
        assertEquals(listOf("보이는 답변입니다." to Locale.KOREAN), engine.spoken)
    }

    @Test
    fun `second speak stops and replaces the first`() {
        val engine = FakeEngine()
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11L, "첫 번째 답변입니다.")
        readAloud.toggle(22L, "두 번째 답변입니다.")
        assertEquals(1, engine.stopCalls)
        assertEquals(22L, readAloud.speakingMessageId)
        assertEquals(2, engine.spoken.size)
    }

    @Test
    fun `toggling the speaking answer stops it`() {
        val engine = FakeEngine()
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11L, "보이는 답변입니다.")
        readAloud.toggle(11L, "보이는 답변입니다.")
        assertNull(readAloud.speakingMessageId)
        assertEquals(1, engine.stopCalls)
        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun `engine done clears the session`() {
        val engine = FakeEngine()
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11L, "보이는 답변입니다.")
        engine.terminalCallbacks.last()()
        assertNull(readAloud.speakingMessageId)
    }

    @Test
    fun `unavailable offline voice gives bounded failure`() {
        val engine = FakeEngine(locales = emptySet())
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        readAloud.toggle(11L, "보이는 답변입니다.")
        assertNull(readAloud.speakingMessageId)
        assertEquals(
            listOf(LocalReadAloudController.NO_OFFLINE_VOICE_MESSAGE),
            notices,
        )
        assertEquals(
            "기기에서 사용할 수 있는 오프라인 음성이 없습니다.",
            LocalReadAloudController.NO_OFFLINE_VOICE_MESSAGE,
        )
    }

    @Test
    fun `missing engine gives bounded failure`() {
        val notices = mutableListOf<String>()
        val readAloud = LocalReadAloudController(
            engineProvider = { null },
            notify = { notices += it },
        )
        readAloud.toggle(11L, "보이는 답변입니다.")
        assertNull(readAloud.speakingMessageId)
        assertEquals(1, notices.size)
    }

    @Test
    fun `shutdown is idempotent and releases the engine`() {
        val engine = FakeEngine()
        val calls = IntArray(1)
        val readAloud = controller(engine, mutableListOf(), calls)
        readAloud.toggle(11L, "보이는 답변입니다.")
        readAloud.shutdown()
        readAloud.shutdown()
        assertEquals(1, engine.shutdownCalls)
        assertNull(readAloud.speakingMessageId)
        assertEquals(1, calls[0])
    }

    // ---- async-init queueing regression -----------------------------------------

    @Test
    fun `first request while initializing queues without speaking`() {
        val engine = FakeEngine(ready = false)
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        readAloud.toggle(11L, "보이는 답변입니다.")
        // Queued: indicator on, engine speak not called yet, no failure shown.
        assertEquals(11L, readAloud.speakingMessageId)
        assertTrue(engine.spoken.isEmpty())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `delayed init success speaks the queued request exactly once`() {
        val engine = FakeEngine(ready = false)
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        readAloud.toggle(11L, "보이는 답변입니다.")
        engine.ready = true
        engine.attachedListener?.onReady()
        assertEquals(listOf("보이는 답변입니다." to Locale.KOREAN), engine.spoken)
        assertEquals(11L, readAloud.speakingMessageId)
        assertTrue(notices.isEmpty())
        // A redundant ready signal never replays the consumed request.
        engine.attachedListener?.onReady()
        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun `queued request cancelled before ready never speaks`() {
        val engine = FakeEngine(ready = false)
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11L, "보이는 답변입니다.")
        readAloud.toggle(11L, "보이는 답변입니다.")
        assertNull(readAloud.speakingMessageId)
        engine.ready = true
        engine.attachedListener?.onReady()
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `delayed init failure gives bounded failure and never speaks`() {
        val engine = FakeEngine(ready = false, failed = true)
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        // toggle() observes initFailed synchronously here.
        readAloud.toggle(11L, "보이는 답변입니다.")
        assertNull(readAloud.speakingMessageId)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(listOf(LocalReadAloudController.INIT_FAILED_MESSAGE), notices)
    }

    @Test
    fun `async init failure after queueing clears with bounded failure`() {
        val engine = FakeEngine(ready = false)
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        readAloud.toggle(11L, "보이는 답변입니다.")
        assertEquals(11L, readAloud.speakingMessageId)
        engine.failed = true
        engine.attachedListener?.onInitFailed()
        assertNull(readAloud.speakingMessageId)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(listOf(LocalReadAloudController.INIT_FAILED_MESSAGE), notices)
    }

    @Test
    fun `initialization failure releases the cached engine so the next read can retry`() {
        val first = FakeEngine(ready = false)
        val recovered = FakeEngine()
        val engines = listOf(first, recovered)
        val providerCalls = IntArray(1)
        val notices = mutableListOf<String>()
        val readAloud = LocalReadAloudController(
            engineProvider = { engines[providerCalls[0]++] },
            notify = { notices += it },
        )

        readAloud.toggle(11L, "첫 번째 시도")
        first.failed = true
        first.attachedListener?.onInitFailed()
        assertNull(readAloud.speakingMessageId)
        assertEquals(1, first.shutdownCalls)

        readAloud.toggle(12L, "두 번째 시도")

        assertEquals(2, providerCalls[0])
        assertEquals("두 번째 시도" to Locale.KOREAN, recovered.spoken.single())
        assertEquals(12L, readAloud.speakingMessageId)
        assertEquals(listOf(LocalReadAloudController.INIT_FAILED_MESSAGE), notices)
    }

    @Test
    fun `long answers are spoken in bounded sequential chunks under one owner`() {
        val engine = FakeEngine(maxInputLength = 8)
        val readAloud = controller(engine, mutableListOf())
        val answer = "hello world"

        readAloud.toggle(11L, answer)
        assertEquals(1, engine.spoken.size)
        assertTrue(engine.spoken.all { it.first.length <= 8 })
        assertEquals(11L, readAloud.speakingMessageId)

        engine.terminalCallbacks[0]()
        assertEquals(2, engine.spoken.size)
        assertEquals(11L, readAloud.speakingMessageId)
        engine.terminalCallbacks[1]()

        assertEquals(answer, engine.spoken.joinToString(separator = "") { it.first })
        assertNull(readAloud.speakingMessageId)
    }

    @Test
    fun `speech chunker honors exact boundary and preserves Korean emoji and combining marks`() {
        val exact = ReadAloudTextChunker.split("12345678", 8, Locale.ENGLISH)
        val over = ReadAloudTextChunker.split("123456789", 8, Locale.ENGLISH)
        val unicodeText = "가나다🙂e\u0301라마바사"
        val unicode = ReadAloudTextChunker.split(unicodeText, 5, Locale.KOREAN)

        assertEquals(listOf("12345678"), exact)
        assertEquals("123456789", over.joinToString(""))
        assertTrue(over.all { it.length <= 8 })
        assertEquals(unicodeText, unicode.joinToString(""))
        assertTrue(unicode.all { it.length <= 5 })
        assertTrue(unicode.zipWithNext().none { (left, right) ->
            left.last().isHighSurrogate() && right.first().isLowSurrogate()
        })
        assertTrue(unicode.zipWithNext().none { (left, right) ->
            left.last() == 'e' && right.first() == '\u0301'
        })
    }

    @Test
    fun `validated offline locale is used for the queued speech`() {
        // Only Korean is installed offline: even English-dominant text must
        // resolve to the validated offline Korean voice, never a network one.
        val engine = FakeEngine(ready = false, locales = setOf(Locale.KOREAN))
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11L, "High bandwidth memory is useful.")
        engine.ready = true
        engine.attachedListener?.onReady()
        assertEquals(listOf("High bandwidth memory is useful." to Locale.KOREAN), engine.spoken)
    }

    @Test
    fun `background stop halts speech and clears the session`() {
        val engine = FakeEngine()
        val readAloud = controller(engine, mutableListOf())
        readAloud.toggle(11L, "보이는 답변입니다.")
        // ChatScreen ON_STOP boundary calls stop(): speech halts, engine kept.
        readAloud.stop()
        assertNull(readAloud.speakingMessageId)
        assertEquals(1, engine.stopCalls)
        assertEquals(0, engine.shutdownCalls)
    }
}
