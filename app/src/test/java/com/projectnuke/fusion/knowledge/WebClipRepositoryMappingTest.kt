package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.inbox.InboxCapturePolicy
import com.projectnuke.fusion.inbox.InboxPreparation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebClipRepositoryMappingTest {
    @Test
    fun `inbox and saved knowledge mappings preserve entered and effective origins with fetched text`() {
        val document = WebClipDocument(
            originalUrl = "https://entered.example/article",
            pageTitle = "Content title",
            displayTitle = "Display title",
            text = "Body from redirected origin",
            textTruncated = false,
            retrievedAt = 10,
            effectiveUrl = "https://content.example/final",
        )

        val inbox = InboxCapturePolicy.prepare(document.toInboxCapture(), 10, "clip-10") as InboxPreparation.Ready
        assertEquals(document.text, inbox.item.content)
        assertEquals(document.originalUrl, inbox.item.originalUri)
        assertEquals(document.effectiveUrl, inbox.item.effectiveSourceUri)

        val saved = document.toSavedKnowledgeEntity("saved-10", "PERSONAL", null, 11)
        assertEquals(document.text, saved.savedText)
        assertEquals(document.originalUrl, saved.sourceUrl)
        assertEquals(document.effectiveUrl, saved.effectiveSourceUrl)
    }

    @Test
    fun `legacy clip mappings retain unknown effective origin`() {
        val document = WebClipDocument(
            originalUrl = "https://entered.example/legacy",
            pageTitle = null,
            displayTitle = "Legacy page",
            text = "Legacy saved text",
            textTruncated = false,
            retrievedAt = 1,
        )

        val inbox = InboxCapturePolicy.prepare(document.toInboxCapture(), 1, "legacy-1") as InboxPreparation.Ready
        val saved = document.toSavedKnowledgeEntity("saved-legacy", "PERSONAL", null, 2)
        assertNull(inbox.item.effectiveSourceUri)
        assertNull(saved.effectiveSourceUrl)
    }
}
