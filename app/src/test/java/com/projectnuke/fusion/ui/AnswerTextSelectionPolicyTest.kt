package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerTextSelectionPolicyTest {
    private val fact = "Acme released ProductX2 in 2026."

    private fun message(content: String, id: Long = 1L) =
        MessageEntity(id, 77L, "assistant", content, 1L)

    private fun capture(
        rawMessage: String,
        selected: String,
        visible: String = rawMessage,
        id: Long = 1L,
        document: MarkdownDocument = parseMarkdownDocument(visible),
    ): AnswerTextSelectionDecision {
        val message = message(rawMessage, id)
        return AnswerTextSelectionPolicy.capture(
            conversationId = message.conversationId,
            message = message,
            answerSurfaceIdentity = AnswerSurfaceIdentity.of(message.conversationId, message),
            selectedParts = listOf(selected),
            document = document,
            selectionId = "test-selection-$id",
        )
    }

    private fun refusal(decision: AnswerTextSelectionDecision) =
        (decision as AnswerTextSelectionDecision.Unsupported).reason

    @Test fun `plain Korean and mixed Korean English sentences map from rendered text`() {
        val korean = capture("서울은 대한민국의 수도입니다.", "서울은 대한민국의 수도입니다.")
        assertTrue(korean is AnswerTextSelectionDecision.Eligible)

        val mixed = "Acme는 ProductX2를 2026년에 출시했다."
        assertTrue(capture(mixed, mixed) is AnswerTextSelectionDecision.Eligible)
    }

    @Test fun `bold and italic spans map to their displayed sentence`() {
        val bold = "**$fact**"
        assertTrue(capture(bold, fact) is AnswerTextSelectionDecision.Eligible)

        val italic = "*$fact*"
        assertTrue(capture(italic, fact) is AnswerTextSelectionDecision.Eligible)
    }

    @Test fun `heading and list item handling uses parsed visible blocks`() {
        val heading = "# $fact"
        assertEquals(
            AnswerTextSelectionRefusal.STRUCTURAL_CONTENT,
            refusal(capture(heading, fact)),
        )

        val list = "- $fact"
        assertTrue(capture(list, fact) is AnswerTextSelectionDecision.Eligible)
    }

    @Test fun `repeated identical passages and cross block selections are refused`() {
        val repeated = "$fact\n\n$fact"
        assertEquals(AnswerTextSelectionRefusal.AMBIGUOUS, refusal(capture(repeated, fact)))

        val two = "Acme released ProductX2 in 2026.\n\nBeta launched ProductY3 in 2025."
        val message = message(two)
        val decision = AnswerTextSelectionPolicy.capture(
            conversationId = message.conversationId,
            message = message,
            answerSurfaceIdentity = AnswerSurfaceIdentity.of(message.conversationId, message),
            selectedParts = listOf("Acme released ProductX2 in 2026.", "Beta launched ProductY3 in 2025."),
            document = parseMarkdownDocument(two),
            selectionId = "cross-block",
        )
        assertEquals(AnswerTextSelectionRefusal.MULTIPLE_TEXT_NODES, refusal(decision))
    }

    @Test fun `inline and fenced code are never claim text`() {
        val inline = "Acme released `ProductX2` in 2026."
        assertEquals(AnswerTextSelectionRefusal.CODE_CONTENT, refusal(capture(inline, fact)))

        val fenced = "```text\n$fact\n```"
        assertEquals(AnswerTextSelectionRefusal.CODE_CONTENT, refusal(capture(fenced, fact)))
    }

    @Test fun `link labels are visible but destinations are not mapped`() {
        val markdown = "[$fact](https://example.com/productx2)"
        assertTrue(capture(markdown, fact) is AnswerTextSelectionDecision.Eligible)
        assertEquals(
            AnswerTextSelectionRefusal.UNMAPPED,
            refusal(capture(markdown, "https://example.com/productx2")),
        )
    }

    @Test fun `citation insertion is excluded while adjacent sentence stays selectable`() {
        val source = EvidenceSource(
            id = "S1",
            title = "ProductX2 release",
            source = "Example",
            url = "https://example.com/productx2",
            snippet = fact,
            publishedAt = null,
            providerType = "test",
            providerDisplayName = "Test",
            queryUsed = "ProductX2",
        )
        val document = parseMarkdownDocumentWithCitations(
            raw = fact,
            insertions = listOf(MarkdownCitationInsertion(fact.length, listOf(source))),
        )
        assertTrue(capture(fact, fact, document = document) is AnswerTextSelectionDecision.Eligible)
        assertEquals(
            AnswerTextSelectionRefusal.CITATION_CONTENT,
            refusal(capture(fact, "$fact[S1]", document = document)),
        )
    }

    @Test fun `line endings normalize through the real Markdown parser`() {
        val raw = "Intro sentence.\r\n\r\n$fact\r\n"
        assertTrue(capture(raw, fact) is AnswerTextSelectionDecision.Eligible)
    }

    @Test fun `empty oversized and incomplete selections fail closed`() {
        assertEquals(AnswerTextSelectionRefusal.EMPTY, refusal(capture(fact, "")))
        assertEquals(
            AnswerTextSelectionRefusal.TOO_LONG,
            refusal(capture("a".repeat(1_001), "a".repeat(1_001))),
        )
        val atLimit = capture("a".repeat(1_000), "a".repeat(1_000))
        assertFalse(atLimit is AnswerTextSelectionDecision.Unsupported &&
            atLimit.reason == AnswerTextSelectionRefusal.TOO_LONG)
        assertEquals(
            AnswerTextSelectionRefusal.INCOMPLETE_STATEMENT,
            refusal(capture("$fact Extra fragment", "$fact Extra")),
        )
    }

    @Test fun `questions and multiple factual sentences are refused`() {
        val question = "Did Acme release ProductX2 in 2026?"
        assertEquals(AnswerTextSelectionRefusal.NOT_CHECKABLE, refusal(capture(question, question)))
        val two = "$fact Beta launched ProductY3 in 2025."
        assertEquals(AnswerTextSelectionRefusal.INCOMPLETE_STATEMENT, refusal(capture(two, two)))
    }

    @Test fun `hidden metadata cannot be selected through the visible document`() {
        val stored = "$fact\n\uE000FUSION_INTERNAL_REASONING: private chain\uE001"
        val hidden = "private chain"
        assertEquals(
            AnswerTextSelectionRefusal.UNMAPPED,
            refusal(capture(stored, hidden, visible = fact)),
        )
    }

    @Test fun `same displayed text in another response version invalidates snapshot`() {
        val first = message(fact, id = 1L)
        val firstDoc = parseMarkdownDocument(fact)
        val eligible = AnswerTextSelectionPolicy.capture(
            conversationId = first.conversationId,
            message = first,
            answerSurfaceIdentity = AnswerSurfaceIdentity.of(first.conversationId, first),
            selectedParts = listOf(fact),
            document = firstDoc,
            selectionId = "version-one",
        ) as AnswerTextSelectionDecision.Eligible
        val second = message(fact, id = 2L)
        assertNotEquals(
            eligible.snapshot.answerSurfaceIdentity,
            AnswerSurfaceIdentity.of(second.conversationId, second),
        )
        assertFalse(
            AnswerTextSelectionPolicy.isCurrent(
                snapshot = eligible.snapshot,
                currentIdentity = AnswerSurfaceIdentity.of(second.conversationId, second),
                currentRenderedAnswerIdentity = AnswerTextSelectionPolicy.renderedIdentity(
                    AnswerTextSelectionPolicy.renderedBlocks(parseMarkdownDocument(fact)),
                ),
                message = second,
            ),
        )
    }

    @Test fun `multiple text nodes are not concatenated into a guessed sentence`() {
        val decision = AnswerTextSelectionPolicy.capture(
            conversationId = 77L,
            message = message(fact),
            answerSurfaceIdentity = AnswerSurfaceIdentity.of(77L, message(fact)),
            selectedParts = listOf("Acme released ProductX2", "in 2026."),
            document = parseMarkdownDocument(fact),
            selectionId = "two-nodes",
        )
        assertEquals(AnswerTextSelectionRefusal.MULTIPLE_TEXT_NODES, refusal(decision))
    }

    @Test fun `previously unstored selection plans exactly one claim and one transient candidate`() {
        val raw = "$fact\n\nBeta launched ProductY3 in 2025."
        val owner = message(raw)
        val document = parseMarkdownDocument(raw)
        val selected = AnswerTextSelectionPolicy.capture(
            conversationId = owner.conversationId,
            message = owner,
            answerSurfaceIdentity = AnswerSurfaceIdentity.of(owner.conversationId, owner),
            selectedParts = listOf(fact),
            document = document,
            selectionId = "new-unsaved-selection",
        ) as AnswerTextSelectionDecision.Eligible
        val renderedIdentity = AnswerTextSelectionPolicy.renderedIdentity(
            AnswerTextSelectionPolicy.renderedBlocks(document),
        )

        val decision = SelectedAnswerTextVerificationPolicy.plan(
            message = owner,
            identity = EvidenceAnswerIdentity.of(owner),
            snapshot = selected.snapshot,
            currentRenderedAnswerIdentity = renderedIdentity,
        ) as SingleClaimPlanDecision.Eligible
        val plan = decision.plan

        assertEquals(fact, plan.claimText)
        assertEquals(selected.snapshot.claimId, plan.claimId)
        assertEquals(1, plan.sourceOnlyPlan.claims.size)
        assertEquals(listOf(plan.claimId), plan.sourceOnlyPlan.allCandidates.map { it.id })
        assertEquals(fact, plan.sourceOnlyPlan.claims.single().candidate.text)
        assertTrue(plan.offersWebSearch)
        assertEquals(listOf(plan.targetedQuery), plan.webPlan().targetedSearchRequests)
        assertEquals(1, plan.webPlan().claims.size)
        assertEquals(owner.content, raw)
    }

    @Test fun `source candidate attached to exact selected claim suppresses web query`() {
        val source = EvidenceSource(
            id = "S1",
            title = "Acme ProductX2 release 2026",
            source = "Example",
            url = "https://example.com/release",
            snippet = fact,
            publishedAt = null,
            providerType = "test",
            providerDisplayName = "Test",
            queryUsed = "Acme ProductX2 2026",
        )
        val raw = "$fact\n\nBeta launched ProductY3 in 2025."
        val owner = message(raw)
        val document = parseMarkdownDocument(raw)
        val selected = AnswerTextSelectionPolicy.capture(
            conversationId = owner.conversationId,
            message = owner,
            answerSurfaceIdentity = AnswerSurfaceIdentity.of(owner.conversationId, owner),
            selectedParts = listOf(fact),
            document = document,
            selectionId = "source-backed-selection",
        ) as AnswerTextSelectionDecision.Eligible
        val renderedIdentity = AnswerTextSelectionPolicy.renderedIdentity(
            AnswerTextSelectionPolicy.renderedBlocks(document),
        )
        val sourceText = com.projectnuke.fusion.search.WebSearchSource(
            title = source.title,
            source = source.source,
            url = source.url,
            snippet = source.snippet,
            publishedAt = source.publishedAt,
            providerType = com.projectnuke.fusion.search.WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = source.providerDisplayName,
            searchCategory = null,
            queryUsed = source.queryUsed,
            rank = 1,
        )
        val withSource = owner.copy(
            content = com.projectnuke.fusion.search.appendSearchSourcesMetadata(raw, listOf(sourceText)),
        )
        val withSourceSnapshot = selected.snapshot.copy(
            canonicalAnswerHash = EvidenceFingerprint.canonicalAnswerHash(withSource.content),
            answerSurfaceIdentity = AnswerSurfaceIdentity.of(withSource.conversationId, withSource),
            renderedAnswerIdentity = renderedIdentity,
        )
        val plan = (SelectedAnswerTextVerificationPolicy.plan(
            message = withSource,
            identity = EvidenceAnswerIdentity.of(withSource),
            snapshot = withSourceSnapshot,
            currentRenderedAnswerIdentity = renderedIdentity,
        ) as SingleClaimPlanDecision.Eligible).plan

        assertFalse(plan.offersWebSearch)
        assertEquals(1, plan.sourceOnlyPlan.claims.size)
        assertTrue(plan.sourceOnlyPlan.targetedSearchRequests.isEmpty())
    }
}
