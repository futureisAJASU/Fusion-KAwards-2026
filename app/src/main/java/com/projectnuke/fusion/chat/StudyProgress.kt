package com.projectnuke.fusion.chat

internal data class StudyTopic(val name: String, val review: Boolean = false, val understood: Boolean = false)
internal data class StudyProgressSession(
    val id: String, val topics: List<StudyTopic> = emptyList(), val submittedTokens: List<String> = emptyList(),
    val questionsRequested: Int = 0, val attempts: Int = 0, val hints: Int = 0,
    val awaitingAttempt: Boolean = false, val ended: Boolean = false,
)
internal data class StudyProgress(val conversationId: Long, val sessions: List<StudyProgressSession> = emptyList())

/** Counts acknowledged Study interactions, not model correctness or generated questions. */
internal object StudyProgressPolicy {
    const val MAX_SESSIONS = 8
    const val MAX_TOPICS = 8
    const val MAX_EVENTS = 64
    fun submission(state: StudyProgress, token: String, text: String): StudyProgress {
        require(token.isNotBlank() && token.length <= 128)
        if (state.sessions.any { token in it.submittedTokens }) return state
        val previous = state.sessions.lastOrNull()?.takeUnless { it.ended }
        val session = previous ?: StudyProgressSession(token)
        val action = StudySessionAction.entries.firstOrNull { text.trimEnd().endsWith(StudySessionPromptPolicy.promptFor(it)) }
        val prefix = action?.let { text.trimEnd().removeSuffix(StudySessionPromptPolicy.promptFor(it)).trim() } ?: text.trim()
        val isQuestion = action == StudySessionAction.QUESTION || action == StudySessionAction.NEXT_QUESTION
        val answerSubmission = action == null || action == StudySessionAction.GRADING
        val attempted = session.awaitingAttempt && prefix.isNotBlank() && !isQuestion && answerSubmission
        val topics = if (session.topics.isEmpty() && action == null && prefix.isNotBlank()) listOf(StudyTopic(prefix.take(80))) else session.topics
        val next = session.copy(topics = topics, submittedTokens = (session.submittedTokens + token).takeLast(MAX_EVENTS),
            questionsRequested = (session.questionsRequested + if (isQuestion) 1 else 0).coerceAtMost(1000),
            hints = (session.hints + if (action == StudySessionAction.HINT) 1 else 0).coerceAtMost(1000),
            attempts = (session.attempts + if (attempted) 1 else 0).coerceAtMost(1000),
            awaitingAttempt = if (isQuestion) true else if (attempted) false else session.awaitingAttempt)
        return state.copy(sessions = ((if (previous == null) state.sessions else state.sessions.dropLast(1)) + next).takeLast(MAX_SESSIONS))
    }
    fun end(state: StudyProgress): StudyProgress = state.copy(sessions = state.sessions.mapIndexed { index, s ->
        if (index == state.sessions.lastIndex) s.copy(ended = true) else s })
    fun topic(state: StudyProgress, sessionId: String, name: String, review: Boolean, understood: Boolean): StudyProgress {
        val normalized = name.trim().take(80)
        require(normalized.isNotEmpty())
        return state.copy(sessions = state.sessions.map { session ->
            if (session.id != sessionId) session else {
                val old = session.topics.indexOfFirst { it.name == normalized }
                val next = StudyTopic(normalized, review, understood)
                val topics = if (old >= 0) session.topics.mapIndexed { i, t -> if (i == old) next else t }
                    else if (session.topics.size < MAX_TOPICS) session.topics + next else session.topics
                session.copy(topics = topics)
            }
        })
    }
}
