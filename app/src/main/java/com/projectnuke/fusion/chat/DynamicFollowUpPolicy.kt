package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import com.projectnuke.fusion.util.visibleMessageContent
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace

/**
 * Dynamic semantic follow-up questions v1 (동적 후속 질문 추천 v1).
 *
 * An enhancement, never a dependency, of the settled primary answer: after
 * the canonical visible assistant answer settles, a bounded asynchronous
 * generation derives approximately [DynamicFollowUpPolicy.MAX_QUESTIONS]
 * topic-specific follow-up QUESTIONS from the latest relevant visible user
 * request and the canonical visible answer.
 *
 * Ownership contract ([DynamicFollowUpOwnership]):
 * - the full identity is frozen before generation starts and re-validated
 *   against live state before any publication;
 * - conversation identity, newChatEpoch, originating user turn identity,
 *   target assistant message identity, canonical active response-version
 *   group and active response-version message id all participate;
 * - a late result for any changed identity is discarded, so a suggestion
 *   generated for answer A can never appear under answer B.
 *
 * Truth/hallucination contract:
 * - suggestions are questions, not new factual claims;
 * - the parser keeps only question-style items and never publishes partial
 *   parser garbage;
 * - the deterministic [NextActionSuggestionPolicy] remains the fallback.
 *
 * Bounds: max 3 published questions, bounded input chars, bounded model
 * output, bounded question length, blank/duplicate/malformed rejection.
 */
internal data class DynamicFollowUpOwnership(
    val conversationId: Long,
    val newChatEpoch: Long,
    val userTurnId: Long,
    val assistantMessageId: Long,
    val versionGroupId: Long,
    val activeResponseMessageId: Long,
)

/** Ephemeral UI-derived state; never durable conversation truth. */
internal data class DynamicFollowUpState(
    val ownership: DynamicFollowUpOwnership,
    val questions: List<String>,
)

internal object DynamicFollowUpPolicy {
    const val MAX_QUESTIONS = 3
    const val MAX_USER_REQUEST_CHARS = 800
    const val MAX_ANSWER_CHARS = 3_200
    const val MAX_QUESTION_CHARS = 60
    const val MAX_OUTPUT_CHARS = 800

    /** Native cancel request deadline; the Conversation is still joined to terminal. */
    const val TIMEOUT_MS = 20_000L

    /** Bounded per-turn output budget; small, well below any KV capacity. */
    const val MAX_OUTPUT_TOKENS = 256

    /** Bound on the source clauses inspected per loaded question. */
    private const val MAX_SOURCE_CLAUSES = 64

    /**
     * Same trust/failure/mode eligibility as ordinary next actions: an ABSTAIN
     * presentation, a failed-search recovery state and the STUDY interaction
     * mode all suppress dynamic follow-ups. Delegates to the existing policy
     * so the two surfaces can never drift apart.
     *
     * Additionally gated by the "추천 질문 자동 생성" user preference: when
     * auto-generation is disabled, dynamic eligibility is false while the
     * deterministic [NextActionSuggestionPolicy] fallback stays unaffected
     * (it never consults this flag).
     */
    fun isEligible(
        routingKind: TrustRoutingPresentationKind,
        isRetrySearchRecovery: Boolean,
        isStudyMode: Boolean,
        isAutoGenerateEnabled: Boolean,
    ): Boolean = isAutoGenerateEnabled && NextActionEligibilityPolicy.isEligible(
        routingKind = routingKind,
        isRetrySearchRecovery = isRetrySearchRecovery,
        isStudyMode = isStudyMode,
    )

