package com.projectnuke.fusion.competition

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.chat.excludePrivateDerivedTurns
import com.projectnuke.fusion.chat.isPrivateTaintedBranch
import com.projectnuke.fusion.data.ConservativeRuleEvidenceAssessor
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceCitationMapper
import com.projectnuke.fusion.data.EvidenceClock
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceTemporalPolicy
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageEvidenceReport
import com.projectnuke.fusion.data.EvidenceVerificationPlanner
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.DataEgressDestination
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.DataEgressScope
import com.projectnuke.fusion.privacy.PreparedEgressRequestFactory
import com.projectnuke.fusion.privacy.RequestPrivacyContext
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentChunker
import com.projectnuke.fusion.retrieval.LocalRagInputPolicy
import com.projectnuke.fusion.retrieval.LocalRagRetrieval
import com.projectnuke.fusion.retrieval.LocalRagSnapshotPolicy
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.retrieval.LocalSemanticRanker
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper
import com.projectnuke.fusion.trust.TrustRoutingRequest
import com.projectnuke.fusion.trust.TrustRouterClock
import com.projectnuke.fusion.translation.TranslationArtifact
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.ui.TrustRecoveryCoordinator
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.LinkedHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C7 host-only policy-contract evaluator.
 *
 * This test is intentionally a cross-feature measurement surface. It invokes
 * the existing deterministic production policy seams; it does not duplicate
 * their rules and it does not invoke an LLM, network, Android device, or
 * physical LiteRT runtime.
 */
class CompetitionTrustEvaluationTest {
    @Test
    fun `v1 labeled policy contract evaluation writes deterministic report`() {
        val scenarioText = loadScenarioText()
        val root = JSONObject(scenarioText)
        assertEquals(EVALUATION_VERSION, root.getString("evaluationVersion"))
        assertEquals(1, root.getInt("schemaVersion"))

        val cases = root.getJSONArray("cases")
        assertEquals(EXPECTED_SCENARIO_COUNT, cases.length())
        assertEquals(EXPECTED_CATEGORY_COUNTS, categoryCounts(cases))
        val scenarioPackSha256 = scenarioPackSha256(scenarioText)
        assertEquals(EXPECTED_SCENARIO_PACK_SHA256, scenarioPackSha256)
        val ids = mutableSetOf<String>()
        val results = buildList {
            for (index in 0 until cases.length()) {
                val scenario = cases.getJSONObject(index)
                val id = scenario.getString("id")
                assertTrue("duplicate or blank case id: $id", id.isNotBlank() && ids.add(id))
                assertTrue(scenario.getString("category").isNotBlank())
                assertTrue(scenario.getString("rationale").isNotBlank())
                assertTrue(scenario.getJSONArray("tags").length() > 0)
                add(runScenario(scenario))
            }
        }

        val metrics = calculateMetrics(results)
        val hardInvariantViolations = results
            .filter { it.safetyViolation }
            .map { it.id }
        val reportFiles = writeReports(results, metrics, hardInvariantViolations, scenarioPackSha256)

        assertTrue(
            "C7 policy contract failures; inspect ${reportFiles.first.absolutePath}",
            results.all { it.passed },
        )
        assertEquals("privacy_violation_count", 0, metrics["privacy_violation_count"])
        assertEquals("translation_authority_violation_count", 0, metrics["translation_authority_violation_count"])
        assertEquals("citation_canonicality_violation_count", 0, metrics["citation_canonicality_violation_count"])
    }

    @Test
    fun `temporal independent oracle rejects wrong expected class even when verdict matches`() {
        val scenario = JSONObject()
            .put("id", "temporal.independent-oracle-regression")
            .put("category", "TEMPORAL_POLICY")
            .put("tags", JSONArray(listOf("regression")))
            .put("rationale", "A wrong independently labeled temporal class must not pass through runtime-derived expectations.")
            .put(
                "evidence",
                JSONObject()
                    .put("claim", "Acme currently supports ProductX2.")
                    .put("expectedTemporalCategory", "TODAY")
                    .put("expectedVerdict", EvidenceVerdict.SUPPORTED_BY_EVIDENCE.name)
                    .put(
                        "source",
                        JSONObject()
                            .put("title", "Acme supports ProductX2.")
                            .put("snippet", "Acme supports ProductX2.")
                            .put("publishedAt", "2026-09-01"),
                    ),
            )

        val result = evaluateEvidence(scenario, temporal = true)

        assertFalse(result.passed)
        assertEquals("TODAY/${EvidenceVerdict.SUPPORTED_BY_EVIDENCE.name}", result.expected)
        assertEquals("CURRENT_STATE/${EvidenceVerdict.SUPPORTED_BY_EVIDENCE.name}", result.actual)
    }

