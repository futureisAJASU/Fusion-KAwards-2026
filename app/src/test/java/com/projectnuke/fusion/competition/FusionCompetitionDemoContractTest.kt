package com.projectnuke.fusion.competition

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ChatInteractionModePolicy
import com.projectnuke.fusion.chat.ChatInteractionModeScope
import com.projectnuke.fusion.chat.ChatInteractionModeState
import com.projectnuke.fusion.chat.ChatPromptComposition
import com.projectnuke.fusion.chat.DynamicFollowUpPolicy
import com.projectnuke.fusion.chat.FinalPromptBudget
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.LocalPromptPreparation
import com.projectnuke.fusion.chat.NextActionSuggestionPolicy
import com.projectnuke.fusion.chat.NextActionTarget
import com.projectnuke.fusion.chat.promptText
import com.projectnuke.fusion.chat.ProjectBriefLoadState
import com.projectnuke.fusion.chat.ProjectBriefSnapshot
import com.projectnuke.fusion.chat.ProjectInstructionPromptPolicy
import com.projectnuke.fusion.chat.ProjectSynthesisMode
import com.projectnuke.fusion.chat.ProjectSynthesisPromptPolicy
import com.projectnuke.fusion.chat.ProjectSynthesisPromptResult
import com.projectnuke.fusion.chat.ResponseVersionCompareEntry
import com.projectnuke.fusion.chat.ResponseVersionComparePolicy
import com.projectnuke.fusion.chat.StudyModePromptPolicy
import com.projectnuke.fusion.chat.StudyPromptRequestOutcome
import com.projectnuke.fusion.chat.StudySessionFlow
import com.projectnuke.fusion.chat.STUDY_MODE_ACTION_OPTIONS
import com.projectnuke.fusion.llm.BackgroundRuntimeOwner
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.DataEgressDestination
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.DataEgressScope
import com.projectnuke.fusion.privacy.PreparedEgressRequestFactory
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentSource
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.retrieval.LocalRagSnapshotPolicy
import com.projectnuke.fusion.ui.ChatEmptyStatePolicy
import com.projectnuke.fusion.ui.ChatGenerationMode
import com.projectnuke.fusion.ui.FrozenSearchRetry
import com.projectnuke.fusion.ui.RegenerationRequestCapture
import com.projectnuke.fusion.ui.ResponseVersionState
import com.projectnuke.fusion.ui.TrustRecoveryCoordinator
import com.projectnuke.fusion.ui.VoiceTextEvent
import com.projectnuke.fusion.ui.buildFrozenSearchRetryCapture
import com.projectnuke.fusion.ui.buildRegenerationGenerationRequestSnapshot
import com.projectnuke.fusion.ui.shouldAutoSubmitVoiceFinal
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingAction
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingPage
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingPathChoice
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingPolicy
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingUiState
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRoutingRequest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side competition/demo contract for the important Fusion journeys.
 *
 * These tests compose the same Android-free policies used by ChatScreen. They
 * do not fake a second generation path, invoke a provider, or claim device
 * behavior; each contract stops at the owned request/egress boundary.
 */
class FusionCompetitionDemoContractTest {

    @Test
    fun `A ordinary chat stays NORMAL and fits the normal prompt family`() {
        val snapshot = requestSnapshot(ChatInteractionMode.NORMAL)
        val route = TrustRouter.route(TrustRoutingRequest(snapshot.rawUserText, generationModeKey = "NORMAL"))
        val studyRoute = TrustRouter.route(TrustRoutingRequest(snapshot.rawUserText, generationModeKey = "STUDY"))

        assertEquals(ChatInteractionMode.NORMAL, snapshot.interactionMode)
        assertEquals("", StudyModePromptPolicy.modelInstruction(snapshot.interactionMode))
        assertEquals(route, studyRoute)

        val prepared = checkNotNull(
            LocalPromptPreparation.prepare(
                rawMessages = listOf(
                    ChatMessage("system", "Fusion base system prompt"),
                    ChatMessage("user", snapshot.rawUserText),
                ),
                budget = FinalPromptBudget(
                    modelId = "demo-normal",
                    external = false,
                    maxOutputTokens = 256,
                    engineKvCapacityTokens = 8_192,
                ),
                settings = GenerationSettings(maxTokens = 256, kvCacheCapacityTokens = 8_192),
            ),
        )
        val preparedText = prepared.messages.joinToString("\n") { it.content }
        assertFalse(preparedText.contains("학습 모드"))
        assertTrue(
            com.projectnuke.fusion.chat.PromptTokenEstimator.estimateTokens(prepared.messages) +
                prepared.appliedOutputTokens + 32 <= 8_192,
        )
    }

