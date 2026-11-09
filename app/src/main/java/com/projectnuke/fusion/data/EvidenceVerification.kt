package com.projectnuke.fusion.data

import com.projectnuke.fusion.audit.AnswerAuditExecutionOrchestrator
import com.projectnuke.fusion.audit.AnswerAuditExecutionSnapshot
import com.projectnuke.fusion.audit.VerifierClaimInput
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.retrieval.SemanticCandidateMatcher
import com.projectnuke.fusion.retrieval.SemanticEvidenceCandidates
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import com.projectnuke.fusion.search.FusionWebSearch
import com.projectnuke.fusion.search.SearchSourceMetadataLimits
import com.projectnuke.fusion.search.WebSearchProviderRepository
import com.projectnuke.fusion.search.WebSearchSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout

const val EVIDENCE_VERIFIER_VERSION = "rules-v11"

object EvidenceVerificationLimits {
    const val MAX_CLAIMS_TO_CHECK = 6
    const val MAX_TARGETED_SEARCH_REQUESTS = 3
    const val MAX_SOURCES_PER_SEARCH_RESULT = 8
    const val MAX_TOTAL_EVIDENCE_SOURCES = 24
    const val OVERALL_VERIFICATION_DEADLINE_MS = 30_000L
}

sealed interface EvidenceSearchResult {
    data class Success(val sources: List<WebSearchSource>) : EvidenceSearchResult
    data class Failed(val reason: String) : EvidenceSearchResult
}

interface EvidenceSearchExecutor {
    suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult
}

/** Search transport seam that requires the exact post-answer authorization at execution time. */
interface PostAnswerVerificationSearchExecutor : EvidenceSearchExecutor {
    suspend fun searchAuthorizedPostAnswerVerification(
        request: TargetedSearchRequest,
        operationId: String,
        identity: PostAnswerVerificationSearchIdentity,
        authorization: PostAnswerVerificationSearchAuthorization,
        isCurrent: suspend () -> Boolean,
    ): EvidenceSearchResult
}

fun interface EvidenceClock {
    fun nowMillis(): Long
}

enum class EvidenceVerificationFailureReason {
    INVALID_MESSAGE,
    INVALID_PLAN,
    STALE_ANSWER,
    DEADLINE_EXCEEDED,
    CODEC_REJECTED,
    EGRESS_REFUSED,
    SEARCH_UNAVAILABLE,
}

sealed interface EvidenceVerificationResult {
    data class Complete(
        val report: EvidenceReport,
        val transientAudit: AnswerAuditExecutionSnapshot? = null,
    ) : EvidenceVerificationResult
    data object Cancelled : EvidenceVerificationResult
    data class Failed(val reason: EvidenceVerificationFailureReason) : EvidenceVerificationResult
}

data class EvidenceAssessment(
    val verdict: EvidenceVerdict,
    val sourceIds: List<String>,
    val reasonCode: String?,
)

/**
 * A bounded evidence payload segment paired with assertion metadata. The
 * [isInterrogative] flag is captured from the raw segment before punctuation
 * removal so an interrogative source can never be promoted to a positive
 * assertion.
 */
data class EvidenceSegment(
    val text: String,
    val isInterrogative: Boolean,
)

interface EvidenceAssessor {
    fun assess(
        claim: EvidenceClaimCandidate,
        sources: List<EvidenceSource>,
    ): EvidenceAssessment
}

/**
 * Uses only bounded, local title/snippet payload segments. This is an evidence
 * alignment rule set, never a truth or confidence classifier.
 *
 * Temporal/currentness policy is centralized in [EvidenceTemporalPolicy]: the
 * claim's [EvidenceTemporalPolicy.TemporalCategory] governs both the
 * publication freshness window and the per-segment event-time alignment check.
 */
