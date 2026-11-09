package com.projectnuke.fusion.search

/**
 * Removes explicit calendar-date clauses that the final model-facing search
 * records do not state. Prompt instructions alone did not prevent the model
 * from turning an undated weekday and time into a calendar date.
 */
internal object SearchAnswerTemporalGroundingPolicy {
    private data class DateClaim(
        val year: Int?,
        val month: Int,
        val day: Int?,
    )

    private val koreanDate = Regex(
        "(?<![\\p{L}\\p{N}])(?:(20\\d{2})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일",
    )
    private val koreanMonth = Regex("(?<![\\p{L}\\p{N}])(20\\d{2})\\s*년\\s*(\\d{1,2})\\s*월")
    private val isoDate = Regex("(?<!\\d)(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
    private val englishMonthDay = Regex(
        "(?i)\\b(January|Jan|February|Feb|March|Mar|April|Apr|May|June|Jun|July|Jul|August|Aug|September|Sept|Sep|October|Oct|November|Nov|December|Dec)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(20\\d{2}))?\\b",
    )
    private val englishDayMonth = Regex(
        "(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(January|Jan|February|Feb|March|Mar|April|Apr|May|June|Jun|July|Jul|August|Aug|September|Sept|Sep|October|Oct|November|Nov|December|Dec)\\.?\\s*(20\\d{2})?\\b",
    )
    private val englishMonthYear = Regex(
        "(?i)\\b(January|Jan|February|Feb|March|Mar|April|Apr|May|June|Jun|July|Jul|August|Aug|September|Sept|Sep|October|Oct|November|Nov|December|Dec)\\.?\\s+(20\\d{2})\\b",
    )
    private val answerClause = Regex("[^,;，；\\n]+(?:[,;，；\\n]+|$)")
    private val koreanOfficeTitle = Regex(
        "(?<![가-힣])([가-힣]{2,4})\\s+(?:전직\\s+대통령|전\\s+대통령|대통령\\s+당선인|대통령|국무총리|총리|장관|국회의원|의원|시장|도지사|지사|회장|대표이사|대표|위원장)(?=$|\\s|[,.!?]|과|와|은|는|이|가|을|를|의|에게|께서|도|만|으로|로|에|에서|부터|까지|처럼|보다)",
    )
    private val organizationOfficeTitleSubjects = setOf(
        "회사", "기업", "업체", "정부", "국가", "단체", "기관", "학교", "병원", "은행", "법원", "검찰", "경찰", "국회",
    )
    private val dateOnlyRequest = Regex(
        "(?i)(?:\\b(?:what|which)\\s+(?:(?:is|was|will\\s+be)\\s+)?(?:the\\s+)?date\\b|\\bon\\s+what\\s+date\\b|몇\\s*월\\s*(?:몇\\s*일|며칠)|(?:무슨|어떤)\\s*날짜)",
    )
    private val explicitTimeRequest = Regex(
        "(?i)(?:\\b(?:time|hour|minute|timezone|time\\s+zone|utc|gmt|edt|est|cdt|cst|mdt|mst|pdt|pst|kst)\\b|\\d{1,2}:\\d{2}|\\d{1,2}\\s*(?:a\\.?m\\.?|p\\.?m\\.?|am|pm)|몇\\s*시|몇\\s*분|시간대|시각)",
    )
    private val englishClockTime = Regex(
        """(?i)(?:\b(?:at|around|about|approximately|by)\s+)?\b\d{1,2}:\d{2}(?:\s*[ap]\.?m\.?)?|\b\d{1,2}\s*(?:a\.?m\.?|p\.?m\.?|am|pm|o['’]?clock|hours?)""",
    )
    private val koreanClockTime = Regex(
        """(?:(?:늦은|이른)\s*)?(?:(?:오전|오후|아침|저녁|밤|정오|자정)\s*)?\d{1,2}\s*시(?:\s*\d{1,2}\s*분)?(?:\s*(?:에|쯤|경))?""",
    )
    private val englishDaypart = Regex(
        "(?i)\\b(?:(?:(?:in|during)\\s+the\\s+)?(?:(?:early|late)\\s+)?(?:morning|afternoon|evening|night|noon|midnight))\\b",
    )
    private val koreanDaypart = Regex("(?:(?:늦은|이른)\\s*)?(?:오전|오후|아침|저녁|밤|정오|자정)")
    private val englishWeekday = Regex(
        "(?i)\\b(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b[,，]?\\s*",
    )
    private val koreanWeekday = Regex("(?:월|화|수|목|금|토|일)요일(?:에)?")
    private val timeZoneLabel = Regex(
        "(?i)\\b(?:EDT|EST|CDT|CST|MDT|MST|PDT|PST|KST|ET|CT|MT|PT|UTC(?:[+-]\\d{1,2}(?::?\\d{2})?)?|GMT(?:[+-]\\d{1,2}(?::?\\d{2})?)?)\\b",
    )
    private val percentage = Regex("(?<![\\d.])\\d{1,3}(?:[.,]\\d+)?\\s*%(?![\\p{L}\\p{N}])")
    private val bankOfKoreaRateValue = Regex(
        "(?i)(?:\\b(?:base|policy|interest)\\s+rate\\b|기준금리|금리)[^\\n.!?]{0,120}?(${percentage.pattern})" +
            "|(${percentage.pattern})[^\\n.!?]{0,120}?(?:\\b(?:base|policy|interest)\\s+rate\\b|기준금리|금리)",
    )
    private val compoundRateRequest = Regex(
        "(?i)\\b(?:and|also|compare|when|where|why|how)\\b|그리고|또한|비교|언제|어디|왜|어떻게",
    )
    private val rateChangeRequest = Regex(
        "(?i)\\b(?:and\\s+)?(?:did|has|have)\\s+(?:it|the\\s+(?:base|policy|interest)\\s+rate)\\s+change(?:d)?\\b",
    )
    private val explicitlyUnchangedBankRate = Regex(
        "(?i)(?:\\b(?:base|policy|interest)\\s+rate\\b|기준금리)[^\\n.!?]{0,80}\\bunchanged\\b",
    )
    private val englishMonthNames = listOf(
        "", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val monthNumbers = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12,
    )

    fun sanitize(
        answer: String,
        admittedSources: List<WebSearchSource>,
        requestedQuery: String? = null,
    ): String {
        if (answer.isBlank() || admittedSources.isEmpty()) return answer

        val metricsMarker = "\n<fusion_metrics>"
        val metricsStart = answer.indexOf(metricsMarker)
        val visibleAnswer = if (metricsStart >= 0) answer.substring(0, metricsStart) else answer
        val metrics = if (metricsStart >= 0) answer.substring(metricsStart) else ""
        val evidenceText = admittedSources.joinToString("\n") { source ->
            listOf(source.title, source.snippet, source.publishedAt).filterNotNull().joinToString(" ")
        }
        canonicalBankOfKoreaRateAnswer(visibleAnswer, admittedSources, requestedQuery)?.let {
            return it + metrics
        }
        val evidenceDates = dateClaims(evidenceText)
        val groundedVisibleAnswer = if (requestsDateOnly(requestedQuery) && evidenceDates.isNotEmpty()) {
            val dateOnlyAnswer = omitUnrequestedTemporalDetails(visibleAnswer)
            canonicalDateOnlyAnswer(dateOnlyAnswer, evidenceDates) ?: dateOnlyAnswer
        } else {
            visibleAnswer
        }
        val titleGroundedAnswer = omitUnsupportedKoreanOfficeTitles(
            answer = groundedVisibleAnswer,
            evidenceText = evidenceText,
            requestedQuery = requestedQuery,
        )
        val unsupportedDates = dateClaims(titleGroundedAnswer).filterNot { claim ->
            evidenceDates.any { evidence -> sameExplicitDate(claim, evidence) }
        }
        if (unsupportedDates.isEmpty()) return titleGroundedAnswer + metrics

        val unsupportedSegmentStarts = answerClause.findAll(titleGroundedAnswer).filter { segment ->
            val segmentDates = dateClaims(segment.value)
            segmentDates.any { candidate -> unsupportedDates.any { it == candidate } }
        }.map { it.range.first }.toSet()
        val retained = answerClause.findAll(titleGroundedAnswer)
            .filterNot { it.range.first in unsupportedSegmentStarts }
            .joinToString(separator = "") { it.value }
            .trim()
            .trimEnd(',', ';', '，', '；')
            .trim()
        val safeAnswer = finishClause(retained).ifBlank {
            canonicalBankOfKoreaRateAnswer(visibleAnswer, admittedSources, requestedQuery)
                ?: canonicalBankOfKoreaRateChangeFallback(admittedSources, requestedQuery, visibleAnswer)
                ?: unsupportedDateFallback(visibleAnswer)
        }
        return safeAnswer + metrics
    }

    /** Generic news summaries cannot add Korean office titles absent from admitted evidence. */
    private fun omitUnsupportedKoreanOfficeTitles(
        answer: String,
        evidenceText: String,
        requestedQuery: String?,
    ): String {
        if (!FusionWebSearch.isBroadNewsQuery(requestedQuery.orEmpty())) return answer
        val normalizedEvidence = evidenceText.replace(Regex("\\s+"), " ")
        return koreanOfficeTitle.replace(answer) { match ->
            val subject = match.groupValues[1]
            val normalizedClaim = match.value.replace(Regex("\\s+"), " ")
            if (
                subject in organizationOfficeTitleSubjects ||
                normalizedEvidence.contains(normalizedClaim)
            ) {
                match.value
            } else {
                subject
            }
        }
    }

    /** Recovers a supported rate and unchanged status when date filtering removes the whole clause. */
    private fun canonicalBankOfKoreaRateChangeFallback(
        admittedSources: List<WebSearchSource>,
        requestedQuery: String?,
        answer: String,
    ): String? {
        val query = requestedQuery?.lowercase().orEmpty()
        val identifiesBankOfKorea = query.contains("bank of korea") ||
            Regex("\\bbok\\b").containsMatchIn(query) ||
            query.contains("한국은행")
        val asksForRate = listOf("base rate", "policy rate", "interest rate", "기준금리").any(query::contains)
        if (!identifiesBankOfKorea || !asksForRate || !rateChangeRequest.containsMatchIn(query)) return null

        val officialRecords = admittedSources.filter {
            it.source.orEmpty().lowercase().removePrefix("www.").endsWith("bok.or.kr")
        }
        val supportedValues = officialRecords
            .asSequence()
            .flatMap { source -> sequenceOf(source.title, source.snippet).filterNotNull() }
            .flatMap { text ->
                bankOfKoreaRateValue.findAll(text).mapNotNull { match ->
                    (match.groups[1]?.value ?: match.groups[2]?.value)
                        ?.replace(Regex("\\s+"), "")
                }
            }
            .distinct()
            .toList()
        if (supportedValues.size != 1) return null

        val rate = supportedValues.single()
        val explicitlyUnchanged = officialRecords.any { source ->
            sequenceOf(source.title, source.snippet).filterNotNull().any { text ->
                explicitlyUnchangedBankRate.containsMatchIn(text) &&
                    bankOfKoreaRateValue.findAll(text).any { match ->
                        (match.groups[1]?.value ?: match.groups[2]?.value)
                            ?.replace(Regex("\\s+"), "") == rate
                    }
            }
        }
        if (!explicitlyUnchanged) return null

        return if (Regex("[\\uAC00-\\uD7AF]").containsMatchIn(answer)) {
            "한국은행 기준금리는 ${rate}이며, 동결됐습니다."
        } else {
            "The Bank of Korea base rate is $rate and was left unchanged."
        }
    }

    /** Canonicalizes simple BOK rate answers from one unique admitted official percentage. */
    private fun canonicalBankOfKoreaRateAnswer(
        answer: String,
        admittedSources: List<WebSearchSource>,
        requestedQuery: String?,
    ): String? {
        val query = requestedQuery?.lowercase().orEmpty()
        val identifiesBankOfKorea = query.contains("bank of korea") ||
            Regex("\\bbok\\b").containsMatchIn(query) ||
            query.contains("한국은행")
        val asksForRate = listOf("base rate", "policy rate", "interest rate", "기준금리").any(query::contains)
        if (!identifiesBankOfKorea || !asksForRate) return null
        if (compoundRateRequest.containsMatchIn(query)) return null

        val supportedValues = admittedSources
            .asSequence()
            .filter { it.source.orEmpty().lowercase().removePrefix("www.").endsWith("bok.or.kr") }
            .flatMap { source ->
                sequenceOf(source.title, source.snippet).filterNotNull().flatMap { text ->
                    bankOfKoreaRateValue.findAll(text).mapNotNull { match ->
                        (match.groups[1]?.value ?: match.groups[2]?.value)
                            ?.replace(Regex("\\s+"), "")
                    }
                }
            }
            .distinct()
            .toList()
        if (supportedValues.size != 1) return null

        val rate = supportedValues.single()
        return if (Regex("[\\uAC00-\\uD7AF]").containsMatchIn(answer)) {
            "한국은행 기준금리는 ${rate}입니다."
        } else {
            "The Bank of Korea base rate is $rate."
        }
    }

    private fun requestsDateOnly(query: String?): Boolean {
        val request = query?.trim().orEmpty()
        return request.isNotEmpty() &&
            dateOnlyRequest.containsMatchIn(request) &&
            !explicitTimeRequest.containsMatchIn(request)
    }

    /** Retains only one complete date explicitly present in the admitted evidence. */
    private fun canonicalDateOnlyAnswer(
        answer: String,
        evidenceDates: List<DateClaim>,
    ): String? {
        val supportedDates = dateClaims(answer)
            .flatMap { answerDate -> evidenceDates.filter { evidenceDate -> sameExplicitDate(answerDate, evidenceDate) } }
            .filter { it.year != null && it.day != null }
            .distinct()
        if (supportedDates.size != 1) return null

        val date = supportedDates.single()
        return if (Regex("[\\uAC00-\\uD7AF]").containsMatchIn(answer)) {
            "${date.year}년 ${date.month}월 ${date.day}일."
        } else {
            val month = englishMonthNames.getOrNull(date.month) ?: return null
            "$month ${date.day}, ${date.year}."
        }
    }

    private fun omitUnrequestedTemporalDetails(answer: String): String {
        return englishClockTime.replace(answer, "")
            .let { koreanClockTime.replace(it, "") }
            .let { englishDaypart.replace(it, "") }
            .let { koreanDaypart.replace(it, "") }
            .let { englishWeekday.replace(it, "") }
            .let { koreanWeekday.replace(it, "") }
            .let { timeZoneLabel.replace(it, "") }
            .replace(Regex("\\(\\s*\\)"), "")
            .replace(Regex("\\s+([,.;!?])"), "$1")
            .replace(Regex(",\\s*([.!?])"), "$1")
            .replace(Regex("(?:,\\s*){2,}"), ", ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun dateClaims(value: String): List<DateClaim> {
        val claims = mutableListOf<Pair<IntRange, DateClaim>>()
        koreanDate.findAll(value).forEach { match ->
            val year = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull() ?: return@forEach
            val day = match.groupValues[3].toIntOrNull() ?: return@forEach
            if (month in 1..12 && day in 1..31) {
                claims += match.range to DateClaim(year, month, day)
            }
        }
        isoDate.findAll(value).forEach { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@forEach
            val month = match.groupValues[2].toIntOrNull() ?: return@forEach
            val day = match.groupValues[3].toIntOrNull() ?: return@forEach
            if (month in 1..12 && day in 1..31) {
                claims += match.range to DateClaim(year, month, day)
            }
        }
        englishMonthDay.findAll(value).forEach { match ->
            val month = monthNumbers[match.groupValues[1].lowercase()] ?: return@forEach
            val day = match.groupValues[2].toIntOrNull() ?: return@forEach
            val year = match.groupValues[3].toIntOrNull()
            if (day in 1..31) claims += match.range to DateClaim(year, month, day)
        }
        englishDayMonth.findAll(value).forEach { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            val month = monthNumbers[match.groupValues[2].lowercase()] ?: return@forEach
            val year = match.groupValues[3].toIntOrNull()
            if (day in 1..31) claims += match.range to DateClaim(year, month, day)
        }
        koreanMonth.findAll(value).forEach { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@forEach
            val month = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (month in 1..12) claims += match.range to DateClaim(year, month, null)
        }
        englishMonthYear.findAll(value).forEach { match ->
            val month = monthNumbers[match.groupValues[1].lowercase()] ?: return@forEach
            val year = match.groupValues[2].toIntOrNull() ?: return@forEach
            claims += match.range to DateClaim(year, month, null)
        }
        return claims
            .sortedBy { it.first.first }
            .fold(mutableListOf<Pair<IntRange, DateClaim>>()) { distinct, claim ->
                if (distinct.none { prior -> prior.first.first <= claim.first.last && claim.first.first <= prior.first.last }) {
                    distinct += claim
                }
                distinct
            }
            .map { it.second }
    }

    private fun sameExplicitDate(answer: DateClaim, evidence: DateClaim): Boolean =
        answer.month == evidence.month &&
            (answer.day == null || answer.day == evidence.day) &&
            (answer.year == null || answer.year == evidence.year)

    private fun finishClause(value: String): String {
        if (value.isBlank()) return ""
        val normalized = when {
            value.endsWith("예정이며") -> value.removeSuffix("이며") + "입니다"
            value.endsWith("이며") -> value.removeSuffix("이며") + "입니다"
            value.endsWith("하며") -> value.removeSuffix("하며") + "합니다"
            value.endsWith("하고") -> value.removeSuffix("하고") + "합니다"
            value.endsWith("하여") -> value.removeSuffix("하여") + "했습니다"
            value.endsWith("해서") -> value.removeSuffix("해서") + "했습니다"
            value.endsWith(" and", ignoreCase = true) -> value.dropLast(4)
            value.endsWith(" or", ignoreCase = true) -> value.dropLast(3)
            value.endsWith(" but", ignoreCase = true) -> value.dropLast(4)
            else -> value
        }.trimEnd(',', ';', '，', '；')
        return if (normalized.isBlank()) "" else normalized + if (normalized.lastOrNull() in setOf('.', '!', '?', '。')) "" else "."
    }

    private fun unsupportedDateFallback(answer: String): String =
        if (Regex("[\\uAC00-\\uD7AF]").containsMatchIn(answer)) {
            "검색 근거에서 답변의 구체적인 날짜를 확인할 수 없어 해당 날짜 주장을 제외했습니다."
        } else {
            "The admitted search evidence does not state the specific date in that answer, so I can't confirm it."
        }
}
