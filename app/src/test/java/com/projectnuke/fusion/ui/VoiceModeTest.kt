package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Turn-based Voice Mode v1: text-mediated loop only. The transcript is
// ordinary user text through the normal pipeline; replies speak the visible
// answer once; the mic never reopens by itself.
class VoiceModeTest {

    private class FakeDictation : VoiceRecognitionEnginePort {
        var startCalls = 0
        var cancelCalls = 0
        var lastToken = 0L
        override fun setListener(listener: DictationEngineListener?) = Unit
        override fun start(sessionToken: Long, languageTag: String): Boolean {
            startCalls++
            lastToken = sessionToken
            return true
        }

        override fun stop() = Unit
        override fun cancel() {
            cancelCalls++
        }

        override fun destroy() = Unit
    }

    private class FakeSpeech : ReadAloudEnginePort {
        var stopCalls = 0
        override fun setListener(listener: ReadAloudEngineListener?) = Unit
        override val isReady: Boolean = true
        override val initFailed: Boolean = false
        override fun offlineLocales(): Set<Locale> = setOf(Locale.KOREAN)
        override fun speak(text: String, locale: Locale, utteranceId: String): ReadAloudSpeakOutcome =
            ReadAloudSpeakOutcome.STARTED

        override fun stop() {
            stopCalls++
        }

        override fun shutdown() = Unit
    }

    private fun mode(
        dictationNotices: MutableList<String> = mutableListOf(),
        speechNotices: MutableList<String> = mutableListOf(),
    ): Triple<VoiceModeController, FakeDictation, FakeSpeech> {
        val engine = FakeDictation()
        val speech = FakeSpeech()
        val dictation = VoiceDictationController(
            engineProvider = { engine },
            notify = { dictationNotices += it },
        )
        val readAloud = LocalReadAloudController(
            engineProvider = { speech },
            notify = { speechNotices += it },
        )
        return Triple(VoiceModeController(dictation, readAloud), engine, speech)
    }

    @Test
    fun `enter and exit manage only recognition and tts`() {
        val (voiceMode, engine, speech) = mode()
        assertFalse(voiceMode.active)
        voiceMode.enter()
        assertTrue(voiceMode.active)
        // Entering never starts recognition or speech by itself.
        assertEquals(0, engine.startCalls)
        voiceMode.exit()
        assertFalse(voiceMode.active)
        // Leaving with live sessions stops recognition/TTS…
        assertEquals(0, engine.cancelCalls)
        assertEquals(0, speech.stopCalls)
    }

    @Test
    fun `exit stops live recognition and speech sessions`() {
        val engine = FakeDictation()
        val speech = FakeSpeech()
        val dictation = VoiceDictationController({ engine }, {})
        val readAloud = LocalReadAloudController({ speech }, {})
        val voiceMode = VoiceModeController(dictation, readAloud)
        voiceMode.enter()
        assertTrue(dictation.start("base", 9L))
        readAloud.toggle(3L, "읽을 답변입니다.")
        voiceMode.exit()
        assertFalse(voiceMode.active)
        assertEquals(1, engine.cancelCalls)
        assertEquals(1, speech.stopCalls)
        // …and exit is idempotent.
        voiceMode.exit()
        assertEquals(1, engine.cancelCalls)
        assertEquals(1, speech.stopCalls)
    }

    @Test
    fun `auto-submit only for applied voice-mode finals`() {
        val final = VoiceTextEvent(7L, "인식된 질문", true)
        assertTrue(shouldAutoSubmitVoiceFinal(true, final, true))
        assertFalse(shouldAutoSubmitVoiceFinal(false, final, true))
        assertFalse(shouldAutoSubmitVoiceFinal(true, final.copy(isFinal = false), true))
        assertFalse(shouldAutoSubmitVoiceFinal(true, final, false))
        assertFalse(shouldAutoSubmitVoiceFinal(true, final.copy(candidate = "   "), true))
    }

    @Test
    fun `reply selection speaks the new assistant message once`() {
        val messages = listOf(
            VoiceModeMessage(1L, "user"),
            VoiceModeMessage(2L, "assistant"),
        )
        val selected = selectVoiceReplyToSpeak(
            voiceModeActive = true,
            awaitingReply = true,
            isGenerating = false,
            messages = messages,
            submitSize = 1,
            submitConversationId = 9L,
            currentConversationId = 9L,
            lastSpokenId = null,
        )
        assertEquals(VoiceModeMessage(2L, "assistant"), selected)
        // Already spoken: never repeat.
        assertNull(
            selectVoiceReplyToSpeak(true, true, false, messages, 1, 9L, 9L, 2L),
        )
    }

