package com.projectnuke.fusion.search

import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.util.Locale

/** Whether the retrieved set supports the specific fact requested by the user. */
enum class SearchAnswerSufficiencyStatus {
    NOT_EVALUATED,
    NOT_REQUIRED,
    SUFFICIENT,
    INSUFFICIENT,
}

internal enum class SearchOfficialAuthorityStatus { NOT_REQUESTED, PRESENT, MISSING }

internal enum class SearchEvidenceFreshnessStatus { NOT_REQUIRED, UNDATED, FRESH, STALE }

internal enum class SearchRequestedFact { NONE, DATE, RATE, POPULATION, COUNT, PRICE, CURRENT_STATUS }

internal data class SearchAnswerSufficiencyAssessment(
    val status: SearchAnswerSufficiencyStatus,
    val requestedFact: SearchRequestedFact,
    val officialAuthority: SearchOfficialAuthorityStatus,
    val freshness: SearchEvidenceFreshnessStatus,
    val directFactEvidencePresent: Boolean,
    val reasonCodes: List<String>,
) {
    val answerSufficient: Boolean
        get() = status != SearchAnswerSufficiencyStatus.INSUFFICIENT
}

internal data class SearchResultRelevanceSignals(
    val officialAuthorityMatch: Boolean,
    val directFactSupport: Boolean,
    val freshnessRank: Int,
)

/**
 * Deterministic answer-support checks over bounded search metadata. This does
 * not decide whether a result is topically useful; it decides whether the
 * admitted records support the requested date, value, status, or authority.
 */
