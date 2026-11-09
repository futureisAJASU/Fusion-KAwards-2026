package com.projectnuke.fusion.data

import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.SearchSourceMetadataLimits
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import java.util.Locale

/** Hard bounds for the pure C1-B1 planning layer. */
object EvidencePlanningLimits {
    const val MAX_CLAIM_CANDIDATES = 48
    const val MAX_CLAIMS_TO_CHECK = 6
    const val MAX_TARGETED_SEARCH_REQUESTS = 3
    const val MAX_EXISTING_SOURCE_CANDIDATES_PER_CLAIM = 5
    const val MAX_EXISTING_SOURCES = SearchSourceMetadataLimits.MAX_SOURCES
    const val MAX_CLAIM_TEXT_LENGTH = 10_000
    const val MAX_TARGETED_QUERY_LENGTH = 240
    const val MAX_QUERY_TERMS = 12
}

/**
 * Shared currentness signal used by planning and durable report presentation.
 *
 * Delegates to [EvidenceTemporalPolicy]: there is a single canonical marker list
 * so classification, verification freshness, and durable expiry always agree.
 */
object EvidenceCurrentSensitivity {
    fun isCurrentSensitiveClaim(text: String): Boolean =
        EvidenceTemporalPolicy.isCurrentSensitive(text)
}

enum class ClaimClassification {
    CHECKABLE_FACT,
    INFERENCE_OR_PREDICTION,
    SUBJECTIVE_OR_NORMATIVE,
    NON_FACTUAL,
}

enum class ClaimPriority {
    HIGH,
    MEDIUM,
    LOW,
    NONE,
}

enum class ClaimClassificationReason {
    DATE_OR_TIME,
    NUMERIC_FACT,
    NAMED_ENTITY_FACT,
    CURRENT_OR_RECENT_FACT,
    ATTRIBUTION_OR_ANNOUNCEMENT,
    EXPLICIT_PREDICTION,
    SUBJECTIVE_LANGUAGE,
    QUESTION_OR_COMMAND,
    CODE_OR_STRUCTURAL_CONTENT,
    TOO_SHORT,
    FACTUAL_DECLARATIVE,
    CREATIVE_OR_NARRATIVE,
}

data class EvidenceClaimCandidate(
    val id: String,
    val text: String,
    val ordinal: Int,
    val classification: ClaimClassification,
    val priority: ClaimPriority,
    val reason: ClaimClassificationReason,
)

data class EvidenceCandidateWindow(
    val candidates: List<EvidenceClaimCandidate>,
    val truncated: Boolean,
    /** At least one non-fenced split segment exceeded the individual claim bound. */
    val overlengthSegmentsExcluded: Boolean = false,
)

/** A source snapshot already attached to the answer; it is never fetched here. */
data class EvidencePlanningSource(
    val id: String,
    val title: String,
    val source: String?,
    val url: String?,
    val snippet: String?,
    val publishedAt: String?,
    val providerType: WebSearchProviderType,
    val providerDisplayName: String,
    val searchCategory: String?,
    val queryUsed: String,
    val rank: Int,
    val qualityHints: List<String>,
)

data class PlannedClaim(
    val candidate: EvidenceClaimCandidate,
    val existingSourceCandidateIds: List<String>,
    val needsTargetedSearch: Boolean,
)

data class ExistingSourceMatch(
    val sourceIds: List<String>,
    val hasStrongCandidate: Boolean,
)

data class TargetedSearchRequest(
    val claimId: String,
    val query: String,
)

data class VerificationPlan(
    val claims: List<PlannedClaim>,
    val existingSources: List<EvidencePlanningSource>,
    val targetedSearchRequests: List<TargetedSearchRequest>,
    /** Bounded canonical candidates retained for visible budget exhaustion. */
    val allCandidates: List<EvidenceClaimCandidate> = emptyList(),
    /** True when the answer contained additional split claims beyond the retained window. */
    val candidateWindowTruncated: Boolean = false,
    /** True when a split segment was excluded rather than truncated to fit a claim. */
    val overlengthSegmentsExcluded: Boolean = false,
)

/** Splits canonical visible answer text without attempting full NLP parsing. */
object EvidenceClaimSplitter {

    data class SplitWindow(
        val claims: List<String>,
        val truncated: Boolean,
        val overlengthSegmentsExcluded: Boolean = false,
    )