    /**
     * Exact dynamic recommendation input authority. Only the latest relevant
     * visible user request and the canonical visible assistant answer enter,
     * both re-stripped through the canonical visible-message-content contract
     * (attachment envelopes, hidden search/trust metadata, metrics/thinking
     * blocks never appear) plus the local-RAG source authority the canonical
     * answer path strips. No localPath, content hash, RAG envelope, trust
     * metadata, provider secret or persistence envelope is accepted or added.
     *
     * Strict residue rule: the general visible-content contract intentionally
     * preserves malformed envelopes instead of silently deleting possible
     * user text. Follow-up generation is stricter — if recognizable Fusion
     * machine-reserved residue survives sanitization, no dynamic request is
     * built (deterministic fallback). Returns null when no usable visible
     * input exists or residue is detected.
     */
    fun buildMessages(rawUserRequest: String, rawAnswer: String): List<ChatMessage>? {
        val (request, answer) = sanitizedParts(rawUserRequest, rawAnswer)
        if (request.isBlank() && answer.isBlank()) return null
        if (hasMachineResidue(request) || hasMachineResidue(answer)) return null
        return listOf(
            ChatMessage(
                role = "system",
                content = SYSTEM_INSTRUCTION,
            ),
            ChatMessage(
                role = "user",
                content = buildString {
                    append("[사용자 질문]\n")
                    append(request.ifBlank { "(없음)" })
                    append("\n\n[답변]\n")
                    append(answer.ifBlank { "(없음)" })
                },
            ),
        )
    }

    private fun sanitizedParts(rawUserRequest: String, rawAnswer: String): Pair<String, String> {
        val request = visibleMessageContent(LocalRagSourceMetadata.strip(rawUserRequest))
            .take(MAX_USER_REQUEST_CHARS)
        val answer = visibleMessageContent(LocalRagSourceMetadata.strip(rawAnswer))
            .take(MAX_ANSWER_CHARS)
        return request to answer
    }

    /**
     * Recognizable Fusion machine-reserved residue. Bounded to known
     * machine markers only: attachment envelopes (any well- or mal-formed
     * fragment), hidden FUSION_* metadata markers, and machine reasoning /
     * answer blocks. Ordinary visible user text — including harmless '<',
     * '[' or user-typed visible filesystem paths — never matches.
     */
    private val MachineResidueMarkers = listOf(
        "fusion_attachment",
        "fusion_search_sources_json",
        "fusion_trust_route_json",
        "fusion_local_rag_sources_json",
        "fusion_answer_context_json",
        "fusion_thinking",
        "fusion_metrics",
        "fusion_answer",
    )

    internal fun hasMachineResidue(sanitizedText: String): Boolean {
        if (sanitizedText.isEmpty()) return false
        val lower = sanitizedText.lowercase()
        return MachineResidueMarkers.any { marker -> marker in lower }
    }

    private val SYSTEM_INSTRUCTION = """
        너는 대화의 다음 질문을 추천하는 도우미다. 아래 제공된 사용자 질문과 답변만 근거로 삼는다.
        규칙:
        - 답변 내용을 이어가는 자연스러운 후속 질문을 정확히 3개 만든다.
        - 각 질문은 한 줄에 하나씩 번호를 붙여 쓴다(1., 2., 3.).
        - 질문만 쓴다. 답이나 설명, 인사를 쓰지 않는다.
        - 제공된 대화에 없는 사실을 전제로 삼지 않는다.
        - 비교(보다/than), 우월·열위, 원인·이유를 묻는 질문은 제공된 대화에 그 관계나 사실이 명시되어 있을 때만 만든다.
        - 대화에 나오지 않은 개념을 이미 알려진 사실처럼 전제하지 않는다. 새로운 개념은 "무엇인가요?", "어떤 관계가 있나요?"처럼 중립적으로 묻는다.
        - 각 질문은 60자 이내로 짧게 쓴다.
        - 대화가 한국어면 한국어로, 그 외에는 대화 언어로 쓴다.
    """.trimIndent()

