package com.projectnuke.fusion.projects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 1 regression: the durable witness — not chronology, not a
 * caller-attached id, not the version group — proves the assistant was
 * produced by the bound request.
 */
class ProjectWorkPlanRequestWitnessPolicyTest {
    private fun witness(
        assistant: Long? = 44L,
        request: String = "request-1",
    ) = ProjectWorkPlanRequestWitnessPolicy.GenerationWitness(
        attemptId = "attempt-3",
        requestId = request,
        conversationId = 7L,
        userMessageId = 11L,
        assistantMessageId = assistant,
    )

    private fun matches(
        request: String? = "request-1",
        conversation: Long? = 7L,
        user: Long? = 11L,
        assistant: Long? = 44L,
        witness: ProjectWorkPlanRequestWitnessPolicy.GenerationWitness? = witness(),
    ) = ProjectWorkPlanRequestWitnessPolicy.receiptMatchesWitness(
        attemptId = "attempt-3",
        requestId = request,
        conversationId = conversation,
        userMessageId = user,
        assistantMessageId = assistant,
        witness = witness,
    )

    @Test
    fun `bound request answer with full witness match settles`() {
        assertTrue(matches())
    }

    @Test
    fun `later normal answer under another request never matches`() {
        // Same attempt witness (R1), but the reported assistant is B's
        // answer from R2 for user B.
        assertFalse(matches(user = 12L, assistant = 45L))
        assertFalse(matches(assistant = 45L))
    }

    @Test
    fun `same-user retry or regeneration under another request never matches`() {
        // R2 produced 45 for the same user U; the bound request is R1.
        assertFalse(matches(assistant = 45L, witness = witness(assistant = 44L)))
        // R1 failed/cancelled with no linked assistant: nothing matches.
        assertFalse(matches(witness = witness(assistant = null)))
        assertFalse(matches(assistant = 45L, witness = witness(assistant = null)))
    }

    @Test
    fun `forged request id never matches`() {
        assertFalse(matches(request = "forged-request"))
        assertFalse(matches(request = "request-1 ", witness = witness(request = "request-1")))
        assertFalse(matches(request = null))
        assertFalse(matches(request = "  "))
    }

    @Test
    fun `missing witness or mismatched scope never matches`() {
        assertFalse(matches(witness = null))
        assertFalse(matches(conversation = 8L))
        assertFalse(matches(user = 0L))
        assertFalse(matches(assistant = 0L))
        assertFalse(
            matches(
                witness = witness().copy(attemptId = "attempt-9"),
            ),
        )
    }
}
