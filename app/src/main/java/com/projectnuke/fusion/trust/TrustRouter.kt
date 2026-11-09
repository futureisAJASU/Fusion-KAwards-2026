package com.projectnuke.fusion.trust

import java.util.Calendar
import java.util.Locale

enum class TrustRoute {
    LOCAL,
    SEARCH,
    ABSTAIN,
    CLOUD_SUGGEST,
}

enum class TrustRoutingReason {
    SIMPLE_STABLE_KNOWLEDGE,
    CURRENT_INFORMATION_REQUIRED,
    EXPLICIT_WEB_REQUEST,
    AUTHORITY_SOURCE_REQUESTED,
    EXPLICIT_NO_SEARCH,
    MANUAL_SEARCH_ENABLED,
    FUTURE_UNKNOWABLE,
    INSUFFICIENT_LOCAL_CAPABILITY,
    HIGH_COMPLEXITY_OPTIONAL_CLOUD,
    LOCAL_CONTENT_PRESENT,
    PRIVATE_OR_SENSITIVE_LOCAL_ONLY,
    STABLE_KNOWLEDGE,
    REASONING_OR_TRANSFORMATION,
    CURRENT_OR_RECENT,
    CHANGEABLE_FACT,
    SPECIFIC_MODERN_FACT,
    EXTERNAL_VERIFICATION_HELPFUL,
    UNCERTAIN_STORED_KNOWLEDGE,
}

enum class TrustRoutingSignal {
    EXPLICIT_WEB_REQUEST,
    AUTHORITY_SOURCE_REQUESTED,
    EXPLICIT_NO_SEARCH,
    MANUAL_SEARCH_ENABLED,
    CURRENT_INFORMATION_REQUESTED,
    FORECAST_REQUEST,
    FUTURE_PREDICTION,
    IMPOSSIBLE_CERTAINTY,
    LOTTERY_OR_EXAM_UNKNOWABLE,
    LOCAL_CONTENT_PRESENT,
    PRIVATE_OR_SENSITIVE_CONTENT,
    EXTERNAL_TRANSMISSION_NOT_AUTHORIZED,
    HIGH_COMPLEXITY,
    LARGE_INPUT,
    AMBIGUOUS_REQUEST,
    LLM_ROUTER_INVOKED,
    LLM_ROUTER_FAILED,
    LEGACY_ROUTER_FALLBACK,
}

data class TrustRoutingRequest(
    val userText: String,
    val localContentPresent: Boolean = false,
    val privateOrSensitiveContentPresent: Boolean = false,
    val manualSearchEnabled: Boolean = false,
    /** Metadata only; the trust route never changes provider choice. */
    val generationModeKey: String? = null,
    val priorUserContext: String? = null,
)

data class TrustRoutingDecision(
    val route: TrustRoute,
    val reason: TrustRoutingReason,
    val signals: Set<TrustRoutingSignal>,
) {
    companion object {
        fun local(
            reason: TrustRoutingReason,
            signals: Set<TrustRoutingSignal> = emptySet(),
        ): TrustRoutingDecision = TrustRoutingDecision(TrustRoute.LOCAL, reason, signals)
    }
}

enum class TrustGateDisposition {
    FINAL_LOCAL,
    FINAL_SEARCH,
    FINAL_ABSTAIN,
    FINAL_CLOUD_SUGGEST,
    NEEDS_LLM_CLASSIFICATION,
}

data class TrustGateDecision(
    val disposition: TrustGateDisposition,
    val reason: TrustRoutingReason,
    val signals: Set<TrustRoutingSignal>,
) {
    fun finalDecision(): TrustRoutingDecision? = when (disposition) {
        TrustGateDisposition.FINAL_LOCAL -> TrustRoutingDecision(TrustRoute.LOCAL, reason, signals)
        TrustGateDisposition.FINAL_SEARCH -> TrustRoutingDecision(TrustRoute.SEARCH, reason, signals)
        TrustGateDisposition.FINAL_ABSTAIN -> TrustRoutingDecision(TrustRoute.ABSTAIN, reason, signals)
        TrustGateDisposition.FINAL_CLOUD_SUGGEST -> TrustRoutingDecision(TrustRoute.CLOUD_SUGGEST, reason, signals)
        TrustGateDisposition.NEEDS_LLM_CLASSIFICATION -> null
    }
}