    /**
     * Robust bounded parser for imperfect local models: numbering/bullet
     * prefixes are normalized, question-style items only, blanks/oversized
     * lines and duplicates rejected, output capped at [MAX_QUESTIONS].
     * Numbered list lines are preferred over unprefixed ones so preamble
     * chatter never displaces real candidates.
     */
    fun parseQuestions(rawOutput: String): List<String> {
        val raw = rawOutput.take(MAX_OUTPUT_CHARS)
        if (raw.isBlank()) return emptyList()
        val prefixed = ArrayList<String>(MAX_QUESTIONS)
        val unprefixed = ArrayList<String>(MAX_QUESTIONS)
        for (line in raw.lines()) {
            val hadPrefix = hasListPrefix(line)
            val text = stripListPrefix(line)
            if (!isQuestionStyle(text)) continue
            if (hadPrefix) prefixed.add(text) else unprefixed.add(text)
        }
        val source = if (prefixed.isNotEmpty()) prefixed else unprefixed
        val seen = HashSet<String>()
        val out = ArrayList<String>(MAX_QUESTIONS)
        for (question in source) {
            if (out.size == MAX_QUESTIONS) break
            if (!seen.add(duplicateKey(question))) continue
            out.add(question)
        }
        return out
    }

    /**
     * Freezing identity by visible text equality is forbidden: ownership is
     * the exact typed identity tuple only.
     */
    fun isOwnershipCurrent(
        frozen: DynamicFollowUpOwnership,
        current: DynamicFollowUpOwnership?,
    ): Boolean = current == frozen

    /**
     * Origin-resolution gate (item 5): a dynamic recommendation is generated
     * only for a resolved originating user turn. A synthetic negative
     * userTurnId (or any id with no live user message) fails closed — an
     * answer-only recommendation is never synthesized.
     */
    fun isOriginUserTurnResolved(
        activeMessages: List<MessageEntity>,
        userTurnId: Long,
    ): Boolean = activeMessages.any { it.role == "user" && it.id == userTurnId }

    /**
     * Bounded partial composition: dynamic semantic questions first, then
     * deterministic contextual fallback for the remaining slots, at most
     * [NextActionSuggestionPolicy.MAX_SUGGESTIONS] total. Zero dynamic
     * questions leaves the deterministic row unchanged. Dynamic/static
     * duplicates (exact target, label, or normalized text) are removed so
     * one family can never exceed the cap. Every duplicate predicate is
     * evaluated against the *presented* row before anything is recorded, so
     * a candidate rejected late (prompt duplicates an already-shown dynamic
     * question) never pollutes the seen sets for later valid candidates.
     * Both the normalized label and the normalized prompt identity of every
     * presented item are recorded, so a presented prompt blocks a later
     * duplicate label and a presented label blocks a later duplicate prompt.
     * The deterministic list already carries provenance/Study entries where
     * eligible, so those behaviors survive in the fill slots.
     */
    fun composeRow(
        dynamicQuestions: List<String>,
        deterministic: List<NextActionSuggestion>,
    ): List<NextActionSuggestion> {
        val cap = NextActionSuggestionPolicy.MAX_SUGGESTIONS
        val dynamic = suggestions(dynamicQuestions).take(MAX_QUESTIONS).take(cap)
        if (dynamic.isEmpty()) return deterministic.take(cap)
        if (dynamic.size >= cap) return dynamic.take(cap)
        val out = ArrayList<NextActionSuggestion>(cap)
        out.addAll(dynamic)
        val seenIds = out.map { it.id }.toHashSet()
        val seenTargets = out.map { it.target }.toHashSet()
        val seenLabels = out.map { it.label }.toHashSet()
        val seenNormalized = HashSet<String>().apply {
            for (item in out) {
                add(duplicateKey(item.label))
                item.promptText?.let { add(duplicateKey(it)) }
            }
        }
        for (candidate in deterministic) {
            if (out.size >= cap) break
            val normalizedLabel = duplicateKey(candidate.label)
            val promptKey = candidate.promptText?.let { duplicateKey(it) }
            val duplicatesPresentedRow = candidate.id in seenIds ||
                candidate.target in seenTargets ||
                candidate.label in seenLabels ||
                normalizedLabel in seenNormalized ||
                (promptKey != null && promptKey in seenNormalized)
            if (duplicatesPresentedRow) continue
            // Accepted: only now commit the candidate's identities.
            seenIds += candidate.id
            seenTargets += candidate.target
            seenLabels += candidate.label
            seenNormalized += normalizedLabel
            promptKey?.let { seenNormalized += it }
            out.add(candidate)
        }
        return out
    }

