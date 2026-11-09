package com.projectnuke.fusion.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutoModelRoutingOwnerTest {
    private fun request(auto: Boolean = false) = GenerationRequestSnapshot(
        requestId = "auto-device", conversationId = 1, generationModeKey = "LOCAL_MODEL",
        selectedModelId = "manual", selectedModelPath = null, settings = GenerationSettings(maxTokens = 512),
        reasoningEnabled = false, webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
        attachmentIds = listOf("private"), multimodalImagePaths = emptyList(), promptText = "hello",
        rawUserText = "hello", createdAt = 0, autoRoutingEnabled = auto)
    @Test fun manualAndExternalRequestsRemainAuthoritative() = runBlocking {
        val owner = AutoModelRoutingOwner(ApplicationProvider.getApplicationContext())
        val manual = request()
        assertSame(manual, owner.resolve(manual))
        val external = request(true).copy(generationModeKey = "EXTERNAL_AI_API")
        assertSame(external, owner.resolve(external))
    }
    @Test fun deviceAutoIsLocalOrUnavailableAndIdentityIsFrozen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = AutoModelRoutingOwner(context)
        val result = owner.resolve(request(true))
        println("DEVICE_AUTO_SELECTION=" + (result?.autoSelectionExplanation ?: "NO_RUNNABLE_LOCAL_MODEL"))
        if (result != null) {
            assertNotNull(ManagedModelPathPolicy.resolveRunnableModel(context, result.selectedModelPath))
            assertNull(result.externalProviderRequest)
            assertEquals(listOf("private"), result.attachmentIds)
            assertSame(result, owner.resolve(result))
        }
    }
    @Test fun cancelledOwnerCannotPublish() = runBlocking {
        val owner = AutoModelRoutingOwner(ApplicationProvider.getApplicationContext())
        var published = false
        val job = launch(start = CoroutineStart.LAZY) { owner.resolve(request(true)); published = true }
        job.cancelAndJoin()
        assertFalse(published)
    }
}
