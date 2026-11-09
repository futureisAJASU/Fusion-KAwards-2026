package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.search.buildSafeWebSearchPriorTurns
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Retry/regeneration carries the frozen safe search-context belonging to
// its request; it is never re-derived from mutable UI history afterwards.
class RegenerationSafeContextPassthroughTest {

    private fun capture() = RegenerationRequestCapture(
        conversationId = 1L,
        targetMessageId = 9L,
        responseVersionState = ResponseVersionState(),
        generationMode = ChatGenerationMode.LOCAL_MODEL,
        selectedModel = "model",
        selectedModelPath = null,
        selectedModelStableId = null,
        modelFamily = null,
        localProfile = null,
        generationSettings = GenerationSettings(),
        reasoningEnabled = false,
        webSearchEnabled = true,
        promptLabInstruction = "",
        externalProviderId = null,
        externalMaxOutputTokens = null,
    )

    @Test
    fun `regeneration snapshot preserves the frozen safe context`() {
        val frozen = listOf("SK하이닉스 HBM4E 수율은 어떻게 돼?", "삼성 HBM4E 수율 최신 정보 알려줘")
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture(),
            requestId = "retry-1",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "p",
            rawUserText = "그럼 최근 자료 더 찾아줘",
            createdAt = 1L,
            history = emptyList(),
            historyMessageIds = emptyList(),
            safeWebSearchPriorUserTurns = frozen,
            trustRoutingDecision = TrustRoutingDecision(
                TrustRoute.SEARCH,
                TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
                emptySet(),
            ),
        )
        assertEquals(frozen, snapshot.safeWebSearchPriorUserTurns)
    }

    @Test
    fun `regeneration snapshot defaults to empty safe context`() {
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture(),
            requestId = "retry-2",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "p",
            rawUserText = "u",
            createdAt = 1L,
            history = emptyList(),
            historyMessageIds = emptyList(),
            trustRoutingDecision = TrustRoutingDecision(
                TrustRoute.LOCAL,
                TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE,
                emptySet(),
            ),
        )
        assertEquals(emptyList<String>(), snapshot.safeWebSearchPriorUserTurns)
    }

    @Test
    fun `regeneration context stops before paired user and filters attachment turns`() {
        val attachmentTurn = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("private.pdf", "application/pdf", "/tmp/private.pdf")),
            "private attachment question",
        )
        val history = listOf(
            MessageEntity(1L, 1L, "user", "safe earlier question", 1L),
            MessageEntity(2L, 1L, "assistant", "earlier answer", 2L),
            MessageEntity(3L, 1L, "user", attachmentTurn, 3L),
            MessageEntity(4L, 1L, "assistant", "attachment-derived answer", 4L),
            MessageEntity(5L, 1L, "user", "regenerated user question", 5L),
            MessageEntity(6L, 1L, "assistant", "target answer", 6L),
            MessageEntity(7L, 1L, "user", "later user question", 7L),
            MessageEntity(8L, 1L, "assistant", "later answer", 8L),
        )
        val versions = ResponseVersionState(
            groupByMessageId = mapOf(2L to 1L, 4L to 3L, 6L to 5L, 8L to 7L),
        )

        // This is the production regeneration path's branch selection and
        // truncation sequence: resolve the active timeline, bind the target
        // assistant to its user, then use only messages before that user.
        val active = activeTimelineMessages(history, versions)
        val targetIndex = active.indexOfFirst { it.id == 6L }
        val previousUserId = versions.groupByMessageId[6L]
        val previousUserIndex = active.indexOfFirst { it.id == previousUserId && it.role == "user" }
        val regenerationHistory = active.take(previousUserIndex)
        val priorTurns = buildSafeWebSearchPriorTurns(
            regenerationHistory.asReversed().filter { it.role == "user" }.map { it.content },
        )

        assertTrue(targetIndex > previousUserIndex)
        assertEquals(listOf("safe earlier question"), priorTurns)
        assertFalse(priorTurns.any { it.contains("private attachment question") })
        assertFalse(priorTurns.any { it.contains("regenerated user question") })
        assertFalse(priorTurns.any { it.contains("later user question") })
    }
}
