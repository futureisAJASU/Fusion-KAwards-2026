package com.projectnuke.fusion.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H21 boundary round-trip: a maximum legal packet (256 knowledge including
 * one 20,000-char note, plus conversations and payload-less files) imports
 * successfully and re-exports untouched with categories preserved.
 * A genuinely over-cap mutation still fails truthfully.
 * NOT RUN on host (Room); compile-gated here.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotRoundTripBoundaryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun maxPacket(): ProjectSnapshot {
        val bigNote = "x".repeat(20_000)
        return ProjectSnapshot(
            name = "Boundary",
            instructions = "",
            createdAt = 1,
            exportedAt = 2,
            conversations = listOf(
                ProjectSnapshotConversation(11L, "C1", "summary one", 3),
                ProjectSnapshotConversation(12L, "C2", "summary two", 4),
            ),
            knowledge = buildList {
                add(
                    ProjectSnapshotKnowledge(
                        "Big note", bigNote, "USER_NOTE", null, null, 5, 6,
                    ),
                )
                repeat(255) { i ->
                    add(
                        ProjectSnapshotKnowledge(
                            "K$i", "body $i", "USER_NOTE", null, null, 5, 6,
                        ),
                    )
                }
            },
            tasks = listOf(
                com.projectnuke.fusion.projects.ProjectSnapshotTask("T", "note", "OPEN", null, null, 5, 6),
            ),
            reviewCards = emptyList(),
            files = listOf(
                ProjectSnapshotFile("a.txt", "text/plain", 7, 10, null, null),
                ProjectSnapshotFile("b.pdf", "application/pdf", 8, 20, null, null),
            ),
        )
    }

    @Test
    fun maxLegalPacket_importUntouchedReexport_succeeds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = ProjectSnapshotRepository(context, database)
        val encoded = ProjectSnapshotArchiveCodec.encode(maxPacket(), emptyMap())
        val imported = repository.importSnapshot(ByteArrayInputStream(encoded))
        assertTrue("max packet must import: $imported", imported is ProjectSnapshotImportResult.Imported)
        val projectId = (imported as ProjectSnapshotImportResult.Imported).projectId

        val reexported = repository.exportProject(
            projectId = projectId,
            authorizedConversationIds = emptySet(),
            authorizedConversations = emptyList(),
            includeSourceFiles = false,
        )
        assertTrue("untouched import must re-export: $reexported", reexported is ProjectSnapshotExportResult.Ready)
        val roundTripped = ProjectSnapshotArchiveCodec.read(
            ByteArrayInputStream((reexported as ProjectSnapshotExportResult.Ready).file.readBytes()),
        ).snapshot
        assertEquals(256, roundTripped.knowledge.size)
        assertEquals(20_000, roundTripped.knowledge.single { it.title == "Big note" }.text.length)
        assertTrue(roundTripped.knowledge.none {
            it.provenanceType == SNAPSHOT_PROVENANCE_SUMMARY ||
                it.provenanceType == SNAPSHOT_PROVENANCE_FILE_REFERENCE
        })
        assertEquals(setOf("C1", "C2"), roundTripped.conversations.map { it.title }.toSet())
        assertEquals(
            setOf("a.txt", "b.pdf"),
            roundTripped.files.map { it.displayName }.toSet(),
        )
        assertTrue(roundTripped.files.all { it.payloadIndex == null })
        assertEquals(1, roundTripped.tasks.size)

        // Genuinely over-cap user mutation still fails truthfully.
        database.savedKnowledgeDao().insert(
            com.projectnuke.fusion.data.SavedKnowledgeEntity(
                id = "user-extra", scope = "PROJECT", projectId = projectId,
                title = "Extra", savedText = "extra", provenanceType = "USER_NOTE",
                sourceConversationId = null, sourceMessageId = null, createdAt = 9, updatedAt = 10,
            ),
        )
        val overCap = repository.exportProject(
            projectId = projectId,
            authorizedConversationIds = emptySet(),
            authorizedConversations = emptyList(),
            includeSourceFiles = false,
        )
        assertTrue("257 genuine knowledge rows must stay over-cap: $overCap", overCap is ProjectSnapshotExportResult.SnapshotTooLarge)
    }
}
