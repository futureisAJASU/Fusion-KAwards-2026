package com.projectnuke.fusion.llm

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.GenerationSessionRegistry
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageValidator
import com.projectnuke.fusion.modelzoo.LiteRtLmValidationResult
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-device qualification against the production LiteRT-LM runtime. */
@RunWith(AndroidJUnit4::class)
class LiteRtLmRealDeviceQualificationTest {
    private val modelName = "gemma-4-E2B-it.litertlm"

    @Test
    fun reportsQualificationManagedModelDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = ManagedModelPathPolicy.getModelDirectory(context)
        Log.i(TAG, "QUALIFICATION_PACKAGE=${context.packageName}")
        Log.i(TAG, "QUALIFICATION_MODEL_DIRECTORY=${directory.absolutePath}")
        Log.i(TAG, "QUALIFICATION_EXTERNAL_FILES=${directory.parentFile == context.getExternalFilesDir(null)}")
        assertEquals("com.projectnuke.fusion.qualification", context.packageName)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun validatesAndRunsCpuModelThenReusesAndReloads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.projectnuke.fusion.qualification", context.packageName)
        val model = ManagedModelPathPolicy.resolveRunnableModel(
            context,
            File(ManagedModelPathPolicy.getModelDirectory(context), modelName).absolutePath,
        )
        assertNotNull("managed .litertlm model must be staged", model)
        model!!
        val preflightStarted = System.nanoTime()
        when (val validation = LiteRtLmPackageValidator.validate(model)) {
            is LiteRtLmValidationResult.Valid -> {
                Log.i(
                    TAG,
                    "PACKAGE_VALIDATION=PASS; bytes=${model.length()}; capabilities=${validation.capabilities}; " +
                        "elapsedMs=${elapsedMs(preflightStarted)}",
                )
            }
            is LiteRtLmValidationResult.Invalid -> error("PACKAGE_VALIDATION=FAIL; reason=${validation.reason}")
        }

