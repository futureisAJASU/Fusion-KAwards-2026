package com.projectnuke.fusion.search

import java.util.Locale

/** Surface tokenizer shared by the authorizer and the trusted public lexicon. */
internal val PublicQueryTokenPattern = Regex("[\\p{L}\\p{N}]+(?:[._+-][\\p{L}\\p{N}]+)*")

internal fun publicQueryTokens(text: String): Set<String> =
    PublicQueryTokenPattern.findAll(text)
        .map { it.value.lowercase(Locale.ROOT) }
        .toCollection(LinkedHashSet())

/** True when [surface] occurs without touching a longer token, in both directions. */
internal fun containsPublicSurface(haystack: String, surface: String): Boolean {
    if (haystack.isBlank() || surface.isBlank()) return false
    val escaped = Regex.escape(surface.lowercase(Locale.ROOT))
    return Regex("(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])").containsMatchIn(haystack)
}

/** One unambiguous public company/index identity with its public listing terms. */
internal data class TrustedPublicEntityRecord(
    val aliases: Set<String>,
    val publicSearchTerms: Set<String>,
)

/**
 * Deterministic, inspectable public entity resolution. Every entry is a
 * hand-curated public identity (issuer name or unambiguous alias) paired with
 * the identifier/exchange the issuer itself publishes. Nothing here is
 * model-derived: a model-supplied ticker that is absent from this table never
 * becomes evidence of identity, and family-only names that could denote
 * several listed issuers never resolve at all.
 */
