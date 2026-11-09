package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.DynamicFollowUpOwnershipLease
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.chat.ProjectFileSnapshot
import com.projectnuke.fusion.chat.ProjectSourceScope
import com.projectnuke.fusion.chat.ProjectSourceScopeKind
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.llm.BackgroundTerminationSource
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 cross-system adversarial tests: conversation-state identity across
 * retry / regenerate / conversation switch, plus late-completion ownership.
 */
class Phase4RetryIdentityTest {

    private fun snapshot(
        requestId: String = "req-1",
        conversationId: Long = 7L,
        rawUserText: String = "current query",
        historyIds: List<Long> = listOf(9L),
        attachments: List<String> = emptyList(),
        projectContext: ProjectContextSnapshot? = null,
        sourceScope: ProjectSourceScope = ProjectSourceScope(),
        interactionMode: ChatInteractionMode = ChatInteractionMode.NORMAL,
    ): GenerationRequestSnapshot = GenerationRequestSnapshot(
        requestId = requestId,
        conversationId = conversationId,
        generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
        selectedModelId = "m",
        selectedModelPath = "/models/a",
        selectedModelStableId = "stable-a",
        modelFamily = ModelFamily.KANANA,
        settings = GenerationSettings(),
        reasoningEnabled = true,
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        attachmentIds = attachments,
        multimodalImagePaths = emptyList(),
        promptText = rawUserText,
        rawUserText = rawUserText,
        createdAt = 1L,
        history = listOf(ChatMessage("user", "older")),
        historyMessageIds = historyIds,
        trustRoutingDecision = TrustRoutingDecision(
            TrustRoute.SEARCH,
            TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
            emptySet(),
        ),
        externalMaxOutputTokens = 512,
        projectContext = projectContext,
        sourceScope = sourceScope,
        interactionMode = interactionMode,
        searchStrategy = SearchStrategySnapshot.DEFAULT,
    )

    private fun userMessage(id: Long, content: String) =
        MessageEntity(id, 7L, "user", content, 2L)

    // ── §3 / §14 frozen retry scope ────────────────────────────────────────