    @Test
    fun `B empty Study entry changes only the ephemeral surface`() {
        val scope = ChatInteractionModeScope(conversationId = 0L, newChatEpoch = 4L)
        val state = ChatInteractionModePolicy.switch(
            state = ChatInteractionModeState(),
            scope = scope,
            targetMode = ChatInteractionMode.STUDY,
            controlsEnabled = true,
        )

        assertEquals(ChatInteractionMode.STUDY, ChatInteractionModePolicy.currentMode(state, scope))
        assertTrue(ChatEmptyStatePolicy.shouldShowStudyState(ChatInteractionMode.STUDY, 0, false))
        assertEquals("무엇을 공부할까요?", ChatEmptyStatePolicy.copyFor(ChatInteractionMode.STUDY, 0, false).title)
        assertEquals("배울 내용을 입력하세요", ChatInteractionModePolicy.composerPlaceholder(ChatInteractionMode.STUDY))
        assertEquals("", StudyModePromptPolicy.modelInstruction(ChatInteractionMode.NORMAL))
    }

    @Test
    fun `C document Study keeps RAG identity and host instruction separate`() {
        val chunk = LocalDocumentChunk(
            docId = "doc-1",
            displayName = "biology.pdf",
            chunkIndex = 2,
            startOffset = 100,
            endOffset = 126,
            contentHashHex = "ignored-by-snapshot",
            text = "PRIVATE DOCUMENT BODY: 광합성은 빛 에너지를 화학 에너지로 바꾼다.",
            pageNumber = 3,
        )
        val selection = LocalRagSelection(
            chunk = chunk,
            source = LocalDocumentSource(
                docId = chunk.docId,
                displayName = chunk.displayName,
                attachmentId = "attachment-1",
                chunkIndex = chunk.chunkIndex,
                startOffset = chunk.startOffset,
                endOffset = chunk.endOffset,
                contentHashHex = chunk.contentHashHex,
                excerpt = chunk.text,
                pageNumber = chunk.pageNumber,
            ),
            lexicalScore = 4,
            semanticScore = 0.8,
            rank = 0,
        )
        val ragSnapshot = LocalRagSnapshotPolicy.snapshot("study-doc", listOf(selection))
        val body = ChatPromptComposition.localizedRagUserContent(
            query = "광합성을 공부하고 싶어",
            selections = listOf(selection),
            coverageByDoc = mapOf(chunk.docId to false),
        )
        val prepared = checkNotNull(
            LocalPromptPreparation.prepare(
                rawMessages = listOf(
                    ChatMessage("system", StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)),
                    ChatMessage("user", body),
                ),
                budget = FinalPromptBudget("demo-study-doc", false, 256, engineKvCapacityTokens = 8_192),
                settings = GenerationSettings(maxTokens = 256, kvCacheCapacityTokens = 8_192),
            ),
        )

