package com.projectnuke.fusion

import com.projectnuke.fusion.ai.model.*
import com.projectnuke.fusion.ai.network.*
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.ai.usecase.TestAiProviderUseCase
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageValidator
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeFailureBoundaryTest {
    private val config = AiProviderConfig("p", AiProviderType.OPENAI, "p", "https://p.example/v1", "model", "key")

    @Test fun `provider test preserves cancellation and fatal identity`() = runTest {
        for (failure in listOf(CancellationException("cancel"), LinkageError("fatal"))) {
            val store = object : SecretStore {
                override suspend fun putSecret(id: String, value: String) = false
                override suspend fun deleteSecret(id: String) = false
                override suspend fun getSecret(id: String): String? = throw failure
            }
            var thrown: Throwable? = null
            try { TestAiProviderUseCase(OpenAiCompatibleClient(store))(config) } catch (error: Throwable) { thrown = error }
            assertSame(failure, thrown)
        }
    }

    @Test fun `discovery fatal error propagates while loading settles`() = runTest {
        val fatal = LinkageError("fatal")
        var thrown: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> thrown = error })
        try {
            val source = object : ExternalModelDiscoverySource {
                override suspend fun discover(config: AiProviderConfig, discoveryIdentity: ExternalDiscoveryIdentity, ephemeralApiKey: String?): ExternalModelDiscoveryResult = throw fatal
            }
            val coordinator = DiscoveryCoordinator(scope, source)
            coordinator.discover(config, 1).join()
            assertSame(fatal, thrown)
            assertFalse(coordinator.state.value.loading)
            assertNull(coordinator.state.value.result)
        } finally { scope.cancel() }
    }

    @Test fun `package validation and capability probing preserve fatal and cancellation identity`() {
        for (failure in listOf(CancellationException("cancel"), LinkageError("fatal"))) {
            val file = object : File("unused") { override fun isFile(): Boolean = throw failure }
            for (probe in listOf<() -> Any>({ LiteRtLmPackageValidator.validate(file) }, { LiteRtLmPackageValidator.capabilities(file) })) {
                var thrown: Throwable? = null
                try { probe() } catch (error: Throwable) { thrown = error }
                assertSame(failure, thrown)
            }
        }
    }
}
