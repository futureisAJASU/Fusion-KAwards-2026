package com.projectnuke.fusion.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// On-device voice dictation: editable dictation into the ordinary composer.
// No engine before explicit action, voice ownership never overwrites user
// edits, no auto-send/generation — all without Android speech services.
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDictationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher
    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
    }

    private class FakeRecognitionEngine(
        var startResult: Boolean = true,
    ) : VoiceRecognitionEnginePort {
        var attachedListener: DictationEngineListener? = null
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        var destroyCalls = 0
        var lastLanguage = ""
        var lastToken = 0L
        val startTokens = mutableListOf<Long>()

        override fun setListener(listener: DictationEngineListener?) {
            this.attachedListener = listener
        }

        override fun start(sessionToken: Long, languageTag: String): Boolean {
            startCalls++
            lastToken = sessionToken
            startTokens += sessionToken
            if (!startResult) return false
            lastLanguage = languageTag
            return true
        }

        override fun stop() {
            stopCalls++
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun destroy() {
            destroyCalls++
        }

        /** Strict emission: callbacks always carry the controller-issued token. */
        fun emitPartial(transcript: String) {
            attachedListener?.onPartial(lastToken, transcript)
        }

        fun emitFinal(transcript: String) {
            attachedListener?.onFinal(lastToken, transcript)
        }

        fun emitError(message: String) {
            attachedListener?.onEngineError(lastToken, message)
        }
    }

    private fun controller(
        engine: FakeRecognitionEngine?,
        notices: MutableList<String> = mutableListOf(),
        providerCalls: IntArray = IntArray(1),
        events: MutableList<VoiceTextEvent> = mutableListOf(),
    ): VoiceDictationController = VoiceDictationController(
        engineProvider = {
            providerCalls[0]++
            engine
        },
        notify = { notices += it },
    ).apply {
        onVoiceText = { events += it }
    }

    // ---- engine lifecycle --------------------------------------------------------

    @Test
    fun `engine never created before explicit mic action`() {
        val engine = FakeRecognitionEngine()
        val calls = IntArray(1)
        val readAloud = controller(engine, providerCalls = calls)
        readAloud.cancel()
        readAloud.destroy()
        assertEquals(0, calls[0])
        assertEquals(DictationState.IDLE, readAloud.state)
    }

    @Test
    fun `start uses device locale and enters listening`() {
        val engine = FakeRecognitionEngine()
        val readAloud = controller(engine)
        assertTrue(readAloud.start("삼성 HBM", ChatViewModel.NEW_CONVERSATION_ID))
        assertEquals(DictationState.LISTENING, readAloud.state)
        assertEquals(1, engine.startCalls)
        assertTrue(engine.lastLanguage.isNotBlank())
    }

    @Test
    fun `unavailable on-device recognizer produces bounded failure`() {
        val engine = FakeRecognitionEngine(startResult = false)
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        assertFalse(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        assertEquals(DictationState.ERROR, readAloud.state)
        assertEquals(listOf(VoiceDictationController.UNAVAILABLE_MESSAGE), notices)
        assertEquals("기기에서 사용할 수 있는 오프라인 음성 인식이 없습니다.", VoiceDictationController.UNAVAILABLE_MESSAGE)
    }

    @Test
    fun `missing engine produces bounded failure without fallback`() {
        val notices = mutableListOf<String>()
        val readAloud = VoiceDictationController(
            engineProvider = { null },
            notify = { notices += it },
        )
        assertFalse(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        assertEquals(1, notices.size)
        assertTrue(notices.single().contains("오프라인"))
    }

    // ---- transcript merging -------------------------------------------------------

    @Test
    fun `partial transcript merges correctly with existing draft`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("삼성 HBM", ChatViewModel.NEW_CONVERSATION_ID))
        val session = readAloud.activeSessionId
        engine.attachedListener?.onPartial(session, "최신 상황 알려줘")
        assertEquals(1, events.size)
        assertEquals("삼성 HBM 최신 상황 알려줘", events.single().candidate)
        assertFalse(events.single().isFinal)
    }

    @Test
    fun `final transcript preserves exact recognized text`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        val session = readAloud.activeSessionId
        engine.attachedListener?.onFinal(session, "정확한 인식 문장입니다.")
        assertEquals(1, events.size)
        assertEquals("정확한 인식 문장입니다.", events.single().candidate)
        assertTrue(events.single().isFinal)
        assertEquals(DictationState.IDLE, readAloud.state)
    }

    @Test
    fun `merge spacing rules`() {
        assertEquals("삼성 HBM 최신 상황 알려줘", mergeVoiceSegment("삼성 HBM", "최신 상황 알려줘"))
        assertEquals("말한 내용", mergeVoiceSegment("", "말한 내용"))
        assertEquals("말한 내용", mergeVoiceSegment("   ", "말한 내용"))
        assertEquals("기존 내용", mergeVoiceSegment("기존 내용", "   "))
        assertEquals("기존 내용 추가", mergeVoiceSegment("기존 내용   ", "  추가"))
    }

    // ---- session ownership ----------------------------------------------------------

    @Test
    fun `same-session stale callbacks after final are ignored`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        val session = readAloud.activeSessionId
        engine.attachedListener?.onFinal(session, "끝.")
        engine.attachedListener?.onPartial(session, "늦은 부분 결과")
        assertEquals(1, events.size)
    }

    @Test
    fun `second session cancels first and stale session ignored`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("첫 번째", ChatViewModel.NEW_CONVERSATION_ID))
        val first = readAloud.activeSessionId
        assertTrue(readAloud.start("두 번째", ChatViewModel.NEW_CONVERSATION_ID))
        val second = readAloud.activeSessionId
        assertTrue(first != second)
        assertEquals(1, engine.cancelCalls)
        engine.attachedListener?.onPartial(first, "첫 번째 세션 잔여")
        assertTrue(events.isEmpty())
        engine.attachedListener?.onPartial(second, "두 번째 세션 내용")
        assertEquals(1, events.size)
        assertEquals("두 번째 두 번째 세션 내용", events.single().candidate)
    }

    @Test
    fun `ownership chaining and supersede on user edit`() {
        val engine = FakeRecognitionEngine()
        val readAloud = controller(engine)
        assertTrue(readAloud.start("base", ChatViewModel.NEW_CONVERSATION_ID))
        val session = readAloud.activeSessionId
        assertEquals("base", readAloud.expectedDraft(session))
        readAloud.noteApplied(session, "base said", isFinal = false)
        assertEquals("base said", readAloud.expectedDraft(session))
        // A failed CAS (user edited meanwhile) supersedes voice ownership:
        // engine session cancelled, no further writes accepted.
        readAloud.noteApplyFailed(session, isFinal = false)
        assertEquals(DictationState.IDLE, readAloud.state)
        assertNull(readAloud.expectedDraft(session))
        assertNull(readAloud.expectedDraft(999L))
    }

    @Test
    fun `stop and finalize enters processing then finalizes`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        val session = readAloud.activeSessionId
        readAloud.stopAndFinalize()
        assertEquals(DictationState.PROCESSING, readAloud.state)
        assertEquals(1, engine.stopCalls)
        engine.attachedListener?.onFinal(session, "마무리 문장")
        assertEquals(DictationState.IDLE, readAloud.state)
        assertEquals("마무리 문장", events.single().candidate)
    }

    @Test
    fun `cancel is idempotent`() {
        val engine = FakeRecognitionEngine()
        val readAloud = controller(engine)
        readAloud.cancel()
        assertTrue(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        readAloud.cancel()
        readAloud.cancel()
        assertEquals(1, engine.cancelCalls)
        assertEquals(DictationState.IDLE, readAloud.state)
    }

    @Test
    fun `destroy is idempotent and releases the engine`() {
        val engine = FakeRecognitionEngine()
        val calls = IntArray(1)
        val readAloud = controller(engine, providerCalls = calls)
        assertTrue(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        readAloud.destroy()
        readAloud.destroy()
        assertEquals(1, engine.destroyCalls)
        assertEquals(1, calls[0])
        assertEquals(DictationState.IDLE, readAloud.state)
    }

    // ---- permission -------------------------------------------------------------------

    @Test
    fun `permission denial leaves draft unchanged with bounded notice`() {
        val engine = FakeRecognitionEngine()
        val notices = mutableListOf<String>()
        val calls = IntArray(1)
        val readAloud = controller(engine, notices, calls)
        readAloud.notePermissionRequest()
        assertEquals(DictationState.REQUESTING_PERMISSION, readAloud.state)
        readAloud.notePermissionResult(false)
        assertEquals(DictationState.IDLE, readAloud.state)
        assertEquals(0, calls[0])
        assertEquals(1, notices.size)
        assertTrue(notices.single().contains("권한"))
    }

    @Test
    fun `permission grant returns to idle without starting`() {
        val engine = FakeRecognitionEngine()
        val calls = IntArray(1)
        val readAloud = controller(engine, providerCalls = calls)
        readAloud.notePermissionRequest()
        readAloud.notePermissionResult(true)
        assertEquals(DictationState.IDLE, readAloud.state)
        assertEquals(0, calls[0])
    }

    // ---- errors --------------------------------------------------------------------------

    @Test
    fun `engine error maps to bounded message without raw codes`() {
        val engine = FakeRecognitionEngine()
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        assertTrue(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        val session = readAloud.activeSessionId
        engine.attachedListener?.onEngineError(session, DictationErrorPolicy.messageFor(DictationErrorPolicy.ERROR_NO_MATCH))
        assertEquals(DictationState.ERROR, readAloud.state)
        assertEquals(1, notices.size)
        assertFalse(notices.single().contains("7"))
    }

    @Test
    fun `stale session errors are ignored`() {
        val engine = FakeRecognitionEngine()
        val notices = mutableListOf<String>()
        val readAloud = controller(engine, notices)
        assertTrue(readAloud.start("", ChatViewModel.NEW_CONVERSATION_ID))
        engine.attachedListener?.onEngineError(424242L, "stale")
        assertEquals(DictationState.LISTENING, readAloud.state)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `error policy covers common failures in korean`() {
        val codes = listOf(
            DictationErrorPolicy.ERROR_NETWORK,
            DictationErrorPolicy.ERROR_AUDIO,
            DictationErrorPolicy.ERROR_SERVER,
            DictationErrorPolicy.ERROR_CLIENT,
            DictationErrorPolicy.ERROR_SPEECH_TIMEOUT,
            DictationErrorPolicy.ERROR_NO_MATCH,
            DictationErrorPolicy.ERROR_RECOGNIZER_BUSY,
            DictationErrorPolicy.ERROR_INSUFFICIENT_PERMISSIONS,
            999,
        )
        codes.forEach { code ->
            val message = DictationErrorPolicy.messageFor(code)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains(code.toString()))
        }
        assertTrue(DictationErrorPolicy.messageFor(DictationErrorPolicy.ERROR_NO_MATCH).contains("인식"))
        assertTrue(DictationErrorPolicy.messageFor(DictationErrorPolicy.ERROR_RECOGNIZER_BUSY).contains("사용 중"))
        assertTrue(DictationErrorPolicy.messageFor(DictationErrorPolicy.ERROR_INSUFFICIENT_PERMISSIONS).contains("권한"))
    }

    // ---- composer application: ordinary text only --------------------------------------------

    @Test
    fun `voice draft applies as ordinary text with no generation`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-dictation-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(
                PersistentComposerDraftStore(
                    file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                    resolveManagedAttachment = { null },
                    registerPendingAttachment = { it.absolutePath },
                ),
            ).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            // Partial then final through the atomic CAS path.
            assertTrue(vm.applyVoiceDraftText(ChatViewModel.NEW_CONVERSATION_ID, "", "삼성 HBM"))
            assertTrue(vm.applyVoiceDraftText(ChatViewModel.NEW_CONVERSATION_ID, "삼성 HBM", "삼성 HBM 최신 상황 알려줘"))
            assertEquals("삼성 HBM 최신 상황 알려줘", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            // Ordinary text only: no attachments, no ownership, no generation.
            assertTrue(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.isEmpty())
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).activeSubmissionToken)
            assertNull(vm.state(ChatViewModel.NEW_CONVERSATION_ID).activeRequestId)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
            // A concurrent user edit fails the CAS instead of being overwritten.
            assertFalse(vm.applyVoiceDraftText(ChatViewModel.NEW_CONVERSATION_ID, "stale expect", "overwrite attempt"))
            assertEquals("삼성 HBM 최신 상황 알려줘", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
        } finally {
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `voice draft cannot clobber an in-flight submission`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-dictation-submit").toFile()
        try {
            val vm = ChatViewModel.forTesting(
                PersistentComposerDraftStore(
                    file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                    resolveManagedAttachment = { null },
                    registerPendingAttachment = { it.absolutePath },
                ),
            ).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            vm.updateDraftText(ChatViewModel.NEW_CONVERSATION_ID, "typed")
            testDispatcher.scheduler.advanceUntilIdle()
            val admission = vm.beginSubmissionResult(ChatViewModel.NEW_CONVERSATION_ID)
            assertTrue(admission is com.projectnuke.fusion.chat.SubmissionAdmissionResult.Accepted)
            assertFalse(vm.applyVoiceDraftText(ChatViewModel.NEW_CONVERSATION_ID, "typed", "typed voice"))
            assertEquals("typed", vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            // Settle the admitted submission so the process-global
            // ManualCompactionOperationRegistry lease does not leak into
            // other tests sharing this worker JVM.
            vm.settleSubmissionOwner(
                ChatViewModel.NEW_CONVERSATION_ID,
                (admission as com.projectnuke.fusion.chat.SubmissionAdmissionResult.Accepted).snapshot.token,
            )
        } finally {
            storeDir.deleteRecursively()
        }
    }

    // ---- final-transcript pending-apply ownership -----------------------------------

    @Test
    fun `final keeps draft ownership alive until acknowledgement`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", 11L))
        val session = readAloud.activeSessionId
        engine.emitFinal("마무리 문장")
        assertEquals(1, events.size)
        val final = events.single()
        assertTrue(final.isFinal)
        assertEquals("마무리 문장", final.candidate)
        assertEquals(11L, final.conversationId)
        // Ownership survives the async UI apply: the final can still be CASed.
        assertEquals("", readAloud.expectedDraft(session))
        assertEquals("", final.expectedDraft)
        assertEquals(11L, readAloud.sessionConversation(session))
        // The acknowledgement settles the session exactly once.
        readAloud.noteApplied(session, final.candidate, isFinal = true)
        assertNull(readAloud.expectedDraft(session))
        assertNull(readAloud.sessionConversation(session))
        readAloud.noteApplied(session, final.candidate, isFinal = true)
        assertNull(readAloud.expectedDraft(session))
        assertEquals(1, events.size)
    }

    @Test
    fun `stale callbacks after final acknowledgement are ignored`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", 11L))
        val session = readAloud.activeSessionId
        engine.emitFinal("끝.")
        readAloud.noteApplied(session, "끝.", isFinal = true)
        engine.attachedListener?.onPartial(session, "늦은 부분 결과")
        engine.attachedListener?.onFinal(session, "늦은 최종 결과")
        engine.attachedListener?.onEngineError(session, "늦은 오류")
        assertEquals(1, events.size)
        assertNull(readAloud.expectedDraft(session))
        assertEquals(DictationState.IDLE, readAloud.state)
    }

    @Test
    fun `final apply failure settles without resurrecting ownership`() {
        val engine = FakeRecognitionEngine()
        val notices = mutableListOf<String>()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, notices, events = events)
        assertTrue(readAloud.start("", 11L))
        val session = readAloud.activeSessionId
        engine.emitFinal("인식 결과")
        // The UI CAS failed (the user edited meanwhile): ownership is gone.
        readAloud.noteApplyFailed(session, isFinal = true)
        assertEquals(DictationState.IDLE, readAloud.state)
        assertNull(readAloud.expectedDraft(session))
        assertTrue(notices.single().contains("직접 입력"))
        // A late acknowledgement cannot resurrect the settled session.
        readAloud.noteApplied(session, "인식 결과", isFinal = true)
        assertNull(readAloud.expectedDraft(session))
        assertEquals(1, events.size)
        // A fresh session still works afterwards.
        assertTrue(readAloud.start("", 11L))
        val next = readAloud.activeSessionId
        assertTrue(next != session)
        engine.emitFinal("다음 인식")
        assertEquals("다음 인식", events.last().candidate)
        assertEquals("", readAloud.expectedDraft(next))
    }

    @Test
    fun `partial chaining feeds the pending final expected draft`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("base", 11L))
        val session = readAloud.activeSessionId
        readAloud.noteApplied(session, "base said", isFinal = false)
        engine.emitFinal("추가")
        assertEquals(1, events.size)
        // The candidate merges the new transcript onto the start base, while
        // the pending ownership still chains from the last applied draft.
        assertEquals("base 추가", events.single().candidate)
        assertEquals("base said", events.single().expectedDraft)
        assertEquals("base said", readAloud.expectedDraft(session))
    }

    // ---- late partial acknowledgement vs pending-final ownership -------------------

    @Test
    fun `late partial acknowledgement must not consume pending final ownership`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("D0", 11L))
        val session = readAloud.activeSessionId
        // Partial P for session T is emitted; its durable application is
        // still in flight and has NOT been acknowledged yet.
        engine.emitPartial("중간")
        val partial = events.single()
        assertFalse(partial.isFinal)
        assertEquals("D0 중간", partial.candidate)
        assertEquals("D0", partial.expectedDraft)
        // Final F for the SAME session arrives before P acknowledges.
        engine.emitFinal("최종")
        val final = events.last()
        assertTrue(final.isFinal)
        // Pending-final ownership for T exists: the final can still CAS.
        assertEquals("D0", readAloud.expectedDraft(session))
        // P's durable CAS commits; the late PARTIAL acknowledgement arrives.
        readAloud.noteApplied(session, partial.candidate, isFinal = false)
        // REQUIRED: final F remains pending and applicable, now chaining
        // from the committed partial. (BROKEN: ownership was consumed by P.)
        assertEquals(partial.candidate, readAloud.expectedDraft(session))
        assertEquals(11L, readAloud.sessionConversation(session))
        assertEquals(DictationState.IDLE, readAloud.state)
    }

    @Test
    fun `late partial commit chains the final which then settles exactly once`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("D0", 11L))
        val session = readAloud.activeSessionId
        engine.emitPartial("중간")
        val partial = events.first()
        engine.emitFinal("최종")
        val final = events.last()
        // The in-flight partial commits and acknowledges first (late).
        readAloud.noteApplied(session, partial.candidate, isFinal = false)
        assertEquals("D0 중간", readAloud.expectedDraft(session))
        // The final CAS can now advance from the committed partial and its
        // acknowledgement settles the pending session exactly once.
        readAloud.noteApplied(session, final.candidate, isFinal = true)
        assertNull(readAloud.expectedDraft(session))
        assertNull(readAloud.sessionConversation(session))
        readAloud.noteApplied(session, final.candidate, isFinal = true)
        assertNull(readAloud.expectedDraft(session))
        // A further late partial cannot resurrect anything either.
        readAloud.noteApplied(session, partial.candidate, isFinal = false)
        assertNull(readAloud.expectedDraft(session))
    }

    @Test
    fun `partial apply failure while final is pending does not settle the final`() {
        val engine = FakeRecognitionEngine()
        val notices = mutableListOf<String>()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, notices, events = events)
        assertTrue(readAloud.start("D0", 11L))
        val session = readAloud.activeSessionId
        engine.emitPartial("중간")
        engine.emitFinal("최종")
        // The partial CAS missed (a user edit landed) while the final is
        // still pending: the miss is recorded, the final keeps ownership.
        readAloud.noteApplyFailed(session, isFinal = false)
        assertEquals("D0", readAloud.expectedDraft(session))
        assertTrue(notices.isEmpty())
        // The final's own CAS miss is what settles and reports.
        readAloud.noteApplyFailed(session, isFinal = true)
        assertNull(readAloud.expectedDraft(session))
        assertTrue(notices.single().contains("직접 입력"))
        // A late partial acknowledgement after the settle is a no-op.
        readAloud.noteApplied(session, "D0 중간", isFinal = false)
        assertNull(readAloud.expectedDraft(session))
    }

    @Test
    fun `superseded session cannot resurrect through late acknowledgements`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("D0", 11L))
        val stale = readAloud.activeSessionId
        engine.emitPartial("중간")
        engine.emitFinal("최종")
        assertEquals("D0", readAloud.expectedDraft(stale))
        // A newer session supersedes the pending final outright.
        assertTrue(readAloud.start("N0", 22L))
        val current = readAloud.activeSessionId
        assertTrue(current != stale)
        assertNull(readAloud.expectedDraft(stale))
        // Late partial/final acknowledgements for the stale session are all
        // fenced; the current session is untouched.
        readAloud.noteApplied(stale, "D0 중간", isFinal = false)
        readAloud.noteApplied(stale, "D0 최종", isFinal = true)
        readAloud.noteApplyFailed(stale, isFinal = false)
        readAloud.noteApplyFailed(stale, isFinal = true)
        assertEquals("N0", readAloud.expectedDraft(current))
        assertEquals(22L, readAloud.sessionConversation(current))
        assertNull(readAloud.expectedDraft(stale))
    }

    @Test
    fun `final still wins the composer after a late partial commit and auto-submits once`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-voice-late-partial").toFile()
        try {
            val vm = ChatViewModel.forTesting(
                PersistentComposerDraftStore(
                    file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                    resolveManagedAttachment = { null },
                    registerPendingAttachment = { it.absolutePath },
                ),
            ).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            // The session base must match the real composer (as ChatScreen
            // passes the live draft into start).
            assertTrue(vm.replaceDraftTextDurably(11L, "D0"))
            val engine = FakeRecognitionEngine()
            val events = mutableListOf<VoiceTextEvent>()
            val readAloud = controller(engine, events = events)
            assertTrue(readAloud.start("D0", 11L))
            val session = readAloud.activeSessionId
            // The recognizer emits partial P then final F before either
            // durable application is acknowledged — the production
            // interleaving. Both enter the shared apply path exactly as
            // ChatScreen does; the durable queue serializes P before F.
            engine.emitPartial("중간")
            engine.emitFinal("최종")
            val submitTargets = mutableListOf<Long>()
            val inFlight = events.map { event ->
                launch {
                    applyVoiceTextEvent(
                        event = event,
                        voiceModeActive = true,
                        currentConversationId = 11L,
                        dictation = readAloud,
                        chatViewModel = vm,
                        onAutoSubmit = { target -> submitTargets += target },
                    )
                }
            }
            testDispatcher.scheduler.advanceUntilIdle()
            inFlight.forEach { it.join() }
            // The FINAL transcript is authoritative in the composer.
            assertEquals("D0 최종", vm.draft(11L).rawInput)
            // Auto-submit fired exactly once, for the applied final.
            assertEquals(listOf(11L), submitTargets)
            // The pending-final ownership settled with the final.
            assertNull(readAloud.expectedDraft(session))
        } finally {
            storeDir.deleteRecursively()
        }
    }

    @Test
    fun `user edit between partial and final is never overwritten`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-voice-useredit").toFile()
        try {
            val vm = ChatViewModel.forTesting(
                PersistentComposerDraftStore(
                    file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                    resolveManagedAttachment = { null },
                    registerPendingAttachment = { it.absolutePath },
                ),
            ).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            assertTrue(vm.replaceDraftTextDurably(11L, "D0"))
            val engine = FakeRecognitionEngine()
            val events = mutableListOf<VoiceTextEvent>()
            val notices = mutableListOf<String>()
            val readAloud = controller(engine, notices, events = events)
            assertTrue(readAloud.start("D0", 11L))
            val session = readAloud.activeSessionId
            engine.emitPartial("중간")
            engine.emitFinal("최종")
            val inFlight = events.map { event ->
                launch {
                    applyVoiceTextEvent(
                        event = event,
                        voiceModeActive = true,
                        currentConversationId = 11L,
                        dictation = readAloud,
                        chatViewModel = vm,
                        onAutoSubmit = { },
                    )
                }
            }
            // A durable user edit lands after both voice commands are
            // queued but before any acknowledgement can run.
            assertTrue(vm.replaceDraftTextDurably(11L, "사용자가 입력한 내용"))
            testDispatcher.scheduler.advanceUntilIdle()
            inFlight.forEach { it.join() }
            // The user's text wins; the final fail-closes, no auto-submit.
            assertEquals("사용자가 입력한 내용", vm.draft(11L).rawInput)
            assertNull(readAloud.expectedDraft(session))
            assertTrue(notices.single().contains("직접 입력"))
        } finally {
            storeDir.deleteRecursively()
        }
    }

    // ---- single authoritative session token ------------------------------------------

    @Test
    fun `engine receives the controller-owned session token`() {
        val engine = FakeRecognitionEngine()
        val readAloud = controller(engine)
        assertTrue(readAloud.start("", 11L))
        assertEquals(readAloud.activeSessionId, engine.lastToken)
        assertEquals(listOf(readAloud.activeSessionId), engine.startTokens)
    }

    @Test
    fun `cancel then start does not drift session identity`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", 11L))
        val first = readAloud.activeSessionId
        readAloud.cancel()
        assertTrue(readAloud.start("", 11L))
        val second = readAloud.activeSessionId
        assertEquals(listOf(first, second), engine.startTokens)
        // The pre-cancel token is stale; the new token is live.
        engine.attachedListener?.onPartial(first, "이전 세션 잔여")
        assertTrue(events.isEmpty())
        engine.emitFinal("새 세션 결과")
        assertEquals(1, events.size)
        assertEquals(second, events.single().sessionId)
        assertEquals("새 세션 결과", events.single().candidate)
    }

    @Test
    fun `replacing a live session does not drift`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("첫 번째", 11L))
        val first = readAloud.activeSessionId
        assertTrue(readAloud.start("두 번째", 11L))
        val second = readAloud.activeSessionId
        assertEquals(listOf(first, second), engine.startTokens)
        assertEquals(second, engine.lastToken)
        engine.attachedListener?.onPartial(first, "첫 번째 세션 잔여")
        assertTrue(events.isEmpty())
        engine.emitPartial("두 번째 세션 내용")
        assertEquals(1, events.size)
        assertEquals("두 번째 두 번째 세션 내용", events.single().candidate)
    }

    // ---- conversation binding -----------------------------------------------------------

    @Test
    fun `final binds its originating conversation`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", 11L))
        val session = readAloud.activeSessionId
        engine.emitPartial("부분")
        assertEquals(11L, events.single().conversationId)
        engine.emitFinal("최종")
        assertEquals(11L, events.last().conversationId)
        assertEquals(11L, readAloud.sessionConversation(session))
        readAloud.noteApplied(session, events.last().candidate, isFinal = true)
        assertNull(readAloud.sessionConversation(session))
    }

    @Test
    fun `conversation switch supersedes the live session`() {
        val engine = FakeRecognitionEngine()
        val events = mutableListOf<VoiceTextEvent>()
        val readAloud = controller(engine, events = events)
        assertTrue(readAloud.start("", 11L))
        val session = readAloud.activeSessionId
        // Same conversation: the session survives.
        readAloud.cancelIfConversationChanged(11L)
        assertEquals(DictationState.LISTENING, readAloud.state)
        engine.emitPartial("계속")
        assertEquals(1, events.size)
        // Navigating away cancels the live session; late callbacks are stale.
        readAloud.cancelIfConversationChanged(22L)
        assertEquals(DictationState.IDLE, readAloud.state)
        assertNull(readAloud.expectedDraft(session))
        engine.attachedListener?.onFinal(session, "늦은 최종")
        assertEquals(1, events.size)
    }
}
