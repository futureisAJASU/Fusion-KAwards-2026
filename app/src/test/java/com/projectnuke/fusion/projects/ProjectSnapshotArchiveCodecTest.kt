package com.projectnuke.fusion.projects

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSnapshotArchiveCodecTest {
    @Test
    fun `archive round trips project data and selected source payload`() {
        val fileBytes = "bounded source".toByteArray()
        val snapshot = sampleSnapshot(fileBytes)
        val encoded = ProjectSnapshotArchiveCodec.encode(snapshot, mapOf(0 to fileBytes))
        val archive = ProjectSnapshotArchiveCodec.read(ByteArrayInputStream(encoded))

        assertEquals(snapshot, archive.snapshot)
        assertTrue(fileBytes.contentEquals(archive.payloads.getValue(0)))
        val manifest = readEntry(encoded, "manifest.json").toString(StandardCharsets.UTF_8)
        assertFalse(manifest.contains("C:\\Users\\"))
        assertFalse(manifest.contains("/data/user/"))
    }

    @Test
    fun `archive rejects traversal member names`() {
        val archive = rawZip("../manifest.json" to "{}".toByteArray())
        assertRejected(archive)
    }

    @Test
    fun `archive round trips web clip provenance and reads version one snapshots`() {
        val clipSnapshot = sampleSnapshot().copy(
            knowledge = listOf(
                ProjectSnapshotKnowledge(
                    title = "Example paper",
                    text = "Frozen body",
                    provenanceType = "WEB_CLIP",
                    sourceConversationId = null,
                    sourceMessageId = null,
                    createdAt = 12,
                    updatedAt = 13,
                    sourceUrl = "https://example.com/paper",
                    sourcePageTitle = "Example paper",
                    retrievedAt = 11,
                ),
            ),
        )
        val roundTrip = ProjectSnapshotArchiveCodec.read(
            ByteArrayInputStream(ProjectSnapshotArchiveCodec.encode(clipSnapshot, emptyMap())),
        )
        assertEquals(clipSnapshot, roundTrip.snapshot)

        val legacyManifest = """{"schemaVersion":1,"name":"Old project","instructions":"","createdAt":1,"exportedAt":2,"conversations":[],"knowledge":[{"title":"Saved","text":"Text","provenanceType":"ASSISTANT_ANSWER","sourceConversationId":null,"sourceMessageId":null,"createdAt":1,"updatedAt":2}],"tasks":[],"reviewCards":[],"files":[]}"""
        val legacy = ProjectSnapshotArchiveCodec.read(
            ByteArrayInputStream(rawZip("manifest.json" to legacyManifest.toByteArray(StandardCharsets.UTF_8))),
        )
        assertEquals(null, legacy.snapshot.knowledge.single().sourceUrl)
        assertEquals(null, legacy.snapshot.knowledge.single().retrievedAt)
    }

    @Test
    fun `archive rejects malformed utf8 and unsupported schema`() {
        assertRejected(rawZip("manifest.json" to byteArrayOf(0xC3.toByte(), 0x28)))

        val valid = ProjectSnapshotArchiveCodec.encode(sampleSnapshot(), emptyMap())
        val originalManifest = readEntry(valid, "manifest.json").toString(StandardCharsets.UTF_8)
        assertTrue(originalManifest.contains("\"schemaVersion\":3"))
        val manifest = originalManifest.replace("\"schemaVersion\":3", "\"schemaVersion\":4")
            .toByteArray(StandardCharsets.UTF_8)
        assertRejected(rawZip("manifest.json" to manifest))

        assertRejected(rawZip("manifest.json" to originalManifest
            .replace("\"createdAt\":10", "\"createdAt\":10.0")
            .toByteArray(StandardCharsets.UTF_8)))
        assertRejected(rawZip("manifest.json" to originalManifest
            .replace("\"sourceId\":4", "\"sourceId\":9223372036854775808")
            .toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `archive rejects payload hash tampering and oversized members`() {
        val originalPayload = byteArrayOf(4, 8, 15, 16, 23, 42)
        val valid = ProjectSnapshotArchiveCodec.encode(sampleSnapshot(originalPayload), mapOf(0 to originalPayload))
        val manifest = readEntry(valid, "manifest.json")
        val tampered = rawZip("manifest.json" to manifest, "sources/000.bin" to byteArrayOf(1, 2, 3))
        assertRejected(tampered)

        val oversizedSource = ByteArray((ProjectSnapshotBounds.MAX_SOURCE_FILE_BYTES + 1).toInt())
        assertRejected(rawZip("sources/000.bin" to oversizedSource))
    }

    @Test
    fun `import collision policies keep project and file names unique`() {
        assertEquals("Notes (가져옴)", ProjectSnapshotImportPolicy.resolveAvailableName("Notes", listOf("notes")))
        assertEquals(
            listOf("scan.pdf", "SCAN (2).PDF", "scan (3).pdf"),
            ProjectSnapshotImportPolicy.uniqueFileNames(listOf("scan.pdf", "SCAN.PDF", "scan.pdf")),
        )
    }

    private fun sampleSnapshot(fileBytes: ByteArray? = null): ProjectSnapshot = ProjectSnapshot(
        name = "Portable notes",
        instructions = "Use local Project context.",
        createdAt = 10L,
        exportedAt = 20L,
        conversations = listOf(ProjectSnapshotConversation(4L, "Chat", "A bounded summary.", 18L)),
        knowledge = listOf(ProjectSnapshotKnowledge("Saved", "Visible text", "ASSISTANT_ANSWER", 4L, 8L, 12L, 14L)),
        tasks = listOf(ProjectSnapshotTask("Review", "Read the note", "OPEN", 4L, 8L, 13L, 15L)),
        reviewCards = listOf(ProjectSnapshotReviewCard("Front", "Back", "USER_TEXT", 1, "HARD", 30L, 20L, 11L, 21L)),
        files = if (fileBytes == null) emptyList() else listOf(
            ProjectSnapshotFile("note.txt", "text/plain", 16L, fileBytes.size.toLong(), sha256(fileBytes), 0),
        ),
    )

    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun readEntry(bytes: ByteArray, name: String): ByteArray = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: error("missing $name")
            if (entry.name == name) return@use zip.readBytes()
            zip.closeEntry()
        }
        error("missing $name")
    }

    private fun assertRejected(bytes: ByteArray) {
        var rejected = false
        try {
            ProjectSnapshotArchiveCodec.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("archive must be rejected", rejected)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