    /**
     * Conservative bounded premise validator (truth closure). Suggestions
     * are questions, not new factual claims: a question carrying a loaded
     * premise — an unsupported factual comparison, causal claim or
     * superiority/inferiority assertion — is rejected unless its relation
     * anchors are jointly grounded in factual authority.
     *
     * Fact authority: the canonical visible ASSISTANT ANSWER is the factual
     * authority for loaded premises. The user request stays valid topic /
     * generation context (and may still introduce concepts for NEUTRAL
     * exploratory questions), but a false premise typed by the user can never
     * authenticate itself — loaded grounding never reads the user request.
     *
     * Relational locality: grounding is not a global bag-of-words. A loaded
     * premise (e.g. "eDRAM이 SRAM보다 빠른 이유는?") is accepted only when
     * every meaningful grounding stem is supported inside one clause of the
     * preserving-whitespace factual authority (sentence / line window), and
     * that clause is not a refutation. Finding the entities and the property
     * in unrelated sentences, or in a clause that explicitly negates the
     * relation ("근거는 없습니다", "is not faster"), never establishes the
     * premise, so scattered vocabulary and refutations fail closed to the
     * deterministic row.
     *
     * Line preservation: the factual-authority path uses the canonical
     * preserving-whitespace visible contract, so newline-delimited bullet /
     * list lines stay separate grounding clauses instead of being collapsed
     * into one sentence.
     *
     * Neutral exploratory questions ("eDRAM은 무엇인가요?", "어떤 관계가
     * 있나요?") carry no loaded marker and stay eligible. Uncertain
     * candidates are rejected and fall back deterministically; this is
     * intentionally not a general NLP framework.
     */
    fun filterSupported(
        questions: List<String>,
        rawUserRequest: String,
        rawAnswer: String,
    ): List<String> {
        if (questions.isEmpty()) return emptyList()
        val (request, answer) = sanitizedParts(rawUserRequest, rawAnswer)
        if (request.isBlank() && answer.isBlank()) return emptyList()
        val factualAnswer = factualAuthorityAnswer(rawAnswer)
        return questions.filter { question ->
            isPremiseSupported(question, factualAnswer)
        }
    }

    /**
     * Dedicated preserving-whitespace factual authority for loaded-premise
     * grounding. Same canonical stripping as the compact path (attachment
     * envelopes, search/trust metadata, metrics/thinking blocks, answer
     * unwrapping, Local RAG metadata), same hard character bound, and the
     * same fail-closed residue rule. Null when no usable factual authority
     * exists (machine residue), so loaded questions reject safely.
     */
    private fun factualAuthorityAnswer(rawAnswer: String): String? {
        val answer = visibleMessageContentPreservingWhitespace(
            LocalRagSourceMetadata.strip(rawAnswer),
        ).take(MAX_ANSWER_CHARS)
        return if (hasMachineResidue(answer)) null else answer
    }