    @Test
    fun `scope excludes messages appended after the frozen turn`() {
        val root = Files.createTempDirectory("fusion-phase4-retry").toFile()
        try {
            val doc = File(root, "doc.txt").apply { writeText("managed doc") }
            val userContent = AttachmentMessageCodec.serializeAttachmentMessage(
                attachments = listOf(
                    AttachmentRecord(name = "doc.txt", mimeType = "text/plain", localPath = doc.canonicalPath),
                ),
                body = "current query",
            )
            val frozen = FrozenSearchRetry.from(
                snapshot(
                    rawUserText = "current query",
                    attachments = listOf(doc.canonicalPath),
                    historyIds = listOf(9L),
                ),
                100L,
            )
            val history = listOf(
                userMessage(9L, "older"),
                userMessage(11L, userContent),
                MessageEntity(12L, 7L, "assistant", "search failed notice", 3L),
                // A later turn the frozen request never saw.
                userMessage(13L, "a newer question"),
                MessageEntity(14L, 7L, "assistant", "a newer answer", 4L),
            )
            val scoped = resolveFrozenRetryScope(history, frozen, 12L)
            assertEquals(listOf(9L, 11L, 12L), scoped?.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scope refuses when the origin attachment set drifted`() {
        val root = Files.createTempDirectory("fusion-phase4-retry").toFile()
        try {
            val doc = File(root, "doc.txt").apply { writeText("managed doc") }
            val extra = File(root, "extra.txt").apply { writeText("extra") }
            val userContent = AttachmentMessageCodec.serializeAttachmentMessage(
                attachments = listOf(
                    AttachmentRecord(name = "doc.txt", mimeType = "text/plain", localPath = doc.canonicalPath),
                    AttachmentRecord(name = "extra.txt", mimeType = "text/plain", localPath = extra.canonicalPath),
                ),
                body = "current query",
            )
            val frozen = FrozenSearchRetry.from(
                snapshot(
                    rawUserText = "current query",
                    attachments = listOf(doc.canonicalPath),
                ),
                100L,
            )
            val history = listOf(userMessage(11L, userContent), MessageEntity(12L, 7L, "assistant", "notice", 3L))
            assertNull(resolveFrozenRetryScope(history, frozen, 12L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scope accepts identical attachment identities in any order`() {
        val root = Files.createTempDirectory("fusion-phase4-retry").toFile()
        try {
            val first = File(root, "first.txt").apply { writeText("first") }
            val second = File(root, "second.txt").apply { writeText("second") }
            val userContent = AttachmentMessageCodec.serializeAttachmentMessage(
                attachments = listOf(
                    AttachmentRecord(name = "second.txt", mimeType = "text/plain", localPath = second.canonicalPath),
                    AttachmentRecord(name = "first.txt", mimeType = "text/plain", localPath = first.canonicalPath),
                ),
                body = "current query",
            )
            val frozen = FrozenSearchRetry.from(
                snapshot(
                    rawUserText = "current query",
                    attachments = listOf(first.canonicalPath, second.canonicalPath),
                    historyIds = emptyList(),
                ),
                100L,
            )
            val history = listOf(userMessage(11L, userContent), MessageEntity(12L, 7L, "assistant", "notice", 3L))
            assertEquals(listOf(11L, 12L), resolveFrozenRetryScope(history, frozen, 12L)?.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scope refuses when the origin is not the nearest preceding user turn`() {
        val frozen = FrozenSearchRetry.from(snapshot(rawUserText = "current query"), 100L)
        val history = listOf(
            userMessage(10L, "current query"),
            userMessage(11L, "a different later question"),
            MessageEntity(12L, 7L, "assistant", "notice", 3L),
        )
        assertNull(resolveFrozenRetryScope(history, frozen, 12L))
    }

    @Test
    fun `scope refuses a target that lives in another conversation`() {
        val frozen = FrozenSearchRetry.from(snapshot(conversationId = 7L), 100L)
        val otherConversation = listOf(
            MessageEntity(9L, 8L, "user", "older", 1L),
            MessageEntity(11L, 8L, "user", "current query", 2L),
            MessageEntity(12L, 8L, "assistant", "notice", 3L),
        )
        // The frozen history ids resolve, but the target/origin must belong to
        // the frozen conversation's own branch.
        assertNull(resolveFrozenRetryScope(otherConversation, frozen, 12L))
    }

    // ── §14 parity of the frozen retry capture ─────────────────────────────

    @Test
    fun `frozen retry capture reuses the frozen project scope and mode instead of current UI`() {
        val project = ProjectContextSnapshot(
            projectId = 3L,
            instructions = "Project three",
            files = listOf(ProjectFileSnapshot(9L, "c.txt", "text/plain", "/managed/c.txt")),
        )
        val scope = ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, projectId = 3L, fileIds = listOf(9L))
        val frozen = FrozenSearchRetry.from(
            snapshot(
                rawUserText = "current query",
                projectContext = project,
                sourceScope = scope,
                interactionMode = ChatInteractionMode.STUDY,
            ),
            100L,
        )
        val capture = buildFrozenSearchRetryCapture(frozen, 12L, ResponseVersionState())
        assertEquals(7L, capture.conversationId)
        assertEquals(12L, capture.targetMessageId)
        assertEquals(scope.frozen(), capture.sourceScope)
        assertEquals(project, capture.projectContext)
        assertEquals(ChatInteractionMode.STUDY, capture.interactionMode)
        assertEquals(ChatGenerationMode.LOCAL_MODEL, capture.generationMode)
        assertEquals(frozen.trustRoutingDecision, capture.frozenTrustRoutingDecision)
        assertTrue(capture.webSearchEnabled)
    }

    @Test
    fun `regeneration snapshot keeps the frozen conversation identity`() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 12L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "m",
            selectedModelPath = "/models/a",
            selectedModelStableId = "stable-a",
            modelFamily = ModelFamily.KANANA,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            externalProviderRequest = null,
            projectContext = null,
            sourceScope = ProjectSourceScope(ProjectSourceScopeKind.PROJECT, projectId = 3L),
            frozenTrustRoutingDecision = TrustRoutingDecision.local(
                TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
                emptySet(),
            ),
        )
        val request = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "retry-1",
            settings = GenerationSettings(),
            reasoningEnabled = true,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.AUTO,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "current query",
            rawUserText = "current query",
            createdAt = 1L,
            history = listOf(ChatMessage("user", "older")),
            historyMessageIds = listOf(9L),
        )
        assertEquals(7L, request.conversationId)
        assertEquals("retry-1", request.requestId)
        // A frozen LOCAL routing decision never keeps AUTO search enabled.
        assertEquals(
            GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            request.webSearchPolicy,
        )
        assertEquals(capture.sourceScope.frozen(), request.sourceScope)
        assertEquals(capture.frozenTrustRoutingDecision, request.trustRoutingDecision)
    }

    @Test
    fun `frozen search routing decision keeps search enabled on retry`() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 12L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "m",
            selectedModelPath = "/models/a",
            selectedModelStableId = "stable-a",
            modelFamily = ModelFamily.KANANA,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            externalProviderRequest = null,
            projectContext = null,
            sourceScope = ProjectSourceScope(),
            frozenTrustRoutingDecision = TrustRoutingDecision(
                TrustRoute.SEARCH,
                TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
                emptySet(),
            ),
        )
        val request = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "retry-2",
            settings = GenerationSettings(),
            reasoningEnabled = true,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.AUTO,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "current query",
            rawUserText = "current query",
            createdAt = 1L,
            history = listOf(ChatMessage("user", "older")),
            historyMessageIds = listOf(9L),
        )
        assertEquals(GenerationRequestSnapshot.WebSearchPolicy.AUTO, request.webSearchPolicy)
    }

    // ── §25 cancellation / late completion ownership ───────────────────────

    @Test
    fun `retired follow-up lease refuses publication after the owner moves on`() {
        val lease = DynamicFollowUpOwnershipLease()
        assertTrue(lease.isCurrent)
        var notified = 0
        lease.observeRetirement { notified++ }
        lease.invalidate(BackgroundTerminationSource.CONVERSATION_SWITCH)
        assertFalse(lease.isCurrent)
        assertEquals(1, notified)
        // A second invalidation is idempotent and never notifies twice.
        lease.invalidate(BackgroundTerminationSource.OWNER_DISPOSED)
        assertEquals(1, notified)
        assertFalse(lease.isCurrent)
    }

    @Test
    fun `late completion for a superseded request cannot reclaim the lease`() {
        val first = DynamicFollowUpOwnershipLease()
        val second = DynamicFollowUpOwnershipLease()
        assertNotSame(first, second)
        first.invalidate(BackgroundTerminationSource.OWNERSHIP_STALE)
        assertFalse(first.isCurrent)
        assertTrue(second.isCurrent)
    }

    private fun assertNotSame(first: Any, second: Any) {
        assertTrue(first !== second)
    }
}
