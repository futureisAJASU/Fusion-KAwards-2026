package com.projectnuke.fusion.search

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Deterministic answer-evidence ordering; no model inference or network access. */
internal object SearchResultRelevancePolicy {
    private val tokenRegex = Regex("""[\p{L}\p{N}]{2,}""")
    private val stopWords = setOf(
        "about", "find", "latest", "news", "recent", "search", "today", "update", "updates",
        "what", "when", "which", "who", "current", "currently", "target", "date", "official", "source", "sources",
        "관련", "검색", "뉴스", "소식", "오늘", "요약", "주요", "최신", "현재", "최근", "언제", "공식", "자료",
    )

    fun rank(
        results: List<FusionSearchResult>,
        originalQuery: String,
        intent: SearchIntent = FusionWebSearch.detectIntent(originalQuery),
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): List<FusionSearchResult> {
        val terms = terms(originalQuery)
        return results.withIndex().sortedWith(
            compareByDescending<IndexedValue<FusionSearchResult>> { (_, result) ->
                SearchAnswerSufficiencyPolicy.relevanceSignals(originalQuery, intent, result, today).officialAuthorityMatch
            }.thenByDescending { (_, result) ->
                SearchAnswerSufficiencyPolicy.relevanceSignals(originalQuery, intent, result, today).freshnessRank
            }.thenByDescending { (_, result) ->
                SearchAnswerSufficiencyPolicy.relevanceSignals(originalQuery, intent, result, today).directFactSupport
            }.thenByDescending { (_, result) -> overlap(terms, result) }
                .thenByDescending { (_, result) -> result.score?.takeIf(Double::isFinite) ?: Double.NEGATIVE_INFINITY }
                .thenBy { (_, result) -> result.rank.takeIf { it > 0 } ?: Int.MAX_VALUE }
                .thenBy { (_, result) -> stableIdentity(result) },
        ).map { it.value }
    }

    private fun overlap(queryTerms: Set<String>, result: FusionSearchResult): Int {
        if (queryTerms.isEmpty()) return 0
        val resultTerms = terms("${result.title} ${result.snippet.orEmpty()}")
        return queryTerms.count(resultTerms::contains)
    }

    private fun terms(value: String): Set<String> = tokenRegex.findAll(value.lowercase(Locale.ROOT))
        .map { it.value }
        .filterNot(stopWords::contains)
        .toSet()

    private fun stableIdentity(result: FusionSearchResult): String =
        (result.url ?: result.title).trim().lowercase(Locale.ROOT)
}
