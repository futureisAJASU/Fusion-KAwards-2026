package com.projectnuke.fusion.search

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StockValueKind {
    CURRENT_PRICE,
    CLOSE,
    MARKET_CAP,
    INDEX_LEVEL,
    GENERIC_STOCK_INFO,
}

enum class StockTemporalKind {
    CURRENT,
    TODAY,
    PREVIOUS_SESSION,
    EXPLICIT_DATE,
    UNSPECIFIED,
}

/** Bounded semantics shared by planning and conservative result validation. */
data class StockQuerySemantics(
    val instrumentTokens: Set<String>,
    val tickerTokens: Set<String> = emptySet(),
    val valueKind: StockValueKind = StockValueKind.GENERIC_STOCK_INFO,
    val instrumentLabel: String = instrumentTokens.joinToString(" "),
    val temporalKind: StockTemporalKind = StockTemporalKind.UNSPECIFIED,
    val requestedDate: LocalDate? = null,
    val targetIsAmbiguous: Boolean = false,
)

private data class StockAliasGroup(
    val aliases: Set<String>,
    val publicSearchTerms: Set<String> = emptySet(),
)

/** Aliases must identify an instrument, not merely a corporate family. */
private val KnownStockAliasGroups = listOf(
    StockAliasGroup(setOf("삼성전자", "samsung electronics", "005930"), setOf("005930", "KRX")),
    StockAliasGroup(setOf("현대자동차", "hyundai motor", "hyundai motor company", "005380"), setOf("005380", "KRX")),
    StockAliasGroup(setOf("nvidia", "nvda"), setOf("NVDA", "NASDAQ")),
    StockAliasGroup(setOf("amd"), setOf("AMD", "NASDAQ")),
    StockAliasGroup(setOf("코스피", "kospi")),
    StockAliasGroup(setOf("코스닥", "kosdaq")),
    StockAliasGroup(setOf("나스닥", "nasdaq", "nasdaq composite")),
)

/**
 * Public entity terms that may be added to an authorized typed stock query.
 * Only exact, unambiguous aliases in this local mapping contribute terms;
 * the function never accepts a model-supplied ticker as evidence of identity.
 */
internal fun trustedPublicStockEnrichmentTerms(query: String): Set<String> {
    if (!hasStockIntentSignal(query)) return emptySet()
    val normalized = query.lowercase(Locale.ROOT)
    return KnownStockAliasGroups.asSequence()
        .filter { group ->
            group.aliases.any { alias ->
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(alias.lowercase(Locale.ROOT))}(?![\\p{L}\\p{N}])")
                    .containsMatchIn(normalized)
            }
        }
        .flatMap { it.publicSearchTerms.asSequence() }
        .map { it.lowercase(Locale.ROOT) }
        .toSet()
}

private val StockSemanticWords = setOf(
    "주가", "시세", "현재가", "종가", "시가", "시가총액", "시총", "코스피", "코스닥", "나스닥",
    "stock", "stocks", "share", "shares", "price", "prices", "current", "now", "today", "close",
    "market", "cap", "marketcap", "market-cap", "level", "composite", "index", "status",
    "오늘", "어제", "현재", "지금", "얼마", "얼마였어", "얼마야", "전망", "예상", "가능성", "상승",
    "하락", "관련", "관심", "투자", "정보", "알려줘", "어때", "어떻게", "뉴스", "최신", "최근",
    "현황", "지수", "수준",
    "전망", "forecast", "outlook", "potential", "growth", "information", "info",
    "the", "a", "an", "of", "for", "in", "on", "was", "is", "what", "how", "much",
)

private val StockTokenRegex = Regex("[A-Za-z][A-Za-z0-9.+-]*|[0-9]{4,6}|[가-힣]{2,}")
private val CalendarYearRegex = Regex("(?:19|20)\\d{2}")
private val ExplicitDateRegex = Regex("\\b((?:19|20)\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b")
private val KoreanDateRegex = Regex("((?:19|20)\\d{2})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일")
private val IndexTokens = setOf("nasdaq", "kospi", "kosdaq", "s&p", "dow", "jones", "composite", "코스피", "코스닥", "나스닥")
private val AmbiguousTargetTokens = setOf("삼성", "현대", "hyundai")
private val StrongIdentityTokens = setOf("삼성전자", "005930", "현대자동차")

private fun stockTokens(text: String): List<String> =
    StockTokenRegex.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()