    fun split(canonicalVisibleAnswer: String): List<String> {
        return splitWindow(canonicalVisibleAnswer).claims
    }

    fun splitWindow(canonicalVisibleAnswer: String): SplitWindow {
        val input = removeFencedCode(canonicalVisibleAnswer)
        if (input.isBlank()) return SplitWindow(emptyList(), truncated = false)

        val retained = ArrayList<String>(EvidencePlanningLimits.MAX_CLAIM_CANDIDATES)
        var start = 0
        var index = 0
        var inInlineCode = false
        var truncated = false
        var overlengthSegmentsExcluded = false

        fun addPiece(endExclusive: Int) {
            val range = cleanedRange(input, start, endExclusive) ?: return
            val (cleanStart, cleanEnd) = range
            if (cleanEnd - cleanStart > EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH) {
                overlengthSegmentsExcluded = true
                return
            }
            if (retained.size == EvidencePlanningLimits.MAX_CLAIM_CANDIDATES) {
                truncated = true
                return
            }
            retained += input.substring(cleanStart, cleanEnd)
        }

        while (index < input.length) {
            val character = input[index]
            if (character == '`') {
                inInlineCode = !inInlineCode
                index++
                continue
            }
            if (!inInlineCode) {
                val isLineBoundary = character == '\n'
                val isSentenceBoundary =
                    character == '.' || character == '?' || character == '!'
                if (isLineBoundary) {
                    addPiece(index)
                    start = index + 1
                } else if (isSentenceBoundary && isBoundaryPunctuation(input, index)) {
                    addPiece(index + 1)
                    start = index + 1
                }
            }
            index++
        }
        addPiece(input.length)

        return SplitWindow(
            claims = retained,
            truncated = truncated,
            overlengthSegmentsExcluded = overlengthSegmentsExcluded,
        )
    }

    /** Trim and remove the existing list marker without allocating an oversized segment. */
    private fun cleanedRange(input: String, from: Int, until: Int): Pair<Int, Int>? {
        var start = from
        var end = until
        while (start < end && input[start].isWhitespace()) start++
        while (end > start && input[end - 1].isWhitespace()) end--
        if (start == end) return null

        val prefixStart = start
        when {
            input[start] == '-' || input[start] == '*' || input[start] == '+' || input[start] == '•' -> {
                start++
                if (start < end && input[start].isWhitespace()) {
                    while (start < end && input[start].isWhitespace()) start++
                } else start = prefixStart
            }
            input[start] in '0'..'9' -> {
                var cursor = start
                while (cursor < end && input[cursor] in '0'..'9') cursor++
                if (cursor < end && (input[cursor] == '.' || input[cursor] == ')')) {
                    cursor++
                    if (cursor < end && input[cursor].isWhitespace()) {
                        while (cursor < end && input[cursor].isWhitespace()) cursor++
                        start = cursor
                    }
                }
            }
        }
        while (end > start && input[end - 1].isWhitespace()) end--
        return if (start < end) start to end else null
    }

    private fun isBoundaryPunctuation(input: String, index: Int): Boolean {
        if (input[index] == '.' && isListMarkerPeriod(input, index)) return false
        if (input[index] == '.' && index > 0 && index + 1 < input.length &&
            input[index - 1].isDigit() && input[index + 1].isDigit()
        ) {
            return false
        }
        if (input[index] == '.' && isCommonAbbreviation(input, index)) return false

        val next = input.getOrNull(index + 1) ?: return true
        return next.isWhitespace() || next == ')' || next == ']' || next == '}' || next == '”' || next == '"'
    }

    private fun isListMarkerPeriod(input: String, index: Int): Boolean {
        val lineStart = input.lastIndexOf('\n', index - 1).let { if (it < 0) 0 else it + 1 }
        var start = lineStart
        while (start < index && input[start].isWhitespace()) start++
        val digits = index - start
        return digits in 1..12 && (start until index).all { input[it].isDigit() }
    }

    private fun isCommonAbbreviation(input: String, index: Int): Boolean {
        val start = (index - 5).coerceAtLeast(0)
        val ending = input.substring(start, index + 1).lowercase(Locale.ROOT)
        return ending.endsWith("e.g.") || ending.endsWith("i.e.") ||
            ending.endsWith("mr.") || ending.endsWith("mrs.") ||
            ending.endsWith("ms.") || ending.endsWith("dr.") ||
            ending.endsWith("prof.") || ending.endsWith("etc.") ||
            ending.endsWith("vs.")
    }

