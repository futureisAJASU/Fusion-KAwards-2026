package com.projectnuke.fusion.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.audit.*
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LiteRtLlmEngine
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentSource
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.verifier.FusionVerifierAssetProvider
import com.projectnuke.fusion.verifier.FusionVerifierAssetResult
import com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Development-only synthetic inputs; this is not an ABCD evaluation. */
@RunWith(AndroidJUnit4::class)
class VerifierVq1PilotTest {
    @Test
    fun realGemmaRevisionAfterC2ContradictionCapturesExactOutcome() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.projectnuke.fusion.qualification", context.packageName)
        val model = requireNotNull(ManagedModelPathPolicy.resolveRunnableModel(
            context, File(ManagedModelPathPolicy.getModelDirectory(context), "gemma-4-E2B-it.litertlm").absolutePath,
        ))
        val engine = LiteRtLlmEngine(context)
        val proxy = FusionVerifierRuntimeOwner.proxy(File(context.filesDir, "verifier/current"))
        val verified = mutableListOf<Pair<VerificationPairRequest, VerifierBackendResult.Verified>>()
        val recording = object : VerifierBackend {
            override val descriptor get() = proxy.descriptor
            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                val result = proxy.verify(request)
                if (result is VerifierBackendResult.Verified) verified += request to result
                return result
            }
        }
        val originalDraft = "고양이는 방석 위에 있습니다."
        val evidenceText = "Qualification evidence: The cat is on the mat."
        val evidenceHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(evidenceText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val attachmentId = "vq3-controlled-evidence"
        val documentName = "FusionVQ3_controlled_evidence.txt"
        val localSelection = LocalRagSelection(
            chunk = LocalDocumentChunk(
                docId = attachmentId,
                displayName = documentName,
                chunkIndex = 0,
                startOffset = 0,
                endOffset = evidenceText.length,
                contentHashHex = evidenceHash,
                text = evidenceText,
            ),
            source = LocalDocumentSource(
                docId = attachmentId,
                displayName = documentName,
                attachmentId = attachmentId,
                chunkIndex = 0,
                startOffset = 0,
                endOffset = evidenceText.length,
                contentHashHex = evidenceHash,
                excerpt = evidenceText,
            ),
            lexicalScore = 1,
            semanticScore = null,
            rank = 0,
        )
        val revisionObservations = mutableListOf<String>()
        val request = GenerationRequestSnapshot(
            requestId = "vq3-revision-${System.currentTimeMillis()}",
            conversationId = 9303L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = model.name,
            selectedModelPath = model.absolutePath,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = listOf(attachmentId),
            multimodalImagePaths = emptyList(),
            promptText = "Where is the cat in the controlled local evidence?",
            rawUserText = "Where is the cat in the controlled local evidence?",
            createdAt = System.currentTimeMillis(),
            interactionMode = ChatInteractionMode.RESEARCH,
        )
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val pssBeforeKb = android.os.Debug.getPss()
        try {
            val coordinator = ResearchInterventionCoordinator(
                ResearchInterventionOrchestrator(
                    activationPolicy = ResearchInterventionActivationPolicy { _, _ -> true },
                ),
                ResearchDraftShadowOwner(
                    activationPolicy = ResearchDraftShadowActivationPolicy { true },
                    backend = recording,
                ),
            )
            val revisionGenerator = ResearchRevisionGenerator { revisionRequest ->
                when (
                    val outcome = withTimeout(30_000L) {
                        engine.generate(
                            messages = listOf(
                                ChatMessage("user", request.rawUserText),
                                ChatMessage("assistant", revisionRequest.originalVisibleAnswer),
                                ChatMessage("system", revisionRequest.instruction),
                            ),
                            profile = RequestedEngineProfile(
                                model.absolutePath,
                                AcceleratorMode.GPU,
                                false,
                                1024,
                                false,
                            ),
                            options = ConversationOptions(
                                maxOutputToken = 64,
                                temperature = 0.8f,
                                topK = 40,
                                topP = 0.9f,
                            ),
                        )
                    }
                ) {
                    is GenerationOutcome.Success -> {
                        val unchanged = outcome.text.trim() == revisionRequest.originalVisibleAnswer.trim()
                        revisionObservations += "success;backend=${outcome.actualBackend};chars=${outcome.text.length};unchanged=$unchanged;fingerprint=${com.projectnuke.fusion.data.EvidenceFingerprint.canonicalAnswerHash(outcome.text)}"
                        ResearchRevisionResult.Revised(outcome.text)
                    }
                    is GenerationOutcome.Failure -> {
                        revisionObservations += "failure;kind=${outcome.kind.name}"
                        ResearchRevisionResult.Failed(outcome.kind.name)
                    }
                    else -> {
                        revisionObservations += "unavailable;type=${outcome::class.simpleName}"
                        ResearchRevisionResult.Failed("REVISION_NOT_AVAILABLE")
                    }
                }
            }
            val resolution = withTimeout(30_000L) {
                coordinator.resolve(
                    request = request,
                    exactStoredAssistantContent = originalDraft,
                    webSources = emptyList(),
                    localSelections = listOf(localSelection),
                    responseVersionTarget = null,
                    composeStoredAnswer = { revised, _ -> revised },
                    revisionGenerator = revisionGenerator,
                    searchAuthorizer = null,
                    retriever = null,
                    owns = { true },
                )
            }
            val audit = requireNotNull(resolution.audit)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString(
                    "vq3_revision",
                    "requestId=${request.requestId};model=${model.name};initial=${verified.firstOrNull()?.second?.label};verified=${verified.map { it.second.label }};pairs=${verified.size};revision=${revisionObservations.joinToString()};action=${audit.action};reason=${audit.reason};revisionAttempted=${audit.revisionAttempted};revised=${resolution.revised};draftStage=${audit.draftStage};elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt};pssBeforeKb=$pssBeforeKb;pssAfterKb=${android.os.Debug.getPss()}",
                )
            })
            assertTrue("C2 must verify the controlled original draft", verified.isNotEmpty())
            assertEquals(AuditVerificationLabel.CONTRADICTED, verified.first().second.label)
            assertTrue("the production policy must attempt the configured revision", audit.revisionAttempted)
            assertTrue("record the real local Gemma revision outcome", revisionObservations.isNotEmpty())
            if (resolution.revised) {
                assertTrue("a changed draft must be independently reverified", verified.size >= 2)
                assertEquals(ResearchInterventionDraftStage.REVISED, audit.draftStage)
            } else {
                assertEquals(ResearchInterventionDraftStage.ORIGINAL, audit.draftStage)
                assertEquals(ResearchInterventionAction.ABSTAIN, audit.action)
            }
        } finally {
            FusionVerifierRuntimeOwner.close()
            engine.unload()
        }
    }

    @Test
    fun generatedDraftThroughRealVerifierAndCoordinator() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.projectnuke.fusion.qualification", context.packageName)
        val model = requireNotNull(ManagedModelPathPolicy.resolveRunnableModel(
            context, File(ManagedModelPathPolicy.getModelDirectory(context), "gemma-4-E2B-it.litertlm").absolutePath,
        ))
        val engine = LiteRtLlmEngine(context)
        val proxy = FusionVerifierRuntimeOwner.proxy(File(context.filesDir, "verifier/current"))
        val seen = mutableListOf<VerifierBackendResult.Verified>()
        val recording = object : VerifierBackend {
            override val descriptor get() = proxy.descriptor
            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                val result = proxy.verify(request)
                if (result is VerifierBackendResult.Verified) seen += result
                return result
            }
        }
        try {
            val generated = withTimeout(120_000) {
                engine.generateStreaming(
                    messages = listOf(ChatMessage("user", "Reply with one short sentence: The cat is on the mat.")),
                    profile = RequestedEngineProfile(model.absolutePath, AcceleratorMode.CPU, false, 1024, false, cpuThreadCount = 2),
                    options = ConversationOptions(maxOutputToken = 24, temperature = 0.2f, topK = 16, topP = 0.9f, seed = 41),
                    onToken = {},
                )
            }
            assertTrue("actual generation: $generated", generated is GenerationOutcome.Success)
            generated as GenerationOutcome.Success
            assertEquals("CPU", generated.actualBackend)
            val draft = generated.text
            val coordinator = ResearchInterventionCoordinator(
                ResearchInterventionOrchestrator(activationPolicy = ResearchInterventionActivationPolicy { _, _ -> true }),
                ResearchDraftShadowOwner(activationPolicy = ResearchDraftShadowActivationPolicy { true }, backend = recording),
            )
            val request = GenerationRequestSnapshot(
                requestId = "vq2-generated", conversationId = 9082L, generationModeKey = "LOCAL_MODEL",
                selectedModelId = model.name, selectedModelPath = model.absolutePath, settings = GenerationSettings(),
                reasoningEnabled = false, webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(), multimodalImagePaths = emptyList(), promptText = "synthetic question",
                rawUserText = "synthetic question", createdAt = 1L, interactionMode = ChatInteractionMode.RESEARCH,
            )
            val source = WebSearchSource(
                title = "Synthetic source", source = "Example", url = "https://example.com/vq2",
                snippet = "The cat is on the mat.", publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT, providerDisplayName = "Synthetic",
                searchCategory = null, queryUsed = "cat", rank = 0,
            )
            val pssBeforeVerifier = android.os.Debug.getPss()
            val resolution = coordinator.resolve(request, draft, listOf(source), emptyList(), null,
                { text, _ -> text }, null, null, null, { true })
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("vq2_generated", "draft=$draft;backend=${generated.actualBackend};labels=${seen.map { it.label }};action=${resolution.audit?.action};reason=${resolution.audit?.reason};pss_before_verifier_kb=$pssBeforeVerifier;pss_after_verifier_kb=${android.os.Debug.getPss()}")
            })
            assertTrue("generated draft must reach real verifier", seen.isNotEmpty())
            assertNotNull("generated draft intervention audit", resolution.audit)
            assertFalse(resolution.audit!!.wholeAnswerCertified)
        } finally {
            FusionVerifierRuntimeOwner.close()
            engine.unload()
        }
    }

    @Test
    fun realVerifierSmokeAndResearchPilot() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.filesDir, "verifier/current")
        assertTrue(FusionVerifierAssetProvider(dir).verify() is FusionVerifierAssetResult.Ready)
        val proxy = FusionVerifierRuntimeOwner.proxy(dir)
        val observations = mutableListOf<String>()
        val cases = listOf(
            Triple("en_supported", "The cat is on the mat.", "The cat is on the mat."),
            Triple("en_contradicted", "The cat is on the mat.", "The cat is not on the mat."),
            Triple("en_insufficient", "The cat is on the mat.", "The cat is black."),
            Triple("ko_supported", "고양이가 매트 위에 있다.", "고양이가 매트 위에 있다."),
            Triple("ko_en", "고양이가 매트 위에 있다.", "The cat is on the mat."),
            Triple("en_ko", "The cat is on the mat.", "고양이가 매트 위에 있다."),
        )
        val expectedLabels = mapOf(
            "en_supported" to AuditVerificationLabel.SUPPORTED,
            "en_contradicted" to AuditVerificationLabel.CONTRADICTED,
            "en_insufficient" to AuditVerificationLabel.INSUFFICIENT,
            "ko_supported" to AuditVerificationLabel.SUPPORTED,
            "ko_en" to AuditVerificationLabel.SUPPORTED,
            "en_ko" to AuditVerificationLabel.SUPPORTED,
        )
        try {
            for ((id, evidence, claim) in cases) {
                val result = proxy.verify(VerificationPairRequest(claim, evidence, evidenceIdentity = id))
                assertTrue("$id: $result", result is VerifierBackendResult.Verified)
                result as VerifierBackendResult.Verified
                assertEquals("real NLI label for synthetic input $id", expectedLabels.getValue(id), result.label)
                observations += "$id=${result.label};scores=${result.scores.map { it.value }}"
            }
            val seen = mutableListOf<VerifierBackendResult.Verified>()
            val recording = object : VerifierBackend {
                override val descriptor get() = proxy.descriptor
                override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                    val result = proxy.verify(request)
                    if (result is VerifierBackendResult.Verified) seen += result
                    return result
                }
            }
            val coordinator = ResearchInterventionCoordinator(
                ResearchInterventionOrchestrator(activationPolicy = ResearchInterventionActivationPolicy { _, _ -> true }),
                ResearchDraftShadowOwner(activationPolicy = ResearchDraftShadowActivationPolicy { true }, backend = recording),
            )
            val request = GenerationRequestSnapshot(
                requestId = "vq1-synthetic", conversationId = 9081L, generationModeKey = "LOCAL_MODEL",
                selectedModelId = null, selectedModelPath = null, settings = GenerationSettings(),
                reasoningEnabled = false, webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(), multimodalImagePaths = emptyList(), promptText = "question",
                rawUserText = "question", createdAt = 1L, interactionMode = ChatInteractionMode.RESEARCH,
            )
            val source = WebSearchSource(
                title = "Synthetic source", source = "Example", url = "https://example.com/vq1",
                snippet = "The cat is on the mat.", publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT, providerDisplayName = "Synthetic",
                searchCategory = null, queryUsed = "cat", rank = 0,
            )
            val drafts = listOf(
                "supported" to "The cat is on the mat.",
                "contradicted" to "The cat is not on the mat.",
                "insufficient" to "The cat is black.",
                "mixed_incomplete" to "The cat is on the mat.\n```text\nThe cat is black.\n```",
            )
            val expectedDecisions = mapOf(
                "supported" to (ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT to ResearchInterventionReason.SUPPORTED_CLAIMS_COVERAGE_INCOMPLETE),
                "contradicted" to (ResearchInterventionAction.ABSTAIN to ResearchInterventionReason.REVISION_UNSUPPORTED),
                "insufficient" to (ResearchInterventionAction.ABSTAIN to ResearchInterventionReason.SEARCH_NOT_AUTHORIZED),
                "mixed_incomplete" to (ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT to ResearchInterventionReason.SUPPORTED_CLAIMS_COVERAGE_INCOMPLETE),
            )
            val expectedPilotLabels = mapOf(
                "supported" to AuditVerificationLabel.SUPPORTED,
                "contradicted" to AuditVerificationLabel.CONTRADICTED,
                "insufficient" to AuditVerificationLabel.INSUFFICIENT,
                "mixed_incomplete" to AuditVerificationLabel.SUPPORTED,
            )
            for ((id, draft) in drafts) {
                seen.clear()
                val resolution = coordinator.resolve(
                    request.copy(requestId = "vq1-$id"), draft, listOf(source), emptyList(), null,
                    { text, _ -> text }, null, null, null, { true },
                )
                assertTrue("real verifier must be called for $id", seen.isNotEmpty())
                assertEquals(listOf(expectedPilotLabels.getValue(id)), seen.map { it.label })
                assertNotNull("intervention audit for $id", resolution.audit)
                assertFalse("no authoritative whole-answer claim", resolution.audit!!.wholeAnswerCertified)
                assertEquals(expectedDecisions.getValue(id).first, resolution.audit!!.action)
                assertEquals(expectedDecisions.getValue(id).second, resolution.audit!!.reason)
                observations += "pilot_$id labels=${seen.map { it.label }};action=${resolution.audit?.action};reason=${resolution.audit?.reason};abstained=${resolution.abstained};revised=${resolution.revised};disposition=${resolution.disposition}"
            }
            val missingDir = File(instrumentation.targetContext.cacheDir, "vq1-intentionally-absent")
            assertFalse("missing-asset test requires an absent directory", missingDir.exists())
            val missingProxy = FusionVerifierRuntimeOwner.proxy(missingDir)
            val unavailable = missingProxy.verify(VerificationPairRequest("claim", "evidence", evidenceIdentity = "missing"))
            assertEquals(VerifierBackendResult.Unavailable, unavailable)
            observations += "missing_asset=$unavailable"
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("vq1", observations.joinToString("\n")) })
        } finally {
            FusionVerifierRuntimeOwner.close()
        }
    }
}