        val profile = RequestedEngineProfile(
            modelPath = model.absolutePath,
            accelerator = AcceleratorMode.CPU,
            mtpRequested = false,
            kvCacheCapacityTokens = 1024,
            enableVisionBackend = false,
            cpuThreadCount = 2,
        )
        val options = ConversationOptions(maxOutputToken = 16, temperature = 0.2f, topK = 16, topP = 0.9f, seed = 29)
        val engine = LiteRtLlmEngine(context)
        try {
            val before = memorySnapshot()
            val firstStarted = System.nanoTime()
            val firstTokens = StringBuilder()
            val first = engine.generateStreaming(
                messages = listOf(
                    ChatMessage("user", "안녕하세요. 한 문장으로 자기소개해 주세요."),
                ),
                profile = profile,
                options = options,
                onToken = { token -> if (firstTokens.length < 512) firstTokens.append(token) },
            )
            logOutcome("FIRST", first, firstTokens.toString(), elapsedMs(firstStarted), before)
            val firstSuccess = first as? GenerationOutcome.Success
                ?: error("FIRST_NATIVE_GENERATION_FAILED=${(first as? GenerationOutcome.Failure)?.kind}")
            assertEquals("CPU", firstSuccess.actualBackend)
            assertEquals(RuntimeBackend.CPU, firstSuccess.snapshot?.selectedTextBackend)
            assertTrue("stream callback should collect output", firstTokens.isNotEmpty())

            val warmStarted = System.nanoTime()
            val warm = engine.generateStreaming(
                messages = listOf(ChatMessage("user", "한 문장으로 오늘 날씨를 묻는 인사를 해 주세요.")),
                profile = profile,
                options = options,
                onToken = {},
            )
            logOutcome("WARM_REUSE", warm, "", elapsedMs(warmStarted), memorySnapshot())
            val warmSuccess = warm as? GenerationOutcome.Success
                ?: error("WARM_GENERATION_FAILED=${(warm as? GenerationOutcome.Failure)?.kind}")
            assertEquals("CPU", warmSuccess.actualBackend)

            engine.unload()
            assertEquals("unload must clear the runtime selection", null, engine.lastRuntimeSelection)
            val reloadStarted = System.nanoTime()
            val reloaded = engine.generateStreaming(
                messages = listOf(ChatMessage("user", "숫자 37을 포함해 짧게 답해 주세요.")),
                profile = profile,
                options = options,
                onToken = {},
            )
            logOutcome("UNLOAD_RELOAD", reloaded, "", elapsedMs(reloadStarted), memorySnapshot())
            val reloadSuccess = reloaded as? GenerationOutcome.Success
                ?: error("RELOAD_GENERATION_FAILED=${(reloaded as? GenerationOutcome.Failure)?.kind}")
            assertEquals("CPU", reloadSuccess.actualBackend)
        } finally {
            engine.unload()
        }
    }

    @Test
    fun realNativeUserStopThenRecoveryAndUnloadReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = requireQualificationModel(context)
        val profile = qualificationCpuProfile(model)
        val options = ConversationOptions(maxOutputToken = 32, temperature = 0.2f, topK = 16, topP = 0.9f, seed = 31)
        val engine = LiteRtLlmEngine(context)
        val controller = NativeConversationCancellationController()
        val key = GenerationRequestKey(3_301, "l3-native-stop")
        val firstToken = CompletableDeferred<Unit>()
        val streamed = StringBuilder()
        val tokenCount = AtomicInteger()
        val memoryBefore = memorySnapshot()
        val started = System.nanoTime()
        try {
            val generation = async {
                withContext(GenerationRequestContext(controller, key)) {
                    engine.generateStreaming(
                        messages = listOf(
                            ChatMessage(
                                "user",
                                "Write a long numbered list of 80 helpful study habits. Explain each one in a full sentence.",
                            ),
                        ),
                        profile = profile,
                        options = options,
                        onToken = { token ->
                            synchronized(streamed) { streamed.append(token) }
                            tokenCount.incrementAndGet()
                            if (token.any { !it.isWhitespace() }) firstToken.complete(Unit)
                        },
                    )
                }
            }

            withTimeout(60_000) { firstToken.await() }
            val stopReserved = controller.reserveIntent(key, GenerationTerminationIntent.USER_STOP)
            val nativeCancelStarted = controller.cancelProcess(key)
            Log.i(
                TAG,
                "ACTIVE_STOP_REQUESTED; key=$key; reserved=$stopReserved; nativeCancelStarted=$nativeCancelStarted; " +
                    "tokensAtStop=${tokenCount.get()}; elapsedMs=${elapsedMs(started)}; memoryBefore=$memoryBefore",
            )
            assertTrue("USER_STOP intent must belong to this request", stopReserved)
            assertTrue("native Conversation must still be registered for this request", nativeCancelStarted)

            val outcome = withTimeout(60_000) { generation.await() }
            val cancelled = outcome as? GenerationOutcome.Cancelled
                ?: error("ACTIVE_STOP_TERMINAL=${(outcome as? GenerationOutcome.Failure)?.kind ?: outcome::class.simpleName}")
            assertEquals(GenerationTerminationIntent.USER_STOP, cancelled.terminationIntent)
            val partialSnapshot = controller.partialSnapshot(key)
            val outputAtTerminal = tokenCount.get()
            synchronized(streamed) {
                assertTrue("visible partial output should be preserved for user stop", streamed.isNotBlank())
            }
            assertTrue("cancelled outcome should carry the visible partial", cancelled.partialText.isNotBlank())
            assertEquals("native Conversation registration must be released", 0, controller.registeredRequestCount())
            assertEquals("USER_STOP intent remains owned until request cleanup", GenerationTerminationIntent.USER_STOP, controller.intentFor(key))
            assertNotNull("request partial is tracked until owner cleanup", partialSnapshot)
            assertEquals("terminal completion must not emit later callbacks", outputAtTerminal, tokenCount.get())
            Log.i(
                TAG,
                "ACTIVE_STOP_TERMINAL=CANCELLED; intent=${cancelled.terminationIntent}; partialChars=${cancelled.partialText.length}; " +
                    "tokens=$outputAtTerminal; elapsedMs=${elapsedMs(started)}; memoryAfter=${memorySnapshot()}",
            )
            controller.clearRequest(key)
            assertEquals("request intent cleared", null, controller.intentFor(key))
            assertEquals("request partial cleared", null, controller.partialSnapshot(key))

            val recoveryKey = GenerationRequestKey(3_301, "l3-after-stop")
            val recoveryStarted = System.nanoTime()
            val recovery = withContext(GenerationRequestContext(controller, recoveryKey)) {
                engine.generateStreaming(
                    messages = listOf(ChatMessage("user", "Reply with one short friendly greeting.")),
                    profile = profile,
                    options = options.copy(maxOutputToken = 16),
                    onToken = {},
                )
            }
            val recovered = recovery as? GenerationOutcome.Success
                ?: error("POST_CANCEL_RECOVERY=${(recovery as? GenerationOutcome.Failure)?.kind ?: recovery::class.simpleName}")
            assertEquals("CPU", recovered.snapshot?.selectedTextBackend?.name)
            assertTrue("recovery response must be usable", recovered.text.isNotBlank())
            assertEquals("new request has no inherited stop intent", null, controller.intentFor(recoveryKey))
            assertFalse(
                "new request must not inherit previous cancelled output",
                cancelled.partialText.isNotBlank() && recovered.text == cancelled.partialText,
            )
            Log.i(
                TAG,
                "POST_CANCEL_RECOVERY=SUCCESS; backend=${recovered.snapshot?.selectedTextBackend}; " +
                    "text=${recovered.text.take(120)}; elapsedMs=${elapsedMs(recoveryStarted)}; memory=${memorySnapshot()}",
            )
            controller.clearRequest(recoveryKey)

            engine.unload()
            assertEquals("unload clears engine selection", null, engine.lastRuntimeSelection)
            val reloadStarted = System.nanoTime()
            val reload = engine.generateStreaming(
                messages = listOf(ChatMessage("user", "Say the number 37 in one short sentence.")),
                profile = profile,
                options = options.copy(maxOutputToken = 16),
                onToken = {},
            )
            val reloaded = reload as? GenerationOutcome.Success
                ?: error("POST_CANCEL_RELOAD=${(reload as? GenerationOutcome.Failure)?.kind ?: reload::class.simpleName}")
            assertEquals("CPU", reloaded.snapshot?.selectedTextBackend?.name)
            assertTrue(reloaded.text.isNotBlank())
            Log.i(
                TAG,
                "POST_CANCEL_UNLOAD_RELOAD=SUCCESS; backend=${reloaded.snapshot?.selectedTextBackend}; " +
                    "elapsedMs=${elapsedMs(reloadStarted)}; memory=${memorySnapshot()}",
            )
        } finally {
            controller.clearRequest(key)
            engine.unload()
        }
        Unit
    }

    @Test
    fun nativeConversationRegistrationHonorsStopReservedBeforeRegistration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = requireQualificationModel(context)
        val profile = qualificationCpuProfile(model)
        val conversationId = 3_302L
        val requestId = "l3-stop-before-register"
        val key = GenerationRequestKey(conversationId, requestId)
        val registry = GenerationSessionRegistry(userStopSettlementTimeoutMs = 20_000L)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val engineReady = CountDownLatch(1)
        val releaseEngine = CountDownLatch(1)
        val reservationAttempted = CompletableDeferred<Unit>()
        val registrationChecks = AtomicInteger()
        val cancellationAdmissions = AtomicInteger()
        val generationStarted = System.nanoTime()
        val outcomeDeferred = CompletableDeferred<GenerationOutcome>()
        registry.onBeforeIntentReservation = { reservationAttempted.complete(Unit) }
        registry.cancellationController.onBeforeRegistrationStopCheck = {
            registrationChecks.incrementAndGet()
            error("Conversation registration must not proceed after the exact request was stopped")
        }
        registry.cancellationController.onBeforeCancellationLock = { cancellationAdmissions.incrementAndGet() }
        val engine = LiteRtLlmEngine(context)
        try {
            val snapshot = GenerationRequestSnapshot(
                requestId = requestId,
                conversationId = conversationId,
                generationModeKey = "LOCAL_MODEL",
                selectedModelId = modelName,
                selectedModelPath = model.absolutePath,
                settings = GenerationSettings(
                    maxTokens = 32,
                    kvCacheCapacityTokens = 1024,
                    accelerator = AcceleratorMode.CPU,
                    speculativeDecodingEnabled = false,
                ),
                reasoningEnabled = false,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(),
                multimodalImagePaths = emptyList(),
                promptText = "Write six short sentences about study habits.",
                rawUserText = "Write six short sentences about study habits.",
                createdAt = System.currentTimeMillis(),
            )
            registry.start(scope, snapshot) {
                val outcome = try {
                    engine.generateStreaming(
                        messages = listOf(ChatMessage("user", snapshot.promptText)),
                        profile = profile,
                        options = ConversationOptions(maxOutputToken = 32, temperature = 0.2f, topK = 16, topP = 0.9f, seed = 37),
                        onToken = {},
                        onStage = { event ->
                            if (event.stage == LocalRuntimeStage.ENGINE_READY) {
                                engineReady.countDown()
                                check(releaseEngine.await(30, TimeUnit.SECONDS)) {
                                    "test stop did not release the Engine-ready barrier"
                                }
                            }
                        },
                    )
                } catch (cancelled: CancellationException) {
                    if (registry.cancellationController.hasUserStop(key)) {
                        GenerationOutcome.Cancelled(
                            partialText = registry.cancellationController.partialSnapshot(key).orEmpty(),
                            terminationIntent = GenerationTerminationIntent.USER_STOP,
                        )
                    } else {
                        throw cancelled
                    }
                } catch (failure: Throwable) {
                    outcomeDeferred.completeExceptionally(failure)
                    throw failure
                }
                outcomeDeferred.complete(outcome)
            }
            assertTrue(
                "native engine must reach the pre-Conversation barrier",
                withContext(Dispatchers.IO) { engineReady.await(90, TimeUnit.SECONDS) },
            )
            val stop = async(Dispatchers.Default) { registry.stopAndJoin(conversationId, requestId) }
            withTimeout(5_000) { reservationAttempted.await() }
            withTimeout(5_000) {
                while (registry.cancellationController.intentFor(key) != GenerationTerminationIntent.USER_STOP) yield()
            }
            releaseEngine.countDown()

            val stopSettled = withTimeout(90_000) { stop.await() }
            val result = withTimeout(10_000) { outcomeDeferred.await() }
            val cancelled = result as? GenerationOutcome.Cancelled
                ?: error("STOP_BEFORE_REGISTER_TERMINAL=${(result as? GenerationOutcome.Failure)?.kind ?: result::class.simpleName}")
            assertTrue("production registry stop should settle the exact request", stopSettled)
            assertEquals(GenerationTerminationIntent.USER_STOP, cancelled.terminationIntent)
            assertEquals("stop before native Conversation must prevent registration", 0, registrationChecks.get())
            assertEquals(
                "no native cancellation may target a Conversation that never started",
                0,
                cancellationAdmissions.get(),
            )
            assertEquals("registry lifecycle cleanup", 0, registry.registeredConversationCount())
            assertEquals("request intent cleanup", 0, registry.recordedIntentCount())
            assertTrue("partial output remains bounded", cancelled.partialText.length <= 512)
            Log.i(
                TAG,
                "STOP_BEFORE_REGISTER=CANCELLED; key=$key; conversationRegistrationChecks=${registrationChecks.get()}; " +
                    "nativeCancellationAdmissions=${cancellationAdmissions.get()}; partialChars=${cancelled.partialText.length}; " +
                    "elapsedMs=${elapsedMs(generationStarted)}; memory=${memorySnapshot()}",
            )
        } finally {
            releaseEngine.countDown()
            scope.cancel()
            engine.unload()
        }
        Unit
    }

    @Test
    fun structuralDeleteBeforeNativeConversationDoesNotCreateOne() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = requireQualificationModel(context)
        val profile = qualificationCpuProfile(model)
        val conversationId = 3_303L
        val requestId = "l3-delete-before-register"
        val deletionOwner = "l3-qualification-delete"
        val key = GenerationRequestKey(conversationId, requestId)
        val registry = GenerationSessionRegistry()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val engineReady = CountDownLatch(1)
        val releaseEngine = CountDownLatch(1)
        val backendAtReady = CompletableDeferred<String?>()
        val registrationChecks = AtomicInteger()
        val cancellationAdmissions = AtomicInteger()
        val terminal = CompletableDeferred<Pair<GenerationOutcome?, GenerationTerminationIntent?>>()
        val engine = LiteRtLlmEngine(context)
        try {
            registry.cancellationController.onBeforeRegistrationStopCheck = {
                registrationChecks.incrementAndGet()
                error("Conversation registration must not proceed after structural deletion")
            }
            registry.cancellationController.onBeforeCancellationLock = { cancellationAdmissions.incrementAndGet() }
            val snapshot = GenerationRequestSnapshot(
                requestId = requestId,
                conversationId = conversationId,
                generationModeKey = "LOCAL_MODEL",
                selectedModelId = modelName,
                selectedModelPath = model.absolutePath,
                settings = GenerationSettings(
                    maxTokens = 16,
                    kvCacheCapacityTokens = 1024,
                    accelerator = AcceleratorMode.CPU,
                    speculativeDecodingEnabled = false,
                ),
                reasoningEnabled = false,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(),
                multimodalImagePaths = emptyList(),
                promptText = "Write one short sentence about reading.",
                rawUserText = "Write one short sentence about reading.",
                createdAt = System.currentTimeMillis(),
            )
            registry.start(scope, snapshot) {
                try {
                    val outcome = engine.generateStreaming(
                        messages = listOf(ChatMessage("user", snapshot.promptText)),
                        profile = profile,
                        options = ConversationOptions(maxOutputToken = 16, temperature = 0.2f, topK = 16, topP = 0.9f, seed = 41),
                        onToken = {},
                        onStage = { event ->
                            if (event.stage == LocalRuntimeStage.ENGINE_READY) {
                                backendAtReady.complete(event.actualBackend)
                                engineReady.countDown()
                                check(releaseEngine.await(30, TimeUnit.SECONDS)) {
                                    "test deletion did not release the Engine-ready barrier"
                                }
                            }
                        },
                    )
                    terminal.complete(outcome to null)
                } catch (cancelled: CancellationException) {
                    val intent = registry.cancellationController.intentFor(key)
                    terminal.complete(null to intent)
                    throw cancelled
                } catch (failure: Throwable) {
                    terminal.completeExceptionally(failure)
                    throw failure
                }
            }
            assertTrue(
                "native engine must reach the pre-Conversation barrier",
                withContext(Dispatchers.IO) { engineReady.await(90, TimeUnit.SECONDS) },
            )
            assertEquals("CPU", withTimeout(5_000) { backendAtReady.await() })
            assertTrue(registry.claimDeletionOwnership(conversationId, deletionOwner))
            val deletion = async(Dispatchers.Default) {
                registry.settleForDeletion(conversationId, "qualification-delete")
            }
            withTimeout(5_000) {
                while (registry.cancellationController.intentFor(key) != GenerationTerminationIntent.CONVERSATION_DELETE) yield()
            }
            releaseEngine.countDown()

            assertTrue("structural deletion settles its exact request", withTimeout(90_000) { deletion.await() })
            val (outcome, thrownCancellationIntent) = withTimeout(10_000) { terminal.await() }
            val terminalIntent = if (outcome == null) {
                thrownCancellationIntent
            } else {
                (outcome as? GenerationOutcome.Cancelled
                    ?: error("STRUCTURAL_DELETE_TERMINAL=${outcome::class.simpleName}")).terminationIntent
            }
            assertEquals(GenerationTerminationIntent.CONVERSATION_DELETE, terminalIntent)
            assertEquals("no native Conversation registration", 0, registrationChecks.get())
            assertEquals("no cancellation call on an unstarted Conversation", 0, cancellationAdmissions.get())
            assertEquals("request token cleanup", null, registry.latestToken(conversationId))
            assertEquals("session cleanup", null, registry.activeSession(conversationId))
            assertEquals("registry lifecycle cleanup", 0, registry.registeredConversationCount())
            assertEquals("request intent cleanup", 0, registry.recordedIntentCount())
            Log.i(
                TAG,
                "STRUCTURAL_DELETE_BEFORE_REGISTER=SETTLED; terminal=${outcome?.let { it::class.simpleName } ?: "THROWN_CANCELLATION"}; intent=$terminalIntent; key=$key; registrations=${registrationChecks.get()}; " +
                    "nativeCancellationAdmissions=${cancellationAdmissions.get()}; memory=${memorySnapshot()}",
            )
        } finally {
            releaseEngine.countDown()
            scope.cancel()
            engine.unload()
            registry.releaseDeletionOwnership(conversationId, deletionOwner)
        }
        Unit
    }

    private fun logOutcome(
        stage: String,
        outcome: GenerationOutcome,
        streamed: String,
        elapsed: Long,
        before: MemorySnapshot,
    ) {
        when (outcome) {
            is GenerationOutcome.Success -> Log.i(
                TAG,
                "$stage=SUCCESS; backend=${outcome.actualBackend}; snapshot=${outcome.snapshot}; " +
                    "truncated=${outcome.truncated}; text=${outcome.text.take(160)}; streamed=${streamed.take(160)}; " +
                    "elapsedMs=$elapsed; before=$before; after=${memorySnapshot()}; stats=${outcome.stats}",
            )
            is GenerationOutcome.Failure -> Log.e(
                TAG,
                "$stage=FAILURE; kind=${outcome.kind}; message=${outcome.message}; " +
                    "attempt=${outcome.attemptSnapshot}; failure=${outcome.failureSnapshot}; " +
                    "elapsedMs=$elapsed; before=$before; after=${memorySnapshot()}",
            )
            is GenerationOutcome.Cancelled -> Log.e(TAG, "$stage=CANCELLED; intent=${outcome.terminationIntent}")
            GenerationOutcome.Empty -> Log.e(TAG, "$stage=EMPTY; elapsedMs=$elapsed")
        }
    }

    private fun memorySnapshot() = MemorySnapshot(
        javaHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
        nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
        pssKb = Debug.getPss(),
    )

    private fun requireQualificationModel(context: Context): File {
        assertEquals("com.projectnuke.fusion.qualification", context.packageName)
        val model = ManagedModelPathPolicy.resolveRunnableModel(
            context,
            File(ManagedModelPathPolicy.getModelDirectory(context), modelName).absolutePath,
        )
        assertNotNull("managed .litertlm model must be staged", model)
        return model!!
    }

    private fun qualificationCpuProfile(model: File) = RequestedEngineProfile(
        modelPath = model.absolutePath,
        accelerator = AcceleratorMode.CPU,
        mtpRequested = false,
        kvCacheCapacityTokens = 1024,
        enableVisionBackend = false,
        cpuThreadCount = 2,
    )

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000

    private data class MemorySnapshot(val javaHeapBytes: Long, val nativeHeapBytes: Long, val pssKb: Long)

    private companion object {
        const val TAG = "FusionL2Qualification"
    }
}
