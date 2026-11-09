package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.model.GenerationSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ResearchModeDeviceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)
    @Test fun stagesAreOperationalAndCancellationSettlesOwnedRun() = runBlocking {
        compose.setContentOnResumedTestHost { MaterialTheme { ResearchModeStatus(true,"인터넷 검색 중...",null,true) } }
        compose.onNodeWithText("검색 중").assertIsDisplayed()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val registry=GenerationSessionRegistry()
        val entered=CompletableDeferred<Unit>(); val cleaned=CompletableDeferred<Unit>()
        val request=GenerationRequestSnapshot("research-smoke",1,"LOCAL_MODEL",null,null,
            settings=GenerationSettings(),reasoningEnabled=false,webSearchPolicy=GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds=emptyList(),multimodalImagePaths=emptyList(),promptText="question",rawUserText="question",createdAt=0,
            interactionMode=ChatInteractionMode.RESEARCH)
        try {
            val session=registry.start(scope,request) { try { entered.complete(Unit); awaitCancellation() } finally { cleaned.complete(Unit) } }
            withTimeout(5000) { entered.await(); registry.cancelAndJoin(1,"device smoke"); cleaned.await(); session.job.join() }
            assertFalse(registry.hasLogicalRequest(1)); assertNull(registry.activeSession(1))
        } finally { scope.cancel() }
    }
}