internal object TrustedPublicEntityLexicon {
    val records: List<TrustedPublicEntityRecord> = listOf(
        TrustedPublicEntityRecord(
            aliases = setOf("삼성전자", "samsung electronics", "samsung electronics co ltd"),
            publicSearchTerms = setOf("005930", "KRX"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("sk하이닉스", "sk hynix", "하이닉스", "hynix"),
            publicSearchTerms = setOf("000660", "KRX"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("현대자동차", "hyundai motor", "hyundai motor company"),
            publicSearchTerms = setOf("005380", "KRX"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("엘지에너지솔루션", "lg energy solution", "lg에너지솔루션"),
            publicSearchTerms = setOf("373220", "KRX"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("네이버", "naver"),
            publicSearchTerms = setOf("035420", "KRX"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("카카오", "kakao"),
            publicSearchTerms = setOf("035720", "KRX"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("nvidia", "nvidia corporation"),
            publicSearchTerms = setOf("NVDA", "NASDAQ"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("advanced micro devices", "amd"),
            publicSearchTerms = setOf("AMD", "NASDAQ"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("apple", "apple inc"),
            publicSearchTerms = setOf("AAPL", "NASDAQ"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("microsoft", "microsoft corporation"),
            publicSearchTerms = setOf("MSFT", "NASDAQ"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("코스피", "kospi", "kospi index"),
            publicSearchTerms = setOf("KOSPI"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("코스닥", "kosdaq"),
            publicSearchTerms = setOf("KOSDAQ"),
        ),
        TrustedPublicEntityRecord(
            aliases = setOf("나스닥", "nasdaq", "nasdaq composite"),
            publicSearchTerms = setOf("NASDAQ"),
        ),
    )

    /**
     * Family-only names that denote several distinct listed issuers (or an
     * unlisted group). They are deliberately absent from [records], and a
     * request naming only one of these resolves to nothing.
     */
    val ambiguousFamilyTokens: Set<String> = setOf(
        "삼성", "현대", "sk", "엘지", "lg", "에스케이", "삼성그룹", "현대차그룹", "sk그룹",
    )

    /** True when the request names a public entity only through a family token. */
    fun isAmbiguousEntityRequest(typedQuery: String): Boolean {
        val normalized = typedQuery.lowercase(Locale.ROOT)
        if (normalized.isBlank()) return false
        if (resolvedRecords(normalized).isNotEmpty()) return false
        return ambiguousFamilyTokens.any { containsPublicSurface(normalized, it) }
    }

    private fun resolvedRecords(normalizedQuery: String): List<TrustedPublicEntityRecord> =
        records.filter { record ->
            record.aliases.any { alias -> containsPublicSurface(normalizedQuery, alias) }
        }

    /**
     * Public terms for entities unambiguously named in the typed request:
     * the issuer-published identifier/exchange plus the public alias surfaces
     * (both languages). Returns nothing for ambiguous family-only names, so a
     * query such as "삼성 주가" cannot be silently widened to Samsung Electronics.
     */
    fun termsFor(typedQuery: String): Set<String> {
        val normalized = typedQuery.lowercase(Locale.ROOT)
        if (normalized.isBlank()) return emptySet()
        val records = resolvedRecords(normalized)
        if (records.isEmpty()) return emptySet()
        val permitted = LinkedHashSet<String>()
        records.forEach { record ->
            permitted += record.publicSearchTerms.map { it.lowercase(Locale.ROOT) }
            record.aliases.forEach { alias -> permitted += publicQueryTokens(alias) }
        }
        return permitted
    }
}

/** One curated public concept with its Korean and English public surfaces. */
internal data class TrustedPublicTermGroup(val surfaces: Set<String>)

/**
 * Deterministic, inspectable Korean-English technical terminology. Each group
 * is a public concept and every surface in it is equally public; naming any
 * surface in the typed request permits only the other surfaces of the same
 * group. Terms never enter from model output, so a hallucinated technical
 * expansion cannot widen the authorized query.
 */
internal object TrustedPublicTerminology {
    private val groups: List<TrustedPublicTermGroup> = listOf(
        TrustedPublicTermGroup(setOf("반도체", "semiconductor", "semiconductors")),
        TrustedPublicTermGroup(setOf("배터리", "battery", "batteries")),
        TrustedPublicTermGroup(setOf("전기차", "electric vehicle", "electric vehicles", "ev")),
        TrustedPublicTermGroup(setOf("인공지능", "artificial intelligence", "ai")),
        TrustedPublicTermGroup(setOf("클라우드", "cloud computing", "cloud")),
        TrustedPublicTermGroup(setOf("데이터센터", "data center", "data centres", "data centers")),
        TrustedPublicTermGroup(setOf("양자컴퓨터", "quantum computing", "quantum computer", "quantum computers")),
        TrustedPublicTermGroup(setOf("로봇", "robotics", "robot", "robots")),
        TrustedPublicTermGroup(setOf("자율주행", "autonomous driving", "self driving")),
        TrustedPublicTermGroup(setOf("바이오", "biotechnology", "bio")),
        TrustedPublicTermGroup(setOf("우주항공", "aerospace", "space industry")),
        TrustedPublicTermGroup(setOf("재생에너지", "renewable energy", "renewables")),
        TrustedPublicTermGroup(setOf("원자력", "nuclear power", "nuclear energy")),
        TrustedPublicTermGroup(setOf("기후변화", "climate change")),
        TrustedPublicTermGroup(setOf("금리", "interest rate", "interest rates")),
        TrustedPublicTermGroup(setOf("환율", "exchange rate", "exchange rates", "fx rate")),
        TrustedPublicTermGroup(setOf("인플레이션", "inflation")),
        TrustedPublicTermGroup(setOf("국내총생산", "gdp", "gross domestic product")),
        TrustedPublicTermGroup(setOf("실업률", "unemployment rate", "jobless rate")),
        TrustedPublicTermGroup(setOf("주가", "stock price", "share price", "share prices")),
        TrustedPublicTermGroup(setOf("시세", "market quotation", "quotation")),
        TrustedPublicTermGroup(setOf("현재가", "current price", "current prices")),
        TrustedPublicTermGroup(setOf("시가총액", "market cap", "market capitalization", "market capitalisation")),
        TrustedPublicTermGroup(setOf("배당", "dividend", "dividends")),
        TrustedPublicTermGroup(setOf("공매도", "short selling", "short interest")),
        TrustedPublicTermGroup(setOf("실적", "earnings", "earnings result", "earnings results")),
        TrustedPublicTermGroup(setOf("매출", "revenue", "revenues", "sales revenue")),
        TrustedPublicTermGroup(setOf("영업이익", "operating profit", "operating income")),
        TrustedPublicTermGroup(setOf("공시", "regulatory filing", "regulatory filings", "disclosure")),
        TrustedPublicTermGroup(setOf("특허", "patent", "patents")),
        TrustedPublicTermGroup(setOf("수출", "export", "exports", "export volume")),
        TrustedPublicTermGroup(setOf("수입", "import", "imports", "import volume")),
        TrustedPublicTermGroup(setOf("공급망", "supply chain", "supply chains")),
        TrustedPublicTermGroup(setOf("채용", "hiring", "recruitment", "job openings")),
        TrustedPublicTermGroup(setOf("인수합병", "merger", "acquisition", "mergers and acquisitions", "m&a")),
        TrustedPublicTermGroup(setOf("소송", "litigation", "lawsuit")),
        TrustedPublicTermGroup(setOf("규제", "regulation", "regulations", "regulatory")),
        TrustedPublicTermGroup(setOf("보안", "security vulnerability", "security vulnerabilities", "cybersecurity")),
    )

    /**
     * Public surface tokens for every concept named in the typed request.
     * Naming no surface of a group contributes nothing, so unrelated public
     * vocabulary cannot be appended on the strength of the request alone.
     */
    fun termsFor(typedQuery: String): Set<String> {
        if (typedQuery.isBlank()) return emptySet()
        val normalized = typedQuery.lowercase(Locale.ROOT)
        return groups.asSequence()
            .filter { group -> group.surfaces.any { containsPublicSurface(normalized, it) } }
            .flatMap { group -> group.surfaces.asSequence() }
            .flatMap { surface -> publicQueryTokens(surface).asSequence() }
            .toSet()
    }
}
