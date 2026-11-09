package com.projectnuke.fusion.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.chat.SubmissionAdmissionResult
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Voice Mode regression gate over the REAL controller ownership sequence
// (not pure helper policy): recognition start -> final transcript -> durable
// composer CAS -> submit eligibility -> exactly one auto-submit request.
// A second conversation proves cross-chat isolation; both drafts start empty
// so CAS equality cannot accidentally hide a misdirected write.
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceModeGateTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
    }

    private companion object {
        const val CONVERSATION_A = 101L
        const val CONVERSATION_B = 202L
    }

    /**
     * Strict engine: callbacks only ever carry the controller-issued start
     * token — no manually plumbed test tokens.
     */
    private class StrictEngine : VoiceRecognitionEnginePort {
        var attached: DictationEngineListener? = null
        var liveToken = 0L
        var startCalls = 0

        override fun setListener(listener: DictationEngineListener?) {
            this.attached = listener
        }

        override fun start(sessionToken: Long, languageTag: String): Boolean {
            startCalls++
            liveToken = sessionToken
            return true
        }

        override fun stop() = Unit

        override fun cancel() {
            liveToken = 0L
        }

        override fun destroy() {
            liveToken = 0L
            attached = null
        }

        fun emitFinal(transcript: String) {
            attached?.onFinal(liveToken, transcript)
        }
    }

    private class FakeSpeech : ReadAloudEnginePort {
        override fun setListener(listener: ReadAloudEngineListener?) = Unit
        override val isReady: Boolean = true
        override val initFailed: Boolean = false
        override fun offlineLocales(): Set<Locale> = setOf(Locale.KOREAN)
        override fun speak(text: String, locale: Locale, utteranceId: String): ReadAloudSpeakOutcome =
            ReadAloudSpeakOutcome.STARTED

        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private fun viewModelIn(storeDir: File): ChatViewModel =
        ChatViewModel.forTesting(
            PersistentComposerDraftStore(
                file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                resolveManagedAttachment = { null },
                registerPendingAttachment = { it.absolutePath },
            ),
        ).also { viewModel = it }

    private fun gate(
        vm: ChatViewModel,
        events: MutableList<VoiceTextEvent>,
    ): Triple<VoiceDictationController, StrictEngine, VoiceModeController> {
        val engine = StrictEngine()
        val dictation = VoiceDictationController(
            engineProvider = { engine },
            notify = { },
        ).apply {
            onVoiceText = { events += it }
        }
        val voiceMode = VoiceModeController(
            dictation,
            LocalReadAloudController(engineProvider = { FakeSpeech() }, notify = { }),
        )
        return Triple(dictation, engine, voiceMode)
    }

    @Test
    fun `voice mode final applies once and auto-submits exactly once`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-voice-gate").toFile()
        try {
            val vm = viewModelIn(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            assertEquals("", vm.draft(CONVERSATION_A).rawInput)
            val events = mutableListOf<VoiceTextEvent>()
            val (dictation, engine, voiceMode) = gate(vm, events)

            voiceMode.enter()
            assertTrue(voiceMode.active)
            assertTrue(dictation.start("", CONVERSATION_A))
            engine.emitFinal("인식된 질문")
            assertEquals(1, events.size)
            val final = events.single()
            assertTrue(final.isFinal)
            assertEquals(CONVERSATION_A, final.conversationId)

            val submits = mutableListOf<Long>()
            val auto = applyVoiceTextEvent(
                event = final,
                voiceModeActive = voiceMode.active,
                currentConversationId = CONVERSATION_A,
                dictation = dictation,
                chatViewModel = vm,
                onAutoSubmit = { submits += it },
            )
            assertTrue(auto)
            assertEquals(listOf(CONVERSATION_A), submits)
            assertEquals("인식된 질문", vm.draft(CONVERSATION_A).rawInput)

            // Exactly-once: re-applying the same final is stale.
            val again = applyVoiceTextEvent(
                event = final,
                voiceModeActive = voiceMode.active,
                currentConversationId = CONVERSATION_A,
                dictation = dictation,
                chatViewModel = vm,
                onAutoSubmit = { submits += it },
            )
            assertFalse(again)
            assertEquals(listOf(CONVERSATION_A), submits)

            // The voice-written draft is eligible at the normal submit entry.
            val admission = vm.beginSubmissionResult(CONVERSATION_A)
            assertTrue(admission is SubmissionAdmissionResult.Accepted)
            val snapshot = (admission as SubmissionAdmissionResult.Accepted).snapshot
            assertEquals("인식된 질문", snapshot.rawInput)
            assertEquals(CONVERSATION_A, snapshot.conversationId)
            assertTrue(vm.settleSubmissionOwner(CONVERSATION_A, snapshot.token))
        } finally {
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `navigate before final cannot modify or submit the new chat`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-voice-gate-nav").toFile()
        try {
            val vm = viewModelIn(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            // Both drafts start empty: CAS equality cannot hide the bug.
            assertEquals("", vm.draft(CONVERSATION_A).rawInput)
            assertEquals("", vm.draft(CONVERSATION_B).rawInput)
            val events = mutableListOf<VoiceTextEvent>()
            val (dictation, engine, voiceMode) = gate(vm, events)

            voiceMode.enter()
            assertTrue(dictation.start("", CONVERSATION_A))
            // The user navigates to B before the final arrives; the live
            // session is superseded like the production conversation guard.
            dictation.cancelIfConversationChanged(CONVERSATION_B)
            engine.emitFinal("A의 질문")
            // …or, if the final was already in flight, the bound application
            // path still targets only the originating conversation.
            val inFlight = events.toList()
            val submits = mutableListOf<Long>()
            for (event in inFlight) {
                applyVoiceTextEvent(
                    event = event,
                    voiceModeActive = voiceMode.active,
                    currentConversationId = CONVERSATION_B,
                    dictation = dictation,
                    chatViewModel = vm,
                    onAutoSubmit = { submits += it },
                )
            }
            assertTrue(submits.isEmpty())
            assertEquals("", vm.draft(CONVERSATION_B).rawInput)
        } finally {
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `bound final applies only to its originating conversation`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-voice-gate-bound").toFile()
        try {
            val vm = viewModelIn(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            assertEquals("", vm.draft(CONVERSATION_A).rawInput)
            assertEquals("", vm.draft(CONVERSATION_B).rawInput)
            val events = mutableListOf<VoiceTextEvent>()
            val (dictation, engine, voiceMode) = gate(vm, events)

            voiceMode.enter()
            assertTrue(dictation.start("", CONVERSATION_A))
            engine.emitFinal("A의 질문")
            assertEquals(1, events.size)

            // Applied while B is visible: only A may change, no auto-submit.
            val submits = mutableListOf<Long>()
            val auto = applyVoiceTextEvent(
                event = events.single(),
                voiceModeActive = voiceMode.active,
                currentConversationId = CONVERSATION_B,
                dictation = dictation,
                chatViewModel = vm,
                onAutoSubmit = { submits += it },
            )
            assertFalse(auto)
            assertTrue(submits.isEmpty())
            assertEquals("A의 질문", vm.draft(CONVERSATION_A).rawInput)
            assertEquals("", vm.draft(CONVERSATION_B).rawInput)
        } finally {
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `cancel then restart accepts the second transcript`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-voice-gate-restart").toFile()
        try {
            val vm = viewModelIn(storeDir)
            assertTrue(vm.draftMachine.awaitHydration())
            val events = mutableListOf<VoiceTextEvent>()
            val (dictation, engine, voiceMode) = gate(vm, events)

            voiceMode.enter()
            assertTrue(dictation.start("", CONVERSATION_A))
            dictation.cancel()
            assertEquals(1, engine.startCalls)
            assertTrue(dictation.start("", CONVERSATION_A))
            assertEquals(2, engine.startCalls)
            engine.emitFinal("두 번째 질문")
            assertEquals(1, events.size)

            val submits = mutableListOf<Long>()
            val auto = applyVoiceTextEvent(
                event = events.single(),
                voiceModeActive = voiceMode.active,
                currentConversationId = CONVERSATION_A,
                dictation = dictation,
                chatViewModel = vm,
                onAutoSubmit = { submits += it },
            )
            assertTrue(auto)
            assertEquals(listOf(CONVERSATION_A), submits)
            assertEquals("두 번째 질문", vm.draft(CONVERSATION_A).rawInput)
        } finally {
            storeDir.deleteRecursively()
        }
    }
}
