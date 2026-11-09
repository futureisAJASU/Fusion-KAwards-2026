package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.projects.FrozenProjectDecisionSource
import com.projectnuke.fusion.projects.ProjectDecisionCaptureLiveSource
import com.projectnuke.fusion.projects.ProjectDecisionCapturePolicy
import com.projectnuke.fusion.projects.ProjectDecisionCaptureResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 cross-system adversarial tests: RAG entity binding against adjacent
 * entities, retrieval metamorphic invariants, and durable decision-capture
 * drift rejection.
 */
class Phase4RetrievalBindingTest {

    private fun chunk(docId: String, text: String, chunkIndex: Int = 1) = LocalDocumentChunk(
        docId = docId,
        displayName = "$docId.txt",
        chunkIndex = chunkIndex,
        startOffset = 0,
        endOffset = text.length,
        contentHashHex = "hash-$docId-$chunkIndex",
        text = text,
    )

    // ── §5 adjacent-entity binding ─────────────────────────────────────────

    @Test
    fun `short adjacent entity name must not match a longer product document`() {
        val p1 = chunk("att-b", "P1 price is 100 dollars")
        val p10 = chunk("att-a", "P10 price is 200 dollars")
        val selections = LocalRagRetrieval.retrieve(query = "P1 price", chunks = listOf(p10, p1))

        assertTrue(selections.isNotEmpty())
        // The requested entity's own document must outrank the adjacent
        // longer-named one, regardless of document id ordering.
        assertEquals(
            "att-b",
            selections.first().chunk.docId,
        )
        val scores = selections.map { it.lexicalScore }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `exact longer entity name still binds to its own document`() {
        val p10 = chunk("att-a", "P10 price is 200 dollars")
        val p12 = chunk("att-b", "P12 price is 300 dollars")
        val selections = LocalRagRetrieval.retrieve(query = "P12 price", chunks = listOf(p10, p12))
        assertEquals("att-b", selections.first().chunk.docId)
        assertTrue(selections.indexOfFirst { it.chunk.docId == "att-a" } > 0)
    }

    @Test
    fun `documents for adjacent entities keep distinct provenance`() {
        val alpha = chunk("att-alpha", "Project Alpha budget is 10 million")
        val alpha2 = chunk("att-alpha2", "Project Alpha 2 budget is 20 million")
        val selections = LocalRagRetrieval.retrieve(
            query = "Project Alpha budget",
            chunks = listOf(alpha, alpha2),
            attachmentIdByDoc = mapOf("att-alpha" to "/a.txt", "att-alpha2" to "/a2.txt"),
        )
        val docIds = selections.map { it.chunk.docId }.distinct()
        assertEquals(2, docIds.size)
        selections.forEach { selection ->
            val expected = if (selection.chunk.docId == "att-alpha") "budget is 10 million" else "budget is 20 million"
            assertTrue(selection.source.excerpt.contains(expected))
        }
    }

    // ── §6 metamorphic invariants ──────────────────────────────────────────

    @Test
    fun `permuting retrieved chunks does not change the semantic answer basis`() {
        val chunks = listOf(
            chunk("att-1", "policy rate is 2.50 percent"),
            chunk("att-2", "policy rate is unchanged at 2.50 percent"),
            chunk("att-3", "unrelated budget notes"),
        )
        val forward = LocalRagRetrieval.retrieve("policy rate", chunks)
        val reversed = LocalRagRetrieval.retrieve("policy rate", chunks.reversed())
        assertEquals(
            forward.map { "${it.chunk.docId}#${it.chunk.chunkIndex}" },
            reversed.map { "${it.chunk.docId}#${it.chunk.chunkIndex}" },
        )
    }

    @Test
    fun `duplicated chunk cannot improve or duplicate the selection basis`() {
        val single = chunk("att-1", "policy rate is 2.50 percent")
        val duplicated = LocalRagRetrieval.retrieve(
            query = "policy rate",
            chunks = listOf(single, single.copy(), single.copy()),
        )
        assertEquals(1, duplicated.size)
        val baseline = LocalRagRetrieval.retrieve("policy rate", listOf(single))
        assertEquals(baseline.first().lexicalScore, duplicated.first().lexicalScore)
    }

    @Test
    fun `appending an unrelated document does not suppress correct evidence`() {
        val correct = chunk("att-correct", "policy rate is 2.50 percent")
        val unrelated = (1..5).map { index -> chunk("att-noise$index", "unrelated topic $index content") }
        val selections = LocalRagRetrieval.retrieve("policy rate", listOf(correct) + unrelated)
        assertEquals("att-correct", selections.first().chunk.docId)
        assertEquals(1, selections.count { it.chunk.docId == "att-correct" })
    }

    @Test
    fun `appending an old conflicting chunk cannot displace the newer selected evidence`() {
        val fresh = chunk("att-fresh", "current policy rate is 2.50 percent")
        val stale = chunk("att-stale", "policy rate is 3.00 percent")
        val selections = LocalRagRetrieval.retrieve("current policy rate", listOf(fresh, stale))
        assertEquals("att-fresh", selections.first().chunk.docId)
        // Both may remain visible as evidence, but the requested "current"
        // value must rank first.
        assertTrue(selections.first().lexicalScore >= selections.last().lexicalScore)
    }

    // ── §22 decision capture drift ─────────────────────────────────────────

    private val source = FrozenProjectDecisionSource(
        projectId = 1L,
        conversationId = 2L,
        assistantMessageId = 30L,
        assistantMessageCreatedAt = 100L,
        responseVersionGroupId = 31L,
        responseVersionMessageIds = listOf(30L, 32L),
        answerFingerprint = ProjectDecisionCapturePolicy.fingerprint("the answer text"),
    )

    private fun live(
        answer: String = "the answer text",
        versionIds: List<Long> = listOf(30L, 32L),
        activeAssistantMessageId: Long? = 30L,
        assignedProjectId: Long? = 1L,
        messageConversationId: Long? = 2L,
    ) = ProjectDecisionCaptureLiveSource(
        projectExists = true,
        conversationExists = true,
        conversationArchived = false,
        archiveAccessAllowed = true,
        assignedProjectId = assignedProjectId,
        assistantMessageExists = true,
        messageConversationId = messageConversationId,
        messageRole = "assistant",
        messageCreatedAt = 100L,
        answerFingerprint = ProjectDecisionCapturePolicy.fingerprint(answer),
        responseVersionGroupId = 31L,
        responseVersionMessageIds = versionIds,
        activeAssistantMessageId = activeAssistantMessageId,
    )

    @Test
    fun `intact source passes decision capture validation`() {
        assertNull(ProjectDecisionCapturePolicy.validate(source, live()))
    }

    @Test
    fun `an edited answer invalidates a pending decision capture`() {
        val result = ProjectDecisionCapturePolicy.validate(source, live(answer = "the edited answer text"))
        assertEquals(ProjectDecisionCaptureResult.SourceChanged, result)
    }

    @Test
    fun `a regenerated answer version invalidates a pending decision capture`() {
        val result = ProjectDecisionCapturePolicy.validate(
            source,
            live(versionIds = listOf(30L, 32L, 33L), activeAssistantMessageId = 33L),
        )
        assertEquals(ProjectDecisionCaptureResult.SourceChanged, result)
    }

    @Test
    fun `moving the conversation to another project invalidates the capture`() {
        val result = ProjectDecisionCapturePolicy.validate(source, live(assignedProjectId = 9L))
        assertEquals(ProjectDecisionCaptureResult.SourceChanged, result)
    }

    @Test
    fun `a missing project or conversation fails closed`() {
        val missingProject = live().copy(projectExists = false)
        assertEquals(
            ProjectDecisionCaptureResult.ProjectMissing,
            ProjectDecisionCapturePolicy.validate(source, missingProject),
        )
        val missingConversation = live().copy(conversationExists = false)
        assertEquals(
            ProjectDecisionCaptureResult.SourceUnavailable,
            ProjectDecisionCapturePolicy.validate(source, missingConversation),
        )
        val archived = live().copy(conversationArchived = true, archiveAccessAllowed = false)
        assertEquals(
            ProjectDecisionCaptureResult.ArchiveInaccessible,
            ProjectDecisionCapturePolicy.validate(source, archived),
        )
    }

    @Test
    fun `decision capture controller refuses a second duplicate save`() = runTest {
        var settleCalls = 0
        val gateway = object : com.projectnuke.fusion.projects.ProjectDecisionCaptureGateway {
            override suspend fun settle(
                source: FrozenProjectDecisionSource,
                draft: com.projectnuke.fusion.projects.ProjectDecisionDraft,
            ): com.projectnuke.fusion.projects.ProjectDecisionCaptureResult {
                settleCalls++
                return com.projectnuke.fusion.projects.ProjectDecisionCaptureResult.Created("d-1")
            }
        }
        val controller = com.projectnuke.fusion.projects.ProjectDecisionCaptureController(gateway)
        controller.begin(source, "Use model B", "Reversal of model A")
        controller.selectSupersede("d-old")

        val first = controller.save()
        assertEquals(
            com.projectnuke.fusion.projects.ProjectDecisionCaptureResult.Created("d-1"),
            first,
        )
        val second = controller.save()
        assertEquals(
            com.projectnuke.fusion.projects.ProjectDecisionCaptureResult.AlreadySaved,
            second,
        )
        assertEquals(1, settleCalls)
    }

    @Test
    fun `a reversal supersedes the old decision instead of duplicating it`() = runTest {
        var supersedes: String? = null
        val gateway = object : com.projectnuke.fusion.projects.ProjectDecisionCaptureGateway {
            override suspend fun settle(
                source: FrozenProjectDecisionSource,
                draft: com.projectnuke.fusion.projects.ProjectDecisionDraft,
            ): com.projectnuke.fusion.projects.ProjectDecisionCaptureResult {
                supersedes = draft.supersedesDecisionId
                return com.projectnuke.fusion.projects.ProjectDecisionCaptureResult.Created("d-2")
            }
        }
        val controller = com.projectnuke.fusion.projects.ProjectDecisionCaptureController(gateway)
        controller.begin(source, "Use model B", "No, let's use B")
        controller.selectSupersede("d-old")
        controller.save()
        assertEquals("d-old", supersedes)
    }

    @Test
    fun `stale draft cannot survive a dismissal`() = runTest {
        val gateway = object : com.projectnuke.fusion.projects.ProjectDecisionCaptureGateway {
            override suspend fun settle(
                source: FrozenProjectDecisionSource,
                draft: com.projectnuke.fusion.projects.ProjectDecisionDraft,
            ) = com.projectnuke.fusion.projects.ProjectDecisionCaptureResult.Created("d-3")
        }
        val controller = com.projectnuke.fusion.projects.ProjectDecisionCaptureController(gateway)
        controller.begin(source, "tentative idea", "maybe")
        controller.dismiss()
        assertEquals(
            com.projectnuke.fusion.projects.ProjectDecisionCaptureResult.SourceUnavailable,
            controller.save(),
        )
        assertFalse(controller.saved)
        assertNotNull(controller.supersedesDecisionIdFlow)
    }
}