/** A testable clock seam. Production routing uses the device's current date. */
fun interface TrustRouterClock {
    fun currentYear(): Int

    fun currentDate(): String = currentYear().toString()
}

object SystemTrustRouterClock : TrustRouterClock {
    override fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    override fun currentDate(): String {
        val calendar = Calendar.getInstance()
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}

enum class TrustClassifierReason {
    STABLE_KNOWLEDGE,
    REASONING_OR_TRANSFORMATION,
    CURRENT_OR_RECENT,
    CHANGEABLE_FACT,
    SPECIFIC_MODERN_FACT,
    EXTERNAL_VERIFICATION_HELPFUL,
    UNCERTAIN_STORED_KNOWLEDGE,
    FUTURE_UNKNOWABLE,
}

data class TrustClassifierRequest(
    val userText: String,
    val currentYear: Int,
    val currentDate: String = currentYear.toString(),
    val localContentPresent: Boolean,
    val privateOrSensitiveContentPresent: Boolean,
    val explicitNoSearchResolved: Boolean,
    val explicitSearchResolved: Boolean,
    val generationModeKey: String?,
    val priorUserContext: String? = null,
)

data class TrustClassifierResult(val rawOutput: String)

interface TrustRoutingClassifier {
    suspend fun classify(request: TrustClassifierRequest): TrustClassifierResult
}

/**
 * Deterministic hard policy plus an optional local classifier for semantic
 * ambiguity. This layer chooses an information route; it never verifies a
 * factual claim and never invokes a search or cloud provider.
 */
object TrustRouter {
    fun hardGate(
        request: TrustRoutingRequest,
        clock: TrustRouterClock = SystemTrustRouterClock,
    ): TrustGateDecision {
        val text = request.userText.trim()
        val intentText = searchIntentText(text)
        val signals = linkedSetOf<TrustRoutingSignal>()
        val explicitNoSearch = hasExplicitNoSearch(text)
        val explicitSearch = hasExplicitSearch(intentText)
        val authoritySourceRequested = hasAuthoritySourceRequest(intentText)
        val currentInformation = hasCurrentInformationIntent(intentText)
        val futureTemporal = hasFutureTemporalIntent(intentText, clock)
        val forecast = hasForecastIntent(intentText, futureTemporal)
        val futurePrediction = hasFuturePrediction(intentText, futureTemporal)
        val impossibleCertainty = hasImpossibleCertainty(intentText, futureTemporal, futurePrediction, forecast)
        val unknowableTarget = hasLotteryOrExamUnknowable(intentText, futureTemporal)
        val complex = hasBoundedHighComplexity(text)

        if (explicitSearch) signals += TrustRoutingSignal.EXPLICIT_WEB_REQUEST
        if (authoritySourceRequested) signals += TrustRoutingSignal.AUTHORITY_SOURCE_REQUESTED
        if (explicitNoSearch) signals += TrustRoutingSignal.EXPLICIT_NO_SEARCH
        if (request.manualSearchEnabled) signals += TrustRoutingSignal.MANUAL_SEARCH_ENABLED
        if (currentInformation) signals += TrustRoutingSignal.CURRENT_INFORMATION_REQUESTED
        if (forecast) signals += TrustRoutingSignal.FORECAST_REQUEST
        if (futurePrediction) signals += TrustRoutingSignal.FUTURE_PREDICTION
        if (impossibleCertainty) signals += TrustRoutingSignal.IMPOSSIBLE_CERTAINTY
        if (unknowableTarget) signals += TrustRoutingSignal.LOTTERY_OR_EXAM_UNKNOWABLE
        if (request.localContentPresent) signals += TrustRoutingSignal.LOCAL_CONTENT_PRESENT
        if (request.privateOrSensitiveContentPresent) signals += TrustRoutingSignal.PRIVATE_OR_SENSITIVE_CONTENT
        if (complex) signals += TrustRoutingSignal.HIGH_COMPLEXITY
        if (text.length >= LargeInputThreshold) signals += TrustRoutingSignal.LARGE_INPUT

        // Precedence is intentionally narrow and auditable:
        // impossible certainty -> privacy/local content -> explicit no-search
        // -> explicit/manual/current/authority search -> obvious local
        // -> cloud suggestion -> semantic ambiguity for the local classifier.
        if (impossibleCertainty || unknowableTarget) {
            return TrustGateDecision(
                disposition = TrustGateDisposition.FINAL_ABSTAIN,
                reason = TrustRoutingReason.FUTURE_UNKNOWABLE,
                signals = signals,
            )
        }

        if (request.privateOrSensitiveContentPresent) {
            return TrustGateDecision(
                disposition = TrustGateDisposition.FINAL_LOCAL,
                reason = TrustRoutingReason.PRIVATE_OR_SENSITIVE_LOCAL_ONLY,
                signals = signals + TrustRoutingSignal.EXTERNAL_TRANSMISSION_NOT_AUTHORIZED,
            )
        }

        if (explicitNoSearch) {
            return TrustGateDecision(
                disposition = TrustGateDisposition.FINAL_LOCAL,
                reason = TrustRoutingReason.EXPLICIT_NO_SEARCH,
                signals = signals,
            )
        }

        if (explicitSearch || request.manualSearchEnabled || currentInformation || forecast || authoritySourceRequested) {
            return TrustGateDecision(
                disposition = TrustGateDisposition.FINAL_SEARCH,
                reason = when {
                    explicitSearch -> TrustRoutingReason.EXPLICIT_WEB_REQUEST
                    request.manualSearchEnabled -> TrustRoutingReason.MANUAL_SEARCH_ENABLED
                    authoritySourceRequested -> TrustRoutingReason.AUTHORITY_SOURCE_REQUESTED
                    else -> TrustRoutingReason.CURRENT_INFORMATION_REQUIRED
                },
                signals = signals,
            )
        }

        if (request.localContentPresent) {
            return TrustGateDecision(
                disposition = TrustGateDisposition.FINAL_LOCAL,
                reason = TrustRoutingReason.LOCAL_CONTENT_PRESENT,
                signals = signals,
            )
        }

        if (isObviousLocalTask(text, clock)) {
            return TrustGateDecision(
                disposition = TrustGateDisposition.FINAL_LOCAL,
                reason = if (isTransformationTask(text)) {
                    TrustRoutingReason.REASONING_OR_TRANSFORMATION
                } else {
                    TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE
                },
                signals = signals,
            )
        }

        if (complex) {
            return TrustGateDecision(
                disposition = TrustGateDisposition.FINAL_CLOUD_SUGGEST,
                reason = if (text.length >= LargeInputThreshold) {
                    TrustRoutingReason.INSUFFICIENT_LOCAL_CAPABILITY
                } else {
                    TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD
                },
                signals = signals,
            )
        }

        return TrustGateDecision(
            disposition = TrustGateDisposition.NEEDS_LLM_CLASSIFICATION,
            reason = TrustRoutingReason.UNCERTAIN_STORED_KNOWLEDGE,
            signals = signals + TrustRoutingSignal.AMBIGUOUS_REQUEST,
        )
    }

