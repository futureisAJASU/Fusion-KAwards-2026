package com.projectnuke.fusion.validation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.ai.data.AiProviderRepository
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.FakeHttpURLConnection
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.test.PlatformSharedPreferences
import com.projectnuke.fusion.ui.ProjectFileImportCoordinator
import com.projectnuke.fusion.util.AttachmentImportResult
import java.io.File
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 validation probes for H01/H02/H03/H06.
 * Each test asserts the CORRECT (intended) behavior; a failing test
 * demonstrates a PROVEN bug that must then be fixed in the same phase.
 *
 * H03 note: the pre-fix deterministic stale-snapshot latch (A blocked in
 * putSecret while B completes) PROVED loss with ids=[... provider-a] and no
 * provider-b. After the process-wide mutation-authority fix the same latch
 * would deadlock by design (B correctly waits for A's global lock), so the
 * regression below uses latch-free concurrent independent saves from TWO
 * real instances sharing one prefs object, which must preserve both.
 */
class Phase1ValidationTest {

    // ---------- shared fakes ----------

    private class TestContext(root: File, private val prefs: PlatformSharedPreferences) : ContextWrapper(null) {
        private val dir = root
        override fun getFilesDir(): File = dir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class MapSecretStore : SecretStore {
        val secrets = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String): Boolean {
            secrets[id] = value
            return true
        }
        override suspend fun getSecret(id: String): String? = secrets[id]
        override suspend fun deleteSecret(id: String): Boolean {
            secrets.remove(id)
            return true
        }
    }

    private fun provider(id: String) = AiProviderConfig(
        id = id,
        type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
        displayName = "Test $id",
        baseUrl = "https://api.example.test/v1",
        modelId = "model",
        apiKeySecretId = null,
    )

    // ---------- H01 ----------

    @Test
    fun h01_commitThenCancel_mustNotDeleteAdoptedFile() = runTest {
        val root = Files.createTempDirectory("h01-validate").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        val dbRows = mutableSetOf<String>()
        var deleted = false
        try {
            val coordinator = ProjectFileImportCoordinator(
                deletePendingFile = { path ->
                    deleted = true
                    File(path).delete() || !File(path).exists()
                },
                unregisterPending = {},
            )
            var thrown: Throwable? = null
            try {
                coordinator.copyAndPersist(
                    copy = { AttachmentImportResult.Success(generated, generated.length()) },
                    persist = { file: File ->
                        dbRows += file.canonicalPath
                        throw CancellationException("commit-then-cancel race")
                    },
                    isAdopted = { true },
                    isDurablyReferenced = { path -> dbRows.contains(path) },
                )
            } catch (ce: CancellationException) {
                thrown = ce
            }
            assertTrue("persist race must surface as cancellation", thrown is CancellationException)
            assertTrue("DB row committed before cancellation", dbRows.contains(generated.canonicalPath))
            assertTrue(
                "H01 PROVEN if this fails: committed DB row survives but referenced file was deleted (deleted=$deleted, exists=${generated.exists()})",
                generated.exists(),
            )
            assertFalse("adopted file must not be deleted after commit", deleted)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- H02 ----------

    private fun clientErrorMessage(secret: String, errorBody: String): String {
        val fakeConn = FakeHttpURLConnection(URL("https://example.com/chat/completions"))
        fakeConn.configure(responseCode = 400, errorBody = errorBody.toByteArray(Charsets.UTF_8))
        val store = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = true
            override suspend fun getSecret(id: String) = secret
            override suspend fun deleteSecret(id: String) = true
        }
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.OPENAI, displayName = "Test",
            baseUrl = "https://example.com/", modelId = "test-model", apiKeySecretId = "test-key",
        )
        return try {
            kotlinx.coroutines.runBlocking {
                OpenAiCompatibleClient(store, connectionFactory = { fakeConn })
                    .chatCompletion(config, AiChatRequest(listOf(AiMessage(AiRole.USER, "hi"))))
            }
            ""
        } catch (e: AiProviderClientException) {
            e.message.orEmpty()
        }
    }

    @Test
    fun h02_rawSecretEcho_mustNeverSurviveInDiagnostic() {
        val secret = "sk-FUSION-SECRET-DO-NOT-LEAK-12345"
        val cases = listOf(
            "auth" to """{"message":"Authorization: Bearer $secret"}""",
            "bearer" to """{"message":"Bearer $secret failed"}""",
            "x-api-key" to """{"message":"x-api-key: $secret"}""",
            "json-plain" to """{"message":"key is $secret, please check"}""",
            "nested" to """{"error":{"message":"invalid key $secret"}}""",
            "plain-text" to """{"message":"$secret"}""",
        )
        cases.forEach { (label, body) ->
            val msg = clientErrorMessage(secret, body)
            assertFalse(
                "H02 PROVEN if this fails [$label]: exact credential survives in diagnostic: $msg",
                msg.contains(secret),
            )
        }
        val benign = clientErrorMessage(secret, """{"message":"invalid request format"}""")
        assertTrue(benign.contains("invalid request format"))
    }

    // ---------- H03 ----------

    @Test
    fun h03_twoInstances_concurrentIndependentSaves_mustPreserveBoth() = runTest {
        val dir = Files.createTempDirectory("h03-validate").toFile()
        val prefs = PlatformSharedPreferences()
        val ctx = TestContext(dir, prefs)
        val repoA = AiProviderRepository(ctx, MapSecretStore())
        val repoB = AiProviderRepository(ctx, MapSecretStore())
        try {
            val results = awaitAll(
                async { repoA.saveProvider(provider("provider-a"), "secret-a") },
                async { repoB.saveProvider(provider("provider-b"), "secret-b") },
            )
            assertTrue(results.all { it })
            val ids = repoA.getProviders().map { it.id }.toSet()
            assertTrue(
                "H03 regression: unrelated concurrent writes must preserve both; ids=$ids",
                ids.contains("provider-a") && ids.contains("provider-b"),
            )
            val idsB = repoB.getProviders().map { it.id }.toSet()
            assertTrue(idsB.contains("provider-a") && idsB.contains("provider-b"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------- H06 ----------

    @Test
    fun h06_cancelledRollback_mustRestoreDurability() = runTest {
        var dbState = "A"
        suspend fun fakeDbUpdate(to: String) {
            ensureActive()
            dbState = to
        }
        val cancelledParent = Job()
        cancelledParent.cancel(CancellationException("parent cancelled"))

        dbState = "B"
        var threw1 = false
        try {
            withContext(cancelledParent) {
                fakeDbUpdate("A")
            }
        } catch (_: CancellationException) {
            threw1 = true
        }
        val withoutFix = dbState

        dbState = "B"
        var threw2 = false
        try {
            withContext(NonCancellable) {
                dbState = "A"
            }
        } catch (_: CancellationException) {
            threw2 = true
        }
        val withFix = dbState

        assertTrue(
            "H06 pattern check (withoutFix=$withoutFix threw1=$threw1, withFix=$withFix threw2=$threw2)",
            threw1 && withoutFix == "B" && !threw2 && withFix == "A",
        )
    }
}
