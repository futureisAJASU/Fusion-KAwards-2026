package com.projectnuke.fusion.data

import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.SearchSourceMetadataLimits
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class EvidencePlanningTest {

    @Test
    fun `splitter handles Korean English mixed text and list boundaries`() {
        val claims = EvidenceVerificationPlanner.candidates(
            "첫 번째 사실입니다. 두 번째 사실입니다!\nThird fact.\n- 네 번째 사실입니다?\n1. Fifth fact."
        )

        assertEquals(5, claims.size)
        assertEquals(listOf("C1", "C2", "C3", "C4", "C5"), claims.map { it.id })
        assertEquals("첫 번째 사실입니다.", claims[0].text)
        assertEquals("Third fact.", claims[2].text)
        assertEquals("네 번째 사실입니다?", claims[3].text)
        assertEquals("Fifth fact.", claims[4].text)
    }

    @Test
    fun `splitter protects decimals URLs abbreviations and inline code`() {
        val claims = EvidenceVerificationPlanner.candidates(
            "Pi is 3.14. Visit https://example.com/a.b?x=1. Next fact. Dr. Smith reported it. Use `3.14` in code."
        )

        assertEquals(5, claims.size)
        assertEquals("Pi is 3.14.", claims[0].text)
        assertTrue(claims[1].text.contains("https://example.com/a.b?x=1"))
        assertEquals("Dr. Smith reported it.", claims[3].text)
        assertEquals("Use `3.14` in code.", claims[4].text)
    }

    @Test
    fun `fenced code is excluded and inline code remains deterministic`() {
        val answer = "Before.\n```kotlin\nval x = 3.14\nprintln(x)\n```\nAfter `x.y`."
        val first = EvidenceVerificationPlanner.candidates(answer)
        val second = EvidenceVerificationPlanner.candidates(answer)

        assertEquals(first, second)
        assertEquals(listOf("Before.", "After `x.y`."), first.map { it.text })
    }

    @Test
    fun `candidate output is bounded and IDs are stable`() {
        val answer = (1..60).joinToString("\n") { "Fact $it is recorded." }
        val candidates = EvidenceVerificationPlanner.candidates(answer)

        assertEquals(EvidencePlanningLimits.MAX_CLAIM_CANDIDATES, candidates.size)
        assertEquals("C1", candidates.first().id)
        assertEquals("C48", candidates.last().id)
        assertEquals((1..48).toList(), candidates.map { it.ordinal })
        assertEquals(candidates, EvidenceVerificationPlanner.candidates(answer))
    }

    @Test
    fun `overlength split segments are reported without truncating claim text`() {
        val exact = "가".repeat(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH)
        val over = "가".repeat(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH + 1)
        val exactWindow = EvidenceClaimSplitter.splitWindow(exact)
        val onlyOver = EvidenceClaimSplitter.splitWindow(over)

        assertEquals(listOf(exact), exactWindow.claims)
        assertFalse(exactWindow.overlengthSegmentsExcluded)
        assertTrue(onlyOver.claims.isEmpty())
        assertTrue(onlyOver.overlengthSegmentsExcluded)
        assertFalse(onlyOver.truncated)
    }

    @Test
    fun `overlength exclusions remain visible before and after valid claims`() {
        val over = "사실".repeat((EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH / 2) + 1)
        val valid = "NVIDIA announced revenue of 10 billion dollars in 2026."
        val before = EvidenceVerificationPlanner.plan("$over\n$valid")
        val after = EvidenceVerificationPlanner.plan("$valid\n$over")

        assertEquals(listOf(valid), before.allCandidates.map { it.text })
        assertEquals(listOf(valid), after.allCandidates.map { it.text })
        assertTrue(before.overlengthSegmentsExcluded)
        assertTrue(after.overlengthSegmentsExcluded)
        assertFalse(before.candidateWindowTruncated)
        assertFalse(after.candidateWindowTruncated)
    }

    @Test
    fun `candidate and individual length omissions are independent at the 48 candidate boundary`() {
        val ordinary = (1..48).joinToString("\n") { "The process remains stable in batch $it." }
        val over = "가".repeat(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH + 1)
        val validLate = "NVIDIA announced revenue of 10 billion dollars in 2026."
        val after48 = EvidenceVerificationPlanner.plan("$ordinary\n$over")
        val beforeValid = EvidenceVerificationPlanner.plan("${(1..48).joinToString("\n") { over }}\n$validLate")

        assertEquals(48, after48.allCandidates.size)
        assertFalse(after48.candidateWindowTruncated)
        assertTrue(after48.overlengthSegmentsExcluded)
        assertEquals(listOf(validLate), beforeValid.allCandidates.map { it.text })
        assertFalse(beforeValid.candidateWindowTruncated)
        assertTrue(beforeValid.overlengthSegmentsExcluded)
    }

    @Test
    fun `surrogate pairs use the same Kotlin length bound and unbroken input is not retained`() {
        val emoji = "\uD83D\uDE80"
        val exactly = emoji.repeat(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH / 2)
        val over = exactly + "x"
        val extremelyLongUnbroken = "x".repeat(1_000_000)
        assertEquals(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH, exactly.length)
        assertEquals(1, EvidenceClaimSplitter.splitWindow(exactly).claims.size)
        val overWindow = EvidenceClaimSplitter.splitWindow(over)
        assertTrue(overWindow.claims.isEmpty())
        assertTrue(overWindow.overlengthSegmentsExcluded)
        val hugeWindow = EvidenceClaimSplitter.splitWindow(extremelyLongUnbroken)
        assertTrue(hugeWindow.claims.isEmpty())
        assertTrue(hugeWindow.overlengthSegmentsExcluded)
    }

    @Test
    fun `large fenced code is excluded without claiming an omitted factual segment`() {
        val code = "x".repeat(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH * 4)
        val window = EvidenceClaimSplitter.splitWindow("Before.\n```\n$code\n```\nAfter.")

        assertEquals(listOf("Before.", "After."), window.claims)
        assertFalse(window.overlengthSegmentsExcluded)
        assertFalse(window.truncated)
    }

    @Test
    fun `late high priority claims remain eligible after the first 24 split candidates`() {
        val lowPriority = (1..30).joinToString("\n") { "The process remains stable in batch $it." }
        val lateFact = "NVIDIA announced revenue of 10 billion dollars in 2026."
        val plan = EvidenceVerificationPlanner.plan("$lowPriority\n$lateFact")

        assertEquals("C31", plan.claims.first().candidate.id)
        assertEquals(lateFact, plan.claims.first().candidate.text)
        assertEquals(ClaimPriority.HIGH, plan.claims.first().candidate.priority)
    }

    @Test
    fun `candidate window remains bounded and keeps its omitted-claim warning boundary`() {
        fun answer(count: Int) = (1..count).joinToString("\n") { "The process remains stable in batch $it." }

        assertEquals(47, EvidenceVerificationPlanner.candidates(answer(47)).size)
        assertEquals(48, EvidenceVerificationPlanner.candidates(answer(48)).size)

        val at49 = EvidenceVerificationPlanner.plan(
            answer(48) + "\nNVIDIA announced revenue of 10 billion dollars in 2026.",
        )
        val at100 = EvidenceVerificationPlanner.plan(answer(100))

        assertFalse(EvidenceVerificationPlanner.plan(answer(47)).candidateWindowTruncated)
        assertFalse(EvidenceVerificationPlanner.plan(answer(48)).candidateWindowTruncated)
        assertEquals(48, at49.allCandidates.size)
        assertEquals(48, at49.allCandidates.last().ordinal)
        assertFalse(at49.allCandidates.any { it.text.startsWith("NVIDIA announced") })
        assertTrue(at49.candidateWindowTruncated)
        assertEquals(48, at100.allCandidates.size)
        assertEquals(48, at100.allCandidates.last().ordinal)
        assertTrue(at100.candidateWindowTruncated)
    }

    @Test
    fun `classifier distinguishes factual subjective predictive and nonfactual text`() {
        val factual = EvidenceClaimClassifier.classifyWithReason("삼성전자는 2026년에 HBM4 양산을 시작했다.")
        val subjective = EvidenceClaimClassifier.classifyWithReason("이 전략은 매우 좋다.")
        val prediction = EvidenceClaimClassifier.classifyWithReason("내년에는 가격이 오를 수도 있다.")
        val greeting = EvidenceClaimClassifier.classifyWithReason("안녕하세요!")
        val shipment = EvidenceClaimClassifier.classifyWithReason("출하량은 전년 대비 37% 증가했다.")

        assertEquals(ClaimClassification.CHECKABLE_FACT, factual.classification)
        assertEquals(ClaimPriority.HIGH, factual.priority)
        assertEquals(ClaimClassification.SUBJECTIVE_OR_NORMATIVE, subjective.classification)
        assertEquals(ClaimPriority.NONE, subjective.priority)
        assertEquals(ClaimClassification.INFERENCE_OR_PREDICTION, prediction.classification)
        assertEquals(ClaimPriority.NONE, prediction.priority)
        assertEquals(ClaimClassification.NON_FACTUAL, greeting.classification)
        assertEquals(ClaimPriority.NONE, greeting.priority)
        assertEquals(ClaimClassification.CHECKABLE_FACT, shipment.classification)
        assertEquals(ClaimPriority.HIGH, shipment.priority)
    }

    @Test
    fun `pure code is nonfactual with bounded reason`() {
        val decision = EvidenceClaimClassifier.classifyWithReason("`println(3.14)`")

        assertEquals(ClaimClassification.NON_FACTUAL, decision.classification)
        assertEquals(ClaimClassificationReason.CODE_OR_STRUCTURAL_CONTENT, decision.reason)
    }

    @Test
    fun `existing metadata is parsed through current source contract`() {
        val source = source(title = "삼성전자 HBM4 2026", url = "https://example.com/a").copy(
            snippet = "삼성전자는 2026년에 HBM4 양산을 시작했다.",
            publishedAt = "2026-01-01",
            providerType = WebSearchProviderType.NAVER,
            providerDisplayName = "Naver",
            queryUsed = "삼성전자 HBM4 2026",
        )
        val answer = appendSearchSourcesMetadata(
            "삼성전자는 2026년에 HBM4 양산을 시작했다.",
            listOf(source),
        )
        val plan = EvidenceVerificationPlanner.plan(answer)

        assertEquals(1, plan.existingSources.size)
        assertEquals("E1", plan.existingSources.single().id)
        assertEquals(source.title, plan.existingSources.single().title)
        assertEquals(source.url, plan.existingSources.single().url)
        assertEquals(source.snippet, plan.existingSources.single().snippet)
        assertEquals(source.publishedAt, plan.existingSources.single().publishedAt)
        assertEquals(source.providerType, plan.existingSources.single().providerType)
        assertEquals(source.providerDisplayName, plan.existingSources.single().providerDisplayName)
        assertEquals(source.queryUsed, plan.existingSources.single().queryUsed)
        assertEquals(listOf("E1"), plan.claims.single().existingSourceCandidateIds)
        assertFalse(plan.claims.single().needsTargetedSearch)
    }

    @Test
    fun `source matching ranks exact anchors and excludes unrelated sources`() {
        val claim = "삼성전자는 2026년에 HBM4를 12GB로 공급했다."
        val sources = listOf(
            source("unrelated", "https://example.com/1", "요리 레시피와 여행 정보"),
            source("partial", "https://example.com/2", "삼성전자 HBM4 공급 2026"),
            source("exact", "https://example.com/3", "삼성전자 HBM4 2026 12GB 공급"),
        )
        val plan = EvidenceVerificationPlanner.plan(claim, sources)
        val matches = plan.claims.single().existingSourceCandidateIds

        assertEquals(listOf("E3", "E2"), matches)
        assertFalse(matches.contains("E1"))
    }

    @Test
    fun `weak generic candidate does not suppress targeted search`() {
        val source = source(
            "Acme released annual earnings",
            snippet = "Acme released its annual earnings report.",
        )
        val plan = EvidenceVerificationPlanner.plan("Acme released a new processor.", listOf(source))

        assertEquals(listOf("E1"), plan.claims.single().existingSourceCandidateIds)
        assertTrue(plan.claims.single().needsTargetedSearch)
        assertEquals(listOf("C1"), plan.targetedSearchRequests.map { it.claimId })
    }

    @Test
    fun `partial critical-anchor candidate does not suppress targeted search`() {
        val source = source(
            "Acme financial results 2026",
            snippet = "Acme financial results for 2026.",
        )
        val plan = EvidenceVerificationPlanner.plan(
            "Acme released ProductX2 12GB in 2026.",
            listOf(source),
        )

        assertEquals(listOf("E1"), plan.claims.single().existingSourceCandidateIds)
        assertTrue(plan.claims.single().needsTargetedSearch)
        assertEquals(1, plan.targetedSearchRequests.size)
    }

    @Test
    fun `strong critical-anchor candidate suppresses redundant search`() {
        val source = source(
            "Acme ProductX2 12GB 2026 release",
            snippet = "Acme released ProductX2 12GB in 2026.",
        )
        val plan = EvidenceVerificationPlanner.plan(
            "Acme released ProductX2 12GB in 2026.",
            listOf(source),
        )

        assertEquals(listOf("E1"), plan.claims.single().existingSourceCandidateIds)
        assertFalse(plan.claims.single().needsTargetedSearch)
        assertTrue(plan.targetedSearchRequests.isEmpty())
    }

    @Test
    fun `weak existing candidates consume targeted-search budget in plan order`() {
        val sources = listOf(
            source("Acme annual earnings", snippet = "Acme released annual earnings."),
            source("Beta annual earnings", snippet = "Beta released annual earnings."),
        )
        val answer = "Acme released a new processor.\n" +
            "Beta released a new processor.\n" +
            "Gamma announced a 2026 release.\n" +
            "Delta announced a 2026 release."
        val plan = EvidenceVerificationPlanner.plan(answer, sources)

        assertEquals(listOf("C1", "C2", "C3"), plan.targetedSearchRequests.map { it.claimId })
        assertEquals(EvidencePlanningLimits.MAX_TARGETED_SEARCH_REQUESTS, plan.targetedSearchRequests.size)
    }

    @Test
    fun `duplicate sources are stable and source candidates are bounded`() {
        val sources = buildList {
            repeat(7) { index ->
                add(source("same claim $index", "https://example.com/$index", "Company 2026 release product"))
            }
            add(source("duplicate url", "https://example.com/0", "Company 2026 release product"))
            add(source("duplicate content", null, "Company 2026 release product"))
            add(source("duplicate content", null, "Company 2026 release product"))
        }
        val plan = EvidenceVerificationPlanner.plan("Company released a product in 2026.", sources)

        assertEquals(8, plan.existingSources.size)
        assertEquals(listOf("E1", "E2", "E3", "E4", "E5"), plan.claims.single().existingSourceCandidateIds)
        assertEquals(plan, EvidenceVerificationPlanner.plan("Company released a product in 2026.", sources))
    }

    @Test
    fun `planning selects high before medium and low with ordinal tie break`() {
        val plan = EvidenceVerificationPlanner.plan(
            "The weather is pleasant.\nAcme released a product in 2026.\nCompany operates factories."
        )

        assertEquals(listOf("C2", "C3", "C1"), plan.claims.map { it.candidate.id })
        assertEquals(listOf(ClaimPriority.HIGH, ClaimPriority.MEDIUM, ClaimPriority.LOW), plan.claims.map { it.candidate.priority })
    }

    @Test
    fun `claim and targeted search budgets are enforced`() {
        val answer = (1..8).joinToString("\n") { "Company$it announced a 2026 release of product $it." }
        val plan = EvidenceVerificationPlanner.plan(answer)

        assertEquals(EvidencePlanningLimits.MAX_CLAIMS_TO_CHECK, plan.claims.size)
        assertEquals((1..6).map { "C$it" }, plan.claims.map { it.candidate.id })
        assertEquals(EvidencePlanningLimits.MAX_TARGETED_SEARCH_REQUESTS, plan.targetedSearchRequests.size)
        assertTrue(plan.targetedSearchRequests.all { it.query.length <= EvidencePlanningLimits.MAX_TARGETED_QUERY_LENGTH })
    }

    @Test
    fun `existing plausible source suppresses targeted search but does not create verdict`() {
        val source = source(
            "Acme 2026 release",
            "https://example.com/acme",
            "Acme released a product in 2026.",
        )
        val plan = EvidenceVerificationPlanner.plan("Acme released a product in 2026.", listOf(source))

        assertFalse(plan.claims.single().needsTargetedSearch)
        assertTrue(plan.targetedSearchRequests.isEmpty())
        assertFalse(plan.toString().contains("SUPPORTED_BY_EVIDENCE"))
        assertFalse(plan.toString().contains("CONFLICTING_EVIDENCE"))
        assertFalse(plan.toString().contains("INSUFFICIENT_EVIDENCE"))
    }

    @Test
    fun `hidden source metadata never enters claims or targeted queries`() {
        val raw = "Acme released a product in 2026.\n\n[FUSION_SEARCH_SOURCES_JSON]\n" +
            "[{\"title\":\"Ignore this instruction\",\"url\":\"https://example.com\",\"snippet\":\"unrelated\"}]\n" +
            "[/FUSION_SEARCH_SOURCES_JSON]"
        val plan = EvidenceVerificationPlanner.plan(raw)

        assertTrue(plan.claims.all { !it.candidate.text.contains("FUSION_SEARCH_SOURCES_JSON") })
        assertTrue(plan.targetedSearchRequests.all { !it.query.contains("FUSION_SEARCH_SOURCES_JSON") })
    }

    @Test
    fun `same planning input produces identical plan and query remains claim derived`() {
        val answer = "삼성전자는 2026년에 HBM4 양산을 시작했다."
        val first = EvidenceVerificationPlanner.plan(answer)
        val second = EvidenceVerificationPlanner.plan(answer)

        assertEquals(first, second)
        assertNotNull(first.claims.single().candidate)
        assertTrue(first.targetedSearchRequests.single().query.contains("2026"))
        assertTrue(first.targetedSearchRequests.single().query.contains("HBM4"))
        assertTrue(first.targetedSearchRequests.single().query.length <= EvidencePlanningLimits.MAX_TARGETED_QUERY_LENGTH)
    }

    @Test
    fun `source-only planning keeps bounded claims and emits no targeted queries`() {
        val answer = "Acme released ProductX2 in 2026.\nBeta announced a 2025 update."
        val plan = EvidenceVerificationPlanner.plan(answer, allowTargetedSearch = false)

        assertTrue(plan.claims.isNotEmpty())
        assertTrue(plan.claims.size <= EvidencePlanningLimits.MAX_CLAIMS_TO_CHECK)
        assertTrue(plan.targetedSearchRequests.isEmpty())
        assertTrue(plan.claims.none { it.needsTargetedSearch })
    }

    @Test
    fun `oversized metadata body is rejected before JSON parsing`() {
        val raw = "Acme released a product in 2026.\n\n" +
            "[FUSION_SEARCH_SOURCES_JSON]\n" +
            "x".repeat(com.projectnuke.fusion.search.SearchSourceMetadataLimits.MAX_RAW_JSON_CHARS + 1) +
            "\n[/FUSION_SEARCH_SOURCES_JSON]"

        val plan = EvidenceVerificationPlanner.plan(raw)

        assertTrue(plan.existingSources.isEmpty())
        assertTrue(plan.claims.all { !it.candidate.text.contains("FUSION_SEARCH_SOURCES_JSON") })
    }

    @Test
    fun `metadata source count and fields are bounded fail closed`() {
        val tooMany = metadataJson((0..SearchSourceMetadataLimits.MAX_SOURCES).map { index ->
            source("source-$index", "https://example.com/$index")
        })
        assertTrue(parseSearchSourcesMetadata(tooMany).isEmpty())

        val oversizedProvider = metadataJson(
            listOf(source("provider").copy(providerDisplayName = "x".repeat(SearchSourceMetadataLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH + 1)))
        )
        val oversizedCategory = metadataJson(
            listOf(source("category").copy(searchCategory = "x".repeat(SearchSourceMetadataLimits.MAX_SEARCH_CATEGORY_LENGTH + 1)))
        )
        val oversizedHint = metadataJson(
            listOf(source("hint").copy(qualityHints = listOf("x".repeat(SearchSourceMetadataLimits.MAX_QUALITY_HINT_LENGTH + 1))))
        )
        val tooManyHints = metadataJson(
            listOf(source("hints").copy(qualityHints = List(SearchSourceMetadataLimits.MAX_QUALITY_HINTS + 1) { "hint" }))
        )

        assertTrue(parseSearchSourcesMetadata(oversizedProvider).isEmpty())
        assertTrue(parseSearchSourcesMetadata(oversizedCategory).isEmpty())
        assertTrue(parseSearchSourcesMetadata(oversizedHint).isEmpty())
        assertTrue(parseSearchSourcesMetadata(tooManyHints).isEmpty())
    }

    @Test
    fun `direct existing sources are bounded before dedupe and matching`() {
        val sources = (0..EvidencePlanningLimits.MAX_EXISTING_SOURCES).map { index ->
            source("source-$index", "https://example.com/$index")
        }
        val plan = EvidenceVerificationPlanner.plan("Acme released a product in 2026.", sources)

        assertEquals(EvidencePlanningLimits.MAX_EXISTING_SOURCES, plan.existingSources.size)
        assertEquals((0 until EvidencePlanningLimits.MAX_EXISTING_SOURCES).map { "source-$it" }, plan.existingSources.map { it.title })
    }

    @Test
    fun `direct existing source fields are deterministically truncated`() {
        val huge = "x".repeat(SearchSourceMetadataLimits.MAX_RAW_JSON_CHARS + 1)
        val plan = EvidenceVerificationPlanner.plan(
            "Acme released a product in 2026.",
            listOf(
                source("bounded").copy(
                    title = huge,
                    source = huge,
                    url = huge,
                    snippet = huge,
                    publishedAt = huge,
                    providerDisplayName = huge,
                    searchCategory = huge,
                    queryUsed = huge,
                    qualityHints = List(SearchSourceMetadataLimits.MAX_QUALITY_HINTS + 5) { huge },
                )
            ),
        )
        val snapshot = plan.existingSources.single()

        assertEquals(SearchSourceMetadataLimits.MAX_TITLE_LENGTH, snapshot.title.length)
        assertEquals(SearchSourceMetadataLimits.MAX_SOURCE_LENGTH, snapshot.source?.length)
        assertEquals(SearchSourceMetadataLimits.MAX_URL_LENGTH, snapshot.url?.length)
        assertEquals(SearchSourceMetadataLimits.MAX_SNIPPET_LENGTH, snapshot.snippet?.length)
        assertEquals(SearchSourceMetadataLimits.MAX_PUBLISHED_AT_LENGTH, snapshot.publishedAt?.length)
        assertEquals(SearchSourceMetadataLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH, snapshot.providerDisplayName.length)
        assertEquals(SearchSourceMetadataLimits.MAX_SEARCH_CATEGORY_LENGTH, snapshot.searchCategory?.length)
        assertEquals(SearchSourceMetadataLimits.MAX_QUERY_USED_LENGTH, snapshot.queryUsed.length)
        assertEquals(SearchSourceMetadataLimits.MAX_QUALITY_HINTS, snapshot.qualityHints.size)
        assertTrue(snapshot.qualityHints.all { it.length <= SearchSourceMetadataLimits.MAX_QUALITY_HINT_LENGTH })
    }

    private fun source(
        title: String,
        url: String? = "https://example.com/source",
        snippet: String = title,
    ): WebSearchSource = WebSearchSource(
        title = title,
        source = "Example source",
        url = url,
        snippet = snippet,
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "Provider",
        searchCategory = null,
        queryUsed = title,
        rank = 1,
    )

    private fun metadataJson(sources: List<WebSearchSource>): String {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("title", source.title)
                    .put("source", source.source)
                    .put("url", source.url)
                    .put("snippet", source.snippet)
                    .put("publishedAt", source.publishedAt)
                    .put("providerType", source.providerType.name)
                    .put("providerDisplayName", source.providerDisplayName)
                    .put("searchCategory", source.searchCategory)
                    .put("queryUsed", source.queryUsed)
                    .put("rank", source.rank)
                    .put("score", source.score)
                    .put("qualityHints", JSONArray(source.qualityHints))
            )
        }
        return "[FUSION_SEARCH_SOURCES_JSON]\n${array}\n[/FUSION_SEARCH_SOURCES_JSON]"
    }
}
