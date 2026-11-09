package com.projectnuke.fusion.validation

import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS
import com.projectnuke.fusion.knowledge.SavedKnowledgeNotePolicy
import com.projectnuke.fusion.projects.ProjectSnapshot
import com.projectnuke.fusion.projects.ProjectSnapshotArchiveCodec
import com.projectnuke.fusion.projects.ProjectSnapshotBounds
import com.projectnuke.fusion.projects.ProjectSnapshotConversation
import com.projectnuke.fusion.projects.ProjectSnapshotFile
import com.projectnuke.fusion.projects.ProjectSnapshotKnowledge
import com.projectnuke.fusion.projects.SNAPSHOT_PROVENANCE_FILE_REFERENCE
import com.projectnuke.fusion.projects.SNAPSHOT_PROVENANCE_KNOWLEDGE
import com.projectnuke.fusion.projects.SNAPSHOT_PROVENANCE_SUMMARY
import com.projectnuke.fusion.projects.partitionProjectKnowledge
import com.projectnuke.fusion.projects.reconstructSnapshotConversations
import com.projectnuke.fusion.projects.reconstructSnapshotFiles
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractPhase2Test {

    private fun knowledge(id: Int, textLen: Int, provenance: String = SNAPSHOT_PROVENANCE_KNOWLEDGE) =
        ProjectSnapshotKnowledge(
            title = "K$id", text = "x".repeat(textLen), provenanceType = provenance,
            sourceConversationId = null, sourceMessageId = null,
            createdAt = 1, updatedAt = 2,
        )

    private fun snapshotWithKnowledge(vararg lengths: Int): ProjectSnapshot = ProjectSnapshot(
        name = "Notes", instructions = "", createdAt = 1, exportedAt = 2,
        conversations = emptyList(),
        knowledge = lengths.mapIndexed { i, len -> knowledge(i, len) },
        tasks = emptyList(), reviewCards = emptyList(), files = emptyList(),
    )

    private fun roundTrip(snapshot: ProjectSnapshot): ProjectSnapshot {
        val encoded = ProjectSnapshotArchiveCodec.encode(snapshot, emptyMap())
        return ProjectSnapshotArchiveCodec.read(ByteArrayInputStream(encoded)).snapshot
    }

    @Test
    fun h20_boundDerivesFromNoteDomain() {
        assertEquals(MAX_SAVED_NOTE_BODY_CHARS, ProjectSnapshotBounds.MAX_KNOWLEDGE_TEXT_CHARS)
        assertEquals(20_000, ProjectSnapshotBounds.MAX_KNOWLEDGE_TEXT_CHARS)
    }

    @Test
    fun h20_schemaVersionBumpedWithOldVersionsReadable() {
        assertEquals(3, ProjectSnapshotBounds.SCHEMA_VERSION)
    }

    @Test
    fun h20_noteBoundaries_roundTripWithoutTruncation() {
        listOf(12_000, 12_001, 20_000).forEach { len ->
            val out = roundTrip(snapshotWithKnowledge(len))
            assertEquals(len, out.knowledge.single().text.length)
            assertTrue(out.knowledge.single().text.all { it == 'x' })
        }
    }

    @Test
    fun h20_invalidNoteBody_rejectedBeforeSnapshot() {
        assertNull(SavedKnowledgeNotePolicy.normalize("t", "x".repeat(20_001), emptyList()))
        assertNotNull(SavedKnowledgeNotePolicy.normalize("t", "x".repeat(20_000), emptyList()))
    }

    @Test
    fun h20_overBoundText_rejectedTruthfullyByCodec() {
        var rejected = false
        try {
            ProjectSnapshotArchiveCodec.encode(snapshotWithKnowledge(20_001), emptyMap())
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("20,001-char text must be rejected, never truncated", rejected)
    }

    // ---------- H21 ----------

    private fun row(id: String, provenance: String, title: String = "T", text: String = "body") =
        SavedKnowledgeEntity(id, "PROJECT", 7L, title, text, provenance, null, null, 1, 2)

    @Test
    fun h21_partition_separatesAuxiliaryRowsFromKnowledge() {
        val rows = listOf(
            row("k1", SNAPSHOT_PROVENANCE_KNOWLEDGE),
            row("s1", SNAPSHOT_PROVENANCE_SUMMARY, title = "Chat · 요약", text = "summary"),
            row("f1", SNAPSHOT_PROVENANCE_FILE_REFERENCE, title = "자료 참조 · doc.pdf", text = "원본 자료는 스냅샷에 포함되지 않았습니다. 형식: text/plain, 크기: 10 bytes"),
            row("n1", "USER_NOTE"),
        )
        val part = partitionProjectKnowledge(rows)
        assertEquals(listOf("k1", "n1"), part.knowledge.map { it.id }.sorted())
        assertEquals(listOf("s1"), part.summaries.map { it.id })
        assertEquals(listOf("f1"), part.fileReferences.map { it.id })
    }

    @Test
    fun h21_maxLegalPacket_genuineStaysWithinCapAfterPartition() {
        // 256 knowledge + 32 summaries + 8 file references: the old code saw
        // 296 rows against a 256 cap; category-aware accounting sees 256.
        val rows = buildList {
            repeat(256) { add(row("k$it", SNAPSHOT_PROVENANCE_KNOWLEDGE)) }
            repeat(32) { add(row("s$it", SNAPSHOT_PROVENANCE_SUMMARY, title = "C$it · 요약", text = "sum $it")) }
            repeat(8) {
                add(row("f$it", SNAPSHOT_PROVENANCE_FILE_REFERENCE, title = "자료 참조 · f$it.txt", text = "원본 자료는 스냅샷에 포함되지 않았습니다. 형식: text/plain, 크기: 10 bytes"))
            }
        }
        val part = partitionProjectKnowledge(rows)
        assertEquals(256, part.knowledge.size)
        assertTrue(part.knowledge.size <= ProjectSnapshotBounds.MAX_KNOWLEDGE_ITEMS)
        assertEquals(32, part.summaries.size)
        assertEquals(8, part.fileReferences.size)
    }

    @Test
    fun h21_genuinelyOverCapKnowledge_stillRejected() {
        val rows = buildList {
            repeat(257) { add(row("k$it", SNAPSHOT_PROVENANCE_KNOWLEDGE)) }
        }
        val part = partitionProjectKnowledge(rows)
        assertTrue(part.knowledge.size > ProjectSnapshotBounds.MAX_KNOWLEDGE_ITEMS)
    }

    @Test
    fun h21_reconstructConversations_restoresTitlesWithDistinctIds() {
        val summaries = listOf(
            row("s1", SNAPSHOT_PROVENANCE_SUMMARY, title = "Chat · 요약", text = "sum one"),
            row("s2", SNAPSHOT_PROVENANCE_SUMMARY, title = "이미 요약된 · 요약", text = "sum two"),
        )
        val out = reconstructSnapshotConversations(summaries, setOf(9L))
        assertEquals(listOf("Chat", "이미 요약된"), out.map { it.title })
        assertEquals(listOf("sum one", "sum two"), out.map { it.summary })
        assertEquals(2, out.map { it.sourceId }.toSet().size)
        assertTrue(out.all { it.sourceId > 0L && it.sourceId != 9L })
    }

    @Test
    fun h21_reconstructFiles_parsesReferenceShape() {
        val refs = listOf(
            row("f1", SNAPSHOT_PROVENANCE_FILE_REFERENCE, title = "자료 참조 · doc.pdf", text = "원본 자료는 스냅샷에 포함되지 않았습니다. 형식: application/pdf, 크기: 12345 bytes"),
        )
        val out = reconstructSnapshotFiles(refs)
        assertNotNull(out)
        assertEquals("doc.pdf", out!!.single().displayName)
        assertEquals("application/pdf", out.single().mimeType)
        assertEquals(12345L, out.single().sizeBytes)
    }

    @Test
    fun h21_reconstructFiles_unparseableFailsTruthfully() {
        val refs = listOf(row("f9", SNAPSHOT_PROVENANCE_FILE_REFERENCE, title = "자료 참조 · x", text = "tampered"))
        assertNull(reconstructSnapshotFiles(refs))
    }

    @Test
    fun h21_fullPacketWithMaxCategories_encodesAndDecodes() {
        val snapshot = ProjectSnapshot(
            name = "Max", instructions = "", createdAt = 1, exportedAt = 2,
            conversations = (0 until 32).map { ProjectSnapshotConversation(100L + it, "C$it", "summary $it", 3) },
            knowledge = (0 until 256).map { knowledge(it, 100) },
            tasks = emptyList(), reviewCards = emptyList(),
            files = (0 until 8).map { ProjectSnapshotFile("f$it.txt", "text/plain", 4, 0, null, null) },
        )
        val out = roundTrip(snapshot)
        assertEquals(256, out.knowledge.size)
        assertEquals(32, out.conversations.size)
        assertEquals(8, out.files.size)
        assertEquals("summary 0", out.conversations.first().summary)
    }
}
