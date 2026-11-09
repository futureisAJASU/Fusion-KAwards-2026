package com.projectnuke.fusion.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F11-03 / F11-04: external share admission and saved-state bounding.
 *
 * F11-03 -- a ContentProvider is not required to encode the real document
 * filename in its URI, so the final URI segment must not be treated as a
 * filename. `content://provider/document/12345` carrying a genuine PDF used to
 * be rejected because the opaque segment `12345` plus a generic MIME looked
 * positively unsupported, before ContentResolver metadata could be read.
 *
 * F11-04 -- an external sender may supply far more documents than the import
 * admits. Only a bounded subset may reach Android saved state; the true
 * distinct count is kept separately so restored reporting stays truthful.
 */
class ExternalDocumentHandoffStateEnvelopeTest {

    @Test
    fun `opaque numeric document uri with generic mime is deferred not rejected`() {
        val event = ExternalDocumentHandoffParser.parseSingle(
            singleUriString = "content://example.provider/document/12345",
            declaredMimeType = "application/octet-stream",
            displayNameHint = null,
            newEventId = { "evt-opaque" },
        )

        // The real DISPLAY_NAME is only available from ContentResolver, so the
        // event must survive to import time.
        assertEquals("evt-opaque", event?.id)
        assertEquals(listOf("content://example.provider/document/12345"), event?.uriStrings)
    }

    @Test
    fun `random opaque token and query parameters are deferred`() {
        listOf(
            "content://provider/document/aB3xZ9",
            "content://provider/document/12345?foo=bar",
            "content://provider/tree/primary%3Adocument%2Fmsf%3A42",
        ).forEach { uri ->
            val event = ExternalDocumentHandoffParser.parseSingle(
                singleUriString = uri,
                declaredMimeType = "application/octet-stream",
                displayNameHint = null,
                newEventId = { "evt" },
            )
            assertEquals("opaque uri must be deferred: $uri", "evt", event?.id)
        }
    }

    @Test
    fun `missing mime with opaque uri is deferred`() {
        val event = ExternalDocumentHandoffParser.parseSingle(
            singleUriString = "content://provider/document/12345",
            declaredMimeType = null,
            displayNameHint = null,
            newEventId = { "evt-nomime" },
        )

        assertEquals("evt-nomime", event?.id)
    }

    @Test
    fun `supported document with a real filename still admitted`() {
        val event = ExternalDocumentHandoffParser.parseSingle(
            singleUriString = "content://provider/document/research.pdf",
            declaredMimeType = "application/octet-stream",
            displayNameHint = null,
            newEventId = { "evt-pdf" },
        )

        assertEquals("evt-pdf", event?.id)
    }

    @Test
    fun `explicit unsupported media is still rejected`() {
        assertEquals(
            null,
            ExternalDocumentHandoffParser.parseSingle(
                singleUriString = "content://provider/document/12345",
                declaredMimeType = "image/png",
                displayNameHint = null,
                newEventId = { "evt-img" },
            ),
        )
        assertEquals(
            null,
            ExternalDocumentHandoffParser.parseSingle(
                singleUriString = "content://provider/document/clip.mp4",
                declaredMimeType = "video/mp4",
                displayNameHint = null,
                newEventId = { "evt-vid" },
            ),
        )
        assertEquals(
            null,
            ExternalDocumentHandoffParser.parseSingle(
                singleUriString = "content://provider/document/deck.pptx",
                declaredMimeType = "application/vnd.ms-powerpoint",
                displayNameHint = null,
                newEventId = { "evt-ppt" },
            ),
        )
    }

    @Test
    fun `non content scheme is rejected regardless of name`() {
        assertEquals(
            null,
            ExternalDocumentHandoffParser.parseSingle(
                singleUriString = "file:///sdcard/notes.txt",
                declaredMimeType = "text/plain",
                displayNameHint = "notes.txt",
                newEventId = { "evt-file" },
            ),
        )
    }
    @Test
    fun `opaque multi-stream batch is not rejected as wholly unsupported`() {
        val event = ExternalDocumentHandoffParser.parseMultiple(
            uriStrings = listOf(
                "content://p/document/1",
                "content://p/document/2",
                "content://p/document/3",
            ),
            declaredMimeType = "application/octet-stream",
            newEventId = { "evt-multi" },
        )

        assertEquals("evt-multi", event?.id)
        assertEquals(3, event?.uriStrings?.size)
    }