    private fun removeFencedCode(input: String): String {
        if (!input.contains("```")) return input
        // A large fenced block is discarded; avoid reserving its entire source
        // length in the output buffer before discovering that it is code.
        val output = StringBuilder(minOf(input.length, 16_384))
        var index = 0
        while (index < input.length) {
            val opensFence = input.startsWith("```", index) &&
                (index == 0 || input[index - 1] == '\n')
            if (!opensFence) {
                output.append(input[index])
                index++
                continue
            }
            val close = input.indexOf("```", index + 3)
            if (close < 0) break
            index = close + 3
        }
        return output.toString()
    }
}

data class ClaimClassificationDecision(
    val classification: ClaimClassification,
    val priority: ClaimPriority,
    val reason: ClaimClassificationReason,
)

/** Heuristic checkability classification; it never asserts truth or falsity. */
object EvidenceClaimClassifier {

    private val YearRegex = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
    private val DateRegex = Regex("""(?:19|20)\d{2}|\d{1,4}\s*(?:년|월|일)|오늘|어제|내일|내년|지난해|전년""")
    private val NumberRegex = Regex("""\d+(?:[.,]\d+)?\s*%?""")
    private val NamedLatinRegex = Regex("""\b(?:[A-Z][A-Za-z0-9-]{2,}|[A-Za-z]{2,}\d[A-Za-z0-9-]*)\b""")
    private val FactualEnglishTerms = listOf(
        "announced", "released", "supports", "supplies", "began production",
        "reported", "increased", "decreased", "was founded", "contains", "includes",
        "located", "is located", "manufactures", "produces",
    )
    private val FactualKoreanTerms = listOf(
        "발표했다", "출시했다", "지원한다", "공급한다", "양산했다", "기록했다",
        "증가했다", "감소했다", "포함한다", "위치한다", "설립됐다", "생산한다",
        "제조한다", "시작했다",
    )
    private val AdditionalFactualKoreanTerms = setOf(
        "\uCD9C\uC2DC", "\uCD9C\uC2DC\uD588\uB2E4", "\uCD9C\uC2DC\uD55C\uB2E4", "\uCD9C\uC2DC\uB410\uB2E4", "\uCD9C\uC2DC\uD588\uC2B5\uB2C8\uB2E4",
        "\uBC1C\uD45C", "\uBC1C\uD45C\uD588\uB2E4", "\uBC1C\uD45C\uD55C\uB2E4", "\uBC1C\uD45C\uB410\uB2E4",
        "\uC9C0\uC6D0", "\uC9C0\uC6D0\uD55C\uB2E4", "\uC9C0\uC6D0\uD588\uB2E4", "\uC9C0\uC6D0\uD569\uB2C8\uB2E4",
        "\uC591\uC0B0", "\uC591\uC0B0\uD588\uB2E4", "\uC591\uC0B0\uD55C\uB2E4",
        "\uC0DD\uC0B0", "\uC0DD\uC0B0\uD588\uB2E4", "\uC0DD\uC0B0\uD55C\uB2E4",
        "\uC81C\uC870", "\uC81C\uC870\uD588\uB2E4", "\uC81C\uC870\uD55C\uB2E4",
        "\uC99D\uAC00", "\uC99D\uAC00\uD588\uB2E4", "\uC99D\uAC00\uD588\uB2E4\uB294",
        "\uAC10\uC18C", "\uAC10\uC18C\uD588\uB2E4",
        "\uD3EC\uD568", "\uD3EC\uD568\uD55C\uB2E4", "\uD3EC\uD568\uD588\uB2E4",
        "\uC124\uB9BD", "\uC124\uB9BD\uD588\uB2E4", "\uC124\uB9BD\uB410\uB2E4",
    )
    private val PredictionTerms = listOf(
        "아마", "일 것이다", "할 수도", "될 수도", "가능성이", "전망", "예상",
        "내년에는", "will ", "would ", "could ", "might ", " may ", "likely", "forecast",
        "predict",
    )
    private val SubjectiveTerms = listOf(
        "내 생각에는", "내가 보기에는", "좋은 전략", "추천한다", "추천해", "좋다", "나쁘다",
        "최고", "멋지", "아름답", "선호", "in my opinion", "i think", "recommend", "best",
        "great", "should", "must",
    )
    private val CommandTerms = listOf(
        "알려줘", "찾아줘", "검색해줘", "추천해줘", "해줘", "하세요", "하십시오", "해주세요",
        "please ", "tell me", "find ", "show me", "explain ", "what ", "why ", "how ",
        "can you ",
    )
    private val GreetingTerms = listOf("안녕하세요", "안녕", "hello", "hi", "hey", "greetings")
    private val CreativeTerms = listOf("옛날에", "동화", "소설", "시 속", "상상", "once upon a time", "fictional", "in this story")
    private val EntitySuffixes = listOf("전자", "주식회사", "기업", "회사", "대학", "은행", "연구소", "정부", "제품", "모델")
    private val AdditionalEntitySuffixes = listOf("\uC804\uC790")

