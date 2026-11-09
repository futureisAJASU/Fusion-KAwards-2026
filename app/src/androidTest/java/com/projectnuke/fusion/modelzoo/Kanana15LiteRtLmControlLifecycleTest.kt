package com.projectnuke.fusion.modelzoo

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.FusionRuntimeManager
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Device-only K0 CONTROL lifecycle for the third-party Kanana 1.5 LiteRT-LM
 * package. This is deliberately not evidence for Fusion's Kanana-2 target.
 *
 * Supply a readable device URI with instrumentation argument
 * `fusion_kanana15_control_uri`. An absent artifact reports
 * `SKIPPED_NO_ARTIFACT_URI` and is skipped; that is never a lifecycle PASS.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class Kanana15LiteRtLmControlLifecycleTest {
    @Test
    fun suppliedControlArtifact_runsImportRuntimeCancellationAndReloadLifecycle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val uriText = InstrumentationRegistry.getArguments().getString(CONTROL_URI_ARGUMENT)
        if (uriText.isNullOrBlank()) {
            println("K0_CONTROL_LIFECYCLE=SKIPPED_NO_ARTIFACT_URI")
        }
        assumeTrue("K0 control artifact URI was not supplied", !uriText.isNullOrBlank())

        val uri = Uri.parse(requireNotNull(uriText))
        val coordinator = ModelImportCoordinatorRegistry.forContext(context)
        var persistedSpec: FusionModelSpec? = null
        var importedFile: File? = null
        try {
            val import = coordinator.import(
                request = ModelImportRequest(
                    sourceIdentity = uri.toString(),
                    displayName = CONTROL_FILE_NAME,
                ),
                commitMetadata = { copied ->
                    FusionModelCatalog.importedSpec(
                        displayName = CONTROL_FILE_NAME,
                        path = copied.absolutePath,
                        family = ModelFamily.CUSTOM,
                    ).also { spec ->
                        persistedSpec = spec
                        FusionModelCatalog.saveImported(context, spec)
                    }
                    true
                },
            )
            assertTrue("CONTROL import result=$import", import is ModelImportResult.Success)
            importedFile = (import as ModelImportResult.Success).file
            assertEquals(CONTROL_BYTES, importedFile.length())
            assertEquals(CONTROL_SHA256, sha256(importedFile))
            val spec = requireNotNull(persistedSpec)
            assertEquals(spec.id, FusionModelCatalog.loadImported(context).single { it.id == spec.id }.id)

            val profile = RequestedEngineProfile(
                modelPath = importedFile.absolutePath,
                accelerator = AcceleratorMode.CPU,
                mtpRequested = false,
                kvCacheCapacityTokens = 4096,
                enableVisionBackend = false,
            )
            val options = ConversationOptions(maxOutputToken = 64, temperature = 0.0f, topK = 1, topP = 1.0f)
            val engine = FusionRuntimeManager.sharedEngine(context)

            val firstTurn = generate(
                engine,
                profile,
                options,
                listOf(ChatMessage("user", "Remember the number 37.")),
            )
            assertUsable("single-turn", firstTurn)
            val first = (firstTurn as GenerationOutcome.Success).text
            val multi = generate(
                engine,
                profile,
                options,
                listOf(
                    ChatMessage("user", "Remember the number 37."),
                    ChatMessage("assistant", first),
                    ChatMessage("user", "What number did I ask you to remember?"),
                ),
            )
            assertUsable("multi-turn", multi)

            val firstToken = CompletableDeferred<Unit>()
            val running = launch {
                FusionRuntimeLock.withChatGeneration {
                    engine.generateStreaming(
                        messages = listOf(ChatMessage("user", "Write a long numbered list.")),
                        profile = profile,
                        options = options.copy(maxOutputToken = 256),
                    ) { firstToken.complete(Unit) }
                }
            }
            withTimeout(120_000) { firstToken.await() }
            running.cancelAndJoin()
            assertTrue("cancellation must terminate the generation job", running.isCancelled)

            val afterCancel = generate(engine, profile, options, listOf(ChatMessage("user", "Say only: reusable")))
            assertUsable("post-cancellation", afterCancel)

            FusionRuntimeLock.withChatGeneration {
                FusionRuntimeManager.unloadSharedEngineAfterExclusive("k0-control-lifecycle")
            }
            val afterReload = generate(engine, profile, options, listOf(ChatMessage("user", "Say only: reloaded")))
            assertUsable("post-reload", afterReload)
            println("K0_CONTROL_LIFECYCLE=EXECUTED_PASS")
        } finally {
            FusionRuntimeLock.withChatGeneration {
                FusionRuntimeManager.unloadSharedEngineAfterExclusive("k0-control-lifecycle-cleanup")
            }
            persistedSpec?.let { FusionModelCatalog.removeImported(context, it) }
            importedFile?.delete()
        }
    }

    private suspend fun generate(
        engine: com.projectnuke.fusion.llm.LiteRtLlmEngine,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        messages: List<ChatMessage>,
    ): GenerationOutcome = FusionRuntimeLock.withChatGeneration {
        engine.generate(messages, profile, options)
    }

    private fun assertUsable(label: String, outcome: GenerationOutcome) {
        assertTrue("$label outcome=$outcome", outcome is GenerationOutcome.Success)
        assertFalse("$label output must be non-empty", (outcome as GenerationOutcome.Success).text.isBlank())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private companion object {
        const val CONTROL_URI_ARGUMENT = "fusion_kanana15_control_uri"
        const val CONTROL_FILE_NAME = "kanana-1.5-2.1b-instruct-2505.litertlm"
        const val CONTROL_BYTES = 2_114_676_672L
        const val CONTROL_SHA256 = "DAF1F19EEC19610BB69DBC499221CECF769E912999C2371E2B673AC74A327E56"
    }
}