class ConservativeRuleEvidenceAssessor(
    private val clock: EvidenceClock = EvidenceClock { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : EvidenceAssessor {

    override fun assess(
        claim: EvidenceClaimCandidate,
        sources: List<EvidenceSource>,
    ): EvidenceAssessment {
        if (claim.classification != ClaimClassification.CHECKABLE_FACT) {
            return EvidenceAssessment(
                verdict = EvidenceVerdict.NOT_CHECKED,
                sourceIds = emptyList(),
                reasonCode = claim.reason.name,
            )
        }
        if (sources.isEmpty()) {
            return EvidenceAssessment(
                verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                sourceIds = emptyList(),
                reasonCode = "NO_USABLE_SOURCE",
            )
        }

        val category = EvidenceTemporalPolicy.classify(claim.text)

        val conflicts = sources.filter { source ->
            localSegments(source).any { segment ->
                isTemporallyAdmissibleForClaim(category, source, segment, claim.text) &&
                    hasReliableConflict(claim.text, segment.text)
            }
        }
        val supporting = sources.filter { source ->
            localSegments(source).any { segment ->
                isTemporallyAdmissibleForClaim(category, source, segment, claim.text) &&
                    isStrongSupport(claim.text, segment.text)
            }
        }
        val contributing = sources.filter { source ->
            source.id in conflicts.map { it.id } || source.id in supporting.map { it.id }
        }.map { it.id }
        if (conflicts.isNotEmpty()) {
            return EvidenceAssessment(
                verdict = EvidenceVerdict.CONFLICTING_EVIDENCE,
                sourceIds = contributing,
                reasonCode = "CRITICAL_VALUE_CONFLICT",
            )
        }

        if (supporting.isNotEmpty()) {
            return EvidenceAssessment(
                verdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
                sourceIds = supporting.map { it.id },
                reasonCode = "STRONG_RULE_ALIGNMENT",
            )
        }

        return EvidenceAssessment(
            verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            sourceIds = sources.map { it.id },
            reasonCode = "INSUFFICIENT_RULE_ALIGNMENT",
        )
    }

    private fun isFreshEnoughForCategory(source: EvidenceSource, category: EvidenceTemporalPolicy.TemporalCategory): Boolean {
        val publishedAt = source.publishedAt ?: return false
        val publishedMillis = EvidencePublishedAtParser.parseMillis(publishedAt, zoneId) ?: return false
        val now = clock.nowMillis()
        val window = EvidenceTemporalPolicy.freshnessWindowMs(category)
        return publishedMillis <= now && now - publishedMillis <= window
    }

    /**
     * Single shared temporal admissibility gate for both support and conflict.
     * A current-sensitive claim may be supported or conflicted only by a source
     * segment that is non-interrogative, fresh for the category, and temporally
     * aligned; historical claims keep the existing conflict behavior ([NONE]).
     */
    private fun isTemporallyAdmissibleForClaim(
        category: EvidenceTemporalPolicy.TemporalCategory,
        source: EvidenceSource,
        segment: EvidenceSegment,
        claimText: String,
    ): Boolean {
        if (segment.isInterrogative) return false
        // A source published before an explicit event date cannot
        // prove the event occurred. Fail closed for all categories.
        val pubMillis = source.publishedAt?.let { EvidencePublishedAtParser.parseMillis(it, zoneId) }
        if (pubMillis != null) {
            val pubDate = Instant.ofEpochMilli(pubMillis).atZone(zoneId).toLocalDate()
            val eventDates = EvidenceAssessmentText.extractDates(segment.text)
            if (eventDates.any { it > pubDate }) return false
        }
        if (category == EvidenceTemporalPolicy.TemporalCategory.NONE) return true
        if (!isFreshEnoughForCategory(source, category)) return false
        return EvidenceTemporalPolicy.isTemporallyAligned(
            category = category,
            segmentText = segment.text,
            claimText = claimText,
            publishedAtMillis = pubMillis,
            nowMillis = clock.nowMillis(),
            zoneId = zoneId,
        )
    }

    private fun isStrongSupport(claimText: String, sourceText: String): Boolean {
        if (!locallyBound(claimText, sourceText)) return false
        // Conditional/absence polarity is intentionally unsupported in this
        // bounded verifier; neither side may silently erase that condition.
        if (EvidenceAssessmentText.hasUnsafeModalityOrNegation(claimText)) return false
        if (EvidenceAssessmentText.hasAmbiguousSameKindValues(claimText, sourceText)) return false
        if (EvidenceAssessmentText.hasIncompatibleEventForm(claimText, sourceText)) return false
        if (EvidenceAssessmentText.hasCurrentFormerMismatch(claimText, sourceText)) return false
        val claimTokens = EvidenceAssessmentText.tokens(claimText).toSet()
        val sourceTokens = EvidenceAssessmentText.tokens(sourceText).toSet()
        if (claimTokens.isEmpty()) return false

        val claimAnchors = EvidenceAssessmentText.criticalAnchors(claimText)
        if (!EvidenceAssessmentText.criticalAnchors(sourceText).containsAll(claimAnchors)) return false
        // rules-v9: a resolved memory-vs-storage mismatch must not support,
        // even when entity, relation, and the bare quantity anchor all match.
        if (!hasCompatibleQuantityAttributes(claimText, sourceText)) return false

        val claimEntities = EvidenceAssessmentText.entityTokens(claimText)
        val entityOverlap = claimEntities.intersect(EvidenceAssessmentText.entityTokens(sourceText)).size
        if (claimEntities.isNotEmpty() && entityOverlap == 0) return false

        val meaningfulOverlap = EvidenceAssessmentText.meaningfulTokens(claimText)
            .intersect(EvidenceAssessmentText.meaningfulTokens(sourceText))
            .size
        val minimumMeaningfulOverlap = if (claimRelationsAreCause(claimText, sourceText)) 1 else 2
        if (meaningfulOverlap < minimumMeaningfulOverlap) return false

        val claimRelations = EvidenceAssessmentText.relationFamilies(claimText)
        val sourceRelations = EvidenceAssessmentText.relationFamilies(sourceText)
        if (claimRelations.isNotEmpty() && claimRelations.intersect(sourceRelations).isEmpty()) return false
        if (!EvidenceAssessmentText.hasEntityBeforeRelation(claimText, sourceText)) return false

        // Keep the lexical/entity checks explicit: matching a bag of literals
        // without the same event relation is not sufficient support.
        val minimumTokenOverlap = if (claimRelationsAreCause(claimText, sourceText)) 1 else 2
        return claimTokens.intersect(sourceTokens).size >= minimumTokenOverlap
    }

    private fun claimRelationsAreCause(claimText: String, sourceText: String): Boolean =
        EvidenceAssessmentText.relationFamilies(claimText) == setOf("cause") &&
            EvidenceAssessmentText.relationFamilies(sourceText) == setOf("cause")

    /**
     * rules-v9: quantity-attribute compatibility. For each relation-scoped
     * claim quantity, a same-value source quantity must have compatible
     * attribute families.
     *
     * - claim specific MEMORY, source specific MEMORY → may support
     * - claim specific MEMORY, source specific STORAGE → INSUFFICIENT
     * - claim specific MEMORY, source unresolved/generic/absent → INSUFFICIENT
     * - claim specific STORAGE, source specific STORAGE → may support
     * - claim specific STORAGE, source specific MEMORY → INSUFFICIENT
     * - claim specific STORAGE, source unresolved/generic/absent → INSUFFICIENT
     * - claim unresolved/generic/absent → continue (no attribute constraint)
     *
     * Different attribute remains INSUFFICIENT, never CONFLICTING.
     */
    private fun hasCompatibleQuantityAttributes(claimText: String, sourceText: String): Boolean {
        val claimQuantities = EvidenceAssessmentText.anchorsNearRelations(claimText)
            .filter { it.kind == "quantity" }
        if (claimQuantities.isEmpty()) return true
        val sourceQuantities = EvidenceAssessmentText.anchorsNearRelations(sourceText)
            .filter { it.kind == "quantity" }
        for (claimAnchor in claimQuantities) {
            val claimFamilies =
                EvidenceAssessmentText.quantityAttributeFamilies(claimText, claimAnchor.value)
            // Claim has unresolved/generic/absent attribute → no constraint
            if (claimFamilies.isEmpty()) continue
            val sameValuePresent = sourceQuantities.any { sourceAnchor ->
                EvidenceAssessmentText.sameUnit(sourceAnchor.value, claimAnchor.value) &&
                    EvidenceAssessmentText.numericValue(sourceAnchor.value) ==
                    EvidenceAssessmentText.numericValue(claimAnchor.value)
            }
            if (!sameValuePresent) continue
            val sourceFamilies =
                EvidenceAssessmentText.quantityAttributeFamilies(sourceText, claimAnchor.value)
            // Claim has specific attribute but source has unresolved/generic/absent → INSUFFICIENT
            if (sourceFamilies.isEmpty()) return false
            // Both have specific attributes but they don't match → INSUFFICIENT
            if (sourceFamilies.intersect(claimFamilies).isEmpty()) {
                return false
            }
        }
        return true
    }

    private fun hasReliableConflict(claimText: String, sourceText: String): Boolean {
        if (!locallyBound(claimText, sourceText)) return false
        if (EvidenceAssessmentText.hasUnsafeModalityOrNegation(claimText)) return false
        if (EvidenceAssessmentText.hasAmbiguousSameKindValues(claimText, sourceText)) return false
        val claimRelations = EvidenceAssessmentText.relationFamilies(claimText)
        val sourceRelations = EvidenceAssessmentText.relationFamilies(sourceText)
        if (claimRelations.isEmpty() || sourceRelations.isEmpty()) return false
        if (!EvidenceAssessmentText.hasCompatiblePredicate(claimText, sourceText)) return false
        if (!hasCompatibleQuantityAttributes(claimText, sourceText)) return false

        val claimEntities = EvidenceAssessmentText.entityTokens(claimText)
        if (claimEntities.isNotEmpty() && claimEntities.intersect(EvidenceAssessmentText.entityTokens(sourceText)).isEmpty()) {
            return false
        }
        val claimAnchors = EvidenceAssessmentText.anchorsNearRelations(claimText)
        val sourceAnchors = EvidenceAssessmentText.anchorsNearRelations(sourceText)
        val claimFormulas = EvidenceAssessmentText.formulaAnchors(claimText)
        val sourceFormulas = EvidenceAssessmentText.formulaAnchors(sourceText)
        if (!EvidenceAssessmentText.hasEntityBeforeRelation(claimText, sourceText)) {
            if (claimFormulas.isEmpty() || sourceFormulas.isEmpty() || claimFormulas == sourceFormulas) return false
            val claimSubject = EvidenceAssessmentText.subjectArguments(claimText)
            val sourceSubject = EvidenceAssessmentText.subjectArguments(sourceText)
            if (claimSubject != sourceSubject) return false
        }
        if (claimFormulas.isNotEmpty() && sourceFormulas.isNotEmpty() && claimFormulas != sourceFormulas) return true
        return claimAnchors.any { claimAnchor ->
            when (claimAnchor.kind) {
                "quantity" -> sourceAnchors.any { sourceAnchor ->
                    sourceAnchor.kind == "quantity" &&
                        EvidenceAssessmentText.sameComparisonContext(claimText, sourceText, claimAnchor, sourceAnchor) &&
                        EvidenceAssessmentText.sameUnit(claimAnchor.value, sourceAnchor.value) &&
                        EvidenceAssessmentText.numericValue(claimAnchor.value) != EvidenceAssessmentText.numericValue(sourceAnchor.value)
                }
                "percentage", "year" -> sourceAnchors.any { sourceAnchor ->
                    claimAnchor.kind == sourceAnchor.kind && claimAnchor.value != sourceAnchor.value
                }
                else -> false
            }
        }
    }

    private fun locallyBound(claim: String, segment: String): Boolean {
        // Extra subjects or multiple predicates in one sentence are ambiguous.
        // Fail closed instead of combining clauses into a synthetic proposition.
        if (!EvidenceAssessmentText.hasCompatiblePredicate(claim, segment)) return false
        if (!EvidenceAssessmentText.hasEntityBeforeRelation(claim, segment)) return false
        if (!EvidenceAssessmentText.singleRelation(segment)) return false
        if (EvidenceAssessmentText.hasUnsafeModalityOrNegation(segment)) return false
        return EvidenceAssessmentText.meaningfulTokens(claim)
            .intersect(EvidenceAssessmentText.meaningfulTokens(segment)).size >= 1
    }

    private fun localSegments(source: EvidenceSource): Sequence<EvidenceSegment> = sequenceOf(
        source.title.take(EvidenceLimits.MAX_TITLE_LENGTH),
        source.snippet.take(EvidenceLimits.MAX_SNIPPET_LENGTH),
    ).flatMap { payload -> splitToSegments(payload) }

    /**
     * Splits a payload on sentence boundaries while preserving interrogative
     * status. A segment is interrogative when its terminal boundary includes
     * '?'. Capturing this before punctuation removal is what prevents a
     * question such as "ProductX2 supports 12GB?" from falsely supporting a
     * positive claim.
     */
    private fun splitToSegments(payload: String): Sequence<EvidenceSegment> = sequence {
        val regex = Regex("[!?;；。\\r\\n]+|(?<!\\d)\\.|\\.(?!\\d)")
        var cursor = 0
        regex.findAll(payload).forEach { match ->
            val segment = payload.substring(cursor, match.range.first).trim()
            if (segment.isNotEmpty() && segment.length <= 512) {
                val terminalInterrogative = match.value.contains('?')
                yield(EvidenceSegment(segment, terminalInterrogative || EvidenceAssessmentText.isLeadingInterrogative(segment)))
            }
            cursor = match.range.last + 1
        }
        val tail = payload.substring(cursor).trim()
        if (tail.isNotEmpty() && tail.length <= 512) {
            yield(EvidenceSegment(tail, EvidenceAssessmentText.isLeadingInterrogative(tail)))
        }
    }
}

internal object EvidenceAssessmentText {
    private val TokenRegex = Regex("[A-Za-z0-9]+|[가-힣]+")
    private val AnchorRegex = Regex(
        """(?i)(?<![A-Za-z0-9])(?:(?:\d{4}|\d{3,4}\s*년)|\d[\d,]*(?:\.\d+)?\s*(?:%|gb|tb|mb|kb|억|만|명|개|대|원|달러|도|degrees?|km/s|m/s)|[a-z]{2,}[a-z0-9-]*\d[a-z0-9-]*)(?![A-Za-z0-9])""",
    )
    private val FormulaRegex = Regex("(?<![A-Za-z0-9])(?=[A-Z][a-z]?\\d)(?:[A-Z][a-z]?\\d*){2,}(?![A-Za-z0-9])")
    private val IsoDateRegex = Regex("""(?<!\d)((?:19|20)\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")

    /**
     * rules-v9: bounded quantity-attribute compatibility for memory vs storage.
     * A bare quantity anchor (12GB) does not say WHAT it measures; support
     * additionally requires compatible attribute families whenever both sides
     * resolve one. Generic terms (capacity/용량) are ambiguous and never block —
     * when claim has specific attribute (MEMORY/STORAGE) but source has only
     * generic/unresolved, the result is INSUFFICIENT.
     *
     * Spaced quantity forms (12 GB, 12  GB, 12 gb, 12 Gb) are supported via
     * updated [AnchorRegex] and [QuantityRegex].
     */
    enum class QuantityAttributeFamily { MEMORY, STORAGE }

    private val StorageAttributeMarkers = listOf("storage", "저장공간", "저장 공간", "스토리지")
    private val KoreanDateRegex = Regex("""(?<!\d)((?:19|20)\d{2})년\s*(\d{1,2})월\s*(\d{1,2})일""")
    private val KoreanMonthRegex = Regex("""(?<!\d)((?:19|20)\d{2})년\s*(\d{1,2})월""")
    private val EnglishMonthFirstRegex = Regex(
        """(?i)\b(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{1,2}),\s*((?:19|20)\d{2})\b""",
    )
    private val EnglishMonthLastRegex = Regex(
        """(?i)\b(\d{1,2})\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+((?:19|20)\d{2})\b""",
    )
    private val UnsafeEnglishRegex = Regex(
        """(?i)(?<![A-Za-z])(?:if|unless|not|never|no|cannot|can't|doesn't|does not|didn't|did not|hasn't|has not|haven't|have not|won't|will not|wouldn't|couldn't|shouldn't|lacks|without|may|might|could|would|will|expected to|expects to|plans? to|planned to|aims to|likely to|possibly|possible|rumored|reportedly|rumor|rumour|rumoured|allegedly|unconfirmed|leaked|leak|is said to)(?![A-Za-z])""",
    )
    private val UnsafeContractionMarkers = listOf(
        "can't", "doesn't", "didn't", "hasn't", "haven't", "won't",
        "wouldn't", "couldn't", "shouldn't",
        "can\u2019t", "doesn\u2019t", "didn\u2019t", "hasn\u2019t", "haven\u2019t", "won\u2019t",
        "wouldn\u2019t", "couldn\u2019t", "shouldn\u2019t",
    )
    private val UnsafeUnicodeContractionRegex = Regex(
        """(?i)(?<![A-Za-z])(?:can|does|did|has|have|will|would|could|should)[^A-Za-z0-9\s]t(?![A-Za-z])""",
    )
    private val UnsafeKoreanMarkers = listOf(
        "\uC54A", // 않
        "\uC544\uB2C8\uB2E4", // 아니다
        "\uC544\uB2C8", // 아니
        "\uBABB", // 못
        "\uBBF8\uC9C0\uC6D0", // 미지원
        "\uC608\uC815", // 예정
        "\uACC4\uD68D", // 계획
        "\uC804\uB9DD", // 전망
        "\uC608\uC0C1", // 예상
        "\uAC00\uB2A5", // 가능
        "\uC218\uB3C4 \uC788\uB2E4", // 수도 있다
        "\uAC83\uC73C\uB85C \uBCF4", // 것으로 보
        "\uAC80\uD1A0", // 검토
        "\uCD94\uC9C4", // 추진
        "\uBAA9\uD45C", // 목표
        "\uBBF8\uC815", // 미정
        "\uB8E8\uBA38", // 루머
        "\uC18C\uBB38", // 소문
        "\uC720\uCD9C", // 유출
        "\uBBF8\uD655\uC778", // 미확인
        "\uC804\uD574\uC9C0\uB2C8", // 전해졌다
        "\uC54C\uB824\uC9C0\uB2C8", // 알려졌다
    )
    private val BoundedKoreanAbsenceTokens = setOf("없다", "없는", "없으면", "없이", "있는", "있으면")
    private val AttributeRegex = Regex(
        "(?i)(ram|vram|memory|storage|capacity|\uB7A8|\uBA54\uBAA8\uB9AC|\uC800\uC7A5\uACF5\uAC04|\uC6A9\uB7C9)",
    )
    private val QuantityRegex = Regex(
        "(?i)\\d[\\d,]*(?:\\.\\d+)?\\s*(?:%|gb|tb|mb|kb|\uC5B5|\uB9CC|\uBA85|\uAC1C|\uB300|\uC6D0|\uB2EC\uB7EC|\uB3C4|degrees?|km/s|m/s)",
    )
    private val StopWords = setOf(
        "the", "a", "an", "is", "are", "was", "were", "this", "that", "and", "or", "in", "of", "to",
        "은", "는", "이", "가", "을", "를", "에", "에서", "으로", "와", "과", "도", "의", "년", "월", "일",
    )
    private val GenericWords = setOf(
        "company", "companies", "product", "products", "new", "source", "news", "data", "report", "reported",
        "annual", "earnings", "results", "자료", "뉴스", "결과", "보고서",
    )
    private val EntityStopWords = setOf("ram", "gb", "tb", "mb", "kb")
    private val MonthNames = setOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )
    private val EntityLikeRegex = Regex("""\b(?:[A-Z][A-Za-z0-9-]{2,}|[A-Za-z]{2,}\d[A-Za-z0-9-]*)\b""")

    data class Anchor(val kind: String, val value: String)
    private data class Anchored(val anchor: Anchor, val characterIndex: Int)

    private val EnglishSurfaceForms = mapOf(
        "created" to "create", "creates" to "create", "made" to "make",
        "founded" to "establish", "established" to "establish",
        "invented" to "invent", "developed" to "develop",
        "occurred" to "occur", "happened" to "happen",
        "began" to "begin", "started" to "start", "ended" to "end", "finished" to "finish",
        "postponed" to "postpone", "delayed" to "postpone",
        "contains" to "contain", "contained" to "contain", "includes" to "include", "included" to "include",
        "consists" to "consist", "consisted" to "consist", "composed" to "compose",
        "located" to "locate", "flows" to "flow", "flowed" to "flow", "belongs" to "belong",
        "caused" to "cause", "causes" to "cause", "leads" to "lead", "led" to "lead",
        "results" to "result", "resulted" to "result",
        "supports" to "support", "supported" to "support", "releases" to "release", "released" to "release",
        "launches" to "launch", "launched" to "launch", "announced" to "announce",
        "manufactures" to "manufacture", "manufactured" to "manufacture",
        "produces" to "produce", "produced" to "produce", "increased" to "increase",
        "decreased" to "decrease", "reported" to "report", "reports" to "report",
        "mentioned" to "mention",
        "faster" to "faster", "slower" to "slower", "greater" to "greater", "smaller" to "smaller",
    )
    private val KoreanPredicateBases = mapOf(
        "지원" to "support", "출시" to "release", "발표" to "announce",
        "생산" to "produce", "제조" to "manufacture", "양산" to "manufacture",
        "증가" to "increase", "감소" to "decrease", "기록" to "report",
        "포함" to "contain", "이루어지" to "consist", "구성" to "compose",
        "위치" to "locate", "속하" to "belong", "흐르" to "flow", "가로지르" to "flow",
        "설립" to "establish", "세우" to "establish", "창제" to "create", "만들" to "make",
        "발명" to "invent", "개발" to "develop", "발생" to "occur", "일어나" to "happen", "발발" to "occur",
        "시작" to "start", "개시" to "start", "끝나" to "end", "종료" to "end",
        "연기" to "postpone", "미루" to "postpone", "미뤄졌다" to "postpone",
        "뜻하" to "mean", "의미하" to "mean", "정의" to "define", "유지" to "maintain",
        "운반" to "transport", "결합" to "bind", "일으키" to "cause", "유발" to "cause",
        "제정" to "enact", "집행" to "enforce",
        "빠르" to "faster", "느리" to "slower", "크" to "greater", "작" to "smaller",
        "합" to "sum", "더하" to "add",
    )
    private val KoreanPredicateEndings = setOf(
        "", "다", "는다", "ㄴ다", "했다", "한다", "합니다", "하는", "하며", "하고", "한",
        "된다", "됐다", "되었다", "되었으며", "되는", "되어", "되어있다", "되어 있다", "해",
        "하였다", "했으며", "된", "면", "으면", "하면", "아서", "어서", "해서", "어졌다", "아졌다",
    )

    private fun normalizeSurfaceToken(raw: String): String {
        val rawToken = raw.lowercase(Locale.ROOT)
        EnglishSurfaceForms[rawToken]?.let { return it }
        KoreanPredicateBases.entries.firstOrNull { (base, _) ->
            rawToken.startsWith(base) && rawToken.removePrefix(base) in KoreanPredicateEndings
        }?.value?.let { return it }
        val token = stripParticle(rawToken)
        EnglishSurfaceForms[token]?.let { return it }
        KoreanPredicateBases.entries.firstOrNull { (base, _) ->
            token.startsWith(base) && token.removePrefix(base) in KoreanPredicateEndings
        }?.value?.let { return it }
        return token
    }

    fun tokens(text: String): List<String> = TokenRegex.findAll(text)
        .map { stripParticle(it.value).lowercase(Locale.ROOT) }
        .filter { it.length > 1 || it.any(Char::isDigit) }
        .filterNot { it in StopWords }
        .toList()

    fun meaningfulTokens(text: String): Set<String> = tokens(text).filterNot { it in GenericWords }.toSet()

    private data class RelationSignal(
        val family: String,
        val subtype: String,
        /** Canonical subject-to-object direction; comparison uses +/-1. */
        val direction: Int = 1,
        val tokenIndex: Int,
    )

    private data class Proposition(
        val relation: RelationSignal,
        val subject: Set<String>,
        val complement: Set<String>,
        val arguments: Set<String>,
    )

    private val EnglishRelationTokens = setOf(
        "release", "launch", "announce", "support", "production", "produce", "manufacture",
        "increase", "decrease", "report", "contain", "include", "compose", "consist", "has",
        "locate", "location", "belong", "flow", "establish", "create", "make", "invent", "develop",
        "occur", "happen", "begin", "start", "end", "finish", "refer", "define", "mean", "maintain",
        "transport", "bind", "cause", "lead", "result", "faster", "slower", "greater", "smaller",
        "equal", "add", "sum", "mention", "postpone",
    )

    private fun isRelationToken(token: String): Boolean =
        token in EnglishRelationTokens || KoreanPredicateBases.values.any { it == token }

    private fun relationSignalForToken(token: String, index: Int): RelationSignal? {
        val normalized = normalizeSurfaceToken(token)
        val signal = when (normalized) {
            "release", "launch" -> RelationSignal("release", "release", tokenIndex = index)
            "announce" -> RelationSignal("announcement", "announce", tokenIndex = index)
            "support" -> RelationSignal("support", "support", tokenIndex = index)
            "production", "produce", "manufacture" -> RelationSignal("production", "production", tokenIndex = index)
            "increase" -> RelationSignal("increase", "increase", tokenIndex = index)
            "decrease" -> RelationSignal("decrease", "decrease", tokenIndex = index)
            "report" -> RelationSignal("report", "report", tokenIndex = index)
            "contain", "include", "compose", "consist", "has" ->
                RelationSignal("composition", "composition", tokenIndex = index)
            "locate", "location", "belong" -> RelationSignal("location", "located_in", tokenIndex = index)
            "flow" -> RelationSignal("location", "flow", tokenIndex = index)
            "establish" -> RelationSignal("establishment", "establish", tokenIndex = index)
            "create", "make" -> RelationSignal("creation", "create", tokenIndex = index)
            "invent" -> RelationSignal("invention", "invent", tokenIndex = index)
            "develop" -> RelationSignal("development", "develop", tokenIndex = index)
            "occur", "happen" -> RelationSignal("occurrence", "occur", tokenIndex = index)
            "begin", "start" -> RelationSignal("occurrence", "begin", tokenIndex = index)
            "end", "finish" -> RelationSignal("occurrence", "end", tokenIndex = index)
            "refer", "define", "mean" -> RelationSignal("identity", normalized, tokenIndex = index)
            "maintain", "transport", "bind" -> RelationSignal("property", normalized, tokenIndex = index)
            "enact" -> RelationSignal("enactment", "enact", tokenIndex = index)
            "enforce" -> RelationSignal("enforcement", "enforce", tokenIndex = index)
            "cause", "lead" -> RelationSignal("cause", "cause", tokenIndex = index)
            "result" -> RelationSignal("cause", "cause", direction = -1, tokenIndex = index)
            "faster", "greater" -> RelationSignal("comparison", "greater", direction = 1, tokenIndex = index)
            "slower", "smaller" -> RelationSignal("comparison", "greater", direction = -1, tokenIndex = index)
            "equal", "add", "sum" -> RelationSignal("equality", "equality", tokenIndex = index)
            "mention" -> RelationSignal("unknown", "mention", tokenIndex = index)
            else -> null
        }
        return signal
    }

    private fun rawSemanticTokens(text: String): List<String> = TokenRegex.findAll(text)
        .map { it.value }
        .filter { raw ->
            val normalized = normalizeSurfaceToken(raw)
            normalized.length > 1 || normalized.any(Char::isDigit) ||
                (normalized.matches(Regex("[가-힣]")) && normalized !in StopWords)
        }
        .toList()

    private fun relationSignals(text: String): List<RelationSignal> {
        val rawTokens = rawSemanticTokens(text)
        val signals = rawTokens.mapIndexedNotNull { index, token -> relationSignalForToken(token, index) }.toMutableList()
        val lowered = text.lowercase(Locale.ROOT)
        val normalizedTokens = rawTokens.map(::normalizeSurfaceToken)

        if (signals.size == 1 && signals.single().family == "composition" &&
            lowered.contains("has property")
        ) {
            signals[0] = signals.single().copy(family = "property", subtype = "attribute")
        }

        // "양산을 시작했다" / "생산을 시작했다" is one bounded production
        // event, not an additional start predicate for local binding.
        if (signals.size == 2 &&
            signals.any { it.family == "production" } &&
            signals.any { it.family == "occurrence" && it.subtype == "begin" } &&
            (lowered.contains("양산을 시작") || lowered.contains("생산을 시작"))
        ) {
            val production = signals.first { it.family == "production" }
            signals.clear()
            signals += production
        }

        if (signals.isEmpty() && Regex("라고\\s*(한다|합니다|함)").containsMatchIn(text)) {
            signals += RelationSignal("identity", "mean", tokenIndex = rawTokens.lastIndex.coerceAtLeast(0))
        }

        // Copulas are only a predicate when no more specific predicate was
        // found. This keeps generic "is" from becoming an unrestricted path.
        if (signals.isEmpty() && (normalizedTokens.any { it in setOf("is", "are", "was", "were") } ||
                    rawTokens.any { it.endsWith("이다") || it.endsWith("된다") || it.endsWith("된다") })) {
            val numeric = criticalAnchors(text).any { it.matches(Regex("\\d.*|[a-z].*\\d.*")) }
            val math = listOf("sum", "angle", "add", "합", "각").any { lowered.contains(it) }
            val property = listOf("mostly", "property", "성질", "대부분", "의 ", "의", "has").any { lowered.contains(it) }
            val location = Regex("(?i)\\b(in|inside|located in|lies in|through)\\b").containsMatchIn(lowered) ||
                Regex("[가-힣]+에(?:서|는|도)?").containsMatchIn(text)
            val family = when {
                numeric && math -> "equality"
                location -> "location"
                property -> "property"
                else -> "identity"
            }
            val subtype = when {
                family == "location" && lowered.contains("through") -> "flow"
                family == "location" -> "located_in"
                else -> "copula"
            }
            val copulaIndex = rawTokens.indexOfFirst { token ->
                val normalized = normalizeSurfaceToken(token)
                normalized in setOf("is", "are", "was", "were") ||
                    token.endsWith("이다") || token.endsWith("입니다") ||
                    token.endsWith("이었다") || token.endsWith("였다") ||
                    token.endsWith("된다") || token.endsWith("되었다") || token.endsWith("됐다")
            }
            signals += RelationSignal(family, subtype, tokenIndex = copulaIndex.takeIf { it >= 0 } ?: rawTokens.lastIndex.coerceAtLeast(0))
        }

        // Korean location/existence is safe only with a location particle;
        // "있다" alone remains unknown.
        if (signals.isEmpty() && rawTokens.any { it in setOf("있다", "있는", "있으며", "있습니다") } &&
            Regex("[가-힣]+에(?:서|는|도)?").containsMatchIn(text)
        ) {
            signals += RelationSignal("location", "located_in", tokenIndex = rawTokens.lastIndex)
        }

        if (signals.isEmpty()) {
            val causalIndex = rawTokens.indexOfFirst { token ->
                token.contains("때문") || token.contains("로 인해") ||
                    token.endsWith("아서") || token.endsWith("어서") || token.endsWith("해서") ||
                    token.endsWith("서")
            }
            if (causalIndex >= 0 || lowered.contains("because") || lowered.contains("due to")) {
                signals += RelationSignal("cause", "cause", tokenIndex = causalIndex.coerceAtLeast(0))
            }
        }

        if (signals.size == 1 && signals.single().family == "cause" &&
            (lowered.contains("because") || lowered.contains("due to") || lowered.contains("로 인해") || lowered.contains("때문"))
        ) {
            val current = signals.single()
            signals[0] = current.copy(direction = 1)
        }
        return signals
    }

    private fun argumentToken(token: String): String {
        val normalized = normalizeSurfaceToken(token)
        return when {
            normalized.endsWith("이다") && normalized.length > 2 -> normalized.removeSuffix("이다")
            normalized.endsWith("된다") && normalized.length > 2 -> normalized.removeSuffix("된다")
            else -> normalized
        }
    }

    private val ArgumentStopWords = StopWords + setOf(
        "by", "than", "because", "due", "from", "to", "through", "mostly", "about", "approximately",
        "약", "대부분", "모두", "현재", "및", "그리고", "의해", "때문", "로", "인해", "라고", "한다",
        "이다", "된다", "되었다", "되었으며", "as",
        "있다", "있는", "있으며", "있습니다",
    )
    private val ArgumentGenericWords = GenericWords + setOf(
        "angle", "angles", "degree", "degrees", "도", "sum", "three", "세", "각", "대륙", "사막", "공기",
        "기관", "음식", "분자", "화학식", "성질", "property", "event", "운동", "상태",
    )
    private val TemporalArgumentWords = MonthNames + setOf(
        "today", "yesterday", "recently", "tomorrow", "on", "오늘", "어제", "최근",
    )

    private fun isArgumentToken(value: String): Boolean =
        value.length > 1 || value.any(Char::isDigit) ||
            (value.matches(Regex("[가-힣]")) && value !in ArgumentStopWords)

    private fun argumentTokens(text: String): Set<String> = rawSemanticTokens(text)
        .map(::argumentToken)
        .filter(::isArgumentToken)
        .filterNot { it in ArgumentStopWords || it in ArgumentGenericWords || it in TemporalArgumentWords || isRelationToken(it) }
        .filterNot { it.matches(Regex("(?i)\\d[\\d,]*(?:\\.\\d+)?(?:%|gb|tb|mb|kb|억|만|명|개|대|원|달러|도|degrees?|km/s|m/s)?|(?:19|20)\\d{2}")) }
        .toSet()

    private fun relationArgumentSets(text: String, relation: RelationSignal): Pair<Set<String>, Set<String>> {
        val raw = rawSemanticTokens(text)
        val before = raw.take(relation.tokenIndex)
        val after = raw.drop(relation.tokenIndex + 1)
        fun content(values: List<String>): Set<String> = values.map(::argumentToken)
            .filter(::isArgumentToken)
            .filterNot { it in ArgumentStopWords || it in ArgumentGenericWords || it in TemporalArgumentWords || isRelationToken(it) }
            .filterNot { it.matches(Regex("(?i)\\d[\\d,]*(?:\\.\\d+)?(?:%|gb|tb|mb|kb|억|만|명|개|대|원|달러|도|degrees?|km/s|m/s)?|(?:19|20)\\d{2}")) }
            .toSet()

        val boundaryToken = raw.getOrNull(relation.tokenIndex).orEmpty()
        val isCopularBoundary =
            normalizeSurfaceToken(boundaryToken) in setOf("is", "are", "was", "were") ||
                boundaryToken in setOf("있다", "있는", "있으며", "있습니다") ||
                boundaryToken.endsWith("이다") || boundaryToken.endsWith("입니다") ||
                boundaryToken.endsWith("이었다") || boundaryToken.endsWith("였다") ||
                boundaryToken.endsWith("된다") || boundaryToken.endsWith("되었다") || boundaryToken.endsWith("됐다")

        if (isCopularBoundary) {
            val copulaStem = when {
                boundaryToken.endsWith("되었습니다") -> boundaryToken.removeSuffix("되었습니다")
                boundaryToken.endsWith("되었다") -> boundaryToken.removeSuffix("되었다")
                boundaryToken.endsWith("됐다") -> boundaryToken.removeSuffix("됐다")
                boundaryToken.endsWith("된다") -> boundaryToken.removeSuffix("된다")
                boundaryToken.endsWith("입니다") -> boundaryToken.removeSuffix("입니다")
                boundaryToken.endsWith("이었다") -> boundaryToken.removeSuffix("이었다")
                boundaryToken.endsWith("였다") -> boundaryToken.removeSuffix("였다")
                boundaryToken.endsWith("이다") -> boundaryToken.removeSuffix("이다")
                else -> ""
            }
            val topicIndex = before.indexOfFirst { it.endsWith("은") || it.endsWith("는") }
            if (topicIndex >= 0) {
                val subject = content(before.take(topicIndex + 1))
                val complement = content(before.drop(topicIndex + 1) + listOf(copulaStem) + after)
                return subject to complement
            }
            return content(before) to content(listOf(copulaStem) + after)
        }

        val byIndex = after.indexOfFirst { it.equals("by", ignoreCase = true) }
        if (byIndex >= 0) return content(after.drop(byIndex + 1)) to content(before)

        val thanIndex = after.indexOfFirst { it.equals("than", ignoreCase = true) }
        if (relation.family == "comparison" && thanIndex >= 0) {
            return content(before) to content(after.drop(thanIndex + 1))
        }

        if (relation.family == "cause") {
            val marker = raw.getOrNull(relation.tokenIndex).orEmpty()
            val causalVerb = marker.endsWith("아서") || marker.endsWith("어서") || marker.endsWith("해서") || marker.endsWith("서")
            if (causalVerb) {
                val cause = raw.getOrNull(relation.tokenIndex - 1)?.let { content(listOf(it)) }.orEmpty()
                val effect = content(before.dropLast(1) + after)
                return cause to effect
            }
            if (marker.contains("때문") || marker.contains("because") || marker.contains("due")) {
                return content(before) to content(after)
            }
        }

        val koreanAgent = Regex("([가-힣A-Za-z0-9]+)(?:은|는|이|가)?\\s*에\\s*의해").find(text)
            ?.groupValues?.getOrNull(1)?.let(::argumentToken)
        if (koreanAgent != null && relation.family in setOf("creation", "establishment", "invention", "development")) {
            val passiveSubject = before.lastOrNull { it.endsWith("은") || it.endsWith("는") || it.endsWith("이") || it.endsWith("가") }
            return setOf(koreanAgent) to passiveSubject?.let { setOf(argumentToken(it)) }.orEmpty()
        }

        val subjectMarked = before.lastOrNull { it.endsWith("은") || it.endsWith("는") || it.endsWith("이") || it.endsWith("가") }
        val objectMarked = before.lastOrNull { it.endsWith("을") || it.endsWith("를") }
        val subject = subjectMarked?.let { marked ->
            content(listOf(marked)).ifEmpty {
                val markedIndex = before.indexOfLast { it == marked }
                content(before.take(markedIndex.coerceAtLeast(0)))
            }
        } ?: content(before.take(1))
        val complement = objectMarked?.let { content(listOf(it)) } ?: run {
            val topicIndex = before.indexOfFirst { it.endsWith("은") || it.endsWith("는") }
            if (topicIndex >= 0 && relation.family in setOf("location", "composition", "property")) {
                content(before.drop(topicIndex + 1) + after)
            } else {
                content(after)
            }
        }
        return subject to complement
    }

    private fun proposition(text: String): Proposition? {
        val relations = relationSignals(text)
        if (relations.size != 1) return null
        val relation = relations.single()
        val (subject, complement) = relationArgumentSets(text, relation)
        val canonicalRelation = if (relation.direction < 0) relation.copy(direction = 1) else relation
        val canonicalArguments = if (relation.direction < 0) complement to subject else subject to complement
        return Proposition(canonicalRelation, canonicalArguments.first, canonicalArguments.second, subject + complement + argumentTokens(text))
    }

    fun hasCompatiblePredicate(claimText: String, sourceText: String): Boolean {
        val claim = proposition(claimText) ?: return false
        val source = proposition(sourceText) ?: return false
        val a = claim.relation
        val b = source.relation
        if (a.family != b.family) return false
        if (a.family != "identity" && a.subtype != b.subtype) return false
        if (a.family == "location" && (a.subtype == "flow" || b.subtype == "flow") && a.subtype != b.subtype) return false
        if (a.family == "comparison" && a.direction != b.direction) return false
        return true
    }

    private fun overlapsSpecific(first: Set<String>, second: Set<String>): Boolean =
        first.intersect(second).isNotEmpty()

    private fun argumentsAlign(claim: Proposition, source: Proposition): Boolean {
        if (claim.arguments.isEmpty() || source.arguments.isEmpty()) return false
        if (claim.subject.isNotEmpty() && source.subject.isNotEmpty() &&
            claim.subject != source.subject
        ) return false
        if (claim.complement.isNotEmpty() && source.complement.isEmpty()) return false
        if (claim.complement.isNotEmpty() && source.complement.isNotEmpty() &&
            claim.complement != source.complement
        ) return false
        if (claim.complement.isEmpty() && source.complement.isEmpty() &&
            claim.subject.isNotEmpty() && overlapsSpecific(claim.subject, source.subject)
        ) return true
        return claim.arguments.intersect(source.arguments).size >= 1
    }

    private fun propositionAligns(claimText: String, sourceText: String): Boolean {
        val claim = proposition(claimText) ?: return false
        val source = proposition(sourceText) ?: return false
        if (!hasCompatiblePredicate(claimText, sourceText)) return false
        return argumentsAlign(claim, source)
    }

    fun entityTokens(text: String): Set<String> {
        val latin = EntityLikeRegex.findAll(text)
            .map { it.value.lowercase(Locale.ROOT) }
            .filterNot { it in EntityStopWords || it in StopWords || it in MonthNames || isRelationToken(it) }
            .toSet()
        return latin + argumentTokens(text)
    }

    fun criticalAnchors(text: String): Set<String> =
        (AnchorRegex.findAll(text).map { normalizeAnchor(it.value) } +
            FormulaRegex.findAll(text).map { normalizeAnchor(it.value) } +
            preciseDateAnchors(text).map { it.anchor.value }).toSet()

    fun formulaAnchors(text: String): Set<String> = FormulaRegex.findAll(text)
        .map { normalizeAnchor(it.value) }
        .toSet()

    fun anchorsNearRelations(text: String): List<Anchor> {
        val textTokens = tokens(text)
        val relations = relationTokenIndexes(textTokens)
        if (relations.isEmpty()) return emptyList()
        return (AnchorRegex.findAll(text).map {
            Anchored(Anchor(anchorKind(it.value), normalizeAnchor(it.value)), it.range.first)
        } + preciseDateAnchors(text))
            .filter { anchored ->
                val anchorIndex = tokenIndexAtCharacter(text, anchored.characterIndex)
                relations.any { kotlin.math.abs(it - anchorIndex) <= 5 }
            }
            .map { it.anchor }
            .distinct()
            .toList()
    }

    fun hasUnsafeModalityOrNegation(text: String): Boolean {
        val raw = text.lowercase(Locale.ROOT)
        val normalized = text
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .lowercase(Locale.ROOT)
        val koreanTokens = TokenRegex.findAll(text).map { it.value }.toSet()
        if (koreanTokens.any { it in BoundedKoreanAbsenceTokens }) return true
        return UnsafeEnglishRegex.containsMatchIn(normalized) ||
            UnsafeContractionMarkers.any(normalized::contains) ||
            UnsafeContractionMarkers.any(raw::contains) ||
            UnsafeUnicodeContractionRegex.containsMatchIn(raw) ||
            UnsafeKoreanMarkers.any(normalized::contains)
    }

    fun hasCurrentFormerMismatch(claimText: String, sourceText: String): Boolean {
        val current = listOf("currently", "current", "현행", "현재", "현")
        val former = listOf("formerly", "former", "구 ", "이전", "과거")
        val claimLower = claimText.lowercase(Locale.ROOT)
        val sourceLower = sourceText.lowercase(Locale.ROOT)
        val claimCurrent = current.any(claimLower::contains)
        val sourceCurrent = current.any(sourceLower::contains)
        val claimFormer = former.any(claimLower::contains)
        val sourceFormer = former.any(sourceLower::contains)
        return claimCurrent && sourceFormer || claimFormer && sourceCurrent
    }

    fun hasAmbiguousSameKindValues(claimText: String, sourceText: String): Boolean {
        val claimKinds = anchorsNearRelations(claimText).map { it.kind }.toSet()
        val sourceAnchors = anchorsNearRelations(sourceText)
        val comparableKinds = setOf("quantity", "percentage", "year", "date", "month")
        return comparableKinds.any { kind ->
            if (kind !in claimKinds) return@any false
            val values = sourceAnchors.filter { it.kind == kind }
            if (values.map { it.value }.distinct().size <= 1) return@any false
            if (kind != "quantity") return@any true
            val byContext = values.groupBy { quantityContext(sourceText, it.value) }
            byContext.any { (context, contextualValues) ->
                context.isEmpty() || contextualValues.map { it.value }.distinct().size > 1
            }
        }
    }

    fun sameComparisonContext(
        claimText: String,
        sourceText: String,
        claimAnchor: Anchor,
        sourceAnchor: Anchor,
    ): Boolean {
        val claimContext = quantityContext(claimText, claimAnchor.value)
        val sourceContext = quantityContext(sourceText, sourceAnchor.value)
        return claimContext.isEmpty() && sourceContext.isEmpty() || claimContext == sourceContext
    }

    fun sameUnit(first: String, second: String): Boolean =
        quantityUnit(first) != null && quantityUnit(first) == quantityUnit(second)

    fun numericValue(value: String): BigDecimal? {
        val number = QuantityRegex.find(value)?.value?.takeWhile { it.isDigit() || it == '.' || it == ',' } ?: return null
        return number.replace(",", "").toBigDecimalOrNull()
    }

    /**
     * rules-v9: attribute families resolved at EVERY occurrence of [anchorValue]
     * in [text]. The 20-char AFTER context wins ("12GB RAM", "12GB 저장공간");
     * the BEFORE context is consulted only when after-context carries no
     * family marker ("메모리가 12GB이다"). A window containing both families,
     * or neither, resolves nothing. "그램" is stripped before matching "램" so
     * weights never masquerade as memory.
     *
     * Spaced quantity forms (12 GB, 12  GB, 12 gb, 12 Gb) are matched via
     * a flexible regex in the original text, preserving word boundaries for
     * attribute markers like "RAM"/"램".
     */
fun quantityAttributeFamilies(text: String, anchorValue: String): Set<QuantityAttributeFamily> {
        val needle = anchorValue.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        if (needle.isEmpty()) return emptySet()

        // Build a regex that matches the quantity with flexible spacing
        // e.g., "12gb" -> matches "12GB", "12 GB", "12  GB", "12 gb", etc.
        val numberPart = needle.takeWhile { it.isDigit() || it == '.' }
        val unitPart = needle.dropWhile { it.isDigit() || it == '.' }
val flexiblePattern = "(?i)(?<![A-Za-z0-9])" + Regex.escape(numberPart) + "\\s*" + Regex.escape(unitPart) + "(?![A-Za-z0-9])"
        val quantityRegex = Regex(flexiblePattern)

        val lowered = text.lowercase(Locale.ROOT)
        val families = mutableSetOf<QuantityAttributeFamily>()

        quantityRegex.findAll(lowered).forEach { match ->
            val afterStart = minOf(lowered.length, match.range.last + 1)
            val afterEnd = minOf(lowered.length, afterStart + 20)
            val beforeStart = maxOf(0, match.range.first - 20)
            val beforeEnd = match.range.first
            val after = lowered.substring(afterStart, afterEnd)
            val before = lowered.substring(beforeStart, beforeEnd)
            classifyAttributeContext(after)?.let { families += it }
                ?: classifyAttributeContext(before)?.let { families += it }
        }
        return families
    }

    private fun classifyAttributeContext(window: String): QuantityAttributeFamily? {
        // "그램" (grams) contains the substring "램"; strip weights first.
        // ASCII "ram" needs letter boundaries ("grams"/"programs" are not RAM;
        // "vram" is matched explicitly). Korean markers stay substring-based
        // because particles attach directly ("램을", "메모리를").
        val scrubbed = window.replace("그램", "")
        val memory = RamStandaloneRegex.containsMatchIn(scrubbed) ||
            scrubbed.contains("vram") ||
            scrubbed.contains("memory") ||
            scrubbed.contains("램") ||
            scrubbed.contains("메모리")
        val storage = StorageAttributeMarkers.any { window.contains(it) }
        if (memory == storage) return null
        return if (memory) QuantityAttributeFamily.MEMORY else QuantityAttributeFamily.STORAGE
    }

    private val RamStandaloneRegex = Regex("""(?<![A-Za-z])ram(?![A-Za-z])""")

    fun relationFamilies(text: String): Set<String> = relationSignals(text).map { it.family }.toSet()

    fun singleRelation(text: String): Boolean = relationSignals(text).size == 1

    fun hasEntityBeforeRelation(claimText: String, sourceText: String): Boolean {
        if (propositionAligns(claimText, sourceText)) return true
        val claimFormula = formulaAnchors(claimText)
        val sourceFormula = formulaAnchors(sourceText)
        if (claimFormula.isNotEmpty() && sourceFormula.isNotEmpty() && claimFormula != sourceFormula) {
            val claim = proposition(claimText)
            val source = proposition(sourceText)
            if (claim != null && source != null &&
                claim.subject == source.subject && hasCompatiblePredicate(claimText, sourceText)
            ) return true
        }
        // Bounded attribute aliases (RAM/메모리) may differ lexically while
        // the quantity-scoped family proves that they describe the same slot.
        val claimAttributes = anchorsNearRelations(claimText)
            .flatMap { quantityAttributeFamilies(claimText, it.value) }
            .toSet()
        val sourceAttributes = anchorsNearRelations(sourceText)
            .flatMap { quantityAttributeFamilies(sourceText, it.value) }
            .toSet()
        if (claimAttributes.isEmpty() || !sourceAttributes.containsAll(claimAttributes)) return false
        val claim = proposition(claimText) ?: return false
        val source = proposition(sourceText) ?: return false
        return hasCompatiblePredicate(claimText, sourceText) &&
            claim.subject.intersect(source.subject).isNotEmpty()
    }

    fun subjectArguments(text: String): Set<String> = proposition(text)?.subject.orEmpty()

    // ---- Completed vs ambiguous present/scheduled event forms ----

    private val CompletedEnglishEventForms = setOf(
        "released", "launched", "announced", "produced", "manufactured",
        "increased", "decreased", "founded", "established", "started", "began",
    )

    private val AmbiguousEnglishEventForms = setOf(
        "releases", "launches", "announces", "produces", "manufactures",
    )

    private val CompletedKoreanEventForms = setOf(
        "출시했다", "출시됐다", "발표했다", "발표됐다", "생산했다", "제조했다",
        "양산했다", "양산을 시작했다", "생산을 시작했다",
    )

    private val AmbiguousKoreanEventForms = setOf(
        "출시한다", "발표한다", "생산한다", "제조한다", "양산한다",
    )

    /**
     * Returns true when the claim uses a completed event form and the
     * source uses an ambiguous present/scheduled form instead. In that
     * case the source cannot prove the completed event happened.
     */
    fun hasIncompatibleEventForm(claimText: String, sourceText: String): Boolean {
        val claimTokens = tokens(claimText).toSet()
        val sourceTokens = tokens(sourceText).toSet()
        val claimHasCompleted = CompletedEnglishEventForms.any { it in claimTokens } ||
            CompletedKoreanEventForms.any { it in claimTokens }
        if (!claimHasCompleted) return false
        val sourceHasAmbiguous = AmbiguousEnglishEventForms.any { it in sourceTokens } ||
            AmbiguousKoreanEventForms.any { it in sourceTokens }
        return sourceHasAmbiguous
    }

    // ---- Bounded CURRENT_STATE past-form guard. A past-only proposition for a
    // claim's relation family can never prove a current state merely because the
    // publication is fresh. Present forms keep supporting. ----

    private val CurrentStateEnglishPastForms = mapOf(
        "support" to setOf("supported"),
        "production" to setOf("manufactured", "produced"),
        "containment" to setOf("had", "contained", "included"),
        "composition" to setOf("had", "contained", "included"),
        "release" to setOf("released", "launched"),
        "announcement" to setOf("announced"),
        "increase" to setOf("increased"),
        "decrease" to setOf("decreased"),
        "report" to setOf("reported"),
        "foundation" to setOf("founded", "established"),
        "establishment" to setOf("founded", "established"),
    )

    private val CurrentStateKoreanPastForms = mapOf(
        "support" to setOf("지원했다", "지원했습니다"),
        "production" to setOf("생산했다", "생산했습니다", "제조했다", "제조했습니다", "양산했다", "양산했습니다"),
        "containment" to setOf("포함했다", "포함했습니다", "포함됐다"),
        "release" to setOf("출시했다", "출시했습니다", "출시됐다"),
        "announcement" to setOf("발표했다", "발표했습니다", "발표됐다"),
        "increase" to setOf("증가했다"),
        "decrease" to setOf("감소했다", "감소했다는"),
        "foundation" to setOf("설립했다", "설립됐다"),
        "establishment" to setOf("설립했다", "설립됐다"),
    )

    private val CurrentStateEnglishPresentForms = mapOf(
        "support" to setOf("support", "supports", "supporting"),
        "production" to setOf("manufacture", "manufactures", "produce", "produces", "producing"),
        "containment" to setOf("has", "have", "contains", "contain", "include", "includes", "including"),
        "composition" to setOf("has", "have", "contains", "contain", "include", "includes", "including"),
        "release" to setOf("release", "releases", "releasing"),
        "announcement" to setOf("announce", "announces", "announcing"),
        "increase" to setOf("increase", "increases", "increasing"),
        "decrease" to setOf("decrease", "decreases", "decreasing"),
        "report" to setOf("report", "reports", "reporting"),
    )

    private val CurrentStateKoreanPresentForms = mapOf(
        "support" to setOf("지원한다", "지원합니다", "지원하는"),
        "production" to setOf("생산한다", "생산합니다", "제조한다", "제조합니다", "양산한다", "양산합니다"),
        "containment" to setOf("포함한다", "포함합니다", "포함하는"),
        "release" to setOf("출시한다", "출시합니다"),
        "announcement" to setOf("발표한다", "발표합니다"),
    )

    private val GenericPastCopulaRegex = Regex("""(?i)(?<![a-z0-9])(?:was|were)(?![a-z0-9])""")

    /**
     * Bounded CURRENT_STATE-only guard: returns true when the segment reports
     * the claim's relation using a past form and does not also carry a present
     * form for the same family. A generic past copula ("was"/"were") likewise
     * cannot prove a current state.
     */
    fun hasCurrentStatePastFormOnly(claimText: String, segmentText: String): Boolean {
        val families = relationFamilies(claimText)
        if (families.isEmpty()) return false
        val segmentTokens = tokens(segmentText).toSet()
        val anyFamilyPast = families.any { family ->
            (CurrentStateEnglishPastForms[family].orEmpty() + CurrentStateKoreanPastForms[family].orEmpty())
                .any { it in segmentTokens }
        }
        val anyFamilyPresent = families.any { family ->
            (CurrentStateEnglishPresentForms[family].orEmpty() + CurrentStateKoreanPresentForms[family].orEmpty())
                .any { it in segmentTokens }
        }
        val anyGenericPast = GenericPastCopulaRegex.containsMatchIn(segmentText.lowercase(Locale.ROOT))
        if (anyGenericPast || anyFamilyPast) return !anyFamilyPresent
        return false
    }

    // ---- Bounded leading-question detector. Terminal '?' is preserved by the
    // segmenter; question-like headings without punctuation are caught here. ----

    private val EnglishLeadingInterrogativeTokens = setOf(
        "is", "are", "was", "were",
        "does", "do", "did",
        "can", "could", "should",
        "has", "have", "had",
        "what", "when", "where", "which", "who", "whom", "whose", "why", "how",
        "whether",
    )

    private val KoreanInterrogativeEndings = listOf("습니까", "합니까", "입니까", "됩니까", "는가", "은가", "인가")

    fun isLeadingInterrogative(segment: String): Boolean {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return false
        val matches = TokenRegex.findAll(trimmed)
        val first = matches.firstOrNull()?.value?.lowercase(Locale.ROOT)
        if (first != null && first in EnglishLeadingInterrogativeTokens) return true
        val last = matches.lastOrNull()?.value?.lowercase(Locale.ROOT)
        return last != null && KoreanInterrogativeEndings.any { last.endsWith(it) }
    }

    private fun relationTokenIndexes(textTokens: List<String>): List<Int> =
        relationSignals(textTokens.joinToString(" ")).map { it.tokenIndex }

    private fun anchorKind(value: String): String = when {
        value.matches(Regex("(?i)(?:\\d{4}|\\d{3,4}\\s*년)")) -> "year"
        value.contains('%') -> "percentage"
        value.matches(Regex("(?i)\\d[\\d,]*(?:\\.\\d+)?\\s*(?:%|gb|tb|mb|kb|억|만|명|개|대|원|달러|도|degrees?|km/s|m/s)")) -> "quantity"
        else -> "identifier"
    }

    private fun normalizeAnchor(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun preciseDateAnchors(text: String): List<Anchored> {
        val result = mutableListOf<Anchored>()

        fun addDate(year: String, month: String, day: String, characterIndex: Int) {
            val date = runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull() ?: return
            result += Anchored(Anchor("date", "date:" + date), characterIndex)
            result += Anchored(
                Anchor("month", "month:" + date.year + "-" + date.monthValue.toString().padStart(2, '0')),
                characterIndex,
            )
        }

        fun addMonth(year: String, month: String, characterIndex: Int) {
            val monthValue = month.toIntOrNull()?.takeIf { it in 1..12 } ?: return
            result += Anchored(
                Anchor("month", "month:" + year.toInt() + "-" + monthValue.toString().padStart(2, '0')),
                characterIndex,
            )
        }

        IsoDateRegex.findAll(text).forEach { addDate(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.range.first) }
        KoreanDateRegex.findAll(text).forEach { addDate(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.range.first) }
        KoreanMonthRegex.findAll(text).forEach { addMonth(it.groupValues[1], it.groupValues[2], it.range.first) }
        EnglishMonthFirstRegex.findAll(text).forEach {
            addDate(it.groupValues[3], englishMonthNumber(it.groupValues[1]).toString(), it.groupValues[2], it.range.first)
        }
        EnglishMonthLastRegex.findAll(text).forEach {
            addDate(it.groupValues[3], englishMonthNumber(it.groupValues[2]).toString(), it.groupValues[1], it.range.first)
        }
        return result.distinct()
    }

    private fun englishMonthNumber(name: String): Int = when (name.lowercase(Locale.ROOT)) {
        "january" -> 1
        "february" -> 2
        "march" -> 3
        "april" -> 4
        "may" -> 5
        "june" -> 6
        "july" -> 7
        "august" -> 8
        "september" -> 9
        "october" -> 10
        "november" -> 11
        else -> 12
    }

    /**
     * Deterministically extracts every explicit absolute date found in [text].
     * Bounded: only full year-month-day dates; month-only mentions are ignored.
     */
    fun extractDates(text: String): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        fun addDate(year: String, month: String, day: String) {
            runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()?.let { result += it }
        }
        IsoDateRegex.findAll(text).forEach { addDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        KoreanDateRegex.findAll(text).forEach { addDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        EnglishMonthFirstRegex.findAll(text).forEach {
            addDate(it.groupValues[3], englishMonthNumber(it.groupValues[1]).toString(), it.groupValues[2])
        }
        EnglishMonthLastRegex.findAll(text).forEach {
            addDate(it.groupValues[3], englishMonthNumber(it.groupValues[2]).toString(), it.groupValues[1])
        }
        return result.distinct()
    }

    private fun quantityUnit(value: String): String? =
        QuantityRegex.find(value)?.value?.dropWhile { it.isDigit() || it == '.' || it == ',' || it.isWhitespace() }?.lowercase(Locale.ROOT)

    private fun quantityContext(text: String, normalizedAnchor: String): String {
        val match = AnchorRegex.findAll(text).firstOrNull {
            anchorKind(it.value) == "quantity" && normalizeAnchor(it.value) == normalizedAnchor
        } ?: return ""
        val afterStart = (match.range.last + 1).coerceAtMost(text.length)
        val afterEnd = (afterStart + 20).coerceAtMost(text.length)
        AttributeRegex.find(text.substring(afterStart, afterEnd))?.let {
            return it.value.lowercase(Locale.ROOT)
        }
        val beforeStart = (match.range.first - 20).coerceAtLeast(0)
        return AttributeRegex.find(text.substring(beforeStart, match.range.first))
            ?.value
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    }

    private fun tokenIndexAtCharacter(text: String, characterIndex: Int): Int {
        return TokenRegex.findAll(text).indexOfFirst { it.range.first <= characterIndex && characterIndex <= it.range.last }
            .coerceAtLeast(0)
    }

    private fun stripParticle(value: String): String {
        // Prefer the short topic/object particles so nouns ending in 라/리/etc.
        // are not truncated by a surface sequence such as "사하라는".
        val suffixes = listOf("을", "를", "은", "는", "이", "가", "에", "와", "과", "도", "의", "으로", "에서", "에게", "처럼", "보다", "까지", "부터")
        return suffixes.firstOrNull { value.length > it.length && value.endsWith(it) }
            ?.let { value.removeSuffix(it) }
            ?: value
    }
}

internal object EvidencePublishedAtParser {
    private val LocalSlashDateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun parseMillis(raw: String, zoneId: ZoneId): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return parseInstant(value)
            ?: parseOffsetDateTime(value)
            ?: parseZonedDateTime(value)
            ?: parseLocalDateTime(value, zoneId)
            ?: parseLocalDate(value, zoneId)
    }

    private fun parseInstant(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun parseOffsetDateTime(value: String): Long? =
        runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

    private fun parseZonedDateTime(value: String): Long? =
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

    private fun parseLocalDateTime(value: String, zoneId: ZoneId): Long? =
        runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()

    private fun parseLocalDate(value: String, zoneId: ZoneId): Long? =
        runCatching {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
            ?: runCatching {
                LocalDate.parse(value, LocalSlashDateFormatter)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
}

class EvidenceVerificationService(
    private val searchExecutor: EvidenceSearchExecutor,
    private val clock: EvidenceClock = EvidenceClock { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val assessor: EvidenceAssessor = ConservativeRuleEvidenceAssessor(clock, zoneId),
    private val overallDeadlineMs: Long = EvidenceVerificationLimits.OVERALL_VERIFICATION_DEADLINE_MS,
    /**
     * Optional S1 semantic candidate discovery. Null (default) preserves the
     * existing deterministic path bit-for-bit. When present, the matcher may
     * only ADD bounded candidate sources for the deterministic assessor;
     * verdict semantics and [EVIDENCE_VERIFIER_VERSION] are unchanged.
     */
    private val semanticMatcher: SemanticCandidateMatcher? = null,
    private val auditExecutionOrchestrator: AnswerAuditExecutionOrchestrator = AnswerAuditExecutionOrchestrator(),
) {

    suspend fun verify(
        message: MessageEntity,
        plan: VerificationPlan,
    ): EvidenceVerificationResult {
        if (message.role != "assistant") return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.INVALID_MESSAGE)
        if (overallDeadlineMs <= 0L) return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.DEADLINE_EXCEEDED)

        val answerHash = EvidenceFingerprint.canonicalAnswerHash(message.content)
        val boundedPlan = boundedPlan(plan) ?: return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.INVALID_PLAN)

        return try {
            withTimeout(overallDeadlineMs) {
                buildCompleteReport(message, answerHash, boundedPlan)
            }
        } catch (_: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("verification cancelled")
            EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.DEADLINE_EXCEEDED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun buildCompleteReport(
        message: MessageEntity,
        answerHash: String,
        plan: VerificationPlan,
    ): EvidenceVerificationResult {
        // An assistant message carrying local-RAG provenance embeds private
        // attachment-derived facts; its claims must not become web egress.
        val privateDerivedAnswer = LocalRagSourceMetadata.hasProvenance(message.content)
        val sourceByPlanId = plan.existingSources.associateBy { it.id }
        val targetedByClaim = plan.targetedSearchRequests
            .asSequence()
            .filter { request -> plan.claims.any { it.candidate.id == request.claimId } }
            .associateBy { it.claimId }
        val accumulator = EvidenceSourceAccumulator()
        val reportClaims = mutableListOf<EvidenceClaim>()
        val auditInputs = mutableListOf<VerifierClaimInput>()

        for (plannedClaim in plan.claims) {
            val candidate = plannedClaim.candidate
            val targetedRequest = targetedByClaim[candidate.id]
            var limitationReasonCode: String? = null
            if (privateDerivedAnswer && targetedRequest != null) {
                limitationReasonCode = "SEARCH_SUPPRESSED_PRIVATE_SOURCE"
            }
            val claimSourceIds = linkedSetOf<String>()
            val claimSources = mutableListOf<EvidenceSource>()

            plannedClaim.existingSourceCandidateIds
                .asSequence()
                .take(EvidencePlanningLimits.MAX_EXISTING_SOURCE_CANDIDATES_PER_CLAIM)
                .mapNotNull(sourceByPlanId::get)
                .forEach { source ->
                    accumulator.add(source.toEvidenceSource())?.let { finalSource ->
                        claimSourceIds += finalSource.id
                        claimSources += finalSource
                    }
                }

            if (candidate.classification == ClaimClassification.CHECKABLE_FACT &&
                plannedClaim.needsTargetedSearch && targetedRequest == null
            ) {
                reportClaims += EvidenceClaim(
                    id = candidate.id,
                    text = candidate.text,
                    verdict = EvidenceVerdict.NOT_CHECKED,
                    sourceIds = claimSourceIds.toList(),
                    reasonCode = "SEARCH_BUDGET_EXHAUSTED",
                )
                continue
            }

            if (candidate.classification == ClaimClassification.CHECKABLE_FACT) targetedRequest?.let { request ->
                // Phase 8: a local-RAG-derived answer must never turn its
                // claims into web queries. Existing public sources and the
                // local deterministic assessment below continue unchanged.
                if (!privateDerivedAnswer) when (val result = executeSearch(request)) {
                    is EvidenceSearchResult.Success -> result.sources
                        .asSequence()
                        .take(EvidenceVerificationLimits.MAX_SOURCES_PER_SEARCH_RESULT)
                        .mapNotNull { it.toEvidenceSource() }
                        .forEach { source ->
                            accumulator.add(source)?.let { finalSource ->
                                if (claimSourceIds.add(finalSource.id)) claimSources += finalSource
                            }
                        }
                    is EvidenceSearchResult.Failed -> limitationReasonCode = "SEARCH_FAILED"
                }
            }

            // S1 semantic candidate discovery: may only ADD bounded sources for
            // the deterministic assessor below. Any matcher failure (asset
            // missing, tokenizer/model/inference failure, OOM, unsupported
            // ABI, cancellation of the matcher scope) keeps [claimSources]
            // unchanged. Scores are never thresholds and never decide verdicts.
            expandWithSemantic(
                claimText = candidate.text,
                claimSources = claimSources,
                claimSourceIds = claimSourceIds,
                pool = plan.existingSources.map { it.toEvidenceSource() },
                accumulator = accumulator,
            )

            // One immutable post-FSE candidate snapshot feeds both independent
            // verification layers. Similarity only selected membership/order.
            val selectedCandidates = claimSources.toList()
            val assessment = assessor.assess(candidate, selectedCandidates)
            if (candidate.classification == ClaimClassification.CHECKABLE_FACT) {
                auditInputs += VerifierClaimInput(
                    claimId = candidate.id,
                    claimText = candidate.text,
                    selectedCandidates = selectedCandidates,
                )
            }
            reportClaims += EvidenceClaim(
                id = candidate.id,
                text = candidate.text,
                verdict = assessment.verdict,
                sourceIds = assessment.sourceIds.distinct().take(EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM),
                reasonCode = limitationReasonCode ?: assessment.reasonCode,
            )
        }

        val selectedClaimIds = plan.claims.map { it.candidate.id }.toSet()
        plan.allCandidates
            .asSequence()
            .filter { it.classification == ClaimClassification.CHECKABLE_FACT && it.id !in selectedClaimIds }
            .take(EvidencePlanningLimits.MAX_CLAIM_CANDIDATES)
            .forEach { candidate ->
                reportClaims += EvidenceClaim(
                    id = candidate.id,
                    text = candidate.text,
                    verdict = EvidenceVerdict.NOT_CHECKED,
                    sourceIds = emptyList(),
                    reasonCode = "CLAIM_BUDGET_EXHAUSTED",
                )
            }

        if (EvidenceFingerprint.canonicalAnswerHash(message.content) != answerHash) {
            return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.STALE_ANSWER)
        }

        val report = EvidenceReport(
            schemaVersion = 1,
            answerSha256 = answerHash,
            checkedAt = clock.nowMillis(),
            verifierVersion = EVIDENCE_VERIFIER_VERSION,
            claims = reportClaims,
            sources = accumulator.sources,
            candidateWindowTruncated = plan.candidateWindowTruncated,
            overlengthSegmentsExcluded = plan.overlengthSegmentsExcluded,
        )
        if (EvidenceCodec.validate(report) is EvidenceValidation.Invalid) {
            return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.CODEC_REJECTED)
        }
        val encoded = runCatching { EvidenceCodec.serialize(report) }.getOrNull()
            ?: return EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.CODEC_REJECTED)
        return when (val loaded = EvidenceCodec.load(encoded)) {
            is EvidenceCodecResult.Valid if loaded.report == report -> {
                val transientAudit = try {
                    auditExecutionOrchestrator.verifyClaims(auditInputs.toList())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Supplemental audit plumbing must never fail the durable rule report.
                    null
                }
                EvidenceVerificationResult.Complete(report, transientAudit)
            }
            else -> EvidenceVerificationResult.Failed(EvidenceVerificationFailureReason.CODEC_REJECTED)
        }
    }

    private fun boundedPlan(plan: VerificationPlan): VerificationPlan? {
        val claims = plan.claims
            .take(EvidenceVerificationLimits.MAX_CLAIMS_TO_CHECK)
            .map { planned ->
                if (planned.candidate.id.length > EvidenceLimits.MAX_ID_LENGTH ||
                    planned.candidate.text.length > EvidenceLimits.MAX_CLAIM_TEXT_LENGTH || planned.candidate.id.isBlank()
                ) return null
                planned.copy(
                    existingSourceCandidateIds = planned.existingSourceCandidateIds
                        .take(EvidencePlanningLimits.MAX_EXISTING_SOURCE_CANDIDATES_PER_CLAIM)
                        .filter { it.length <= EvidenceLimits.MAX_ID_LENGTH && it.isNotBlank() }
                        .distinct(),
                )
            }
        val sources = plan.existingSources
            .take(EvidencePlanningLimits.MAX_EXISTING_SOURCES)
            .filter { it.id.length <= EvidenceLimits.MAX_ID_LENGTH && it.id.isNotBlank() }
            .map { source -> source.toBoundedPlanningSource() }
        val requests = plan.targetedSearchRequests
            .take(EvidenceVerificationLimits.MAX_TARGETED_SEARCH_REQUESTS)
            .mapNotNull { request ->
                if (request.claimId.length > EvidenceLimits.MAX_ID_LENGTH ||
                    request.query.length > EvidencePlanningLimits.MAX_TARGETED_QUERY_LENGTH || request.claimId.isBlank()
                ) return@mapNotNull null
                val query = request.query.trim()
                if (query.isBlank()) null else TargetedSearchRequest(request.claimId, query)
            }
        val allCandidates = plan.allCandidates
            .take(EvidencePlanningLimits.MAX_CLAIM_CANDIDATES)
            .map { candidate ->
                if (candidate.id.length > EvidenceLimits.MAX_ID_LENGTH ||
                    candidate.text.length > EvidenceLimits.MAX_CLAIM_TEXT_LENGTH ||
                    candidate.id.isBlank()
                ) return null
                candidate
            }
        return VerificationPlan(
            claims = claims,
            existingSources = sources,
            targetedSearchRequests = requests,
            allCandidates = allCandidates,
            candidateWindowTruncated = plan.candidateWindowTruncated,
            overlengthSegmentsExcluded = plan.overlengthSegmentsExcluded,
        )
    }

    private suspend fun executeSearch(request: TargetedSearchRequest): EvidenceSearchResult = try {
        searchExecutor.search(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        EvidenceSearchResult.Failed(exception::class.simpleName ?: "SEARCH_FAILED")
    }

    /**
     * Optional semantic expansion over the full existing-source pool.
     * Discovery-only: appends at most the remaining budget from the semantic
     * ranking via [EvidenceSourceAccumulator] (content-deduped, re-identified)
     * and never removes, reorders verdict inputs, or persists scores.
     */
    private suspend fun expandWithSemantic(
        claimText: String,
        claimSources: MutableList<EvidenceSource>,
        claimSourceIds: LinkedHashSet<String>,
        pool: List<EvidenceSource>,
        accumulator: EvidenceSourceAccumulator,
    ) {
        val matcher = semanticMatcher ?: return
        if (pool.isEmpty()) return
        if (claimSources.size >= EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES) return
        try {
            val ranked = SemanticEvidenceCandidates.rankSources(
                claimText = claimText,
                sources = pool,
                matcher = matcher,
                // Dedicated semantic Top-K: recall aid only, never the whole pool.
                limit = SemanticEvidenceCandidates.MAX_SEMANTIC_CANDIDATES_PER_CLAIM,
            ) ?: return
            if (ranked.unavailableReason != null) return
            val poolById = pool.associateBy { it.id }
            for (match in ranked.matches) {
                if (claimSources.size >= EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES) break
                val candidate = poolById[match.segmentId] ?: continue
                val added = accumulator.add(candidate) ?: continue
                if (claimSourceIds.add(added.id)) claimSources += added
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fail-safe: keep deterministic sources unchanged.
        }
    }

    private fun EvidencePlanningSource.toBoundedPlanningSource(): EvidencePlanningSource = copy(
        id = id.take(EvidenceLimits.MAX_ID_LENGTH),
        title = title.take(EvidenceLimits.MAX_TITLE_LENGTH),
        source = source?.take(EvidenceLimits.MAX_SOURCE_NAME_LENGTH),
        url = url?.take(EvidenceLimits.MAX_URL_LENGTH),
        snippet = snippet?.take(EvidenceLimits.MAX_SNIPPET_LENGTH),
        publishedAt = publishedAt?.take(EvidenceLimits.MAX_PUBLISHED_AT_LENGTH),
        providerDisplayName = providerDisplayName.take(EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH),
        queryUsed = queryUsed.take(EvidenceLimits.MAX_QUERY_USED_LENGTH),
        searchCategory = searchCategory?.take(SearchSourceMetadataLimits.MAX_SEARCH_CATEGORY_LENGTH),
        qualityHints = qualityHints.take(SearchSourceMetadataLimits.MAX_QUALITY_HINTS)
            .map { it.take(SearchSourceMetadataLimits.MAX_QUALITY_HINT_LENGTH) },
    )
}

private class EvidenceSourceAccumulator {
    val sources = mutableListOf<EvidenceSource>()
    private val byKey = linkedMapOf<String, EvidenceSource>()

    fun add(source: EvidenceSource): EvidenceSource? {
        if (!source.isUsable()) return null
        val key = sourceKey(source)
        byKey[key]?.let { return it }
        if (sources.size >= EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES) return null
        val bounded = source.copy(id = "E${sources.size + 1}")
        byKey[key] = bounded
        sources += bounded
        return bounded
    }

    private fun sourceKey(source: EvidenceSource): String {
        val normalizedUrl = source.url.trim().lowercase(Locale.ROOT)
        if (normalizedUrl.isNotBlank()) return "url:$normalizedUrl"
        return listOf(source.title, source.source, source.snippet, source.publishedAt, source.providerDisplayName, source.queryUsed)
            .joinToString("|") { it.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") }
    }
}

private fun EvidencePlanningSource.toEvidenceSource(): EvidenceSource = EvidenceSource(
    id = id,
    title = title.take(EvidenceLimits.MAX_TITLE_LENGTH),
    source = source.orEmpty().take(EvidenceLimits.MAX_SOURCE_NAME_LENGTH),
    url = url.orEmpty().take(EvidenceLimits.MAX_URL_LENGTH),
    snippet = snippet.orEmpty().take(EvidenceLimits.MAX_SNIPPET_LENGTH),
    publishedAt = publishedAt?.take(EvidenceLimits.MAX_PUBLISHED_AT_LENGTH),
    providerType = providerType.name.take(EvidenceLimits.MAX_PROVIDER_TYPE_LENGTH),
    providerDisplayName = providerDisplayName.take(EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH),
    queryUsed = queryUsed.take(EvidenceLimits.MAX_QUERY_USED_LENGTH),
)

private fun WebSearchSource.toEvidenceSource(): EvidenceSource? {
    val source = EvidenceSource(
        id = "",
        title = title.take(EvidenceLimits.MAX_TITLE_LENGTH),
        source = source.orEmpty().take(EvidenceLimits.MAX_SOURCE_NAME_LENGTH),
        url = url.orEmpty().take(EvidenceLimits.MAX_URL_LENGTH),
        snippet = snippet.orEmpty().take(EvidenceLimits.MAX_SNIPPET_LENGTH),
        publishedAt = publishedAt?.take(EvidenceLimits.MAX_PUBLISHED_AT_LENGTH),
        providerType = providerType.name.take(EvidenceLimits.MAX_PROVIDER_TYPE_LENGTH),
        providerDisplayName = providerDisplayName.take(EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH),
        queryUsed = queryUsed.take(EvidenceLimits.MAX_QUERY_USED_LENGTH),
    )
    return source.takeIf { it.isUsable() }
}

private fun EvidenceSource.isUsable(): Boolean = title.isNotBlank() || source.isNotBlank() || url.isNotBlank() || snippet.isNotBlank()

/** Evidence adapter; it does not alter normal chat search behavior. */
class FusionEvidenceSearchExecutor(
    private val providerRepository: WebSearchProviderRepository? = null,
) : PostAnswerVerificationSearchExecutor {
    /** Direct targeted execution is refused; callers must provide the post-answer consent seam. */
    override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult =
        EvidenceSearchResult.Failed("EGRESS_REFUSED")

    override suspend fun searchAuthorizedPostAnswerVerification(
        request: TargetedSearchRequest,
        operationId: String,
        identity: PostAnswerVerificationSearchIdentity,
        authorization: PostAnswerVerificationSearchAuthorization,
        isCurrent: suspend () -> Boolean,
    ): EvidenceSearchResult {
        if (!DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                authorization = authorization,
                operationId = operationId,
                identity = identity,
                claimId = request.claimId,
                query = request.query,
            ) || !isCurrent()
        ) return EvidenceSearchResult.Failed("EGRESS_REFUSED")
        return try {
            val response = FusionWebSearch.searchExactApprovedQuery(request.query, providerRepository)
            response.toEvidenceSearchResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            EvidenceSearchResult.Failed(exception::class.simpleName ?: "SEARCH_FAILED")
        }
    }
}

internal fun com.projectnuke.fusion.search.FusionSearchResponse.toEvidenceSearchResult(): EvidenceSearchResult {
    if (sources.isEmpty() && traces.any {
        it.exceptionClass != null || it.httpStatus?.let { status -> status !in 200..299 } == true ||
            (it.httpStatus == null && it.fallbackReason != null)
    }) return EvidenceSearchResult.Failed("SEARCH_UNAVAILABLE")
    return EvidenceSearchResult.Success(sources.take(EvidenceVerificationLimits.MAX_SOURCES_PER_SEARCH_RESULT))
}
