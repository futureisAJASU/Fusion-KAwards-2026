package com.projectnuke.fusion.projects

/**
 * Issue 1: pure exact-request witness matcher for Work Plan MESSAGE
 * settlement.
 *
 * The durable fact proving `assistantMessageId was produced by requestId
 * R` is the generation witness row written at the authoritative
 * persistence boundary — never observer chronology, never a
 * caller-attached requestId, never the response-version `groupId` (which
 * proves user-turn/version ownership, not request identity).
 *
 * A receipt settles only when the witness for its attempt links exactly
 * the receipt's request, conversation, user message, and assistant. Pure
 * so the rule is unit-testable without Room.
 */
object ProjectWorkPlanRequestWitnessPolicy {
    data class GenerationWitness(
        val attemptId: String,
        val requestId: String,
        val conversationId: Long,
        val userMessageId: Long,
        val assistantMessageId: Long?,
    )

    fun receiptMatchesWitness(
        attemptId: String,
        requestId: String?,
        conversationId: Long?,
        userMessageId: Long?,
        assistantMessageId: Long?,
        witness: GenerationWitness?,
    ): Boolean {
        if (witness == null) return false
        if (attemptId.isBlank() || witness.attemptId != attemptId) return false
        if (requestId.isNullOrBlank() || witness.requestId != requestId) return false
        if (conversationId == null || conversationId <= 0L || witness.conversationId != conversationId) return false
        if (userMessageId == null || userMessageId <= 0L || witness.userMessageId != userMessageId) return false
        if (assistantMessageId == null || assistantMessageId <= 0L) return false
        // The exact request must have linked exactly this assistant. A null
        // link (failed/cancelled request) or a different assistant (retry /
        // regeneration under another request, or a forged requestId) never
        // settles.
        return witness.assistantMessageId == assistantMessageId
    }
}