    private fun isPremiseSupported(
        question: String,
        factualAnswer: String?,
    ): Boolean {
        if (!hasLoadedMarker(question)) return true
        val answer = factualAnswer ?: return false
        if (answer.isBlank()) return false
        val stems = groundingStems(question)
        if (stems.isEmpty()) return false
        // Adjacent-entity comparisons: DISTINCT stems that share a leading
        // fragment are different named entities being compared against each
        // other ("삼성전자" vs "삼성전자서비스", "S24" vs "S24FE", "Model 3"
        // vs "Model 30"). Prefix containment can therefore never ground such a
        // stem — a source clause that merely starts with the same characters
        // is a different entity, not the requested one. Repeated identical
        // stems are unaffected, so ordinary conjugation tolerance ("사용돼"
        // vs "사용됩니다") still grounds exactly as before.
        val distinctStems = stems.distinct()
        val exactOnlyStems = distinctStems.filter { stem ->
            distinctStems.any { other ->
                other != stem &&
                    (other.take(2) == stem.take(2) ||
                        other.startsWith(stem) ||
                        stem.startsWith(other))
            }
        }.toSet()
        val normalizedSource = foldIrregular(answer.lowercase())
        val clauses = sourceClauses(normalizedSource)
        if (clauses.isEmpty()) return false
        for (clause in clauses) {
            if (isRefutedClause(clause)) continue
            val compactClause = clause.filterNot { it.isWhitespace() }
            if (stems.all { stem -> stemSupported(stem, clause, compactClause, stem in exactOnlyStems) }) {
                return true
            }
        }
        return false
    }

    private val ComparativeMarkers = listOf(
        "보다", "에 비해", "에비해", "만큼", "가장", "제일", "최고", "최적",
        "than", "compared", "versus", "better", "worse", "faster", "slower",
        "higher", "lower", "stronger", "weaker", "best",
    )
    private val ComparativeTokenMarkers = setOf("더", "vs", "more", "most", "less", "least")

    private val CausalMarkers = listOf("왜", "이유", "원인", "때문", "어째서", "why", "how come")
    private val CausalTokenMarkers = setOf("reason", "cause", "causes")

    private val AssertiveMarkers = listOf("대체", "능가", "초월", "앞지르", "replace", "outperform", "surpass", "beat")

    /**
     * Conservative refutation / negation signals for factual-authority
     * clauses. A clause carrying any of these bounded forms cannot establish
     * a loaded factual premise ("근거는 없습니다", "is not faster than")
     * unless the negation is provably unrelated — which this bounded checker
     * does not attempt. False negatives are accepted because the
     * deterministic row is the fallback. The matches run on the folded,
     * lowercased clause.
     */
    private val RefutationSignals = listOf(
        // Korean negation / refutation roots (substring coverage of
        // 아니다/아니면, 않다/않습니다, 없다/없습니다, 틀리다, 근거가 없다,
        // 일반화할 수 없다 …).
        "아니", "않", "없", "틀리", "근거가 없", "일반화할 수 없",
        // English negation / refutation.
        "not", "isn't", "aren't", "wasn't", "weren't", "cannot", "can't",
        "no evidence", "false", "incorrect",
    )

    private fun isRefutedClause(clause: String): Boolean =
        RefutationSignals.any { signal -> signal in clause }

    private fun hasLoadedMarker(question: String): Boolean {
        val lower = question.lowercase()
        if (ComparativeMarkers.any { it in lower }) return true
        if (CausalMarkers.any { it in lower }) return true
        if (AssertiveMarkers.any { it in lower }) return true
        val tokens = tokenizeQuestion(lower)
        return tokens.any { it in ComparativeTokenMarkers || it in CausalTokenMarkers }
    }

    private val TokenSplitRegex =
        Regex("""[\s,.;:!?~'"「」『』()\[\]{}·•・、，。？！…—–\-/]+""")

    /**
     * Relational-locality windowing for loaded premises. The normalized
     * visible source is split on sentence punctuation and line boundaries
     * into small bounded clauses; a loaded comparison/causal premise must be
     * jointly grounded inside one such clause. Windows are intentionally NOT
     * merged: merging "eDRAM과 SRAM은 메모리 종류입니다." with an unrelated
     * "캐시는 빠른 메모리를 사용합니다." would re-accept the exact
     * scattered-vocabulary case this validator must reject.
     */
    private val ClauseDelimiterRegex =
        Regex("[.\u3002!\uFF01?\uFF1F;\uFF1B\r\n]+")