    fun classify(text: String): Pair<ClaimClassification, ClaimPriority> =
        classifyWithReason(text).let { it.classification to it.priority }

    fun classifyWithReason(text: String): ClaimClassificationDecision {
        val trimmed = text.trim()
        val normalized = trimmed.lowercase(Locale.ROOT)
        val tokenCount = ClaimMatching.tokenize(trimmed).size

        if (trimmed.isEmpty() || tokenCount < 2) {
            return ClaimClassificationDecision(
                ClaimClassification.NON_FACTUAL,
                ClaimPriority.NONE,
                ClaimClassificationReason.TOO_SHORT,
            )
        }
        if (isCodeOrStructuralContent(trimmed)) {
            return ClaimClassificationDecision(
                ClaimClassification.NON_FACTUAL,
                ClaimPriority.NONE,
                ClaimClassificationReason.CODE_OR_STRUCTURAL_CONTENT,
            )
        }
        if (GreetingTerms.any { normalized.startsWith(it) }) {
            return ClaimClassificationDecision(
                ClaimClassification.NON_FACTUAL,
                ClaimPriority.NONE,
                ClaimClassificationReason.TOO_SHORT,
            )
        }
        if (trimmed.endsWith('?') || CommandTerms.any { normalized.startsWith(it) || normalized.contains(it) }) {
            return ClaimClassificationDecision(
                ClaimClassification.NON_FACTUAL,
                ClaimPriority.NONE,
                ClaimClassificationReason.QUESTION_OR_COMMAND,
            )
        }
        if (SubjectiveTerms.any { normalized.contains(it) }) {
            return ClaimClassificationDecision(
                ClaimClassification.SUBJECTIVE_OR_NORMATIVE,
                ClaimPriority.NONE,
                ClaimClassificationReason.SUBJECTIVE_LANGUAGE,
            )
        }
        if (PredictionTerms.any { normalized.contains(it) }) {
            return ClaimClassificationDecision(
                ClaimClassification.INFERENCE_OR_PREDICTION,
                ClaimPriority.NONE,
                ClaimClassificationReason.EXPLICIT_PREDICTION,
            )
        }
        if (CreativeTerms.any { normalized.contains(it) }) {
            return ClaimClassificationDecision(
                ClaimClassification.NON_FACTUAL,
                ClaimPriority.NONE,
                ClaimClassificationReason.CREATIVE_OR_NARRATIVE,
            )
        }

        val hasYearOrDate = YearRegex.containsMatchIn(trimmed) || DateRegex.containsMatchIn(normalized)
        val hasNumber = NumberRegex.containsMatchIn(trimmed)
        val hasNamedEntity = hasNamedEntity(trimmed)
        val hasCurrentSignal = EvidenceCurrentSensitivity.isCurrentSensitiveClaim(trimmed)
        val hasFactualPredicate = FactualEnglishTerms.any { normalized.contains(it) } ||
            FactualKoreanTerms.any { normalized.contains(it) } ||
            AdditionalFactualKoreanTerms.any { normalized.contains(it) }
        val hasDeclarativeShape = hasFactualPredicate ||
            trimmed.endsWith('.') || trimmed.endsWith('!') ||
            normalized.endsWith("다") || normalized.endsWith("이다") ||
            normalized.endsWith("있다") || normalized.endsWith("없다")

        if (hasYearOrDate || hasNumber || hasNamedEntity || hasFactualPredicate || hasDeclarativeShape) {
            val reason = when {
                hasCurrentSignal -> ClaimClassificationReason.CURRENT_OR_RECENT_FACT
                hasFactualPredicate && hasNumber -> ClaimClassificationReason.NUMERIC_FACT
                hasYearOrDate -> ClaimClassificationReason.DATE_OR_TIME
                hasNamedEntity -> ClaimClassificationReason.NAMED_ENTITY_FACT
                hasNumber -> ClaimClassificationReason.NUMERIC_FACT
                hasFactualPredicate -> ClaimClassificationReason.ATTRIBUTION_OR_ANNOUNCEMENT
                else -> ClaimClassificationReason.FACTUAL_DECLARATIVE
            }
            val highPriority = hasCurrentSignal ||
                (hasYearOrDate && hasNamedEntity) ||
                (hasNumber && hasFactualPredicate) ||
                hasFactualPredicate
            val priority = when {
                highPriority -> ClaimPriority.HIGH
                hasNamedEntity -> ClaimPriority.MEDIUM
                else -> ClaimPriority.LOW
            }
            return ClaimClassificationDecision(ClaimClassification.CHECKABLE_FACT, priority, reason)
        }

        return ClaimClassificationDecision(
            ClaimClassification.NON_FACTUAL,
            ClaimPriority.NONE,
            ClaimClassificationReason.TOO_SHORT,
        )
    }

