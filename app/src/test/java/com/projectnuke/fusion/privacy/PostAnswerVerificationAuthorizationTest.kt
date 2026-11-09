package com.projectnuke.fusion.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PostAnswerVerificationAuthorizationTest {
    private val identity = PostAnswerVerificationSearchIdentity(
        conversationId = 10L,
        assistantMessageId = 21L,
        answerSha256 = "a".repeat(64),
        responseVersionId = 21L,
    )
    private val queries = listOf(
        AuthorizedTargetedQuery("C1", "Acme ProductX2 2026"),
        AuthorizedTargetedQuery("C2", "Beta 2025 update"),
    )

    private fun authorization(
        operationId: String = "operation-a",
        owner: PostAnswerVerificationSearchIdentity = identity,
        approved: List<AuthorizedTargetedQuery> = queries,
    ) = requireNotNull(
        PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(operationId, owner, approved),
    )

    @Test
    fun `authorization accepts only its exact operation claim and query pair`() {
        val auth = authorization()

        assertFalse(DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(auth, "operation-a", identity, "C1", "Acme ProductX2 2026"))
        assertTrue(auth.claimExecution("operation-a", identity))
        assertTrue(DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(auth, "operation-a", identity, "C1", "Acme ProductX2 2026"))
        assertFalse(DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(auth, "operation-a", identity, "C1", "Acme ProductX2 latest"))
        assertFalse(DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(auth, "operation-a", identity, "C2", "Acme ProductX2 2026"))
        assertFalse(DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(auth, "operation-b", identity, "C1", "Acme ProductX2 2026"))
    }

    @Test
    fun `answer conversation message and response version mutations invalidate authorization`() {
        val auth = authorization()
        val changedIdentities = listOf(
            identity.copy(conversationId = 11L),
            identity.copy(assistantMessageId = 22L),
            identity.copy(answerSha256 = "b".repeat(64)),
            identity.copy(responseVersionId = 22L),
        )

        changedIdentities.forEach { changed ->
            assertFalse(
                DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                    auth, "operation-a", changed, "C1", "Acme ProductX2 2026",
                ),
            )
        }
    }

    @Test
    fun `authorization is one-shot and query list cannot be mutated`() {
        val auth = authorization()

        assertTrue(auth.claimExecution("operation-a", identity))
        assertFalse(auth.claimExecution("operation-a", identity))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (auth.queries as MutableList<AuthorizedTargetedQuery>).add(AuthorizedTargetedQuery("C3", "unapproved"))
        }
    }

    @Test
    fun `authorization factory preserves existing query bounds`() {
        assertNotNull(authorization())
        assertTrue(
            PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                "operation-a", identity,
                (1..4).map { AuthorizedTargetedQuery("C$it", "query $it") },
            ) == null,
        )
        assertTrue(
            PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                "operation-a", identity,
                listOf(AuthorizedTargetedQuery("C1", "x".repeat(PostAnswerVerificationSearchAuthorization.MAX_QUERY_CHARS + 1))),
            ) == null,
        )
    }
}