        assertTrue(LocalRagSnapshotPolicy.validate(ragSnapshot, listOf(chunk)))
        assertTrue(body.contains("PRIVATE DOCUMENT BODY"))
        assertFalse(body.contains("현재 요청은 학습 모드입니다."))
        val preparedText = prepared.messages.joinToString("\n") { it.content }
        assertTrue(preparedText.contains("현재 요청은 학습 모드입니다."))
        assertTrue(preparedText.contains("PRIVATE DOCUMENT BODY"))
        assertEquals(listOf("doc-1#2"), ragSnapshot.chunkKeys)
        assertEquals(listOf(3), ragSnapshot.pageNumbers)
    }

    @Test
    fun `D project guidance and Study behavior remain distinct system context`() {
        val project = ProjectInstructionPromptPolicy.modelMessage("문서 용어는 프로젝트 표기대로 유지해 주세요.")
        val study = StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)

        assertTrue(project.contains(ProjectInstructionPromptPolicy.BEGIN_MARKER))
        assertTrue(project.contains("현재 사용자의 더 새로운 명시적 요청과 충돌하면 현재 요청이 우선입니다."))
        assertTrue(study.contains("현재 요청은 학습 모드입니다."))
        assertFalse(project.contains("현재 요청은 학습 모드입니다."))
        assertFalse(study.contains("문서 용어는 프로젝트 표기대로"))
        assertEquals(
            "문서 용어는 프로젝트 표기대로 유지해 주세요.",
            ProjectInstructionPromptPolicy.escapeReservedMarkers("문서 용어는 프로젝트 표기대로 유지해 주세요."),
        )
    }

    @Test
    fun `E external Study uses exact egress identity and unchanged private gate`() {
        val provider = AiProviderConfig(
            id = "demo-provider",
            type = AiProviderType.OPENAI,
            displayName = "Demo provider",
            baseUrl = "https://provider.example.test",
            modelId = "demo-model",
            apiKeySecretId = "demo-secret",
        )
        val exact = AiChatRequest(
            messages = listOf(
                AiMessage(AiRole.SYSTEM, StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)),
                AiMessage(AiRole.USER, "현재 개념을 설명해 주세요."),
            ),
            temperature = 0.2,
            maxTokens = 128,
        )
        val controller = DataEgressController()
        val prepared = PreparedEgressRequestFactory.prepare(
            requestId = "external-study",
            destination = DataEgressDestination.EXTERNAL_AI,
            provider = provider,
            exactRequest = exact,
            transmittedUserText = "현재 개념을 설명해 주세요.",
            transmittedHistoryText = "",
            privateExcerpts = listOf("private attachment excerpt"),
        )

        assertEquals(0, controller.pendingTicketCountForTest())
        assertFalse(controller.arm(prepared, null))
        assertEquals(0, controller.pendingTicketCountForTest())

        val consent = DataEgressPolicy.makeConsent(
            consentId = "consent-1",
            requestId = prepared.requestId,
            providerId = prepared.providerId,
            payloadHashHex = prepared.payloadHashHex,
            includesPrivate = true,
            scope = DataEgressScope.USER_QUERY_AND_HISTORY,
        )
        assertTrue(controller.arm(prepared, consent))
        assertEquals(DataEgressController.Authorization.Granted, controller.authorize(provider, exact, prepared.requestId))
        assertEquals(0, controller.pendingTicketCountForTest())
    }

    @Test
    fun `F trust routing is identical for NORMAL and STUDY raw requests`() {
        val requests = listOf(
            TrustRoutingRequest("광합성이 뭐야?"),
            TrustRoutingRequest("오늘 최신 뉴스를 검색해 줘"),
            TrustRoutingRequest(
                userText = "첨부 문서의 정의를 설명해 줘",
                localContentPresent = true,
                privateOrSensitiveContentPresent = true,
            ),
        )

        requests.forEach { request ->
            val normal = TrustRouter.route(request.copy(generationModeKey = ChatInteractionMode.NORMAL.name))
            val study = TrustRouter.route(request.copy(generationModeKey = ChatInteractionMode.STUDY.name))
            assertEquals(normal, study)
        }
    }

    @Test
    fun `G failed SEARCH retry stays STUDY after current UI changes`() {
        val failed = requestSnapshot(ChatInteractionMode.STUDY).copy(
            requestId = "failed-search",
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            trustRoutingDecision = TrustRoutingDecision(
                route = TrustRoute.SEARCH,
                reason = TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
                signals = emptySet(),
            ),
        )
        val coordinator = TrustRecoveryCoordinator()
        coordinator.freezeSearchRetry(91L, failed)
        val frozen = checkNotNull(coordinator.takeSearchRetry(91L))
        val capture = buildFrozenSearchRetryCapture(frozen, 92L, ResponseVersionState())

        assertEquals(ChatInteractionMode.STUDY, frozen.interactionMode)
        assertEquals(ChatInteractionMode.STUDY, capture.interactionMode)
        assertEquals(TrustRoute.SEARCH, capture.frozenTrustRoutingDecision?.route)
        assertEquals(null, coordinator.takeSearchRetry(91L))
    }

    @Test
    fun `H regenerate captures the mode selected at the tap boundary`() {
        val studyRequest = buildRegenerationGenerationRequestSnapshot(
            capture = regenerationCapture(ChatInteractionMode.STUDY),
            requestId = "regenerate-study",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "같은 답변을 학습 방식으로 다시 설명해 줘",
            rawUserText = "같은 답변을 학습 방식으로 다시 설명해 줘",
            createdAt = 1L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )
        val normalRequest = buildRegenerationGenerationRequestSnapshot(
            capture = regenerationCapture(ChatInteractionMode.NORMAL),
            requestId = "regenerate-normal",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "같은 답변을 다시 작성해 줘",
            rawUserText = "같은 답변을 다시 작성해 줘",
            createdAt = 1L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )

        assertEquals(ChatInteractionMode.STUDY, studyRequest.interactionMode)
        assertEquals(ChatInteractionMode.NORMAL, normalRequest.interactionMode)
        assertTrue(StudyModePromptPolicy.modelInstruction(studyRequest.interactionMode).isNotBlank())
        assertEquals("", StudyModePromptPolicy.modelInstruction(normalRequest.interactionMode))
    }

    @Test
    fun `I voice final is ordinary text and uses the same mode snapshot boundary`() {
        val event = VoiceTextEvent(
            sessionId = 7L,
            candidate = "인식된 학습 질문",
            isFinal = true,
            conversationId = 12L,
        )
        val snapshot = requestSnapshot(ChatInteractionMode.STUDY).copy(
            conversationId = event.conversationId,
            promptText = event.candidate,
            rawUserText = event.candidate,
        )

        assertTrue(shouldAutoSubmitVoiceFinal(true, event, draftApplied = true))
        assertEquals(ChatInteractionMode.STUDY, snapshot.interactionMode)
        assertEquals(event.candidate, snapshot.rawUserText)
        assertTrue(snapshot.history.isEmpty())
    }

    @Test
    fun `J Study tools append only durable prompts and never send or retrieve`() = runTest {
        val state = ChatInteractionModeState(ChatInteractionMode.STUDY, 12L, 0L)
        val scope = ChatInteractionModeScope(12L, 0L)
        var appendCalls = 0
        var sendCalls = 0
        var retrievalCalls = 0

        val outcomes = STUDY_MODE_ACTION_OPTIONS.map { option ->
            val result = StudySessionFlow.request(
                action = option.action,
                modeState = state,
                scope = scope,
                controlsEnabled = true,
                appendPrompt = { conversationId, text ->
                    assertEquals(12L, conversationId)
                    assertTrue(text.isNotBlank())
                    appendCalls++
                    true
                },
            )
            result.outcome
        }

        assertEquals(5, outcomes.size)
        assertTrue(outcomes.all { it == StudyPromptRequestOutcome.Appended })
        assertEquals(5, appendCalls)
        assertEquals(0, sendCalls)
        assertEquals(0, retrievalCalls)
        val methodNames = StudySessionFlow.javaClass.declaredMethods.map { it.name }
        assertTrue(methodNames.none { it.contains("send", ignoreCase = true) })
        assertTrue(methodNames.none { it.contains("generat", ignoreCase = true) })
        assertTrue(methodNames.none { it.contains("retriev", ignoreCase = true) })
    }

    @Test
    fun `J stale Study action appends nothing and only append failure can notify`() = runTest {
        val result = StudySessionFlow.requestQuestion(
            modeState = ChatInteractionModeState(ChatInteractionMode.STUDY, 12L, 0L),
            scope = ChatInteractionModeScope(99L, 0L),
            controlsEnabled = true,
            appendPrompt = { _, _ -> error("stale action must not append") },
        )

        assertEquals(StudyPromptRequestOutcome.Rejected, result.outcome)
        assertFalse(com.projectnuke.fusion.chat.StudyPromptFailureNoticePolicy.shouldShowFailureNotice(result.outcome))
    }

    @Test
    fun `K mode resets across conversation and fresh-chat epoch boundaries`() {
        val study = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            ChatInteractionModeScope(10L, 1L),
            ChatInteractionMode.STUDY,
            true,
        )

        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModePolicy.currentMode(study, 11L, 1L))
        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModePolicy.currentMode(study, 10L, 2L))
        assertEquals("입력하여 채팅", ChatInteractionModePolicy.composerPlaceholder(ChatInteractionMode.NORMAL))
    }

    @Test
    fun `L document shortcut and global selector enter the same Study state without prompt text`() {
        val suggestion = NextActionSuggestionPolicy.DOC_STUDY_QUESTIONS
        assertEquals("학습 모드로 계속", suggestion.label)
        assertTrue(suggestion.target is NextActionTarget.EnterStudyMode)
        assertEquals(null, suggestion.promptText)

        val global = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            ChatInteractionModeScope(0L, 8L),
            ChatInteractionMode.STUDY,
            true,
        )
        assertEquals(ChatInteractionMode.STUDY, global.mode)
        assertEquals(null, suggestion.promptText)
    }

    @Test
    fun `M Project Brief synthesis remains bounded snapshot-only and explicitly sendable`() {
        val brief = ProjectBriefSnapshot(
            projectId = 44L,
            projectName = "internal project name",
            conversationCount = 3,
            fileCount = 1,
            recentSummaryCount = 1,
            recentSummaries = listOf(
                com.projectnuke.fusion.chat.ProjectBriefConversationItem(
                    conversationId = 7L,
                    title = "결정 기록",
                    summaryPreview = "최근에 합의한 내용",
                    updatedAt = 10L,
                ),
            ),
        )
        val result = ProjectSynthesisPromptPolicy.build(brief, 44L, ProjectSynthesisMode.NEXT_STEPS)
        val available = com.projectnuke.fusion.chat.isProjectSynthesisAvailable(
            ProjectBriefLoadState.Ready(brief),
            selectedProjectId = 44L,
        )

        assertTrue(available)
        assertTrue(result is ProjectSynthesisPromptResult.Ready)
        val ready = result as ProjectSynthesisPromptResult.Ready
        assertTrue(ready.text.length <= ProjectSynthesisPromptPolicy.MAX_SYNTHESIS_PROMPT_CHARS)
        assertTrue(ready.text.contains("최근에 합의한 내용"))
        val methodNames = ProjectSynthesisPromptPolicy.javaClass.declaredMethods.map { it.name }
        assertTrue(methodNames.none { it.contains("send", ignoreCase = true) })
        assertTrue(methodNames.none { it.contains("generat", ignoreCase = true) })
    }

    @Test
    fun `N share and compare expose only canonical visible answers`() {        val visible = "현재 선택된 답변"
        val shared = com.projectnuke.fusion.chat.AssistantSharePayloadPolicy.buildAnswerOnly(visible)
        val compare = ResponseVersionComparePolicy.snapshot(
            entries = listOf(
                ResponseVersionCompareEntry(11L, 1, "이전 답변"),
                ResponseVersionCompareEntry(12L, 2, visible),
            ),
            activeMessageId = 12L,
        )

        assertTrue(shared is com.projectnuke.fusion.chat.AssistantShareBuildResult.Ready)
        val sharedReady = shared as com.projectnuke.fusion.chat.AssistantShareBuildResult.Ready
        assertEquals(visible, sharedReady.text)
        assertNotNull(compare)
        assertEquals(visible, ResponseVersionComparePolicy.activeEntry(checkNotNull(compare))?.visibleAnswer)
        assertFalse(sharedReady.text.contains("학습 모드"))
        assertFalse(checkNotNull(compare).entries.any { it.visibleAnswer.contains("학습 모드") })
    }

    // Dynamic semantic follow-up v1: representative host contract only (no
    // physical LiteRT inference). Quality, latency, thermal and battery
    // claims are explicitly out of scope; see the host/device matrix.
    @Test
    fun `O dynamic follow-up input is visible-only and output stays bounded`() {
        val messages = DynamicFollowUpPolicy.buildMessages(
            "SRAM과 DRAM의 차이가 뭐야?",
            "SRAM은 속도가 빠르고 DRAM은 집적도가 높습니다.",
        )
        assertNotNull(messages)
        val joined = messages!!.joinToString("\n") { it.content }
        assertTrue(joined.contains("SRAM"))
        assertFalse(joined.contains("FUSION_"))
        val parsed = DynamicFollowUpPolicy.parseQuestions(
            "1. SRAM은 왜 캐시에 주로 사용돼?\n" +
                "2. DRAM의 장점은 뭐야?\n" +
                "3. eDRAM은 어떤 메모리야?\n" +
                "4. 다섯 번째 질문은?\n" +
                "5. 여섯 번째 질문은?",
        )
        assertEquals(DynamicFollowUpPolicy.MAX_QUESTIONS, parsed.size)
    }

    @Test
    fun `P unsupported loaded premise fails closed while neutral questions survive`() {
        val request = "SRAM과 DRAM의 차이가 뭐야?"
        val answer = "SRAM은 속도가 빠르고 DRAM은 집적도가 높습니다. " +
            "SRAM은 주로 캐시 메모리에, DRAM은 주기억장치에 사용됩니다."
        assertTrue(
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM이 SRAM보다 빠른 이유는?"),
                request,
                answer,
            ).isEmpty(),
        )
        assertEquals(
            listOf("eDRAM은 무엇인가요?"),
            DynamicFollowUpPolicy.filterSupported(
                listOf("eDRAM은 무엇인가요?"),
                request,
                answer,
            ),
        )
    }

    @Test
    fun `Q dynamic follow-up tap seeds the composer and never auto-sends`() {
        val mapped = DynamicFollowUpPolicy.suggestions(
            listOf("SRAM은 왜 캐시에 주로 사용돼?", "DRAM의 장점은 뭐야?"),
        )
        assertEquals(2, mapped.size)
        for (suggestion in mapped) {
            val target = suggestion.target
            assertTrue(target is NextActionTarget.ComposerPrompt)
            assertEquals(suggestion.label, (target as NextActionTarget.ComposerPrompt).text)
        }
    }

    @Test
    fun `R Study suppresses ordinary dynamic follow-ups`() {
        assertFalse(
            DynamicFollowUpPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = true,
                isAutoGenerateEnabled = true,
            ),
        )
    }

    @Test
    fun `S background follow-up runtime stays single-flight under foreground admission`() = runBlocking {
        val background = launch { awaitCancellation() }
        val ticket = BackgroundRuntimeOwner.tryAcquire(background, 7L)
        try {
            assertNotNull(ticket)
            FusionRuntimeLock.withChatGeneration(
                conversationId = null,
                lifecycleRequestId = null,
            ) {
                val queued = launch { awaitCancellation() }
                try {
                    assertNull(BackgroundRuntimeOwner.tryAcquire(queued, 7L))
                } finally {
                    queued.cancel()
                    queued.join()
                }
                "foreground admitted"
            }
            background.join()
            assertTrue(background.isCancelled)
        } finally {
            ticket?.let { BackgroundRuntimeOwner.release(it) }
            if (background.isActive) {
                background.cancel()
                background.join()
            }
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    private fun requestSnapshot(mode: ChatInteractionMode): GenerationRequestSnapshot =
        GenerationRequestSnapshot(
            requestId = "demo-$mode",
            conversationId = 7L,
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            selectedModelId = "demo-model",
            selectedModelPath = null,
            modelFamily = ModelFamily.QWEN,
            settings = GenerationSettings(maxTokens = 256, kvCacheCapacityTokens = 8_192),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "광합성이 뭐야?",
            rawUserText = "광합성이 뭐야?",
            createdAt = 1L,
            interactionMode = mode,
        )

    private fun regenerationCapture(mode: ChatInteractionMode): RegenerationRequestCapture =
        RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 21L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "demo-model",
            selectedModelPath = null,
            selectedModelStableId = "demo-stable",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            interactionMode = mode,
        )

    // 4F golden-path contract: PRODUCT COMPOSITION through separate host-tested
    // ownership boundaries. Not an inference benchmark, not a quality metric,
    // not a device test, not provider-privacy proof.
    @Test
    fun `Z golden path composes local chat local docs study evidence and optional follow ups separately`() {
        // (1) First-run path choice is expressible and never implies a forced
        // download or forced cloud setup; browse-only is a first-class action.
        assertEquals(
            FusionOnboardingAction.COMPLETE_BROWSE,
            FusionOnboardingPolicy.resolveCompletionAction(
                FusionOnboardingUiState(
                    page = FusionOnboardingPage.NEXT_ACTION,
                    pathChoice = null,
                ),
            ),
        )
        assertEquals(
            FusionOnboardingAction.COMPLETE_LOCAL_ROUTE,
            FusionOnboardingPolicy.resolveCompletionAction(
                FusionOnboardingUiState(
                    page = FusionOnboardingPage.NEXT_ACTION,
                    pathChoice = FusionOnboardingPathChoice.LOCAL_MODEL,
                ),
            ),
        )

        // (2) No runnable local model is represented truthfully: the managed
        // model path resolver returns null for a missing/foreign/non-managed
        // file, so no surface can claim readiness.
        val modelRoot = java.io.File.createTempFile("fusion-golden", "").parentFile!!
        assertNull(
            com.projectnuke.fusion.util.ManagedModelPathPolicy.resolveRunnableModel(modelRoot, "/tmp/nonexistent.litertlm"),
        )

        // (3) Ordinary NORMAL chat policy stays clean: no Study instruction is
        // injected and the deterministic next-action gate remains eligible.
        assertEquals("", com.projectnuke.fusion.chat.StudyModePromptPolicy.modelInstruction(ChatInteractionMode.NORMAL))
        assertTrue(
            com.projectnuke.fusion.chat.NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )

        // (4) Local-document context preserves Local RAG identity as a typed
        // provenance, and the parser round-trips that identity.
        val ragRaw = com.projectnuke.fusion.retrieval.LocalRagSourceMetadata.append(
            "문서에서 가져온 답변",
            listOf(
                com.projectnuke.fusion.retrieval.LocalRagSourceEntry(
                    fileName = "참고자료.txt",
                    attachmentId = "/managed/attachments/uuid-참고자료.txt",
                    chunkIndex = 0,
                    startOffset = 0,
                    endOffset = 16,
                    contentHashHex = "a".repeat(64),
                    excerpt = "문서에서 가져온 답변",
                    rank = 1,
                    lexicalScore = 8,
                    semanticScore = null,
                ),
            ),
        )
        assertTrue(com.projectnuke.fusion.retrieval.LocalRagSourceMetadata.hasProvenance(ragRaw))
        assertEquals(
            "문서에서 가져온 답변",
            com.projectnuke.fusion.util.visibleMessageContent(ragRaw),
        )

        // (5) Study is an explicitly entered, separate interaction mode: its
        // host instruction is non-empty while NORMAL stays byte-identical.
        assertTrue(
            com.projectnuke.fusion.chat.StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY).isNotBlank(),
        )

        // (6) The canonical visible answer exposes evidence/share/version
        // behaviors without hidden local-RAG provenance leaking.
        assertEquals(
            "문서에서 가져온 답변",
            com.projectnuke.fusion.util.visibleMessageContent(ragRaw),
        )
        val shared = com.projectnuke.fusion.chat.AssistantSharePayloadPolicy.buildAnswerOnly("광합성을 설명해 주세요.")
        assertTrue(shared is com.projectnuke.fusion.chat.AssistantShareBuildResult.Ready)
        assertEquals(
            "광합성을 설명해 주세요.",
            (shared as com.projectnuke.fusion.chat.AssistantShareBuildResult.Ready).text,
        )

        // (7) A settled NORMAL local answer may expose bounded semantic
        // follow-up (max 3, parsed from the model output).
        assertEquals(
            3,
            DynamicFollowUpPolicy.parseQuestions(
                "1. 질문1?\n2. 질문2?\n3. 질문3?\n4. 질문4?",
            ).size,
        )

        // (8) A dynamic-question tap always produces an editable composer
        // prompt only (never auto-send).
        val tap = DynamicFollowUpPolicy.suggestions(listOf("왜 광합성이 중요해?")).single()
        assertTrue(tap.target is NextActionTarget.ComposerPrompt)
        assertEquals("왜 광합성이 중요해?", (tap.target as NextActionTarget.ComposerPrompt).text)

        // (9) Study suppresses ordinary dynamic follow-up.
        assertFalse(
            DynamicFollowUpPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = true,
                isAutoGenerateEnabled = true,
            ),
        )

        // (10) Disabling the automatic follow-up preference leaves the
        // deterministic action surface available.
        assertTrue(com.projectnuke.fusion.chat.DynamicFollowUpPreference.DEFAULT_AUTO_GENERATE)
        assertFalse(
            DynamicFollowUpPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
                isAutoGenerateEnabled = false,
            ),
        )
        assertTrue(
            com.projectnuke.fusion.chat.NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )

        // (11) None of the steps above implicitly authorizes external data
        // egress: an un-armed exact egress request is refused at the
        // transport boundary (0 outbound calls).
        val egress = DataEgressController()
        assertTrue(
            egress.authorize(
                config = AiProviderConfig(
                    id = "golden-provider",
                    type = AiProviderType.OPENAI,
                    displayName = "Golden",
                    baseUrl = "https://provider.example",
                    modelId = "m",
                    apiKeySecretId = null,
                ),
                request = AiChatRequest(messages = listOf(AiMessage(AiRole.USER, "광합성을 설명해 주세요."))),
                requestId = "unarmed-golden",
            ) is DataEgressController.Authorization.Refused,
        )
    }

}
