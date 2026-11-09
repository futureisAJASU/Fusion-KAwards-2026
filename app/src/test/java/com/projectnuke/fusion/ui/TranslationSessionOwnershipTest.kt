package com.projectnuke.fusion.ui

import com.projectnuke.fusion.translation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class TranslationSessionOwnershipTest {
    @Test fun `late terminal from closed session cannot replace reopened session`() = runTest {
        for (success in listOf(false, true)) {
            val gate = CompletableDeferred<TranslationResult>()
            val translator = object : LocalTranslator {
                override val capabilities = TranslationBackends.CURRENT
                override suspend fun translate(request: TranslationRequest): TranslationResult = withContext(NonCancellable) { gate.await() }
            }
            val controller = StandaloneTranslationController(this, LocalTranslationCoordinator(translator))
            controller.open("Hello world")
            controller.translate()
            runCurrent()
            controller.close()
            controller.open("Hello world")
            controller.translate()
            runCurrent()
            val newSession = controller.state.value
            val result = if (success) TranslationResult.Success(TranslationPolicy.makeArtifact(
                TranslationRequest(originalText = "Hello world", targetLanguage = "ko", sourceIdentity = "old"), "안녕하세요", translator.capabilities,
            )!!) else TranslationResult.Failure("old session")
            gate.complete(result)
            runCurrent()
            assertEquals(newSession, controller.state.value)
            controller.close()
        }
    }
}