private fun rawStockTokens(text: String): List<String> =
    StockTokenRegex.findAll(text).map { it.value }.toList()

private fun isTickerToken(raw: String): Boolean {
    val numericTicker = raw.matches(Regex("\\d{6}")) && !CalendarYearRegex.matches(raw)
    val alphabeticTicker = raw.length in 1..5 && raw.any(Char::isLetter) && raw.all { it.isUpperCase() || it.isDigit() }
    return numericTicker || alphabeticTicker
}

private fun aliasesForQuery(semantics: StockQuerySemantics): Set<String> {
    val queryTokens = semantics.instrumentTokens + semantics.tickerTokens
    return KnownStockAliasGroups
        .filter { group ->
            group.aliases.any { alias ->
                val aliasTokens = stockTokens(alias)
                aliasTokens.isNotEmpty() &&
                    (aliasTokens.all { it in queryTokens } || aliasTokens.any { it in queryTokens })
            }
        }
        .flatMap { it.aliases }
        .toSet()
}

private fun parseDate(year: String, month: String, day: String): LocalDate? =
    runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()

private fun extractDate(text: String): LocalDate? =
    ExplicitDateRegex.find(text)?.let { parseDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        ?: KoreanDateRegex.find(text)?.let { parseDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

private fun containsAny(text: String, terms: List<String>): Boolean =
    terms.any { text.contains(it, ignoreCase = true) }

/** Extract requested instrument independently from result text. */
fun stockQuerySemantics(query: String): StockQuerySemantics {
    val rawTokens = rawStockTokens(query)
    val normalizedTokens = rawTokens.map { it.lowercase(Locale.ROOT) }
    val tickerTokens = rawTokens
        .filter(::isTickerToken)
        .map { it.lowercase(Locale.ROOT) }
        .filterNot { it in setOf("today", "close", "stock", "share", "price", "now", "current") }
        .toSet()
    val instrumentEntries = rawTokens.zip(normalizedTokens)
        .filter { (raw, normalized) ->
            normalized !in StockSemanticWords &&
                normalized !in tickerTokens &&
                normalized.length >= 2 &&
                !raw.all(Char::isDigit)
        }
    val lower = query.lowercase(Locale.ROOT)
    val explicitDate = extractDate(query)
    val valueKind = when {
        containsAny(lower, listOf("시가총액", "시총", "market cap", "market-cap", "marketcap")) -> StockValueKind.MARKET_CAP
        containsAny(lower, listOf("코스피", "코스닥", "나스닥", "kospi", "kosdaq", "nasdaq", "s&p", "dow jones")) -> StockValueKind.INDEX_LEVEL
        containsAny(lower, listOf("종가", "마감가", "장마감", "마감", "closing price", "close")) -> StockValueKind.CLOSE
        containsAny(lower, listOf("주가", "시세", "현재가", "stock price", "share price", "current price", "price")) -> StockValueKind.CURRENT_PRICE
        else -> StockValueKind.GENERIC_STOCK_INFO
    }
    val temporalKind = when {
        explicitDate != null -> StockTemporalKind.EXPLICIT_DATE
        containsAny(lower, listOf("어제", "yesterday")) -> StockTemporalKind.PREVIOUS_SESSION
        containsAny(lower, listOf("오늘", "today")) -> StockTemporalKind.TODAY
        containsAny(lower, listOf("현재가", "지금", "현재", "current price", "now", "current")) -> StockTemporalKind.CURRENT
        else -> StockTemporalKind.UNSPECIFIED
    }
    val instrumentTokens = instrumentEntries.map { it.second }.toCollection(LinkedHashSet())
    if (instrumentTokens.isEmpty() && valueKind == StockValueKind.INDEX_LEVEL) {
        rawTokens
            .zip(normalizedTokens)
            .filter { (_, normalized) -> normalized in IndexTokens }
            .forEach { instrumentTokens += it.second }
    }
    val label = instrumentEntries.map { it.first }.joinToString(" ").ifBlank {
        rawTokens.filter { it.lowercase(Locale.ROOT) in tickerTokens }.joinToString(" ")
    }.ifBlank {
        rawTokens.filter { it.lowercase(Locale.ROOT) in instrumentTokens }.joinToString(" ")
    }
    val hasStrongHyundaiIdentity = lower.contains("hyundai motor") || lower.contains("hyundai motor company")
    val targetIsAmbiguous = normalizedTokens.any { it in AmbiguousTargetTokens } &&
        normalizedTokens.none { it in StrongIdentityTokens } &&
        !hasStrongHyundaiIdentity
    return StockQuerySemantics(
        instrumentTokens = instrumentTokens,
        tickerTokens = tickerTokens,
        valueKind = valueKind,
        instrumentLabel = label,
        temporalKind = temporalKind,
        requestedDate = explicitDate,
        targetIsAmbiguous = targetIsAmbiguous,
    )
}

private val EnglishStockIntentPatterns = listOf(
    Regex("\\b(?:stock|share)\\s+price\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:market\\s+cap|market-cap|marketcap)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:nasdaq|s&p(?:\\s+500)?|dow\\s+jones)\\s+(?:close|level|index)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b[A-Z]{1,5}\\s+(?:stock|share)\\b"),
)

/** Finance-specific detection; generic `price` remains a product/consumer term. */
internal fun hasStockIntentSignal(text: String): Boolean {
    val lower = text.lowercase(Locale.ROOT)
    val koreanSignal = listOf("주가", "시세", "코스피", "코스닥", "나스닥", "현재가", "종가", "시가총액", "시총")
        .any { lower.contains(it) }
    val uppercaseOrNumericTicker = Regex("(?<![A-Za-z0-9])(?:\\d{6}|[A-Z]{1,5})(?![A-Za-z0-9])").containsMatchIn(text)
    val strongTickerContext = listOf("stock", "stocks", "share", "shares", "close", "market cap", "market-cap", "marketcap")
        .any { lower.contains(it) }
    val numericQuoteContext = Regex("\\b\\d{6}\\b.*\\b(?:current price|current|now|price|close)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)
    return koreanSignal ||
        (uppercaseOrNumericTicker && (strongTickerContext || numericQuoteContext)) ||
        EnglishStockIntentPatterns.any { it.containsMatchIn(text) }
}

internal fun stockResultBindsToTarget(
    result: FusionSearchResult,
    semantics: StockQuerySemantics,
): Boolean {
    if (semantics.targetIsAmbiguous) return false
    if (semantics.instrumentTokens.isEmpty() && semantics.tickerTokens.isEmpty()) return false
    val candidateText = "${result.title} ${result.snippet.orEmpty()}"
    val candidateTokens = stockTokens(candidateText).toSet()
    if (semantics.tickerTokens.any { it in candidateTokens }) return true

    val aliases = aliasesForQuery(semantics)
    if (aliases.any { alias ->
            val aliasTokens = stockTokens(alias)
            aliasTokens.isNotEmpty() &&
                if (aliasTokens.size == 1) aliasTokens.first() in candidateTokens
                else candidateTokens.windowed(aliasTokens.size).any { it == aliasTokens }
        }) return true

    // Unseen companies use exact token binding. Requiring every extracted
    // target token avoids accepting a result for only one half of a name.
    return semantics.instrumentTokens.all { it in candidateTokens }
}

private val NumericValuePattern = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?"
private val CurrencySuffixPattern = "(?:원|krw|usd|eur|달러|\\$|€|₩|억|조|million|billion|trillion|[kmbt])"
private val CurrencyValueRegex = Regex(
    "(?i)(?:[$€₩]\\s*$NumericValuePattern(?![A-Za-z가-힣0-9])|(?:USD|KRW|EUR)\\s*$NumericValuePattern(?![A-Za-z가-힣0-9])|$NumericValuePattern\\s*$CurrencySuffixPattern(?![A-Za-z가-힣0-9]))"
)
private val NumberRegex = Regex("(?<![A-Za-z가-힣0-9])$NumericValuePattern(?![A-Za-z가-힣0-9])")
private val QuoteAnchorRegex = Regex("(?i)(주가|시세|현재가|종가|price|current(?:\\s+price)?|close)\\D{0,36}($NumericValuePattern)")
private val MarketCapAnchorRegex = Regex("(?i)(시가총액|시총|market\\s*cap|market-cap|marketcap)\\D{0,48}(?:[$€₩]\\s*)?$NumericValuePattern(?:\\s*(?:원|krw|usd|달러|억|조|million|billion|trillion|[kmbt]))?")
private val InvalidQuoteSuffixes = listOf("%", "퍼센트", "주", "년", "건", "개", "명", "회")

private fun isCalendarYear(value: String): Boolean = CalendarYearRegex.matches(value)

private fun isConservativeQuoteMatch(match: MatchResult, text: String): Boolean {
    val number = match.groupValues.getOrNull(2).orEmpty()
    if (isCalendarYear(number)) return false
    val suffix = text.substring(match.range.last + 1).trimStart()
    if (InvalidQuoteSuffixes.any { suffix.startsWith(it) }) return false
    if (suffix.startsWith("상승") || suffix.startsWith("하락") || suffix.startsWith("전망") || suffix.startsWith("예상")) return false
    return true
}

private fun hasConservativeIndexNumber(text: String): Boolean = NumberRegex.findAll(text).any { match ->
    val value = match.value
    val suffix = text.substring(match.range.last + 1).trimStart()
    (value.contains(',') || value.contains('.')) &&
        !isCalendarYear(value) &&
        !suffix.startsWith("%") &&
        !suffix.startsWith("년") &&
        !suffix.startsWith("주")
}

internal fun hasStockValueEvidence(text: String, valueKind: StockValueKind): Boolean {
    val explicitValue = CurrencyValueRegex.containsMatchIn(text)
    val quoteAnchor = QuoteAnchorRegex.findAll(text).any { isConservativeQuoteMatch(it, text) }
    return when (valueKind) {
        StockValueKind.MARKET_CAP -> MarketCapAnchorRegex.containsMatchIn(text) &&
            (explicitValue || text.contains("억") || text.contains("조") || Regex("(?i)\\b(?:million|billion|trillion|[kmbt])\\b").containsMatchIn(text))
        StockValueKind.INDEX_LEVEL -> explicitValue || hasConservativeIndexNumber(text) || quoteAnchor
        StockValueKind.CURRENT_PRICE,
        StockValueKind.CLOSE,
        StockValueKind.GENERIC_STOCK_INFO -> explicitValue || quoteAnchor
    }
}

private fun parseProviderDate(value: String): LocalDate? =
    extractDate(value)
        ?: runCatching { Instant.parse(value).atZone(java.time.ZoneOffset.UTC).toLocalDate() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)).toLocalDate() }.getOrNull()

internal fun stockEvidenceDates(result: FusionSearchResult): Set<LocalDate> =
    sequenceOf(result.publishedAt.orEmpty(), result.title, result.snippet.orEmpty())
        .flatMap { value ->
            sequence {
                ExplicitDateRegex.findAll(value).forEach { match ->
                    parseDate(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let { yield(it) }
                }
                KoreanDateRegex.findAll(value).forEach { match ->
                    parseDate(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let { yield(it) }
                }
                parseProviderDate(value)?.let { yield(it) }
            }
        }
        .toSet()

/** Current/today accepts an undated page only when its quote anchor is strong. */
internal fun stockResultTemporalEvidence(
    result: FusionSearchResult,
    semantics: StockQuerySemantics,
    evaluationDate: LocalDate,
): Boolean {
    val dates = stockEvidenceDates(result)
    val candidateText = "${result.title} ${result.snippet.orEmpty()}"
    return when (semantics.temporalKind) {
        StockTemporalKind.CURRENT,
        StockTemporalKind.TODAY -> {
            // A provider date is an explicit temporal claim. For a local
            // today/current request, every such claim must be today's local
            // calendar date; never silently substitute yesterday.
            if (dates.any { it != evaluationDate }) return false
            if (dates.isNotEmpty()) return true
            // No date is not freshness proof. It is tolerated only for a
            // strong quote/current-price page, never for arbitrary prose plus a number.
            hasStockValueEvidence(candidateText, semantics.valueKind) &&
                (candidateText.contains("현재가", ignoreCase = true) ||
                    candidateText.contains("current price", ignoreCase = true) ||
                    candidateText.contains("current", ignoreCase = true) ||
                    candidateText.contains("now", ignoreCase = true) ||
                    QuoteAnchorRegex.containsMatchIn(candidateText))
        }
        StockTemporalKind.EXPLICIT_DATE -> {
            semantics.requestedDate != null && dates.contains(semantics.requestedDate)
        }
        StockTemporalKind.PREVIOUS_SESSION -> {
            dates.isNotEmpty() && dates.all { it == evaluationDate.minusDays(1) }
        }
        StockTemporalKind.UNSPECIFIED -> true
    }
}

internal fun stockEvaluationDate(clock: Clock, zoneId: ZoneId): LocalDate =
    LocalDate.now(clock.withZone(zoneId))

internal fun currentStockEvaluationDate(): LocalDate =
    stockEvaluationDate(Clock.systemDefaultZone(), ZoneId.systemDefault())