    private fun hasNamedEntity(text: String): Boolean {
        val firstWordStart = text.indexOfFirst { it.isLetterOrDigit() }.coerceAtLeast(0)
        if (NamedLatinRegex.findAll(text).any { match ->
                match.range.first != firstWordStart || match.value !in CommonSentenceInitials
            }
        ) return true
        return Regex("[가-힣]{2,}")
            .findAll(text)
            .any { token ->
                (EntitySuffixes + AdditionalEntitySuffixes).any { suffix -> token.value.contains(suffix) }
            }
    }

    private val CommonSentenceInitials = setOf("The", "This", "That", "A", "An", "It", "There")

    private fun isCodeOrStructuralContent(text: String): Boolean {
        val trimmed = text.trim()
        if ((trimmed.startsWith('`') && trimmed.endsWith('`')) || trimmed.startsWith("``")) return true
        if (trimmed.startsWith("fun ") || trimmed.startsWith("val ") || trimmed.startsWith("var ") ||
            trimmed.startsWith("class ") || trimmed.startsWith("import ") ||
            trimmed.contains("=>") || trimmed.contains("{}")
        ) return true
        val inlineCodeCount = trimmed.count { it == '`' }
        return inlineCodeCount >= 2 && trimmed.replace(Regex("`[^`]*`"), "").trim().isEmpty()
    }
}

private object ClaimMatching {
    private val TokenRegex = Regex("""[A-Za-z0-9]+|[가-힣]+""")
    private val QueryTokenRegex = Regex("""\d+(?:[.,]\d+)?%|[A-Za-z0-9]+|[가-힣]+""")
    private val CriticalAnchorRegex = Regex(
        """(?i)(?<![A-Za-z0-9])(?:19\d{2}|20\d{2}|\d+(?:\.\d+)?\s*%|\d+(?:\.\d+)?(?:gb|tb|mb|kb|억|만|명|개|대|원|달러)|[a-z]{2,}[a-z0-9-]*\d[a-z0-9-]*)(?![A-Za-z0-9])"""
    )
    private val StopWords = setOf(
        "the", "a", "an", "is", "are", "was", "were", "has", "have", "this", "that", "and", "or", "in", "of", "to",
        "은", "는", "이", "가", "을", "를", "에", "에서", "으로", "와", "과", "도", "의", "년", "월", "일",
    )
    private val GenericTokens = setOf(
        "company", "companies", "product", "products", "released", "release", "reported", "report",
        "announced", "announcement", "annual", "earnings", "results", "new", "source", "news", "data",
        "회사", "기업", "제품", "출시", "발표", "보고", "결과", "자료", "뉴스",
    )
    private val EntityLikeRegex = Regex("""\b(?:[A-Z][A-Za-z0-9-]{2,}|[A-Za-z]{2,}\d[A-Za-z0-9-]*)\b""")

    fun tokenize(text: String): List<String> = TokenRegex.findAll(text)
        .map { stripKoreanParticle(it.value).lowercase(Locale.ROOT) }
        .filter { it.length > 1 || it.any(Char::isDigit) }
        .filterNot { it in StopWords }
        .toList()