    private fun sourceClauses(normalizedSource: String): List<String> =
        normalizedSource
            .split(ClauseDelimiterRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_SOURCE_CLAUSES)

    private fun tokenizeQuestion(text: String): List<String> =
        text.split(TokenSplitRegex).filter { it.isNotEmpty() }

    /** Whole entity tokens inside an already lowercased/folded source clause. */
    private val EntityTokenRegex = Regex("[가-힣a-z0-9]+")

    /**
     * Interrogative framing and grammar with no grounding value. Detection
     * runs before this removal, so causal markers (왜/이유/why/…) still
     * trigger the loaded path while never grounding themselves.
     */
    private val FramingTokens = setOf(
        "왜", "이유", "원인", "때문", "어째서", "무엇", "뭐", "어떤", "무슨",
        "어떻게", "언제", "어디", "누구", "얼마나", "몇", "것", "수", "등",
        "what", "why", "how", "when", "where", "who", "whom", "whose", "which",
        "is", "are", "was", "were", "be", "been", "being",
        "the", "a", "an", "of", "to", "in", "on", "for", "and", "or",
        "do", "does", "did", "than", "reason", "cause", "causes",
        "compared", "come",
    )

    /**
     * Fixed Korean particle/ending suffixes, longest match first, applied
     * until fixpoint (bounded: a token can only shrink). Formal verb
     * endings (ㅂ니다/습니다) and plain-style endings precede particles so
     * "빠릅니다" grounds to "빠르".
     */
    private val Suffixes = listOf(
        "습니다", "ㅂ니다", "입니다",
        "에서는", "에게는", "한테서", "에게서", "으로서", "으로써",
        "나요", "는지", "은지", "는가", "은가",
        "에서", "으로", "하고", "이랑", "처럼", "같이", "조차", "마저",
        "보다", "까지", "부터", "한테", "에게",
        "이야", "이다",
        "은", "는", "이", "가", "을", "를", "에", "의", "와", "과",
        "로", "도", "만", "랑", "냐", "니", "요", "까", "ㄴ", "ㄹ", "다",
    )

    private fun stripSuffixes(token: String): String {
        var current = token
        while (current.length > 1) {
            val match = Suffixes.firstOrNull { current.endsWith(it) } ?: break
            current = current.dropLast(match.length)
        }
        return current
    }

    /**
     * Groundable content stems: framing removed, suffixes stripped,
     * single-character residue skipped as too generic. Latin/digit-bearing
     * tokens (SRAM, eDRAM, 3nm) are entities and must match the source
     * exactly (case-insensitive); pure-Hangul descriptors allow a
     * first-two-character containment so ordinary conjugation ("사용돼" vs
     * "사용됩니다") does not veto an established concept.
     */
    private fun groundingStems(question: String): List<String> {
        val out = ArrayList<String>()
        for (token in tokenizeQuestion(question.lowercase())) {
            val trimmed = token.trim { !it.isLetterOrDigit() }
            if (trimmed.isEmpty() || trimmed in FramingTokens) continue
            val stem = stripSuffixes(trimmed)
            // Single-character digits are retained because they are the only
            // distinguishing part of ordinal model entities ("Model 3" vs
            // "Model 30"); single-character word residue stays too generic.
            if ((stem.length < 2 && stem.singleOrNull()?.isDigit() != true) ||
                stem in FramingTokens
            ) continue
            out.add(stem)
        }
        return out
    }