    /**
     * Compatibility entry point for deterministic callers. Ambiguous input
     * is deliberately local when no classifier is supplied; production send
     * orchestration uses [routeWithClassifier] instead.
     */
    fun route(
        request: TrustRoutingRequest,
        clock: TrustRouterClock = SystemTrustRouterClock,
    ): TrustRoutingDecision {
        val gate = hardGate(request, clock)
        return gate.finalDecision() ?: TrustRoutingDecision.local(
            reason = TrustRoutingReason.UNCERTAIN_STORED_KNOWLEDGE,
            signals = gate.signals + TrustRoutingSignal.LEGACY_ROUTER_FALLBACK,
        )
    }

    suspend fun routeWithClassifier(
        request: TrustRoutingRequest,
        classifier: TrustRoutingClassifier?,
        legacySearchPlanner: (String) -> Boolean = { false },
        clock: TrustRouterClock = SystemTrustRouterClock,
    ): TrustRoutingDecision {
        val gate = hardGate(request, clock)
        gate.finalDecision()?.let { return it }

        if (classifier == null) {
            return legacyFallback(gate, request, legacySearchPlanner)
        }

        val classifierRequest = TrustClassifierRequest(
            userText = request.userText,
            currentYear = clock.currentYear(),
            currentDate = clock.currentDate(),
            localContentPresent = request.localContentPresent,
            privateOrSensitiveContentPresent = request.privateOrSensitiveContentPresent,
            explicitNoSearchResolved = gate.signals.contains(TrustRoutingSignal.EXPLICIT_NO_SEARCH),
            explicitSearchResolved = gate.signals.contains(TrustRoutingSignal.EXPLICIT_WEB_REQUEST),
            generationModeKey = request.generationModeKey,
            priorUserContext = request.priorUserContext?.take(512),
        )

        val rawResult = try {
            classifier.classify(classifierRequest)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val parsed = rawResult?.let { TrustClassifierParser.parse(it.rawOutput) }
        if (parsed == null) {
            return legacyFallback(
                gate = gate,
                request = request,
                legacySearchPlanner = legacySearchPlanner,
                classifierFailed = true,
                classifierInvoked = true,
            )
        }

        val signals = gate.signals + TrustRoutingSignal.LLM_ROUTER_INVOKED
        return TrustRoutingDecision(
            route = parsed.route,
            reason = parsed.reason.toRoutingReason(),
            signals = signals,
        )
    }

    private fun legacyFallback(
        gate: TrustGateDecision,
        request: TrustRoutingRequest,
        legacySearchPlanner: (String) -> Boolean,
        classifierFailed: Boolean = false,
        classifierInvoked: Boolean = false,
    ): TrustRoutingDecision {
        val signals = linkedSetOf<TrustRoutingSignal>().apply {
            addAll(gate.signals)
            add(TrustRoutingSignal.LEGACY_ROUTER_FALLBACK)
            if (classifierInvoked) add(TrustRoutingSignal.LLM_ROUTER_INVOKED)
            if (classifierFailed) add(TrustRoutingSignal.LLM_ROUTER_FAILED)
        }
        return if (legacySearchPlanner(request.userText)) {
            TrustRoutingDecision(
                route = TrustRoute.SEARCH,
                reason = TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
                signals = signals,
            )
        } else {
            TrustRoutingDecision.local(
                reason = TrustRoutingReason.UNCERTAIN_STORED_KNOWLEDGE,
                signals = signals,
            )
        }
    }

    private fun hasExplicitNoSearch(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val compactKorean = normalized.replace(Regex("\\s+"), "")
        return KoreanNoSearchRegex.containsMatchIn(normalized) ||
            compactKorean.contains("검색하지마") ||
            compactKorean.contains("검색하지말고") ||
            compactKorean.contains("검색없이") ||
            compactKorean.contains("검색하지않고") ||
            compactKorean.contains("찾지말고") ||
            EnglishNoSearchRegex.containsMatchIn(normalized)
    }

    private fun hasExplicitSearch(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        return KoreanSearchRegex.containsMatchIn(normalized) ||
            normalized.trim() == "검색" ||
            EnglishSearchRegex.containsMatchIn(normalized)
    }

    private fun hasAuthoritySourceRequest(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val explicitOfficialSource = OfficialSourceRequestRegex.containsMatchIn(normalized)
        val namedAuthorityAttribution = EnglishAuthorityAttributionRegex.findAll(text).any { match ->
            match.groupValues.getOrNull(1)?.firstOrNull()?.isUpperCase() == true
        } && hasFactualRequestShape(normalized)
        return explicitOfficialSource || namedAuthorityAttribution
    }

    /**
     * In source-grounded transformations, text after the leading instruction
     * separator is source material. Its words such as "latest" or "search"
     * must not authorize external retrieval. A search instruction in the
     * leading clause remains active.
     */
    private fun searchIntentText(text: String): String {
        val separators = listOf(text.indexOf(':'), text.indexOf('\n')).filter { it >= 0 }
        val boundary = separators.minOrNull() ?: return text
        val instruction = text.substring(0, boundary)
        return if (isTransformationTask(instruction)) instruction else text
    }

    private fun hasCurrentInformationIntent(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val currentMarker = KoreanCurrentRegex.containsMatchIn(normalized) ||
            EnglishCurrentRegex.containsMatchIn(normalized)
        return currentMarker &&
            !KoreanCurrentMetaLanguageRegex.containsMatchIn(normalized) &&
            hasFactualRequestShape(normalized)
    }

    private fun hasFutureTemporalIntent(text: String, clock: TrustRouterClock): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        if (KoreanFutureRegex.containsMatchIn(normalized) || EnglishFutureRegex.containsMatchIn(normalized)) {
            return true
        }
        return KoreanYearRegex.findAll(normalized).any { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let { it > clock.currentYear() } == true
        } || EnglishYearRegex.findAll(normalized).any { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let { it > clock.currentYear() } == true
        }
    }