    fun meaningfulTokens(text: String): Set<String> = tokenize(text).filterNot { it in GenericTokens }.toSet()

    fun entityTokens(text: String): Set<String> {
        val latinEntities = EntityLikeRegex.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toSet()
        val koreanEntities = Regex("[가-힣]{2,}").findAll(text)
            .filter { match -> listOf("전자", "주식회사", "기업", "회사", "대학", "은행", "연구소", "정부", "모델").any(match.value::contains) }
            .map { stripKoreanParticle(it.value).lowercase(Locale.ROOT) }
            .toSet()
        return latinEntities + koreanEntities
    }

    fun isGeneric(token: String): Boolean = token in GenericTokens

    fun queryTokens(text: String): List<String> = QueryTokenRegex.findAll(text)
        .map { stripKoreanParticle(it.value) }
        .map { it.trim() }
        .filter { it.length > 1 || it.any(Char::isDigit) || it.endsWith('%') }
        .filterNot { it.lowercase(Locale.ROOT) in StopWords }
        .toList()

    fun criticalAnchors(text: String): List<String> = CriticalAnchorRegex.findAll(text)
        .map { it.value.replace(Regex("\\s+"), "").lowercase(Locale.ROOT) }
        .distinct()
        .toList()

    fun normalizedWithoutWhitespace(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun stripKoreanParticle(raw: String): String {
        if (raw.any { it.isLetter() && it !in '가'..'힣' }) return raw
        val suffixes = listOf("으로부터", "에서는", "에게", "에서", "으로", "처럼", "까지", "부터", "에는", "을", "를", "은", "는", "이", "가", "에", "의", "와", "과", "도", "로")
        val suffix = suffixes.firstOrNull { raw.endsWith(it) && raw.length > it.length + 1 }
        return if (suffix == null) raw else raw.removeSuffix(suffix)
    }
}

internal object EvidenceSourceCandidateMatcher {
    private data class ScoredSource(
        val sourceId: String,
        val score: Int,
        val originalIndex: Int,
        val strong: Boolean,
    )

    /** Candidate ranking and search-suppression strength are deliberately separate. */
    fun match(claimText: String, sources: List<EvidencePlanningSource>): ExistingSourceMatch {
        val claimTokens = ClaimMatching.tokenize(claimText).toSet()
        val meaningfulClaimTokens = ClaimMatching.meaningfulTokens(claimText)
        val entityClaimTokens = ClaimMatching.entityTokens(claimText)
        val anchors = ClaimMatching.criticalAnchors(claimText)
        if (claimTokens.isEmpty()) return ExistingSourceMatch(emptyList(), false)

        val scored = sources.mapIndexedNotNull { index, source ->
            val titleTokens = ClaimMatching.tokenize(source.title).toSet()
            val snippetTokens = ClaimMatching.tokenize(source.snippet.orEmpty()).toSet()
            val sourceTokens = ClaimMatching.tokenize(source.source.orEmpty()).toSet()
            val queryTokens = ClaimMatching.tokenize(source.queryUsed).toSet()
            val allText = listOf(source.title, source.source, source.url, source.snippet, source.queryUsed)
                .filterNotNull()
                .joinToString(" ")
            val sourceWithoutWhitespace = ClaimMatching.normalizedWithoutWhitespace(allText)
            val matchedAnchors = anchors.count { sourceWithoutWhitespace.contains(it) }
            if (anchors.isNotEmpty() && matchedAnchors == 0) return@mapIndexedNotNull null

            val allSourceTokens = titleTokens + snippetTokens + sourceTokens + queryTokens
            val overlapTokens = claimTokens.intersect(allSourceTokens)
            val overlap = overlapTokens.size
            val meaningfulOverlapTokens = overlapTokens.intersect(meaningfulClaimTokens)
            val meaningfulOverlap = meaningfulOverlapTokens.size
            val entityOverlap = overlapTokens.intersect(entityClaimTokens).size
            val nonEntityMeaningfulOverlap = meaningfulOverlapTokens.count { it !in entityClaimTokens }
            val titleOverlap = claimTokens.intersect(titleTokens).size
            val snippetOverlap = claimTokens.intersect(snippetTokens).size
            val queryOverlap = claimTokens.intersect(queryTokens).size
            val titleMeaningfulOverlap = meaningfulClaimTokens.intersect(titleTokens).count { !ClaimMatching.isGeneric(it) }
            val queryMeaningfulOverlap = meaningfulClaimTokens.intersect(queryTokens).count { !ClaimMatching.isGeneric(it) }
            val score = matchedAnchors * 1000 + overlap * 10 + titleOverlap * 8 + snippetOverlap * 4 + queryOverlap * 2
            val strongLexicalOverlap = meaningfulOverlap >= 2 ||
                (entityOverlap >= 1 && nonEntityMeaningfulOverlap >= 1) ||
                titleMeaningfulOverlap >= 2 || queryMeaningfulOverlap >= 2
            val strong = (anchors.isEmpty() || matchedAnchors == anchors.size) && strongLexicalOverlap
            if (score <= 0) null else ScoredSource(source.id, score, index, strong)
        }
        val selected = scored
            .sortedWith(compareByDescending<ScoredSource> { it.score }.thenBy { it.originalIndex })
            .take(EvidencePlanningLimits.MAX_EXISTING_SOURCE_CANDIDATES_PER_CLAIM)
        return ExistingSourceMatch(selected.map { it.sourceId }, selected.any { it.strong })
    }
}

private object TargetedQueryPlanner {
    private val FillerTerms = setOf(
        "please", "tell", "me", "find", "show", "explain", "내", "생각", "알려", "찾아", "검색", "추천",
        "해줘", "하세요", "하십시오", "해주세요",
    )