    private fun stemSupported(
        stem: String,
        normalizedSource: String,
        compactSource: String,
        exactOnly: Boolean = false,
    ): Boolean {
        val folded = foldIrregular(stem)
        // Entity-like stems bind at a token boundary. Numeric/alphanumeric
        // entities are never valid inside a longer token ("p1" is not "p10"),
        // and adjacent stems require the same exact identity. A recognized
        // trailing particle/ending is still allowed ("삼성전자" in
        // "삼성전자는"), so agglutination remains grounded.
        if (exactOnly || folded.any { it in '0'..'9' }) {
            return entityBoundarySupported(folded, normalizedSource)
        }
        if (folded.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) {
            return folded in normalizedSource
        }
        if (folded in normalizedSource || folded in compactSource) return true
        return folded.length >= 3 && folded.take(2) in compactSource
    }

    private fun entityBoundarySupported(foldedStem: String, normalizedSource: String): Boolean {
        if (foldedStem.isEmpty()) return false
        return EntityTokenRegex.findAll(normalizedSource).any { match ->
            val token = match.value
            token == foldedStem ||
                (token.length > foldedStem.length &&
                    token.startsWith(foldedStem) &&
                    stripSuffixes(token) == foldedStem)
        }
    }

    /**
     * Minimal irregular-conjugation fold (르/되/하 families), applied
     * identically to question stems and source text — never a stemmer.
     * Without it, a stripped stem such as "빠르" can never match its
     * conjugated source form "빠릅니다" (syllable 릅 ≠ 르) and established
     * questions would be wrongly rejected. The fold is a consistent
     * transformation, so identical words still match; residual collision
     * risk is documented and always fails toward deterministic fallback
     * through the multi-stem conjunction.
     */
    private val IrregularFold = listOf(
        "른" to "르", "를" to "르", "러" to "르", "려" to "르",
        "름" to "르", "릅" to "르", "럽" to "르", "렀" to "르",
        "런" to "르", "럴" to "르", "럼" to "르",
        "돼" to "되", "됩" to "되", "둠" to "되", "됨" to "되",
        "됐" to "되", "됬" to "되", "둬" to "되",
        "해" to "하", "했" to "하",
    )

    private fun foldIrregular(text: String): String {
        var out = text
        for ((from, to) in IrregularFold) out = out.replace(from, to)
        return out
    }

    /**
     * Maps parsed questions to the ordinary Next Action surface. Every
     * suggestion is a composer prompt (tap seeds the question; the user still
     * presses Send); no study entry, no verification claim, no auto-send.
     */
    fun suggestions(questions: List<String>): List<NextActionSuggestion> {
        if (questions.isEmpty()) return emptyList()
        val out = ArrayList<NextActionSuggestion>(MAX_QUESTIONS)
        questions.take(MAX_QUESTIONS).forEachIndexed { index, question ->
            if (question.isBlank() || question.length > MAX_QUESTION_CHARS) return@forEachIndexed
            out.add(
                NextActionSuggestion(
                    id = "dynamic_followup_$index",
                    kind = NextActionKind.DYNAMIC_FOLLOW_UP,
                    label = question,
                    target = NextActionTarget.ComposerPrompt(question),
                ),
            )
        }
        return out
    }

    private fun isQuestionStyle(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.length > MAX_QUESTION_CHARS) return false
        return '?' in text || '？' in text
    }

    private val ListPrefixRegex =
        Regex("""^\s*(?:[-*•‣–]|\d{1,2}[.)．:：]|\(\d{1,2}\))\s+""")
    private val BareNumberPrefixRegex =
        Regex("""^\s*\d{1,2}[.)．]\s*""")

    private fun hasListPrefix(line: String): Boolean = ListPrefixRegex.containsMatchIn(line)

    private fun stripListPrefix(line: String): String {
        val withoutBullet = ListPrefixRegex.replaceFirst(line, "")
        return BareNumberPrefixRegex.replaceFirst(withoutBullet, "").trim()
    }

    private fun duplicateKey(question: String): String =
        question.trim()
            .trimEnd('?', '？', '.', '。', '!', '！')
            .lowercase()
}