    private fun loadScenarioText(): String {
        val stream = javaClass.classLoader?.getResourceAsStream(SCENARIO_RESOURCE)
            ?: error("missing scenario resource: $SCENARIO_RESOURCE")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun runScenario(scenario: JSONObject): CaseResult = runCatching {
        when (scenario.getString("category")) {
            "ROUTING_POLICY" -> evaluateRouting(scenario)
            "TEMPORAL_POLICY" -> evaluateEvidence(scenario, temporal = true)
            "EVIDENCE_VERIFICATION" -> evaluateEvidence(scenario, temporal = false)
            "CITATION_CANONICALITY" -> evaluateCitation(scenario)
            "PRIVACY_EGRESS" -> evaluatePrivacy(scenario)
            "TRANSLATION_AUTHORITY" -> evaluateTranslation(scenario)
            "LOCAL_RAG_POLICY" -> evaluateRag(scenario)
            "FAILURE_RECOVERY_POLICY" -> evaluateRecovery(scenario)
            else -> error("unsupported category: ${scenario.getString("category")}")
        }
    }.getOrElse { failure ->
        CaseResult(
            id = scenario.optString("id", "unknown"),
            category = scenario.optString("category", "unknown"),
            tags = scenario.optJSONArray("tags").toStringList(),
            rationale = scenario.optString("rationale", ""),
            expected = expectedFromScenario(scenario),
            actual = "ERROR:${failure::class.simpleName}:${failure.message.orEmpty()}",
            passed = false,
            seam = "scenario evaluation",
        )
    }

    private fun evaluateRouting(scenario: JSONObject): CaseResult {
        val input = scenario.getJSONObject("routing")
        val decision = TrustRouter.route(
            TrustRoutingRequest(
                userText = input.getString("query"),
                localContentPresent = input.optBoolean("localContentPresent", false),
                privateOrSensitiveContentPresent = input.optBoolean("privateOrSensitiveContentPresent", false),
                manualSearchEnabled = input.optBoolean("manualSearchEnabled", false),
            ),
            clock = FIXED_ROUTER_CLOCK,
        )
        val presentation = TrustRoutingPresentationMapper.map(decision).kind.name
        val actual = "${decision.route.name}/$presentation"
        val expected = "${input.getString("expectedRoute")}/${input.getString("expectedPresentation")}"
        return result(
            scenario = scenario,
            expected = expected,
            actual = actual,
            seam = "TrustRouter.route + TrustRoutingPresentationMapper.map",
        )
    }

    private fun evaluateEvidence(scenario: JSONObject, temporal: Boolean): CaseResult {
        val input = scenario.getJSONObject("evidence")
        val claimText = input.getString("claim")
        val source = evidenceSource(input.getJSONObject("source"), scenario.getString("id"))
        val candidate = EvidenceVerificationPlanner.candidates(claimText).firstOrNull()
            ?: error("no claim candidate for ${scenario.getString("id")}")
        val assessment = ConservativeRuleEvidenceAssessor(
            clock = EvidenceClock { FIXED_NOW_MILLIS },
            zoneId = UTC,
        ).assess(candidate, listOf(source))
        val actualTemporalCategory = EvidenceTemporalPolicy.classify(claimText).name
        val actualVerdict = assessment.verdict.name
        val actual = if (temporal) "$actualTemporalCategory/$actualVerdict" else actualVerdict
        val expected = if (temporal) {
            "${input.getString("expectedTemporalCategory")}/${input.getString("expectedVerdict")}"
        } else {
            input.getString("expectedVerdict")
        }
        return result(
            scenario = scenario,
            expected = expected,
            actual = actual,
            seam = "EvidenceVerificationPlanner.candidates + ConservativeRuleEvidenceAssessor",
        )
    }

    private fun evaluateCitation(scenario: JSONObject): CaseResult {
        val input = scenario.getJSONObject("citation")
        val answer = "Acme released ProductX2 in 2026."
        val message = MessageEntity(7001L, 70L, "assistant", answer, FIXED_NOW_MILLIS)
        val source = citationSource("E1", answer)
        val sourceIds = if (input.getString("variant") == "missing-source") listOf("E999") else listOf("E1")
        val verifierVersion = if (input.getString("variant") == "stale") "rules-v1" else EVIDENCE_VERIFIER_VERSION
        val report = EvidenceReport(
            schemaVersion = 1,
            answerSha256 = EvidenceFingerprint.canonicalAnswerHash(answer),
            checkedAt = FIXED_NOW_MILLIS,
            verifierVersion = verifierVersion,
            claims = listOf(EvidenceClaim("C1", answer, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, sourceIds, null)),
            sources = listOf(source),
        )
        val mapped = EvidenceCitationMapper.map(
            message = message,
            evidence = MessageEvidenceReport(message.id, report),
            nowMillis = FIXED_NOW_MILLIS,
        )
        val actual = if (mapped.isEmpty()) "EMPTY" else "MAPPED"
        return result(
            scenario = scenario,
            expected = input.getString("expected"),
            actual = actual,
            seam = "EvidenceCitationMapper.map",
            safetyViolation = input.getString("expected") == "EMPTY" && actual == "MAPPED",
        )
    }

    private fun evaluatePrivacy(scenario: JSONObject): CaseResult {
        val input = scenario.getJSONObject("privacy")
        val kind = input.getString("kind")
        val actual = when (kind) {
            "private-search-block" -> {
                val query = DataEgressPolicy.buildWebQuery(
                    input.getString("query"),
                    RequestPrivacyContext(
                        hasAttachments = true,
                        attachmentCount = 1,
                        fileNames = input.getJSONArray("fileNames").toStringList(),
                        userText = input.getString("query"),
                    ),
                )
                if (DataEgressPolicy.webQueryContainsPrivate(
                        query,
                        input.getJSONArray("excerpts").toStringList(),
                        input.getJSONArray("fileNames").toStringList(),
                    )
                ) "BLOCK" else "ALLOW"
            }
            "private-cloud-block" -> if (
                DataEgressPolicy.cloudEscalationAllowed(
                    RequestPrivacyContext(hasAttachments = true, hasLocalRagExcerpts = true),
                )
            ) "ALLOW" else "BLOCK"
            "exact-consent" -> exactConsent(input)
            "no-consent" -> {
                val provider = provider("provider-a")
                val transport = request("private request")
                val prepared = PreparedEgressRequestFactory.prepare(
                    requestId = "req-no-consent", destination = DataEgressDestination.EXTERNAL_AI,
                    provider = provider, exactRequest = transport, transmittedUserText = "private request",
                    transmittedHistoryText = "", privateExcerpts = listOf("private excerpt"),
                )
                val controller = DataEgressController()
                if (!controller.arm(prepared, null) &&
                    controller.authorize(provider, transport, prepared.requestId) is DataEgressController.Authorization.Refused
                ) "BLOCK" else "ALLOW"
            }
            "request-mismatch" -> {
                val hash = DataEgressPolicy.payloadHash("payload")
                val consent = DataEgressPolicy.makeConsent(
                    "c1", "req-old", "provider-a", hash, true, DataEgressScope.USER_QUERY_AND_HISTORY,
                )
                if (DataEgressPolicy.validateConsent(consent, "req-new", "provider-a", hash)) "ALLOW" else "BLOCK"
            }
            "provider-mismatch" -> {
                val hash = DataEgressPolicy.payloadHash("payload")
                val consent = DataEgressPolicy.makeConsent(
                    "c1", "req-1", "provider-a", hash, true, DataEgressScope.USER_QUERY_AND_HISTORY,
                )
                if (DataEgressPolicy.validateConsent(consent, "req-1", "provider-b", hash)) "ALLOW" else "BLOCK"
            }
            "payload-mutation" -> {
                val provider = provider("provider-a")
                val request = request("summarize")
                val first = PreparedEgressRequestFactory.prepare(
                    requestId = "req-mut", destination = DataEgressDestination.EXTERNAL_AI,
                    provider = provider, exactRequest = request, transmittedUserText = "summarize",
                    transmittedHistoryText = "", privateExcerpts = listOf("excerpt-a"),
                )
                val mutated = PreparedEgressRequestFactory.prepare(
                    requestId = "req-mut", destination = DataEgressDestination.EXTERNAL_AI,
                    provider = provider, exactRequest = request, transmittedUserText = "summarize",
                    transmittedHistoryText = "", privateExcerpts = listOf("excerpt-b"),
                )
                val controller = DataEgressController()
                val consent = DataEgressPolicy.makeConsent(
                    "c1", first.requestId, first.providerId, first.payloadHashHex, true,
                    DataEgressScope.USER_QUERY_AND_HISTORY,
                )
                val armed = controller.arm(mutated, consent)
                if (!armed && controller.authorize(provider, request, "req-mut") is DataEgressController.Authorization.Refused) "BLOCK" else "ALLOW"
            }
            "one-shot" -> {
                val provider = provider("provider-a")
                val transport = request("ordinary text")
                val prepared = PreparedEgressRequestFactory.prepare(
                    requestId = "req-one", destination = DataEgressDestination.EXTERNAL_AI,
                    provider = provider, exactRequest = transport, transmittedUserText = "ordinary text",
                    transmittedHistoryText = "",
                )
                val controller = DataEgressController()
                val armed = controller.arm(prepared, null)
                val first = controller.authorize(provider, transport, "req-one")
                val replay = controller.authorize(provider, transport, "req-one")
                if (armed && first is DataEgressController.Authorization.Granted &&
                    replay is DataEgressController.Authorization.Refused
                ) "ONE_GRANTED_ONE_BLOCKED" else "INVALID"
            }
            "cancellation-stale-permit" -> {
                val provider = provider("provider-a")
                val transport = request("cancel me")
                val prepared = PreparedEgressRequestFactory.prepare(
                    requestId = "req-cancel", destination = DataEgressDestination.EXTERNAL_AI,
                    provider = provider, exactRequest = transport, transmittedUserText = "cancel me",
                    transmittedHistoryText = "",
                )
                val controller = DataEgressController()
                val armed = controller.arm(prepared, null)
                controller.disarm("req-cancel")
                if (armed && controller.authorize(provider, transport, "req-cancel") is DataEgressController.Authorization.Refused) "BLOCK" else "ALLOW"
            }
            "private-derived-history" -> {
                val privateContent = LocalRagSourceMetadata.append(
                    "private answer",
                    listOf(LocalRagSourceEntry("report.txt", "att-1", 0, 0, 14, "hash", "private answer", 0, 1, null)),
                )
                val messages = listOf(
                    MessageEntity(1L, 1L, "user", "summarize", FIXED_NOW_MILLIS),
                    MessageEntity(2L, 1L, "assistant", privateContent, FIXED_NOW_MILLIS),
                )
                val excluded = excludePrivateDerivedTurns(messages)
                val historySafe = isPrivateTaintedBranch(messages) && excluded.none { it.content == privateContent }
                val cloudBlocked = !DataEgressPolicy.cloudEscalationAllowed(
                    RequestPrivacyContext(hasLocalRagExcerpts = true, localExcerptChars = 14),
                )
                if (historySafe && cloudBlocked) "EXCLUDED_AND_BLOCKED" else "LEAKED"
            }
            else -> error("unsupported privacy kind: $kind")
        }
        val expected = input.getString("expected")
        return result(
            scenario = scenario,
            expected = expected,
            actual = actual,
            seam = "DataEgressPolicy + PreparedEgressRequestFactory + DataEgressController",
            safetyViolation = when {
                expected == "BLOCK" -> actual != "BLOCK"
                expected == "EXCLUDED_AND_BLOCKED" -> actual == "LEAKED"
                else -> false
            },
        )
    }

    private fun exactConsent(input: JSONObject): String {
        val provider = provider(input.getString("providerId"))
        val transport = request(input.getString("userText"))
        val prepared = PreparedEgressRequestFactory.prepare(
            requestId = input.getString("requestId"),
            destination = DataEgressDestination.EXTERNAL_AI,
            provider = provider,
            exactRequest = transport,
            transmittedUserText = input.getString("userText"),
            transmittedHistoryText = "",
            privateExcerpts = listOf(input.getString("privateExcerpt")),
        )
        val consent = DataEgressPolicy.makeConsent(
            "consent-1", prepared.requestId, prepared.providerId, prepared.payloadHashHex,
            true, DataEgressScope.USER_QUERY_AND_HISTORY,
        )
        val controller = DataEgressController()
        val armed = controller.arm(prepared, consent)
        val authorized = controller.authorize(provider, transport, prepared.requestId)
        val replay = controller.authorize(provider, transport, prepared.requestId)
        return if (armed && authorized is DataEgressController.Authorization.Granted &&
            replay is DataEgressController.Authorization.Refused
        ) "GRANTED_ONCE" else "BLOCK"
    }

    private fun evaluateTranslation(scenario: JSONObject): CaseResult {
        val input = scenario.getJSONObject("translation")
        val original = input.getString("original")
        val variant = input.getString("variant")
        val baseTargetLanguage = if (variant == "target-language-mismatch") "ko" else input.getString("targetLanguage")
        val capabilities = com.projectnuke.fusion.translation.TranslationBackends.CURRENT
        val base = TranslationPolicy.makeArtifact(
            request = com.projectnuke.fusion.translation.TranslationRequest(
                originalText = original,
                targetLanguage = baseTargetLanguage,
                sourceLanguage = com.projectnuke.fusion.translation.TranslationSourceLanguage.Unspecified,
                route = com.projectnuke.fusion.translation.TranslationRoute.EVIDENCE_PRESENTATION,
                sourceIdentity = input.getString("sourceIdentity"),
                sourceUrl = "https://example.test/source",
            ),
            translatedText = input.getString("translated"),
            capabilities = capabilities,
        )
        if (variant == "target-language-mismatch") {
            requireNotNull(base) { "target-language-mismatch fixture must start from a valid supported-language artifact" }
        }
        val artifact = when (variant) {
            "stale-original" -> TranslationArtifact(
                originalText = "A different source.",
                originalHashHex = TranslationPolicy.sha256Hex(original),
                targetLanguage = "ko",
                translatedText = "오래된 번역.",
                implementationVersion = "local-v1",
                backendId = capabilities.backendId,
                requestedSourceLanguage = "unspecified",
                sourceIdentity = input.getString("sourceIdentity"),
            )
            "source-url-mutation" -> base?.copy(sourceUrl = "https://attacker.invalid/mutated")
            "target-language-mismatch" -> base?.copy(targetLanguage = input.getString("targetLanguage"))
            else -> base
        }
        val valid = artifact?.let { TranslationPolicy.validateArtifact(it, capabilities) } == true
        val canonical = artifact?.let(TranslationPolicy::canonicalEvidenceText)
        val containsHostMetadata = artifact?.let { TranslationPolicy.containsHostMetadata(it.translatedText) } == true
        val actual = when (variant) {
            "valid", "instructional-source" -> if (
                valid && canonical == original && !containsHostMetadata && artifact?.translatedText != original
            ) "ACCEPT" else "REJECT"
            "source-url-mutation" -> if (valid && canonical == original && !containsHostMetadata) "ACCEPT_CANONICAL_ORIGINAL" else "REJECT"
            else -> if (!valid) "REJECT" else "ACCEPT"
        }
        val expected = input.getString("expected")
        val authorityViolation = (expected == "REJECT" && actual == "ACCEPT") ||
            (valid && (canonical != original || containsHostMetadata))
        return result(
            scenario = scenario,
            expected = expected,
            actual = actual,
            seam = "TranslationPolicy.makeArtifact + validateArtifact + canonicalEvidenceText",
            safetyViolation = authorityViolation,
        )
    }

    private fun evaluateRag(scenario: JSONObject): CaseResult {
        val input = scenario.getJSONObject("rag")
        val kind = input.getString("kind")
        val actual = when (kind) {
            "supported-local" -> {
                val supported = LocalRagInputPolicy.isSupported(input.getString("mimeType"), input.getString("fileName"))
                val chunks = LocalDocumentChunker.chunk("doc-1", input.getString("fileName"), input.getString("content"))
                val selections = LocalRagRetrieval.retrieve(input.getString("query"), chunks, mapOf("doc-1" to "attachment-1"))
                if (supported && selections.isNotEmpty() && !LocalRagRetrieval.requiresNetwork()) "SELECTED_LOCAL" else "INVALID"
            }
            "snapshot-mismatch" -> {
                val chunks = LocalDocumentChunker.chunk("doc-2", "notes.txt", "apple orchard notes " .repeat(20))
                val selections = LocalRagRetrieval.retrieve(input.getString("query"), chunks)
                val snapshot = LocalRagSnapshotPolicy.snapshot("req-rag", selections)
                val mutated = chunks.mapIndexed { index, chunk ->
                    if (index == selections.firstOrNull()?.chunk?.chunkIndex) chunk.copy(text = "unrelated replacement") else chunk
                }
                if (selections.isNotEmpty() && !LocalRagSnapshotPolicy.validate(snapshot, mutated)) "INVALIDATED" else "INVALID"
            }
            "unrelated-semantic-fallback" -> {
                val lexical = LocalDocumentChunk("lexical", "a.txt", 0, 0, 20, "hash-a", "apple orchard harvest")
                val unrelated = LocalDocumentChunk("semantic", "b.txt", 0, 0, 20, "hash-b", "completely unrelated material")
                val selections = LocalRagRetrieval.retrieve(
                    "apple orchard",
                    listOf(lexical, unrelated),
                    topK = 5,
                    semanticRanker = object : LocalSemanticRanker {
                        override fun score(query: String, chunkText: String): Double =
                            if (chunkText == unrelated.text) 0.99 else 0.1
                    },
                )
                if (selections.firstOrNull()?.chunk?.docId == "lexical") "LEXICAL_FIRST" else "SEMANTIC_DISPLACED"
            }
            "unsupported-input" -> if (
                !LocalRagInputPolicy.isSupported(input.getString("mimeType"), input.getString("fileName"))
            ) "UNSUPPORTED" else "SUPPORTED"
            else -> error("unsupported RAG kind: $kind")
        }
        return result(
            scenario = scenario,
            expected = input.getString("expected"),
            actual = actual,
            seam = "LocalRagInputPolicy + LocalDocumentChunker + LocalRagRetrieval + LocalRagSnapshotPolicy",
        )
    }

    private fun evaluateRecovery(scenario: JSONObject): CaseResult {
        val input = scenario.getJSONObject("recovery")
        val coordinator = TrustRecoveryCoordinator()
        val actual = when (input.getString("kind")) {
            "search-failed" -> coordinator.searchFailure(
                searchDecision(), attempted = false, usableResult = false, requestId = "r-failed",
            )?.javaClass?.simpleName.orEmpty()
            "search-no-usable-result" -> coordinator.searchFailure(
                searchDecision(), attempted = true, usableResult = false, requestId = "r-empty",
            )?.javaClass?.simpleName.orEmpty()
            "cloud-no-auto-retry" -> {
                val failure = coordinator.cloudFailure("pending-1")
                if (failure.preservesLocalAnswer && !failure.autoRetried && !failure.switchedProvider) {
                    "PRESERVE_LOCAL_EXPLICIT_RETRY"
                } else "AUTO_MUTATION"
            }
            "retry-single-flight" -> {
                val first = coordinator.tryBeginSearchRetry("r1")
                val second = coordinator.tryBeginSearchRetry("r1")
                coordinator.endSearchRetry("r1")
                val afterRelease = coordinator.tryBeginSearchRetry("r1")
                coordinator.endSearchRetry("r1")
                if (first && !second && afterRelease) "ONE_AT_A_TIME" else "CONCURRENT"
            }
            else -> error("unsupported recovery kind")
        }
        return result(
            scenario = scenario,
            expected = input.getString("expected"),
            actual = actual,
            seam = "TrustRecoveryCoordinator",
        )
    }

    private fun searchDecision() = com.projectnuke.fusion.trust.TrustRoutingDecision(
        TrustRoute.SEARCH,
        com.projectnuke.fusion.trust.TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
        emptySet(),
    )

    private fun result(
        scenario: JSONObject,
        expected: String,
        actual: String,
        seam: String,
        safetyViolation: Boolean = false,
    ): CaseResult = CaseResult(
        id = scenario.getString("id"),
        category = scenario.getString("category"),
        tags = scenario.getJSONArray("tags").toStringList(),
        rationale = scenario.getString("rationale"),
        expected = expected,
        actual = actual,
        passed = expected == actual,
        seam = seam,
        safetyViolation = safetyViolation,
    )

    private fun expectedFromScenario(scenario: JSONObject): String = when (scenario.getString("category")) {
        "ROUTING_POLICY" -> {
            val input = scenario.optJSONObject("routing") ?: return "unknown"
            "${input.optString("expectedRoute", "")}/${input.optString("expectedPresentation", "")}"
        }
        "EVIDENCE_VERIFICATION" -> scenario.optJSONObject("evidence")?.optString("expectedVerdict", "unknown") ?: "unknown"
        "TEMPORAL_POLICY" -> scenario.optJSONObject("evidence")?.let {
            "${it.optString("expectedTemporalCategory", "unknown")}/${it.optString("expectedVerdict", "unknown")}"
        } ?: "unknown"
        "CITATION_CANONICALITY" -> scenario.optJSONObject("citation")?.optString("expected", "unknown") ?: "unknown"
        "PRIVACY_EGRESS" -> scenario.optJSONObject("privacy")?.optString("expected", "unknown") ?: "unknown"
        "TRANSLATION_AUTHORITY" -> scenario.optJSONObject("translation")?.optString("expected", "unknown") ?: "unknown"
        "LOCAL_RAG_POLICY" -> scenario.optJSONObject("rag")?.optString("expected", "unknown") ?: "unknown"
        "FAILURE_RECOVERY_POLICY" -> scenario.optJSONObject("recovery")?.optString("expected", "unknown") ?: "unknown"
        else -> "unknown"
    }

    private fun evidenceSource(input: JSONObject, id: String): EvidenceSource = EvidenceSource(
        id = input.optString("id", "E-$id"),
        title = input.getString("title"),
        source = input.optString("source", "fixture"),
        url = input.optString("url", "https://example.test/$id"),
        snippet = input.getString("snippet"),
        publishedAt = input.optString("publishedAt").takeIf { it.isNotBlank() },
        providerType = input.optString("providerType", WebSearchProviderType.FREE_DEFAULT.name),
        providerDisplayName = input.optString("providerDisplayName", "C7 fixture"),
        queryUsed = input.optString("queryUsed", "fixture"),
    )

    private fun citationSource(id: String, text: String) = EvidenceSource(
        id = id,
        title = text,
        source = "C7 fixture",
        url = "https://example.test/$id",
        snippet = text,
        publishedAt = "2026-09-01",
        providerType = WebSearchProviderType.FREE_DEFAULT.name,
        providerDisplayName = "C7 fixture",
        queryUsed = "ProductX2 release",
    )

    private fun provider(id: String) = AiProviderConfig(
        id = id,
        type = AiProviderType.OPENAI,
        displayName = "C7 fixture provider",
        baseUrl = "https://example.test/api",
        modelId = "fixture-model",
        apiKeySecretId = "fixture-secret",
    )

    private fun request(text: String) = AiChatRequest(listOf(AiMessage(AiRole.USER, text)), 0.1, 16)

    private fun calculateMetrics(results: List<CaseResult>): Map<String, Int> {
        fun count(predicate: (CaseResult) -> Boolean) = results.count(predicate)
        fun correct(predicate: (CaseResult) -> Boolean) = results.count { predicate(it) && it.passed }
        val routing = results.filter { it.category == "ROUTING_POLICY" }
        val evidence = results.filter { it.category == "EVIDENCE_VERIFICATION" }
        val temporal = results.filter { it.category == "TEMPORAL_POLICY" }
        val privacy = results.filter { it.category == "PRIVACY_EGRESS" }
        val translation = results.filter { it.category == "TRANSLATION_AUTHORITY" }
        val citation = results.filter { it.category == "CITATION_CANONICALITY" }
        val rag = results.filter { it.category == "LOCAL_RAG_POLICY" }
        val recovery = results.filter { it.category == "FAILURE_RECOVERY_POLICY" }
        val routeName: (CaseResult) -> String = { it.expected.substringBefore('/') }
        val actualRoute: (CaseResult) -> String = { it.actual.substringBefore('/') }
        val supported = evidence.filter { it.expected == EvidenceVerdict.SUPPORTED_BY_EVIDENCE.name }
        val negative = evidence.filter { it.expected != EvidenceVerdict.SUPPORTED_BY_EVIDENCE.name }
        return linkedMapOf(
            "routing_decision_correct" to correct { it in routing },
            "routing_decision_total" to routing.size,
            "search_required_correct" to routing.count { routeName(it) == "SEARCH" && actualRoute(it) == "SEARCH" },
            "search_required_total" to routing.count { routeName(it) == "SEARCH" },
            "abstain_required_correct" to routing.count { routeName(it) == "ABSTAIN" && actualRoute(it) == "ABSTAIN" },
            "abstain_required_total" to routing.count { routeName(it) == "ABSTAIN" },
            "stable_local_correct" to routing.count { "stable-local" in it.tags && routeName(it) == "LOCAL" && actualRoute(it) == "LOCAL" },
            "stable_local_total" to routing.count { "stable-local" in it.tags },
            "evidence_supported_correct" to supported.count { it.passed },
            "evidence_supported_total" to supported.size,
            "evidence_negative_correct" to negative.count { it.passed },
            "evidence_negative_total" to negative.size,
            "false_support_count" to negative.count { it.actual == EvidenceVerdict.SUPPORTED_BY_EVIDENCE.name },
            "temporal_classification_correct" to temporal.count {
                it.expected.substringBefore('/') == it.actual.substringBefore('/')
            },
            "temporal_classification_total" to temporal.size,
            "temporal_verdict_correct" to temporal.count {
                it.expected.substringAfter('/') == it.actual.substringAfter('/')
            },
            "temporal_verdict_total" to temporal.size,
            "temporal_policy_correct" to temporal.count { it.passed },
            "temporal_policy_total" to temporal.size,
            "privacy_contract_correct" to privacy.count { it.passed },
            "privacy_contract_total" to privacy.size,
            "privacy_contract_failure_count" to privacy.count { !it.passed },
            "privacy_violation_count" to privacy.count { it.safetyViolation },
            "translation_authority_correct" to translation.count { it.passed },
            "translation_authority_total" to translation.size,
            "translation_contract_failure_count" to translation.count { !it.passed },
            "translation_authority_violation_count" to translation.count { it.safetyViolation },
            "citation_canonicality_correct" to citation.count { it.passed },
            "citation_canonicality_total" to citation.size,
            "citation_contract_failure_count" to citation.count { !it.passed },
            "citation_canonicality_violation_count" to citation.count { it.safetyViolation },
            "rag_policy_correct" to rag.count { it.passed },
            "rag_policy_total" to rag.size,
            "failure_recovery_policy_correct" to recovery.count { it.passed },
            "failure_recovery_policy_total" to recovery.size,
        )
    }

    private fun writeReports(
        results: List<CaseResult>,
        metrics: Map<String, Int>,
        hardInvariantViolations: List<String>,
        scenarioPackSha256: String,
    ): Pair<File, File> {
        val repoRoot = findRepositoryRoot()
        val provenance = gitProvenance(repoRoot)
        val directory = File(repoRoot, "app/build/reports/fusion-trust-eval").apply { mkdirs() }
        val summaryJson = File(directory, "summary.json")
        val summaryMd = File(directory, "summary.md")
        val categoryCounts = LinkedHashMap<String, Int>()
        results.forEach { categoryCounts[it.category] = (categoryCounts[it.category] ?: 0) + 1 }
        val failed = results.filterNot { it.passed }
        val jsonMetrics = JSONObject()
        metrics.forEach { (name, value) -> jsonMetrics.put(name, value) }
        val failedJson = JSONArray()
        failed.forEach { item ->
            failedJson.put(
                JSONObject()
                    .put("id", item.id)
                    .put("expected", item.expected)
                    .put("actual", item.actual)
                    .put("productionSeam", item.seam)
                    .put("rootCause", "Not proven by this harness; investigate the production seam before changing labels."),
            )
        }
        val categoryJson = JSONObject()
        categoryCounts.forEach { (name, count) -> categoryJson.put(name, count) }
        val summary = JSONObject()
            .put("evaluationVersion", EVALUATION_VERSION)
            .put("sourceGitSha", provenance.sourceGitSha ?: JSONObject.NULL)
            .put("scenarioPackSha256", scenarioPackSha256)
            .put("workingTreeClean", provenance.workingTreeClean ?: JSONObject.NULL)
            .put("provenanceReproducible", provenance.reproducible)
            .put("scenarioResource", SCENARIO_RESOURCE)
            .put("scenarioCount", results.size)
            .put("categoryCounts", categoryJson)
            .put("metrics", jsonMetrics)
            .put("failedCases", failedJson)
            .put("hardInvariantViolations", JSONArray(hardInvariantViolations))
            .put("scope", JSONArray(SCOPE))
            .put("nonScope", JSONArray(NON_SCOPE))
            .put("claimBoundary", JSONObject()
                .put("policyContractResult", "Deterministic labeled policy seam results only.")
                .put("modelQualityResult", "NOT_MEASURED"))
        summaryJson.writeText(summary.toString(2), Charsets.UTF_8)

        val md = buildString {
            appendLine("# Fusion competition trust evaluation v1")
            appendLine()
            appendLine("- evaluation version: `$EVALUATION_VERSION`")
            appendLine("- source Git SHA: `${provenance.sourceGitSha ?: "UNKNOWN"}`")
            appendLine("- scenario-pack SHA-256: `$scenarioPackSha256`")
            appendLine("- working tree clean: `${provenance.workingTreeClean?.toString() ?: "UNKNOWN"}`")
            appendLine("- provenance reproducible: `${provenance.reproducible}`")
            appendLine("- scenario count: `${results.size}`")
            appendLine("- scenario resource: `$SCENARIO_RESOURCE`")
            appendLine()
            appendLine("## Category counts")
            categoryCounts.forEach { (category, count) -> appendLine("- $category: $count") }
            appendLine()
            appendLine("## Metrics")
            metrics.forEach { (name, value) ->
                if (name.endsWith("_count")) appendLine("- $name = $value")
                else if (name.endsWith("_correct")) {
                    val totalName = name.removeSuffix("_correct") + "_total"
                    appendLine("- $name = $value/${metrics[totalName] ?: 0}")
                }
            }
            appendLine()
            appendLine("## Failed scenarios")
            if (failed.isEmpty()) appendLine("- none")
            failed.forEach { item ->
                appendLine("- `${item.id}`: expected `${item.expected}`, actual `${item.actual}`; seam: ${item.seam}")
            }
            appendLine()
            appendLine("## Hard invariants")
            appendLine("- privacy_violation_count == 0: ${metrics["privacy_violation_count"] == 0}")
            appendLine("- translation_authority_violation_count == 0: ${metrics["translation_authority_violation_count"] == 0}")
            appendLine("- citation_canonicality_violation_count == 0: ${metrics["citation_canonicality_violation_count"] == 0}")
            appendLine()
            appendLine("## Claim boundary")
            appendLine("POLICY CONTRACT RESULT: the deterministic production policy seams returned the expected outcomes for these labeled scenarios.")
            appendLine("MODEL QUALITY RESULT: NOT MEASURED. This report is not a hallucination rate, factual-accuracy percentage, or translation-quality score.")
            appendLine()
            appendLine("## Scope")
            SCOPE.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Non-scope")
            NON_SCOPE.forEach { appendLine("- $it") }
        }
        summaryMd.writeText(md, Charsets.UTF_8)
        return summaryJson to summaryMd
    }

    private fun findRepositoryRoot(): File {
        val workingDirectory = System.getProperty("user.dir").orEmpty()
        var current = File(workingDirectory).absoluteFile
        repeat(6) {
            if (File(current, ".git").exists() && File(current, "app").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        return File(workingDirectory).absoluteFile
    }

    private fun categoryCounts(cases: JSONArray): Map<String, Int> = buildMap {
        for (index in 0 until cases.length()) {
            val category = cases.getJSONObject(index).getString("category")
            put(category, (this[category] ?: 0) + 1)
        }
    }

    private fun scenarioPackSha256(text: String): String = sha256Hex(
        text.replace("\r\n", "\n").replace('\r', '\n'),
    )

    private fun sha256Hex(text: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun gitProvenance(repoRoot: File): GitProvenance {
        val shaResult = runGit(repoRoot, "rev-parse", "HEAD")
        val statusResult = runGit(repoRoot, "status", "--porcelain", "--untracked-files=all")
        val sha = shaResult.output.trim().takeIf {
            shaResult.exitCode == 0 && it.matches(Regex("[0-9a-f]{40}"))
        }
        val workingTreeClean = statusResult.output.takeIf { statusResult.exitCode == 0 }?.isBlank()
        return GitProvenance(
            sourceGitSha = sha,
            workingTreeClean = workingTreeClean,
            reproducible = sha != null && workingTreeClean == true,
        )
    }

    private fun runGit(repoRoot: File, vararg arguments: String): CommandResult = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments.toList())
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        CommandResult(process.waitFor(), output)
    }.getOrElse { CommandResult(-1, it.message.orEmpty()) }

    private data class CaseResult(
        val id: String,
        val category: String,
        val tags: List<String>,
        val rationale: String,
        val expected: String,
        val actual: String,
        val passed: Boolean,
        val seam: String,
        val safetyViolation: Boolean = false,
    )

    private data class CommandResult(val exitCode: Int, val output: String)

    private data class GitProvenance(
        val sourceGitSha: String?,
        val workingTreeClean: Boolean?,
        val reproducible: Boolean,
    )

    private companion object {
        const val EVALUATION_VERSION = "FUSION_COMPETITION_POLICY_CONTRACT_EVAL_V1"
        const val SCENARIO_RESOURCE = "competition/fusion_trust_eval_v1.json"
        const val EXPECTED_SCENARIO_COUNT = 50
        const val EXPECTED_SCENARIO_PACK_SHA256 = "8cec778bbbeae5c9e5f52ccbfc87e05d3ff6a12ab2a7720a96aee946dd636979"
        val EXPECTED_CATEGORY_COUNTS = linkedMapOf(
            "ROUTING_POLICY" to 10,
            "TEMPORAL_POLICY" to 4,
            "EVIDENCE_VERIFICATION" to 9,
            "CITATION_CANONICALITY" to 3,
            "PRIVACY_EGRESS" to 10,
            "TRANSLATION_AUTHORITY" to 6,
            "LOCAL_RAG_POLICY" to 4,
            "FAILURE_RECOVERY_POLICY" to 4,
        )
        val UTC: ZoneId = ZoneId.of("UTC")
        val FIXED_NOW_MILLIS: Long = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
        val FIXED_ROUTER_CLOCK = object : TrustRouterClock {
            override fun currentYear(): Int = 2026
            override fun currentDate(): String = "2026-09-05"
        }
        val SCOPE = listOf(
            "ROUTING_POLICY",
            "TEMPORAL_POLICY",
            "EVIDENCE_VERIFICATION",
            "CITATION_CANONICALITY",
            "PRIVACY_EGRESS",
            "TRANSLATION_AUTHORITY",
            "LOCAL_RAG_POLICY",
            "FAILURE_RECOVERY_POLICY",
        )
        val NON_SCOPE = listOf(
            "actual LLM factual accuracy",
            "actual Korean generation quality",
            "actual translation quality",
            "TTFT, tokens/sec, RAM, thermal, and battery",
            "native LiteRT behavior",
            "physical search/network quality",
            "physical device, ADB, instrumentation, Kanana A/B, model conversion, and cloud APIs",
        )
    }
}

private fun JSONArray?.toStringList(): List<String> = if (this == null) {
    emptyList()
} else {
    (0 until length()).map { getString(it) }
}
