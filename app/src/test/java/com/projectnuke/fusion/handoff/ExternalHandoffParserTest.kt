package com.projectnuke.fusion.handoff

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// v2 unified precedence: text semantics preserved exactly, document streams
// take the document path, SEND_MULTIPLE is document-only.
class ExternalHandoffParserTest {

    private var counter = 0
    private fun idFactory() = { "evt-${++counter}" }

    @Test
    fun `process text uses existing text path unchanged`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_PROCESS_TEXT,
            mimeType = "text/plain",
            shareTextExtra = null,
            processTextExtra = "선택된 텍스트",
            singleStreamUri = "content://x/doc.txt",
            multipleStreamUris = listOf("content://x/a.txt"),
            newEventId = idFactory(),
        )
        assertTrue(event is ExternalTextHandoff)
        assertEquals("선택된 텍스트", (event as ExternalTextHandoff).text)
    }

    @Test
    fun `send with stream uses document path`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            shareTextExtra = "본문",
            processTextExtra = null,
            singleStreamUri = "content://provider/notes.txt",
            multipleStreamUris = null,
            newEventId = idFactory(),
        )
        assertTrue(event is ExternalDocumentHandoff)
        assertEquals(listOf("content://provider/notes.txt"), (event as ExternalDocumentHandoff).uriStrings)
    }

    @Test
    fun `send text with no stream still uses existing text path`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            shareTextExtra = "공유할 문단 내용",
            processTextExtra = null,
            singleStreamUri = null,
            multipleStreamUris = null,
            newEventId = idFactory(),
        )
        assertTrue(event is ExternalTextHandoff)
        assertEquals("공유할 문단 내용", (event as ExternalTextHandoff).text)
    }

    @Test
    fun `send multiple uses document handoff only`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "text/plain",
            shareTextExtra = "본문",
            processTextExtra = null,
            singleStreamUri = null,
            multipleStreamUris = listOf("content://x/a.txt", "content://x/b.md"),
            newEventId = idFactory(),
        )
        assertTrue(event is ExternalDocumentHandoff)
        assertEquals(2, (event as ExternalDocumentHandoff).uriStrings.size)
    }

    @Test
    fun `send multiple with no streams is rejected`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "text/plain",
            shareTextExtra = "본문",
            processTextExtra = null,
            singleStreamUri = null,
            multipleStreamUris = null,
            newEventId = idFactory(),
        )
        assertNull(event)
    }

    @Test
    fun `file uri in a single send is rejected at the scheme boundary`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            shareTextExtra = null,
            processTextExtra = null,
            singleStreamUri = "file:///storage/emulated/0/sensitive.txt",
            multipleStreamUris = null,
            newEventId = idFactory(),
        )
        // Bounded rejection: never a content-less fallback, never a fake success.
        assertNull(event)
    }

    @Test
    fun `non content schemes in send multiple are dropped without fake success`() {
        val first = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "text/plain",
            shareTextExtra = null,
            processTextExtra = null,
            singleStreamUri = null,
            multipleStreamUris = listOf("content://x/good.txt", "file:///etc/passwd"),
            newEventId = idFactory(),
        )
        assertTrue(first is ExternalDocumentHandoff)
        assertEquals(
            listOf("content://x/good.txt"),
            (first as ExternalDocumentHandoff).uriStrings,
        )

        val allRejected = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND_MULTIPLE,
            mimeType = "text/plain",
            shareTextExtra = null,
            processTextExtra = null,
            singleStreamUri = null,
            multipleStreamUris = listOf("file:///etc/passwd", "ftp://x/y.txt"),
            newEventId = idFactory(),
        )
        assertNull(allRejected)
    }

    @Test
    fun `send with file stream and extra text never falls back to the text path`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            shareTextExtra = "끼워 넣을 본문",
            processTextExtra = null,
            singleStreamUri = "file:///storage/emulated/0/sensitive.txt",
            multipleStreamUris = null,
            newEventId = idFactory(),
        )
        assertNull("a rejected stream must not silently become text", event)
    }

    @Test
    fun `identical uri list with different ids are separate events`() {
        val first = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            shareTextExtra = null,
            processTextExtra = null,
            singleStreamUri = "content://x/same.txt",
            multipleStreamUris = null,
            newEventId = { "id-1" },
        ) as ExternalDocumentHandoff
        val second = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            shareTextExtra = null,
            processTextExtra = null,
            singleStreamUri = "content://x/same.txt",
            multipleStreamUris = null,
            newEventId = { "id-2" },
        ) as ExternalDocumentHandoff
        assertEquals(first.uriStrings, second.uriStrings)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `unsupported single stream mime is rejected`() {
        val event = ExternalHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "image/png",
            shareTextExtra = null,
            processTextExtra = null,
            singleStreamUri = "content://x/photo.png",
            multipleStreamUris = null,
            displayNameHint = "photo.png",
            newEventId = idFactory(),
        )
        assertNull(event)
    }

    @Test
    fun `document event serializes safely to cold recreated representation`() {
        // URI persistence is safe string identity + metadata, never streams.
        val original = ExternalDocumentHandoff(
            id = "doc-1",
            uriStrings = listOf("content://x/a.txt", "content://x/b.md"),
            declaredMimeType = "text/plain",
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        )
        val serializedUris = ArrayList(original.uriStrings)
        val restored = ExternalDocumentHandoff(
            id = "doc-1",
            uriStrings = serializedUris.distinct(),
            declaredMimeType = "text/plain",
            source = ExternalDocumentHandoffSource.valueOf(original.source.name),
        )
        assertEquals(original, restored)
        assertNotNull(restored.id)
        assertTrue(restored.uriStrings.all { it.isNotBlank() })
    }
}
