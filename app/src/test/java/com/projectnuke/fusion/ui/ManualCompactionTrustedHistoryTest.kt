package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.util.AttachmentRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManualCompactionTrustedHistoryTest {
    @Test
    fun `manual compact input uses visible assistant answer and user attachment body`() {
        val root = Files.createTempDirectory("manual_compact_history").toFile()
        val attachment = File(root, "managed.txt").apply { writeText("managed") }
        val path = attachment.canonicalPath.replace("\\", "/")
        val record = AttachmentRecord("managed.txt", "text/plain", path)
        val user = com.projectnuke.fusion.util.AttachmentMessageCodec
            .serializeAttachmentMessage(listOf(record), "hello")
        val assistant = "<fusion_thinking>SECRET_INTERNAL_REASONING</fusion_thinking>" +
            "<fusion_metrics>secret=1</fusion_metrics>" +
            "<fusion_answer>visible answer</fusion_answer>"

        val sanitized = sanitizeTrustedHistory(
            listOf(
                ChatMessage("user", user),
                ChatMessage("assistant", assistant),
            ),
            root,
        )

        assertEquals("hello", sanitized[0].content.trim())
        assertEquals("visible answer", sanitized[1].content)
        assertFalse(sanitized.joinToString { it.content }.contains("SECRET_INTERNAL_REASONING"))
        assertFalse(sanitized.joinToString { it.content }.contains("fusion_metrics"))
        assertFalse(sanitized.joinToString { it.content }.contains(path))
        root.deleteRecursively()
    }

    @Test
    fun `trust route marker is excluded from model-visible assistant history`() {
        val root = Files.createTempDirectory("trust_route_history").toFile()
        val stored = TrustRoutingMetadataCodec.append(
            "visible answer",
            TrustRoutingDecision(
                route = TrustRoute.SEARCH,
                reason = TrustRoutingReason.EXPLICIT_WEB_REQUEST,
                signals = emptySet(),
            ),
        )

        val sanitized = sanitizeTrustedHistory(
            listOf(ChatMessage("assistant", stored)),
            root,
        )

        assertEquals("visible answer", sanitized.single().content)
        assertFalse(sanitized.single().content.contains("FUSION_TRUST_ROUTE_JSON"))
        root.deleteRecursively()
    }

}