internal object SearchAnswerSufficiencyPolicy {
    private val tokenPattern = Regex("[\\p{L}\\p{N}]+")
    private val eventIdPattern = Regex("\\b([A-Z][A-Za-z0-9-]{1,}\\s+(?:[IVXLCDM]{1,5}|\\d+[A-Za-z]?))\\b")
    private val numberPattern = Regex("(?<![\\p{L}\\p{N}])(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?![\\p{L}\\p{N}])")
    private val percentagePattern = Regex("(?i)(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?\\s*(?:%|percent|per\\s+cent|bps?|basis\\s+points)(?![\\p{L}])")
    private val isoDatePattern = Regex("\\b20\\d{2}-\\d{1,2}-\\d{1,2}\\b")
    private val compactPathDatePattern = Regex("(?:^|/)(20\\d{2})(\\d{2})(\\d{2})(?:/|$)")
    private val separatedPathDatePattern = Regex("(?:^|/)(20\\d{2})/(\\d{1,2})/(\\d{1,2})(?:/|$)")
    private val dashedPathDatePattern = Regex("(?<!\\d)(20\\d{2}-\\d{1,2}-\\d{1,2})(?!\\d)")
    private val englishMonthYearPattern = Regex(
        "(?i)\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(20\\d{2})\\b",
    )
    private val koreanMonthYearPattern = Regex("(20\\d{2})\\s*년\\s*(\\d{1,2})\\s*월")
    private val koreanDatePattern = Regex("\\b20\\d{2}\\s*년\\s*\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일")
    private val englishDatePattern = Regex(
        "(?i)\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+\\d{1,2}(?:\\s*[-–]\\s*\\d{1,2})?,?\\s+20\\d{2}\\b",
    )
    private val currentTerms = listOf("current", "currently", "today", "latest", "recent", "now", "현재", "오늘", "최신", "최근", "요즘")
    private val preparatoryActivityTerms = listOf(
        "training", "trained", "simulating", "simulation", "treadmill", "suited task", "rehearsal", "훈련", "시뮬레이션",
    )
    private val plannedStatusTerms = listOf("scheduled", "targeted", "currently targets", "planned to", "expected to launch", "예정", "목표 발사")
    private val preEventContextPattern = Regex("(?i)\\b(?:before|prior to|ahead of)\\b[^.!?]{0,80}\\b(?:mission|flight|launch)\\b")
    private val explicitMissionOutcomePattern = Regex(
        "(?i)\\b(?:mission|flight)\\b[^.!?]{0,40}\\b(?:completed|concluded|ended|returned|splashed down)\\b|" +
            "\\b(?:completed|concluded|ended)\\s+(?:the\\s+)?(?:mission|flight)\\b|" +
            "\\b(?:splashed down|returned (?:safely )?to earth|brought (?:them|the crew|four astronauts) safely back to earth)\\b",
    )
    private val officialTerms = listOf(
        "official source", "official sources", "official announcement", "official data", "official site",
        "official website", "official", "공식 자료", "공식 발표", "공식 사이트", "공식 웹사이트", "정부 발표", "공식",
    )
    private val statusTerms = listOf(
        "currently targeting", "is active", "ongoing", "in progress", "scheduled",
        "delayed", "postponed", "cancelled", "canceled", "launched", "lifted off", "flew", "completed",
        "occurred", "splashed down", "발사했", "발사됐", "발사되었", "완료", "진행 중", "취소", "연기",
    )
    private val explicitStatusValuePattern = Regex(
        "(?i)\\bstatus\\s*:\\s*(?:cruise|active|completed|delayed|in transit|on track|planned|scheduled|launched)\\b",
    )
    private val inTransitStatusTerms = listOf(
        "is traveling", "are traveling", "is travelling", "are travelling", "is cruising", "are cruising",
        "currently traveling", "currently travelling", "on its way", "en route", "in transit",
        "항해 중", "항행 중", "순항 중",
    )
    private val officeholderRoleTerms = listOf(
        "prime minister", "president", "governor", "mayor", "chancellor", "premier", "monarch",
        "대통령", "총리", "주지사", "시장",
    )
    private val officeholderAppointmentTerms = listOf(
        "inaugurated", "appointed", "designated", "elected", "sworn in", "took office", "became",
        "serves as", "serving as", "취임", "임명", "지명", "선출", "당선", "재임",
    )
    private val officeholderDisqualifyingPrefixPattern = Regex(
        "(?i)(?:\\b(?:former|previous|previously|ex|past|prior|late)\\b[-\\s]*|(?:^|\\s)(?:전|전직|이전)\\s*)$",
    )
    private val officeholderFutureAppointmentPattern = Regex(
        "(?i)(?:\\b(?:will\\s+be|(?:is|was)\\s+expected\\s+to\\s+be|is\\s+scheduled\\s+to\\s+be|is\\s+set\\s+to\\s+be)\\s+" +
            "(?:inaugurated|appointed|designated|elected|sworn\\s+in)\\b|" +
            "\\bwill\\s+take\\s+office\\b|" +
            "\\b(?:inauguration|appointment|swearing-in)\\s+is\\s+(?:scheduled|planned|expected)\\b|" +
            "\\b예정\\b)",
    )
    private val capitalizedPersonNameAfterRolePattern = Regex("\\b[A-Z][\\p{L}'’.-]+(?:\\s+[A-Z][\\p{L}'’.-]+)?\\b")
    private val numericNegationPattern = Regex(
        "(?i)(?:\\b(?:not|never|without|unlikely|nor|isn't|wasn't|aren't|weren't|doesn't|didn't|don't|cannot|can't|won't)\\b|\\bno\\s+longer\\b|않|아니|없)",
    )
    private val numericNonAssertivePattern = Regex(
        "(?i)(?:" +
            "\\b(?:above|below|under|over|between|about|approximately|around|roughly|nearly|almost)\\b|" +
            "\\b(?:at\\s+least|at\\s+most|less\\s+than|more\\s+than|greater\\s+than|fewer\\s+than|up\\s+to|no\\s+more\\s+than|no\\s+less\\s+than|in\\s+excess\\s+of|near)\\b|" +
            "\\b(?:may|might|could|would|will|expected|forecast|projected|target|targeted|planned|scheduled|outlook)\\b|" +
            "\\b(?:previous|previously|former|formerly|prior|earlier|old)\\b|" +
            "이전|전직|예정|전망|목표" +
            ")",
    )
    private val numericPostQualifierPattern = Regex(
        "(?i)^\\s*(?:(?:%|percent|per\\s+cent|bps?|basis\\s+points)\\s*)?" +
            "(?:target|forecast|projection|expectation|expected|planned|scheduled|previous|former|prior|earlier)\\b",
    )
    private val completionTerms = listOf(
        "launched", "lifted off", "flew", "completed", "occurred", "splashed down", "took place",
        "발사했", "발사됐", "발사되었", "완료", "비행했", "귀환했",
    )
    private val stableEventStatusTerms = listOf(
        "flew", "completed", "occurred", "splashed down", "took place", "returned", "비행했", "귀환했", "완료",
    )
    private val queryStopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "does", "for", "from", "have", "how", "i", "in", "is",
        "it", "of", "on", "or", "the", "to", "was", "what", "when", "where", "which", "who", "why", "with",
        "current", "currently", "latest", "today", "recent", "now", "official", "source", "sources", "date", "target",
        "rate", "policy", "how", "many", "number", "please", "tell", "me", "find", "news", "about", "search",
        "현재", "오늘", "최신", "최근", "공식", "자료", "발표", "사이트", "정부", "언제", "몇", "얼마", "알려줘", "알려주세요",
    )
    private val statusRequestTerms = listOf(
        "status", "state", "progress of", "what is happening to", "what happened to",
        "상태", "현황", "진행 상황", "현재 상황", "어떻게 되고",
    )
    private val statusRequestStopWords = queryStopWords + setOf(
        "status", "state", "progress", "happening", "happened", "update", "updates",
        "상태", "현황", "진행", "상황", "업데이트",
    )
    private val rateCues = listOf("base rate", "policy rate", "interest rate", "rate", "기준금리", "금리", "요율", "수익률")
    private val populationCues = listOf("population", "인구", "residents")
    private val priceCues = listOf("price", "cost", "usd", "krw", "₩", "$", "가격", "주가")
    private val nonPriceMetricPattern = Regex(
        "(?i)(?:\\b(?:price|cost)\\s+(?:index|indices|target|change|increase|decrease|rise|fall|growth|inflation|trend|forecast|outlook|estimate|level)\\b|" +
            "\\bprice[-\\s]+to[-\\s]+earnings\\b|\\bp/e\\b|주가\\s*지수|물가\\s*지수)",
    )
    private val nonPopulationMetricPattern = Regex(
        "(?i)(?:\\bpopulation\\s+(?:density|growth|change|decline|increase|decrease|rate|trend|forecast|projection)\\b|" +
            "인구\\s*(?:밀도|증가율|감소율|변화))",
    )
    private val basisPointChangePattern = Regex(
        "(?i)(?:\\b(?:changed|cut|raised|lowered|hiked|increased|decreased|moved)\\s+by\\b|" +
            "\\b(?:change|cut|hike|move)\\s+of\\b)",
    )
    private val countCues = listOf("member states", "members", "countries", "people", "students", "employees", "users", "회원국", "명", "개", "건", "곳")
    private val entityStopWords = queryStopWords + setOf(
        "base", "benchmark", "change", "decision", "did", "estimate", "interest", "key", "price", "cost",
        "population", "residents", "users", "subscribers", "employees", "people",
        "countries", "country", "member", "members", "state", "states",
    )

    fun evaluate(
        query: String,
        intent: SearchIntent,
        results: List<FusionSearchResult>,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): SearchAnswerSufficiencyAssessment {
        val fact = requestedFact(query, intent)
        val officialRequested = asksForOfficialEvidence(query)
        val eventAnchor = eventIdPattern.find(query)?.groupValues?.getOrNull(1)
        val supportedResults = results.filter { result -> result.title.isNotBlank() && !result.url.isNullOrBlank() }
        val officialResults = supportedResults.filter { matchesRequestedOfficialAuthority(query, it) }
        val officialStatus = when {
            !officialRequested -> SearchOfficialAuthorityStatus.NOT_REQUESTED
            officialResults.isNotEmpty() -> SearchOfficialAuthorityStatus.PRESENT
            else -> SearchOfficialAuthorityStatus.MISSING
        }
        val freshness = freshnessStatus(query, intent, supportedResults, today)
        val directResults = supportedResults.filter { supportsRequestedFact(it, query, fact, eventAnchor, today) }
        val eligibleDirectResults = directResults.filter { result ->
            isEligibleAnswerEvidence(result, query, intent, fact, eventAnchor, today)
        }
        val freshnessEligibleDirectResults = if (fact == SearchRequestedFact.NONE) {
            eligibleDirectResults.filter { matchesRequestedTopicSubject(query, it) }
        } else {
            eligibleDirectResults
        }
        val factResults = if (officialRequested && fact != SearchRequestedFact.NONE) {
            eligibleDirectResults.filter { it in officialResults }
        } else {
            eligibleDirectResults
        }
        val numericFact = fact in setOf(
            SearchRequestedFact.RATE,
            SearchRequestedFact.POPULATION,
            SearchRequestedFact.COUNT,
            SearchRequestedFact.PRICE,
        )
        val conflictCandidates = if (numericFact) {
            val eligibleForConflictCheck = if (officialRequested) directResults.filter { it in officialResults } else directResults
            eligibleForConflictCheck.filter {
                freshnessFor(it, query, intent, today) != SearchEvidenceFreshnessStatus.STALE
            }
        } else {
            emptyList()
        }
        val conflictingValues = numericFact && conflictingRequestedValues(conflictCandidates, fact)
        val conflictingDates = fact == SearchRequestedFact.DATE && conflictingRequestedDates(eligibleDirectResults)
        val freshnessFailed = freshness == SearchEvidenceFreshnessStatus.STALE && freshnessEligibleDirectResults.isEmpty()
        val reasons = buildList {
            if (officialRequested) add(if (officialStatus == SearchOfficialAuthorityStatus.PRESENT) "official_authority_present" else "official_authority_missing")
            if (fact != SearchRequestedFact.NONE && directResults.isEmpty()) add("requested_${fact.name.lowercase(Locale.ROOT)}_not_in_evidence")
            if (fact == SearchRequestedFact.DATE && eventAnchor != null && supportedResults.none { containsPhrase(it, eventAnchor) }) add("named_event_identity_missing")
            if (freshness == SearchEvidenceFreshnessStatus.STALE) add("dated_evidence_stale")
            if (conflictingValues) add("conflicting_requested_values")
            if (conflictingDates) add("conflicting_requested_dates")
        }
        val required = officialRequested || fact != SearchRequestedFact.NONE || freshnessFailed
        val insufficient = (officialRequested && officialStatus == SearchOfficialAuthorityStatus.MISSING) ||
            (fact != SearchRequestedFact.NONE && factResults.isEmpty()) ||
            conflictingValues ||
            conflictingDates ||
            freshnessFailed
        val status = when {
            insufficient -> SearchAnswerSufficiencyStatus.INSUFFICIENT
            required -> SearchAnswerSufficiencyStatus.SUFFICIENT
            else -> SearchAnswerSufficiencyStatus.NOT_REQUIRED
        }
        return SearchAnswerSufficiencyAssessment(
            status = status,
            requestedFact = fact,
            officialAuthority = officialStatus,
            freshness = freshness,
            directFactEvidencePresent = factResults.isNotEmpty(),
            reasonCodes = reasons,
        )
    }

    fun relevanceSignals(
        query: String,
        intent: SearchIntent,
        result: FusionSearchResult,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): SearchResultRelevanceSignals {
        val fact = requestedFact(query, intent)
        return SearchResultRelevanceSignals(
            officialAuthorityMatch = asksForOfficialEvidence(query) && matchesRequestedOfficialAuthority(query, result),
            directFactSupport = supportsRequestedFact(
                result = result,
                query = query,
                fact = fact,
                eventAnchor = eventIdPattern.find(query)?.groupValues?.getOrNull(1),
                today = today,
            ),
            freshnessRank = when (freshnessFor(result, query, intent, today)) {
                SearchEvidenceFreshnessStatus.FRESH -> 2
                SearchEvidenceFreshnessStatus.UNDATED, SearchEvidenceFreshnessStatus.NOT_REQUIRED -> 1
                SearchEvidenceFreshnessStatus.STALE -> 0
            },
        )
    }

    fun asksForOfficialEvidence(query: String): Boolean {
        val normalized = query.lowercase(Locale.ROOT)
        return officialTerms.any(normalized::contains)
    }

    /**
     * An explicit official-source request constrains which records may reach
     * answer generation. When no matching authority result exists, this
     * returns an empty list so nonofficial records cannot silently satisfy
     * the request.
     */
    fun restrictToRequestedOfficialEvidence(
        query: String,
        results: List<FusionSearchResult>,
    ): List<FusionSearchResult> =
        if (asksForOfficialEvidence(query)) {
            results.filter { matchesRequestedOfficialAuthority(query, it) }
        } else {
            results
        }

    /**
     * Ranks model-facing records deterministically and removes stale or
     * contradictory numeric facts when one unambiguous fresh fact is present.
     */
    fun selectAnswerEvidence(
        query: String,
        intent: SearchIntent,
        results: List<FusionSearchResult>,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): List<FusionSearchResult> {
        val rankedResults = SearchResultRelevancePolicy.rank(results, query, intent, today)
        val nonStaleNews = if (intent == SearchIntent.NEWS) {
            rankedResults.filter { result ->
                freshnessFor(result, query, intent, today) != SearchEvidenceFreshnessStatus.STALE
            }
        } else {
            emptyList()
        }
        val ranked = nonStaleNews.ifEmpty { rankedResults }
        val eventAnchor = eventIdPattern.find(query)?.groupValues?.getOrNull(1)
        val fact = requestedFact(query, intent)
        val numericFacts = setOf(
            SearchRequestedFact.RATE,
            SearchRequestedFact.POPULATION,
            SearchRequestedFact.COUNT,
            SearchRequestedFact.PRICE,
        )
        val namedAuthorityEvidence = if (fact in numericFacts && !asksForOfficialEvidence(query)) {
            ranked.filter { result ->
                matchesRecognizedAuthorityDomain(query, result) &&
                    supportsRequestedFact(result, query, fact, eventAnchor, today) &&
                    isEligibleAnswerEvidence(result, query, intent, fact, eventAnchor, today)
            }
        } else {
            emptyList()
        }
        val namedAuthorityValues = namedAuthorityEvidence.mapNotNull { requestedNumericValue(it, fact) }.distinct()
        if (namedAuthorityValues.size == 1) return namedAuthorityEvidence
        if (fact != SearchRequestedFact.CURRENT_STATUS && eventAnchor != null) {
            val entityMatchedResults = ranked.filter { containsPhrase(it, eventAnchor) }
            if (entityMatchedResults.isNotEmpty()) return entityMatchedResults
        }
        if (fact == SearchRequestedFact.CURRENT_STATUS) {
            val directStatusResults = ranked.filter { result ->
                supportsRequestedFact(result, query, fact, eventAnchor, today) &&
                    isEligibleAnswerEvidence(result, query, intent, fact, eventAnchor, today)
            }
            if (directStatusResults.isNotEmpty()) return directStatusResults
        }
        if (fact !in numericFacts) return ranked
        val freshDirect = ranked.filter { result ->
            supportsRequestedFact(result, query, fact, eventAnchor, today) &&
                freshnessFor(result, query, intent, today) == SearchEvidenceFreshnessStatus.FRESH
        }
        val freshValues = freshDirect.mapNotNull { requestedNumericValue(it, fact) }.distinct()
        if (freshValues.size != 1) return ranked
        val currentValue = freshValues.single()
        return ranked.filter { result ->
            if (freshnessFor(result, query, intent, today) == SearchEvidenceFreshnessStatus.STALE) {
                false
            } else {
                val value = requestedNumericValue(result, fact)
                value == null || value == currentValue
            }
        }
    }

    private fun isEligibleAnswerEvidence(
        result: FusionSearchResult,
        query: String,
        intent: SearchIntent,
        fact: SearchRequestedFact,
        eventAnchor: String?,
        today: LocalDate,
    ): Boolean {
        val freshness = freshnessFor(result, query, intent, today)
        return freshness in setOf(SearchEvidenceFreshnessStatus.NOT_REQUIRED, SearchEvidenceFreshnessStatus.FRESH) ||
            (freshness == SearchEvidenceFreshnessStatus.UNDATED && matchesRecognizedAuthorityDomain(query, result)) ||
            (fact == SearchRequestedFact.CURRENT_STATUS && hasStableCurrentStatusEvidence(result, eventAnchor, today))
    }

    private fun hasStableCurrentStatusEvidence(
        result: FusionSearchResult,
        eventAnchor: String?,
        today: LocalDate,
    ): Boolean = listOf(result.title, result.snippet.orEmpty()).any { field ->
        val normalized = field.lowercase(Locale.ROOT)
        if (eventAnchor != null && !containsPhrase(field, eventAnchor)) return@any false
        if (preEventContextPattern.containsMatchIn(field) && !explicitMissionOutcomePattern.containsMatchIn(field)) {
            return@any false
        }
        val explicitOutcome = explicitMissionOutcomePattern.containsMatchIn(field)
        val datedPastEvent = findConcreteDate(field)?.isBefore(today) == true &&
            stableEventStatusTerms.any(normalized::contains)
        explicitOutcome || datedPastEvent
    }

    fun isBroadCurrentRequest(query: String, intent: SearchIntent): Boolean =
        intent in setOf(SearchIntent.NEWS, SearchIntent.CURRENT_INFO) || currentTerms.any(query.lowercase(Locale.ROOT)::contains)

    private fun requestedFact(query: String, intent: SearchIntent): SearchRequestedFact {
        if (intent == SearchIntent.STOCK) return SearchRequestedFact.NONE // Retain the established stock-specific evidence gate.
        val text = query.lowercase(Locale.ROOT)
        val detected = when {
            listOf("when", "what date", "target date", "launch date", "on what date", "언제", "목표일", "발사일", "날짜", "일정").any(text::contains) -> SearchRequestedFact.DATE
            listOf("policy rate", "interest rate", "base rate", "rate", "금리", "기준금리", "요율", "수익률").any(text::contains) -> SearchRequestedFact.RATE
            listOf("population", "인구").any(text::contains) -> SearchRequestedFact.POPULATION
            listOf("how many", "number of", "member states", "몇 명", "몇 개", "회원국", "수는 몇").any(text::contains) -> SearchRequestedFact.COUNT
            listOf("price", "cost", "how much", "가격", "얼마").any(text::contains) -> SearchRequestedFact.PRICE
            isBroadCurrentRequest(query, intent) -> SearchRequestedFact.CURRENT_STATUS
            else -> SearchRequestedFact.NONE
        }
        // News intent describes the retrieval topic, but a direct numeric/date
        // question inside that topic still needs answer-bearing evidence.
        return if (
            intent == SearchIntent.NEWS &&
            detected == SearchRequestedFact.CURRENT_STATUS &&
            !hasNamedCurrentStatusRequest(query)
        ) {
            SearchRequestedFact.NONE
        } else {
            detected
        }
    }

    private fun hasNamedCurrentStatusRequest(query: String): Boolean {
        val normalized = query.lowercase(Locale.ROOT)
        if (statusRequestTerms.none(normalized::contains)) return false
        return tokenPattern.findAll(normalized)
            .map { it.value }
            .any { it !in statusRequestStopWords }
    }

    private fun supportsRequestedFact(
        result: FusionSearchResult,
        query: String,
        fact: SearchRequestedFact,
        eventAnchor: String?,
        today: LocalDate,
    ): Boolean {
        val text = "${result.title} ${result.snippet.orEmpty()}"
        val normalized = text.lowercase(Locale.ROOT)
        if (eventAnchor != null && !containsPhrase(result, eventAnchor)) return false
        if (fact in setOf(
                SearchRequestedFact.RATE,
                SearchRequestedFact.POPULATION,
                SearchRequestedFact.COUNT,
                SearchRequestedFact.PRICE,
            ) && !matchesRequestedEntity(query, text)
        ) return false
        return when (fact) {
            SearchRequestedFact.NONE -> true
            SearchRequestedFact.DATE -> {
                val date = findConcreteDate(text)
                if (date == null) return false
                val completionEvidence = completionTerms.any(normalized::contains)
                if (asksForCompletedEvent(query) && !completionEvidence) return false
                if (date < today && !completionEvidence) return false
                true
            }
            SearchRequestedFact.RATE -> contextualNumericValue(result, percentagePattern, rateCues) != null
            SearchRequestedFact.POPULATION -> contextualNumericValue(result, numberPattern, populationCues) != null &&
                !nonPopulationMetricPattern.containsMatchIn(normalized)
            SearchRequestedFact.COUNT -> countSubjectMatches(query, normalized) &&
                contextualNumericValue(result, numberPattern, countCues) != null
            SearchRequestedFact.PRICE -> contextualNumericValue(result, numberPattern, priceCues) != null &&
                !nonPriceMetricPattern.containsMatchIn(normalized)
            SearchRequestedFact.CURRENT_STATUS -> supportsCurrentStatus(result, eventAnchor, today, query)
        }
    }

    private fun supportsCurrentStatus(
        result: FusionSearchResult,
        eventAnchor: String?,
        today: LocalDate,
        query: String,
    ): Boolean {
        val field = "${result.title} ${result.snippet.orEmpty()}"
        val normalized = field.lowercase(Locale.ROOT)
        if (eventAnchor != null && !containsPhrase(field, eventAnchor)) return false
        if (!matchesCurrentStatusSubject(query, field)) return false
        if (supportsCurrentOfficeholderListing(query, field)) return true
        val explicitOutcome = explicitMissionOutcomePattern.containsMatchIn(field)
        val statusMentioned = statusTerms.any(normalized::contains) ||
            inTransitStatusTerms.any(normalized::contains) ||
            explicitStatusValuePattern.containsMatchIn(field) ||
            explicitOutcome
        if (!statusMentioned) return false
        val preparatoryStatus = preEventContextPattern.containsMatchIn(field) &&
            preparatoryActivityTerms.any(normalized::contains)
        val statedPastPlan = plannedStatusTerms.any(normalized::contains) &&
            findConcreteDate(field)?.isBefore(today) == true
        return statusMentioned && (!preparatoryStatus || explicitOutcome) && (!statedPastPlan || explicitOutcome)
    }

    private fun asksForCompletedEvent(query: String): Boolean {
        val normalized = query.lowercase(Locale.ROOT)
        if (Regex("\\b(?:when|what date)\\s+did\\b|\\bdid\\b").containsMatchIn(normalized)) return true
        return completionTerms.any(normalized::contains)
    }

    private fun matchesCurrentStatusSubject(query: String, field: String): Boolean {
        val subjectTerms = tokenPattern.findAll(query.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot(statusRequestStopWords::contains)
            .toList()
        if (subjectTerms.isEmpty()) return true
        val evidenceTerms = tokenPattern.findAll(field.lowercase(Locale.ROOT))
            .map { it.value }
            .toSet()
        return subjectTerms.count(evidenceTerms::contains) >= minOf(2, subjectTerms.size)
    }

    private fun supportsCurrentOfficeholderListing(query: String, field: String): Boolean {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val role = officeholderRoleTerms
            .filter { normalizedQuery.contains(it.lowercase(Locale.ROOT)) }
            .maxByOrNull(String::length)
            ?: return false
        val rolePattern = Regex("(?i)\\b${Regex.escape(role)}\\b")
        val namedHolder = rolePattern.findAll(field).any { roleMatch ->
            val prefixStart = (roleMatch.range.first - 32).coerceAtLeast(0)
            val prefix = field.substring(prefixStart, roleMatch.range.first)
            if (officeholderDisqualifyingPrefixPattern.containsMatchIn(prefix)) return@any false
            val roleTail = field.substring(roleMatch.range.last + 1).take(96)
            namedPersonImmediatelyAfterRole(roleTail)
        }
        val appointmentEvidence = officeholderAppointmentTerms.any(field.lowercase(Locale.ROOT)::contains)
        if (officeholderFutureAppointmentPattern.containsMatchIn(field)) return false
        return namedHolder && appointmentEvidence
    }

    private fun namedPersonImmediatelyAfterRole(roleTail: String): Boolean {
        val tail = roleTail.trimStart(' ', '\t', ':', '：', '-', '–', '—', ',', '.')
        val name = capitalizedPersonNameAfterRolePattern.find(tail) ?: return false
        return name.range.first == 0
    }

    private fun matchesRequestedEntity(query: String, text: String): Boolean {
        val normalizedQuery = canonicalMatchText(query)
        val required = tokenPattern.findAll(normalizedQuery)
            .map { it.value }
            .filterNot(entityStopWords::contains)
            .filter { it.length > 1 || it.any(Char::isDigit) }
            .toList()
        if (required.isEmpty()) return true
        val evidenceTokens = tokenPattern.findAll(canonicalMatchText(text))
            .map { it.value }
            .toMutableSet()
        if (normalizedQuery.contains("united nations") && evidenceTokens.contains("un")) {
            evidenceTokens += setOf("united", "nations")
        }
        if ((normalizedQuery.contains("bank of korea") || normalizedQuery.contains("한국은행")) &&
            evidenceTokens.contains("bok")
        ) {
            evidenceTokens += setOf("bank", "korea")
        }
        return required.all(evidenceTokens::contains)
    }

    private fun countSubjectMatches(query: String, resultText: String): Boolean {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        if (normalizedQuery.contains("member states") && !resultText.contains("member states")) return false
        if (normalizedQuery.contains("회원국") && !resultText.contains("회원국")) return false
        val queryTerms = tokenPattern.findAll(query.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot(queryStopWords::contains)
            .toList()
        if (queryTerms.isEmpty()) return true
        val subjectTerms = queryTerms.filter { resultText.contains(it) }
        return subjectTerms.size >= minOf(2, queryTerms.size)
    }

    private fun conflictingRequestedValues(results: List<FusionSearchResult>, fact: SearchRequestedFact): Boolean {
        val values = results.mapNotNull { requestedNumericValue(it, fact) }.distinct()
        return values.size > 1
    }

    private fun conflictingRequestedDates(results: List<FusionSearchResult>): Boolean {
        val dates = results.mapNotNull { result ->
            findConcreteDate("${result.title} ${result.snippet.orEmpty()}")
        }.distinct()
        return dates.size > 1
    }

    private fun requestedNumericValue(result: FusionSearchResult, fact: SearchRequestedFact): String? = when (fact) {
        SearchRequestedFact.RATE -> contextualNumericValue(result, percentagePattern, rateCues)
        SearchRequestedFact.POPULATION -> contextualNumericValue(result, numberPattern, populationCues)
        SearchRequestedFact.COUNT -> contextualNumericValue(result, numberPattern, countCues)
        SearchRequestedFact.PRICE -> contextualNumericValue(result, numberPattern, priceCues)
        else -> null
    }

    private fun contextualNumericValue(result: FusionSearchResult, numberRegex: Regex, cues: List<String>): String? =
        listOf(result.title, result.snippet.orEmpty()).firstNotNullOfOrNull { field ->
            contextualNumericValue(field, numberRegex, cues)
        }

    private fun contextualNumericValue(text: String, numberRegex: Regex, cues: List<String>): String? {
        val normalized = text.lowercase(Locale.ROOT)
        val candidates = numberRegex.findAll(normalized).filterNot { isCalendarYear(it.value) }.toList()
        val cuePositions = cues.flatMap { cue ->
            Regex(Regex.escape(cue), RegexOption.IGNORE_CASE).findAll(normalized).map { it.range }.toList()
        }
        val number = candidates.firstOrNull { candidate ->
            !isNonAssertedNumericCandidate(normalized, candidate) && cuePositions.any { cue ->
                val gap = when {
                    candidate.range.last < cue.first -> cue.first - candidate.range.last - 1
                    cue.last < candidate.range.first -> candidate.range.first - cue.last - 1
                    else -> 0
                }
                gap <= 36
            }
        } ?: return null
        val numericPrefix = Regex("^[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?")
            .find(number.value)
            ?.value
            ?: return number.value.lowercase(Locale.ROOT).replace(",", "")
        val normalizedValue = runCatching {
            BigDecimal(numericPrefix.replace(",", "")).stripTrailingZeros().toPlainString()
        }.getOrElse { numericPrefix.lowercase(Locale.ROOT).replace(",", "") }
        return if (Regex("(?i)\\b(?:bps?|basis\\s+points?)\\b").containsMatchIn(number.value)) {
            "${normalizedValue}bps"
        } else {
            normalizedValue
        }
    }

    private fun isNonAssertedNumericCandidate(text: String, candidate: MatchResult): Boolean {
        val prefixStart = (candidate.range.first - 48).coerceAtLeast(0)
        val prefix = text.substring(prefixStart, candidate.range.first)
        if (numericNegationPattern.containsMatchIn(prefix) || numericNonAssertivePattern.containsMatchIn(prefix)) {
            return true
        }
        if (Regex("(?i)\\b(?:bps?|basis\\s+points?)\\b").containsMatchIn(candidate.value) &&
            basisPointChangePattern.containsMatchIn(prefix)
        ) {
            return true
        }
        val suffixEnd = (candidate.range.last + 1 + 24).coerceAtMost(text.length)
        val suffix = text.substring(candidate.range.last + 1, suffixEnd)
        return numericPostQualifierPattern.containsMatchIn(suffix)
    }

    private fun isCalendarYear(value: String): Boolean {
        val year = value.toIntOrNull() ?: return false
        return year in 1900..2100
    }

    private fun freshnessStatus(
        query: String,
        intent: SearchIntent,
        results: List<FusionSearchResult>,
        today: LocalDate,
    ): SearchEvidenceFreshnessStatus {
        if (!isBroadCurrentRequest(query, intent)) return SearchEvidenceFreshnessStatus.NOT_REQUIRED
        val fact = requestedFact(query, intent)
        val topicScopedResults = if (fact == SearchRequestedFact.NONE) {
            results.filter { matchesRequestedTopicSubject(query, it) }
        } else {
            results
        }
        val scopedResults = topicScopedResults.ifEmpty { results }
        val eventAnchor = eventIdPattern.find(query)?.groupValues?.getOrNull(1)
        val completedRequestedEvent = fact == SearchRequestedFact.DATE && scopedResults.any { result ->
            supportsRequestedFact(result, query, SearchRequestedFact.DATE, eventAnchor, today) &&
                completionTerms.any("${result.title} ${result.snippet.orEmpty()}".lowercase(Locale.ROOT)::contains)
        }
        if (completedRequestedEvent) return SearchEvidenceFreshnessStatus.FRESH
        val completedRequestedStatus = fact == SearchRequestedFact.CURRENT_STATUS && scopedResults.any { result ->
            supportsCurrentStatus(result, eventIdPattern.find(query)?.groupValues?.getOrNull(1), today, query) &&
                hasStableCurrentStatusEvidence(result, eventIdPattern.find(query)?.groupValues?.getOrNull(1), today)
        }
        if (completedRequestedStatus) return SearchEvidenceFreshnessStatus.FRESH
        val statuses = scopedResults.map { result ->
            result to freshnessFor(result, query, intent, today)
        }
        if (statuses.any { (_, status) -> status == SearchEvidenceFreshnessStatus.FRESH }) {
            return SearchEvidenceFreshnessStatus.FRESH
        }
        val undatedAuthoritative = statuses.any { (result, status) ->
            status == SearchEvidenceFreshnessStatus.UNDATED && matchesRecognizedAuthorityDomain(query, result)
        }
        if (undatedAuthoritative) return SearchEvidenceFreshnessStatus.UNDATED
        if (statuses.any { (_, status) -> status == SearchEvidenceFreshnessStatus.STALE }) {
            return SearchEvidenceFreshnessStatus.STALE
        }
        return if (statuses.any { (_, status) -> status == SearchEvidenceFreshnessStatus.UNDATED }) {
            SearchEvidenceFreshnessStatus.UNDATED
        } else {
            SearchEvidenceFreshnessStatus.NOT_REQUIRED
        }
    }

    private fun matchesRequestedTopicSubject(
        query: String,
        result: FusionSearchResult,
    ): Boolean {
        val subjectTerms = tokenPattern.findAll(query.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot(queryStopWords::contains)
            .filter { it.length > 1 || it.any(Char::isDigit) }
            .toList()
        if (subjectTerms.isEmpty()) return true
        val evidenceTerms = tokenPattern.findAll("${result.title} ${result.snippet.orEmpty()}".lowercase(Locale.ROOT))
            .map { it.value }
            .toSet()
        return subjectTerms.any(evidenceTerms::contains)
    }

    private fun freshnessFor(
        result: FusionSearchResult,
        query: String,
        intent: SearchIntent,
        today: LocalDate,
    ): SearchEvidenceFreshnessStatus {
        if (!isBroadCurrentRequest(query, intent)) return SearchEvidenceFreshnessStatus.NOT_REQUIRED
        val publishedAtDate = publishedDate(result.publishedAt)
        val urlDate = publishedDateFromUrl(result.url)
        val titleDate = findConcreteDate(result.title)
        val titleMonth = findTitleMonth(result.title)
        val date = publishedAtDate ?: urlDate ?: titleDate ?: titleMonth?.atDay(1)
            ?: return SearchEvidenceFreshnessStatus.UNDATED
        val text = "${result.title} ${result.snippet.orEmpty()}"
        val factDate = findConcreteDate(text)
        val completedPastDateAnswer = requestedFact(query, intent) == SearchRequestedFact.DATE &&
            factDate != null && !factDate.isAfter(today) && completionTerms.any(text.lowercase(Locale.ROOT)::contains)
        val historicalFact = factDate != null && factDate.isBefore(today.minusDays(maximumEvidenceAgeDays(query, intent))) &&
            !completionTerms.any(text.lowercase(Locale.ROOT)::contains)
        val staleDate = if (publishedAtDate == null && titleDate == null && titleMonth != null) {
            val maxAgeDays = maximumEvidenceAgeDays(query, intent)
            titleMonth.atEndOfMonth().isBefore(today.minusDays(maxAgeDays)) || titleMonth.atDay(1).isAfter(today.plusDays(1))
        } else {
            isStale(date, query, intent, today)
        }
        return if ((staleDate && !completedPastDateAnswer) || historicalFact) {
            SearchEvidenceFreshnessStatus.STALE
        } else {
            SearchEvidenceFreshnessStatus.FRESH
        }
    }

    private fun findTitleMonth(title: String): java.time.YearMonth? {
        koreanMonthYearPattern.find(title)?.let { match ->
            return runCatching {
                java.time.YearMonth.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }.getOrNull()
        }
        val match = englishMonthYearPattern.find(title) ?: return null
        val month = when (match.groupValues[1].take(3).lowercase(Locale.ROOT)) {
            "jan" -> 1
            "feb" -> 2
            "mar" -> 3
            "apr" -> 4
            "may" -> 5
            "jun" -> 6
            "jul" -> 7
            "aug" -> 8
            "sep" -> 9
            "oct" -> 10
            "nov" -> 11
            "dec" -> 12
            else -> return null
        }
        return runCatching { java.time.YearMonth.of(match.groupValues[2].toInt(), month) }.getOrNull()
    }

    private fun maximumEvidenceAgeDays(query: String, intent: SearchIntent): Long {
        if (intent != SearchIntent.NEWS) return 180L
        val currentTopicNews = currentTerms.any(query.lowercase(Locale.ROOT)::contains) &&
            requestedFact(query, intent) == SearchRequestedFact.NONE
        return if (currentTopicNews) 3L else 30L
    }

    private fun isStale(date: LocalDate, query: String, intent: SearchIntent, today: LocalDate): Boolean {
        val maxAgeDays = maximumEvidenceAgeDays(query, intent)
        return date.isBefore(today.minusDays(maxAgeDays)) || date.isAfter(today.plusDays(1))
    }

    private fun publishedDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        runCatching { LocalDate.parse(value.take(10)) }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()?.let { return it }
        runCatching { Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()?.let { return it }
        return runCatching {
            java.time.ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate()
        }.getOrNull()
    }

    private fun publishedDateFromUrl(raw: String?): LocalDate? {
        val path = raw?.let { runCatching { URL(it).path }.getOrNull() } ?: return null
        val compact = compactPathDatePattern.find(path)?.groupValues
        if (compact != null) {
            runCatching {
                LocalDate.of(compact[1].toInt(), compact[2].toInt(), compact[3].toInt())
            }.getOrNull()?.let { return it }
        }
        val separated = separatedPathDatePattern.find(path)?.groupValues
        if (separated != null) {
            runCatching {
                LocalDate.of(separated[1].toInt(), separated[2].toInt(), separated[3].toInt())
            }.getOrNull()?.let { return it }
        }
        val dashed = dashedPathDatePattern.find(path)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { LocalDate.parse(dashed) }.getOrNull()
    }

    private fun findConcreteDate(text: String): LocalDate? {
        isoDatePattern.find(text)?.value?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()?.let { return it }
        }
        koreanDatePattern.find(text)?.value?.let { value ->
            val parts = Regex("(20\\d{2})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일").find(value)?.groupValues
            if (parts != null) runCatching { LocalDate.of(parts[1].toInt(), parts[2].toInt(), parts[3].toInt()) }.getOrNull()?.let { return it }
        }
        englishDatePattern.find(text)?.value?.let { value ->
            val normalized = value
                .replace(Regex("\\s*[-–]\\s*\\d{1,2}(?=,?\\s+20\\d{2})"), "")
                .replace(",", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val formats = listOf("MMMM d yyyy", "MMM d yyyy")
            formats.forEach { format -> runCatching { LocalDate.parse(normalized, DateTimeFormatter.ofPattern(format, Locale.ENGLISH)) }.getOrNull()?.let { return it } }
        }
        return null
    }

    private fun containsPhrase(result: FusionSearchResult, phrase: String): Boolean =
        containsPhrase("${result.title} ${result.snippet.orEmpty()}", phrase)

    private fun canonicalMatchText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun containsPhrase(text: String, phrase: String): Boolean =
        Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

    private fun matchesRequestedOfficialAuthority(query: String, result: FusionSearchResult): Boolean {
        if (!asksForOfficialEvidence(query)) return false
        return matchesRecognizedAuthorityDomain(query, result)
    }

    private fun matchesRecognizedAuthorityDomain(query: String, result: FusionSearchResult): Boolean {
        val host = result.url?.let { runCatching { URL(it).host.lowercase(Locale.ROOT).removePrefix("www.") }.getOrNull() }
            ?: return false
        val normalized = query.lowercase(Locale.ROOT)
        val exactAuthorityDomains = buildList {
            if (normalized.contains("nasa")) add("nasa.gov")
            if (normalized.contains("bank of korea") || normalized.contains("한국은행") || Regex("\\bbok\\b").containsMatchIn(normalized)) add("bok.or.kr")
            if (normalized.contains("seoul") || normalized.contains("서울")) add("seoul.go.kr")
            if (normalized.contains("united nations") || normalized.contains("유엔") || normalized.contains("국제연합")) add("un.org")
        }.distinct()
        if (exactAuthorityDomains.isNotEmpty()) return exactAuthorityDomains.any { host == it || host.endsWith(".$it") }
        return listOf(".gov", ".go.kr", ".gov.kr", ".go.jp", ".gov.uk", ".gov.au", ".govt.nz", ".mil")
            .any(host::endsWith)
    }

}