    fun create(claimText: String): String {
        val anchors = ClaimMatching.criticalAnchors(claimText)
        val ordered = ClaimMatching.queryTokens(claimText)
            .filterNot { it.lowercase(Locale.ROOT) in FillerTerms }
            .distinct()
            .toMutableList()

        anchors.forEach { anchor ->
            if (ordered.none { it.replace(Regex("\\s+"), "") == anchor }) ordered += anchor
        }

        val selected = ordered.take(EvidencePlanningLimits.MAX_QUERY_TERMS).toMutableList()
        anchors.forEach { anchor ->
            if (selected.none { it.replace(Regex("\\s+"), "") == anchor }) {
                val replaceIndex = selected.indexOfLast { it !in anchors }
                if (replaceIndex >= 0) selected[replaceIndex] = anchor
            }
        }

        val query = StringBuilder()
        selected.forEach { term ->
            if (term.isBlank() || term.length > EvidencePlanningLimits.MAX_TARGETED_QUERY_LENGTH) return@forEach
            val extra = if (query.isEmpty()) term else " $term"
            if (query.length + extra.length <= EvidencePlanningLimits.MAX_TARGETED_QUERY_LENGTH) query.append(extra)
        }
        return query.toString().ifBlank { "fact check" }
    }
}

/** Pure C1-B1 claim/source planner. It does not search, verify, or persist anything. */
object EvidenceVerificationPlanner {

    fun candidates(rawAssistantAnswer: String): List<EvidenceClaimCandidate> =
        candidatesWithCoverage(rawAssistantAnswer).candidates

    /**
     * Exact bounded targeted query the canonical planner would derive for a
     * single claim text. Exposed so a strictly bounded single-claim operation
     * (UX-P2B) can reuse the identical query derivation instead of inventing a
     * second query policy. Pure and deterministic; it performs no search.
     */
    fun targetedQueryFor(claimText: String): String = TargetedQueryPlanner.create(claimText)

    fun candidatesWithCoverage(rawAssistantAnswer: String): EvidenceCandidateWindow {
        val canonicalVisibleAnswer = visibleMessageContentPreservingWhitespace(rawAssistantAnswer)
        val split = EvidenceClaimSplitter.splitWindow(canonicalVisibleAnswer)
        val candidates = split.claims.mapIndexed { index, text ->
            val decision = EvidenceClaimClassifier.classifyWithReason(text)
            EvidenceClaimCandidate(
                id = "C${index + 1}",
                text = text,
                ordinal = index + 1,
                classification = decision.classification,
                priority = decision.priority,
                reason = decision.reason,
            )
        }
        return EvidenceCandidateWindow(
            candidates = candidates,
            truncated = split.truncated,
            overlengthSegmentsExcluded = split.overlengthSegmentsExcluded,
        )
    }