    private fun hasForecastIntent(text: String, futureTemporal: Boolean): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val explicitForecastRequest = ForecastRequestRegex.containsMatchIn(normalized)
        val futureForecastTopic = futureTemporal && ForecastTopicRegex.containsMatchIn(normalized) &&
            (hasFactualRequestShape(normalized) ||
                hasExplicitSearch(normalized) ||
                StrongCertaintyRegex.containsMatchIn(normalized))
        return explicitForecastRequest || futureForecastTopic
    }

    private fun hasFuturePrediction(text: String, futureTemporal: Boolean): Boolean {
        if (!futureTemporal) return false
        val normalized = text.lowercase(Locale.ROOT)
        return PredictionActionRegex.containsMatchIn(normalized) ||
            hasAccuracyOrCertaintyMarker(normalized) && hasFactualRequestShape(normalized) ||
            FutureFactQuestionRegex.containsMatchIn(normalized)
    }

    private fun hasImpossibleCertainty(
        text: String,
        futureTemporal: Boolean,
        futurePrediction: Boolean,
        forecast: Boolean,
    ): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val strongCertainty = StrongCertaintyRegex.containsMatchIn(normalized)
        return hasLotteryOrExamUnknowable(normalized, futureTemporal) ||
            forecast && strongCertainty ||
            futureTemporal && futurePrediction && !forecast &&
                hasAccuracyOrCertaintyMarker(normalized)
    }

    private fun hasLotteryOrExamUnknowable(text: String, futureTemporal: Boolean): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val futureDraw = LotteryFutureDrawRegex.containsMatchIn(normalized)
        val lotteryResult = LotteryWinningResultRegex.containsMatchIn(normalized)
        val lotteryRequest = hasFactualRequestShape(normalized) ||
            PredictionActionRegex.containsMatchIn(normalized) ||
            StrongCertaintyRegex.containsMatchIn(normalized)
        val futureExam = futureTemporal &&
            !ExamFutureMetadataRegex.containsMatchIn(normalized) &&
            !ExamProbabilityRegex.containsMatchIn(normalized) &&
            ExamFutureResultRegex.containsMatchIn(normalized) &&
            (hasFactualRequestShape(normalized) || StrongCertaintyRegex.containsMatchIn(normalized))
        return LotteryContextRegex.containsMatchIn(normalized) && futureDraw && lotteryResult && lotteryRequest ||
            futureExam
    }

    private fun hasAccuracyOrCertaintyMarker(text: String): Boolean =
        WeakAccuracyRegex.containsMatchIn(text) || StrongCertaintyRegex.containsMatchIn(text)

    private fun isObviousLocalTask(text: String, clock: TrustRouterClock): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        if (ModernSpecificRegex.containsMatchIn(normalized)) return false
        if (isTransformationTask(normalized)) return true
        if (SimpleStableRegex.containsMatchIn(normalized)) return true
        val yearMentioned = KoreanYearRegex.containsMatchIn(normalized) || EnglishYearRegex.containsMatchIn(normalized)
        return yearMentioned && !hasFutureTemporalIntent(normalized, clock) && ConceptualQuestionRegex.containsMatchIn(normalized)
    }

    private fun isTransformationTask(text: String): Boolean =
        TransformationRegex.containsMatchIn(text.lowercase(Locale.ROOT))

    private fun hasFactualRequestShape(text: String): Boolean =
        FactualRequestRegex.containsMatchIn(text) || text.contains("?") || text.contains("？")

    private fun hasBoundedHighComplexity(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val strongMarker = StrongComplexityRegex.containsMatchIn(normalized)
        val generalMarker = GeneralComplexityRegex.containsMatchIn(normalized)
        return strongMarker || generalMarker && text.length >= GeneralComplexityMinimumLength
    }

    private fun TrustClassifierReason.toRoutingReason(): TrustRoutingReason = when (this) {
        TrustClassifierReason.STABLE_KNOWLEDGE -> TrustRoutingReason.STABLE_KNOWLEDGE
        TrustClassifierReason.REASONING_OR_TRANSFORMATION -> TrustRoutingReason.REASONING_OR_TRANSFORMATION
        TrustClassifierReason.CURRENT_OR_RECENT -> TrustRoutingReason.CURRENT_OR_RECENT
        TrustClassifierReason.CHANGEABLE_FACT -> TrustRoutingReason.CHANGEABLE_FACT
        TrustClassifierReason.SPECIFIC_MODERN_FACT -> TrustRoutingReason.SPECIFIC_MODERN_FACT
        TrustClassifierReason.EXTERNAL_VERIFICATION_HELPFUL -> TrustRoutingReason.EXTERNAL_VERIFICATION_HELPFUL
        TrustClassifierReason.UNCERTAIN_STORED_KNOWLEDGE -> TrustRoutingReason.UNCERTAIN_STORED_KNOWLEDGE
        TrustClassifierReason.FUTURE_UNKNOWABLE -> TrustRoutingReason.FUTURE_UNKNOWABLE
    }

    private const val LargeInputThreshold = 4_000
    private const val GeneralComplexityMinimumLength = 1_200

    private val KoreanNoSearchRegex = Regex(
        "(?:검색\\s*(?:하지\\s*(?:마|말고|않고)|없이)|웹\\s*검색\\s*없이|인터넷\\s*검색\\s*없이|찾지\\s*말고)",
    )
    private val EnglishNoSearchRegex = Regex(
        "(?i)(?<![A-Za-z])(?:don't\\s+(?:search|look\\s*up)|do\\s+not\\s+(?:search|look\\s*up)|without\\s+(?:web\\s+)?search(?:ing)?|no\\s+(?:web\\s+|internet\\s+)?search)(?![A-Za-z])",
    )
    private val KoreanSearchRegex = Regex(
        "(?:검색\\s*(?:해|해서|하여)(?:줘|요|봐)?|찾아\\s*(?:봐|줘)|(?:웹|인터넷)에서|출처\\s*찾아|최신\\s*정보\\s*찾아)",
    )
    private val EnglishSearchRegex = Regex(
        "(?i)(?<![A-Za-z])(?:search|look\\s+up|find\\s+online|web\\s+search|internet\\s+search|latest\\s+sources|find\\s+sources)(?![A-Za-z])",
    )
    private val OfficialSourceRequestRegex = Regex(
        "(?:공식\\s*(?:자료|출처|사이트|발표|공지|데이터)|정부\\s*(?:자료|발표|공지)|(?<![A-Za-z])official\\s+(?:source|site|announcement|statement|data)(?![A-Za-z])|(?<![A-Za-z])government\\s+(?:source|announcement|statement|data)(?![A-Za-z]))",
        RegexOption.IGNORE_CASE,
    )
    private val EnglishAuthorityAttributionRegex = Regex(
        "(?i)(?<![A-Za-z])according\\s+to\\s+(?:the\\s+)?([A-Za-z][A-Za-z0-9&.'-]*)",
    )
    private val KoreanCurrentRegex = Regex(
        "(?<![가-힣])(?:지금|현재|오늘|어제|이번\\s*주|최근|최신|요즘|현\\s*시점)(?:은|는|이|가|의|에|도|부터)?(?![가-힣])",
    )
    private val EnglishCurrentRegex = Regex(
        "(?i)(?<![A-Za-z])(?:currently|today|yesterday|this\\s+week|recent|latest|right\\s+now|now)(?![A-Za-z])",
    )
    private val KoreanCurrentMetaLanguageRegex = Regex(
        "(?:현재\\s*(?:라는\\s*단어|시제)|현재완료|과거시제)",
    )
    private val KoreanFutureRegex = Regex("(?:내일|모레|다음\\s*(?:주|달|해|시험|추첨))")
    private val EnglishFutureRegex = Regex(
        "(?i)(?<![A-Za-z])(?:tomorrow|the\\s+next\\s+(?:week|month|year)|next\\s+(?:week|year|exam|test)|future)(?![A-Za-z])",
    )
    private val KoreanYearRegex = Regex("(?<!\\d)(20\\d{2})년")
    private val EnglishYearRegex = Regex("(?i)(?<![A-Za-z])(20\\d{2})(?![A-Za-z])")
    private val ForecastRequestRegex = Regex(
        "(?:(?:날씨|기온|비(?:\\s*올|가|는|를|에|와|도)|눈(?:\\s*올|이|은|을|에|와|도)).{0,16}예보|예보.{0,16}(?:알려|찾아|검색|어때)|비(?:\\s*올|가|는|를).{0,12}가능성|기온.{0,12}전망)|(?<![A-Za-z])(?:weather\\s+forecast|chance\\s+of\\s+(?:rain|snow)|forecast\\s+(?:for|tomorrow)|what\\s+will\\s+the\\s+weather)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )
    private val ForecastTopicRegex = Regex(
        "(?:날씨|기온|비(?:\\s*올|가|는|를|에|와|도)|눈(?:\\s*올|이|은|을|에|와|도))|(?<![A-Za-z])(?:weather|rain|snow|temperature)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )
    private val PredictionActionRegex = Regex(
        "(?:예측|전망|예상)\\s*(?:해줘|해 주세요|해봐|알려줘|할까)|(?:얼마가|누가|무슨 일이)\\s*(?:될까|일어날까)|(?<![A-Za-z])(?:predict|forecast)\\s+(?:who|what|which|the|next|tomorrow)|what\\s+will\\s+|who\\s+will\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val FutureFactQuestionRegex = Regex(
        "(?:누가|누구|얼마|어디|무엇|뭐).{0,20}(?:될까|일까|인지)|(?<![A-Za-z])(?:what|who|how\\s+much|which)\\s+will\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val WeakAccuracyRegex = Regex(
        "(?:정확히|정확하게|accurately)",
        RegexOption.IGNORE_CASE,
    )
    private val StrongCertaintyRegex = Regex(
        "(?:100%|확실하게|확실히|확정|반드시|보장|오차\\s*없이|exactly|with\\s+certainty|certainly|guarantee|guaranteed|確実)",
        RegexOption.IGNORE_CASE,
    )
    private val LotteryContextRegex = Regex(
        "(?:로또|복권|추첨|당첨\\s*번호)|(?<![A-Za-z])(?:lotto|lottery|draw)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )
    private val LotteryFutureDrawRegex = Regex(
        "(?:다음|이번\\s*주|이번주).{0,16}(?:추첨|로또|복권|번호)|(?<![A-Za-z])(?:next\\s+week|next\\s+draw|upcoming)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )
    private val LotteryWinningResultRegex = Regex(
        "(?:당첨\\s*(?:번호|될\\s*번호)|(?:나올|나오는)\\s*번호|번호\\s*(?:가|이)?\\s*당첨될|winning\\s+(?:lottery\\s+)?numbers?|numbers?\\s+that\\s+will\\s+win)",
        RegexOption.IGNORE_CASE,
    )
    private val ExamFutureResultRegex = Regex(
        "(?:시험|수능).{0,50}(?:나올|출제될|정답|어떤\\s+문제)|(?:question|problem|answer)s?.{0,30}(?:will\\s+(?:be\\s+on|appear)|appear\\s+on).{0,20}(?:exam|test)|(?:exam|test).{0,30}(?:question|answer)s?.{0,30}(?:will|appear|in\\s+advance)|(?:exam|test).{0,30}answer",
        RegexOption.IGNORE_CASE,
    )
    private val ExamFutureMetadataRegex = Regex(
        "(?:정답).{0,16}(?:공개|일정|언제|올라|어디서|확인)|(?:(?:when|where).{0,32}(?:answer\\s*key|answers?)|(?:answer\\s*key|answers?).{0,32}(?:when|where|released|published|posted|available|check))",
        RegexOption.IGNORE_CASE,
    )
    private val ExamProbabilityRegex = Regex(
        "(?:가능성|경향|예상|유형이\\s*나올\\s*가능|likely|probable|probability|trend|expected)",
        RegexOption.IGNORE_CASE,
    )
    private val FactualRequestRegex = Regex(
        "(?:누구|누가|무엇|뭐|어디|얼마|언제|비교|알려|말해|설명|찾아|계산|what|who|where|when|how|compare|explain|tell|calculate|give me)",
        RegexOption.IGNORE_CASE,
    )
    private val ConceptualQuestionRegex = Regex(
        "(?:뭐야|무엇인지|무슨 뜻|무슨 의미|차이|왜 중요한|어떻게 (?:측정|작동)|설명해|정의|what is|what does .* mean|difference between|explain|how does .* work|why .* important)",
        RegexOption.IGNORE_CASE,
    )
    private val TransformationRegex = Regex(
        "(?:요약|번역|영어로|자연스럽게|고쳐|다듬|정리해줘|summari[sz]e|translate|rewrite|paraphrase|proofread|fix this)",
        RegexOption.IGNORE_CASE,
    )
    private val SimpleStableRegex = Regex(
        "(?:^\\s*1\\s*[+＋]\\s*1|광합성|삼각형의? 내각|역사 핵심|과학 개념|수학 문제.{0,20}(?:풀어|해결)|국회는 무슨 일을|선거 제도|날씨와 기후의 차이|주가가 무엇|뉴스 기사의 구조|환율이 경제에 미치는 영향|금리는 왜 중요|통계에서 예측의 의미|예측 모델|전망이라는 단어|(?<![A-Za-z])what is weather\\b|(?<![A-Za-z])what exactly does prediction mean\\b|(?<![A-Za-z])explain how stock prices work\\b|(?<![A-Za-z])what does probability mean\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val ModernSpecificRegex = Regex(
        "(?:hbm\\s*[234]|rtx\\s*50\\d{2}|삼성전자|product\\s*[a-z]?\\d+|specific modern|latest release|current product)",
        RegexOption.IGNORE_CASE,
    )
    private val StrongComplexityRegex = Regex(
        "(?:대규모\\s*문서|긴\\s*(?:문서|코드)|복잡한\\s*코드\\s*분석|다단계\\s*추론|large\\s+document|very\\s+long\\s+(?:document|code)|complex\\s+(?:code|codebase)\\s+analysis|multi[- ]stage\\s+reasoning|codebase)",
        RegexOption.IGNORE_CASE,
    )
    private val GeneralComplexityRegex = Regex(
        "(?:복잡한|전문가\\s*수준|complex|advanced|professional|multi[- ]step)",
        RegexOption.IGNORE_CASE,
    )
}

object TrustClassifierParser {
    data class ParsedClassification(
        val route: TrustRoute,
        val reason: TrustClassifierReason,
    )

    fun parse(rawOutput: String): ParsedClassification? {
        val normalized = rawOutput.trim()
        if (normalized.isBlank() || normalized.any { it == '\n' || it == '\r' }) return null
        val fields = normalized.split('|')
        if (fields.size != 2 || fields.any { it.isBlank() }) return null
        val route = when (fields[0]) {
            "LOCAL" -> TrustRoute.LOCAL
            "SEARCH" -> TrustRoute.SEARCH
            "ABSTAIN" -> TrustRoute.ABSTAIN
            else -> return null
        }
        val reason = runCatching { TrustClassifierReason.valueOf(fields[1]) }.getOrNull() ?: return null
        val compatible = when (route) {
            TrustRoute.LOCAL -> reason == TrustClassifierReason.STABLE_KNOWLEDGE ||
                reason == TrustClassifierReason.REASONING_OR_TRANSFORMATION
            TrustRoute.SEARCH -> reason == TrustClassifierReason.CURRENT_OR_RECENT ||
                reason == TrustClassifierReason.CHANGEABLE_FACT ||
                reason == TrustClassifierReason.SPECIFIC_MODERN_FACT ||
                reason == TrustClassifierReason.EXTERNAL_VERIFICATION_HELPFUL ||
                reason == TrustClassifierReason.UNCERTAIN_STORED_KNOWLEDGE
            TrustRoute.ABSTAIN -> reason == TrustClassifierReason.FUTURE_UNKNOWABLE
            TrustRoute.CLOUD_SUGGEST -> false
        }
        if (!compatible) return null
        return ParsedClassification(route, reason)
    }
}