    @Test
    fun `multi-stream batch with every entry explicitly unsupported is rejected`() {
        val event = ExternalDocumentHandoffParser.parseMultiple(
            uriStrings = listOf("content://p/a.png", "content://p/b.png"),
            declaredMimeType = null,
            displayNameByUri = mapOf(
                "content://p/a.png" to "a.png",
                "content://p/b.png" to "b.png",
            ),
            mimeByUri = mapOf(
                "content://p/a.png" to "image/png",
                "content://p/b.png" to "image/png",
            ),
            newEventId = { "evt-allbad" },
        )

        assertEquals(null, event)
    }

    @Test
    fun `multi-stream batch with mixed valid and opaque entries is admitted`() {
        val event = ExternalDocumentHandoffParser.parseMultiple(
            uriStrings = listOf("content://p/a.png", "content://p/document/9", "content://p/notes.txt"),
            declaredMimeType = null,
            displayNameByUri = mapOf("content://p/a.png" to "a.png"),
            mimeByUri = mapOf("content://p/a.png" to "image/png"),
            newEventId = { "evt-mixed" },
        )

        // One entry is explicitly unsupported but the batch is not wholly so, so
        // per-file truth is decided at import time.
        assertEquals("evt-mixed", event?.id)
    }

    @Test
    fun `bounded persistence keeps only the permitted uri subset`() {
        val oversized = ExternalDocumentHandoff(
            id = "evt-flood",
            uriStrings = (1..200).map { "content://p/document/$it" },
            declaredMimeType = "application/octet-stream",
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
            totalDistinctUriCount = 200,
        )

        val bounded = ExternalDocumentHandoff.bounded(oversized)

        assertEquals(ExternalDocumentHandoff.MAX_PERSISTED_URIS, bounded.uriStrings.size)
        assertEquals(
            "the true distinct count must survive so reporting stays truthful",
            200,
            bounded.totalDistinctUriCount,
        )
    }

    @Test
    fun `bounded persistence drops oversized individual uri strings`() {
        val huge = "content://p/document/" + "x".repeat(50_000)
        val event = ExternalDocumentHandoff(
            id = "evt-huge",
            uriStrings = listOf(huge, "content://p/document/ok"),
            declaredMimeType = null,
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        )

        val bounded = ExternalDocumentHandoff.bounded(event)

        assertTrue(
            "an unbounded URI must never be persisted",
            bounded.uriStrings.none { it.length > 2048 },
        )
        assertEquals(listOf("content://p/document/ok"), bounded.uriStrings)
    }

    @Test
    fun `bounded persistence deduplicates and ignores blanks`() {
        val event = ExternalDocumentHandoff(
            id = "evt-dup",
            uriStrings = listOf("content://p/1", "", "  ", "content://p/1", "content://p/2"),
            declaredMimeType = null,
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        )

        val bounded = ExternalDocumentHandoff.bounded(event)

        assertEquals(listOf("content://p/1", "content://p/2"), bounded.uriStrings)
        assertEquals(5, bounded.totalDistinctUriCount)
    }

    @Test
    fun `restore tolerates a malformed or oversized saved bundle`() {
        val restored = ExternalDocumentHandoff.restore(
            id = "evt-restore",
            uriStrings = (1..500).map { "content://p/document/$it" } + listOf("", "  "),
            declaredMimeType = "application/octet-stream",
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
            totalDistinctUriCount = null,
        )

        assertEquals(ExternalDocumentHandoff.MAX_PERSISTED_URIS, restored.uriStrings.size)
        assertEquals(
            "a missing count must fall back to the retained subset, never understate it",
            ExternalDocumentHandoff.MAX_PERSISTED_URIS,
            restored.totalDistinctUriCount,
        )
    }

    @Test
    fun `restore never understates the retained uri count`() {
        val restored = ExternalDocumentHandoff.restore(
            id = "evt-corrupt",
            uriStrings = listOf("content://p/1", "content://p/2", "content://p/3"),
            declaredMimeType = null,
            source = ExternalDocumentHandoffSource.SHARE_STREAM,
            totalDistinctUriCount = -99,
        )

        assertEquals(3, restored.uriStrings.size)
        assertTrue(restored.totalDistinctUriCount >= restored.uriStrings.size)
    }

    @Test
    fun `restore keeps an existing valid saved state compatible`() {
        // Bundles written before the count existed carry no total; they must
        // restore exactly as before.
        val restored = ExternalDocumentHandoff.restore(
            id = "evt-legacy",
            uriStrings = listOf("content://p/a.txt", "content://p/b.txt"),
            declaredMimeType = "text/plain",
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
            totalDistinctUriCount = null,
        )

        assertEquals(listOf("content://p/a.txt", "content://p/b.txt"), restored.uriStrings)
        assertEquals(2, restored.totalDistinctUriCount)
        assertEquals("text/plain", restored.declaredMimeType)
    }
}