    /**
     * Parses only the existing attached source snapshot. Source strings remain
     * untrusted data and are used only for deterministic token matching.
     */
    fun plan(
        rawAssistantAnswer: String,
        existingSources: List<WebSearchSource>? = null,
        allowTargetedSearch: Boolean = true,
    ): VerificationPlan {
        val planningSources = normalizeSources(existingSources ?: parseSearchSourcesMetadata(rawAssistantAnswer))
        val candidateWindow = candidatesWithCoverage(rawAssistantAnswer)
        val candidateSnapshot = candidateWindow.candidates
        val selectedClaims = candidateSnapshot
            .asSequence()
            .filter { it.classification == ClaimClassification.CHECKABLE_FACT }
            .sortedWith(compareByDescending<EvidenceClaimCandidate> { priorityRank(it.priority) }.thenBy { it.ordinal })
            .take(EvidencePlanningLimits.MAX_CLAIMS_TO_CHECK)
            .toList()

        val plannedClaims = selectedClaims.map { candidate ->
            val match = EvidenceSourceCandidateMatcher.match(candidate.text, planningSources)
            PlannedClaim(
                candidate = candidate,
                existingSourceCandidateIds = match.sourceIds,
                needsTargetedSearch = allowTargetedSearch && !match.hasStrongCandidate,
            )
        }
        val targetedSearchRequests = plannedClaims
            .asSequence()
            .filter { it.needsTargetedSearch }
            .take(EvidencePlanningLimits.MAX_TARGETED_SEARCH_REQUESTS)
            .map { TargetedSearchRequest(it.candidate.id, TargetedQueryPlanner.create(it.candidate.text)) }
            .toList()

        return VerificationPlan(
            claims = plannedClaims,
            existingSources = planningSources,
            targetedSearchRequests = targetedSearchRequests,
            allCandidates = candidateSnapshot,
            candidateWindowTruncated = candidateWindow.truncated,
            overlengthSegmentsExcluded = candidateWindow.overlengthSegmentsExcluded,
        )
    }

    private fun normalizeSources(sources: List<WebSearchSource>): List<EvidencePlanningSource> {
        // Bound intake before constructing dedupe keys or any matcher input.
        val bounded = sources.asSequence()
            .take(EvidencePlanningLimits.MAX_EXISTING_SOURCES)
            .map { source ->
                EvidencePlanningSource(
                    id = "",
                    title = source.title.take(SearchSourceMetadataLimits.MAX_TITLE_LENGTH),
                    source = source.source?.take(SearchSourceMetadataLimits.MAX_SOURCE_LENGTH),
                    url = source.url?.take(SearchSourceMetadataLimits.MAX_URL_LENGTH),
                    snippet = source.snippet?.take(SearchSourceMetadataLimits.MAX_SNIPPET_LENGTH),
                    publishedAt = source.publishedAt?.take(SearchSourceMetadataLimits.MAX_PUBLISHED_AT_LENGTH),
                    providerType = source.providerType,
                    providerDisplayName = source.providerDisplayName.take(SearchSourceMetadataLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH),
                    searchCategory = source.searchCategory?.take(SearchSourceMetadataLimits.MAX_SEARCH_CATEGORY_LENGTH),
                    queryUsed = source.queryUsed.take(SearchSourceMetadataLimits.MAX_QUERY_USED_LENGTH),
                    rank = source.rank,
                    qualityHints = source.qualityHints
                        .take(SearchSourceMetadataLimits.MAX_QUALITY_HINTS)
                        .map { it.take(SearchSourceMetadataLimits.MAX_QUALITY_HINT_LENGTH) },
                )
            }
            .filter { source ->
                source.title.isNotBlank() || !source.url.isNullOrBlank() || !source.snippet.isNullOrBlank()
            }
            .toList()

        val seen = mutableSetOf<String>()
        return bounded.asSequence()
            .filter { source -> seen.add(sourceKey(source)) }
            .mapIndexed { index, source -> source.copy(id = "E${index + 1}") }
            .toList()
    }

    private fun sourceKey(source: EvidencePlanningSource): String {
        val url = source.url?.trim()?.lowercase(Locale.ROOT)
        if (!url.isNullOrBlank()) return "url:$url"
        return listOf(source.title, source.source, source.snippet, source.queryUsed)
            .joinToString("|") { it.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") }
    }

    private fun priorityRank(priority: ClaimPriority): Int = when (priority) {
        ClaimPriority.HIGH -> 3
        ClaimPriority.MEDIUM -> 2
        ClaimPriority.LOW -> 1
        ClaimPriority.NONE -> 0
    }
}