    @Test
    fun `reply selection waits for generation and growth`() {
        val messages = listOf(VoiceModeMessage(1L, "user"))
        // Still generating.
        assertNull(selectVoiceReplyToSpeak(true, true, true, messages, 0, 9L, 9L, null))
        // No new message yet.
        assertNull(selectVoiceReplyToSpeak(true, true, false, messages, 1, 9L, 9L, null))
        // Mode off or not awaiting.
        assertNull(selectVoiceReplyToSpeak(false, true, false, messages, 0, 9L, 9L, null))
        assertNull(selectVoiceReplyToSpeak(true, false, false, messages, 0, 9L, 9L, null))
    }

    @Test
    fun `reply selection follows submitted user id when the latest 100 message window is full`() {
        val messages = (3L..100L).map { VoiceModeMessage(it, "assistant") } + listOf(
            VoiceModeMessage(101L, "user"),
            VoiceModeMessage(102L, "assistant"),
        )

        assertEquals(
            VoiceModeMessage(102L, "assistant"),
            selectVoiceReplyToSpeak(
                voiceModeActive = true,
                awaitingReply = true,
                isGenerating = false,
                messages = messages,
                submitSize = 100,
                submitConversationId = 9L,
                currentConversationId = 9L,
                lastSpokenId = null,
                awaitingUserMessageId = 101L,
            ),
        )
    }

    @Test
    fun `reply selection rejects a later user turn even after submitted row ages out`() {
        val messages = (4L..101L).map { VoiceModeMessage(it, "assistant") } + listOf(
            VoiceModeMessage(102L, "user"),
            VoiceModeMessage(103L, "assistant"),
        )

        assertNull(
            selectVoiceReplyToSpeak(
                voiceModeActive = true,
                awaitingReply = true,
                isGenerating = false,
                messages = messages,
                submitSize = 100,
                submitConversationId = 9L,
                currentConversationId = 9L,
                lastSpokenId = null,
                awaitingUserMessageId = 2L,
            ),
        )
    }

    @Test
    fun `reply selection never speaks the wrong chat`() {
        val messages = listOf(
            VoiceModeMessage(1L, "user"),
            VoiceModeMessage(2L, "assistant"),
        )
        assertNull(selectVoiceReplyToSpeak(true, true, false, messages, 0, 9L, 10L, null))
    }

    @Test
    fun `non-assistant latest message is not spoken`() {
        val messages = listOf(
            VoiceModeMessage(1L, "user"),
            VoiceModeMessage(3L, "user"),
        )
        assertNull(selectVoiceReplyToSpeak(true, true, false, messages, 1, 9L, 9L, null))
    }

    @Test
    fun `generation snapshot carries no audio payload`() {
        // Structural guarantee: only recognized TEXT (rawUserText/promptText)
        // enters the normal pipeline. Fail loudly if an audio-shaped member
        // is ever added to the request snapshot.
        val audioLike = GenerationRequestSnapshot::class.members.filter { member ->
            val name = member.name.lowercase()
            name.contains("audio") || name.contains("speech") || name.contains("recording") ||
                name.contains("utterance") || name.contains("microphone") || name.contains("voice")
        }
        assertTrue("audio-shaped snapshot members: ${audioLike.map { it.name }}", audioLike.isEmpty())
        val byteArrays = GenerationRequestSnapshot::class.members.filter { member ->
            (member.returnType.classifier as? kotlin.reflect.KClass<*>)?.qualifiedName == "kotlin.ByteArray"
        }
        assertTrue(byteArrays.isEmpty())
        // The transcript travels as ordinary text.
        val snapshot = GenerationRequestSnapshot(
            requestId = "voice-1",
            conversationId = 9L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = null,
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "인식된 질문",
            rawUserText = "인식된 질문",
            createdAt = 1L,
        )
        assertEquals("인식된 질문", snapshot.rawUserText)
        assertEquals("LOCAL_MODEL", snapshot.generationModeKey)
    }

    @Test
    fun `text-only model path accepts transcript as ordinary history`() {
        // Voice Mode performs no audio-capability inspection: the transcript
        // is a plain user ChatMessage like typed text.
        val history = listOf(ChatMessage(role = "user", content = "인식된 질문"))
        assertEquals("user", history.single().role)
        assertEquals("인식된 질문", history.single().content)
    }
}
