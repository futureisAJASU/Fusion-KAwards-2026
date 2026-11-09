package com.projectnuke.fusion.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.ui.ConversationSummaryMemory
import com.projectnuke.fusion.ui.deleteConversationSummary
import com.projectnuke.fusion.ui.saveConversationSummary
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

@RunWith(AndroidJUnit4::class)
class ProjectSnapshotRepositoryTest {
    @Test
    fun nearMaximumSnapshotRoundTripsOnDeviceWithManyNotesCardsAndPayloads(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val nonce = System.nanoTime()
        val firstSource = File(AttachmentStorageManager.getAttachmentDirectory(context), "snapshot-peak-a-$nonce.txt")
        val secondSource = File(AttachmentStorageManager.getAttachmentDirectory(context), "snapshot-peak-b-$nonce.txt")
        val exports = mutableListOf<File>()
        var importedProjectId: Long? = null
        val sampling = AtomicBoolean(false)
        val peakHeapBytes = AtomicLong(0L)
        val runtime = Runtime.getRuntime()
        fun usedHeapBytes() = runtime.totalMemory() - runtime.freeMemory()
        val sampler = Thread {
            while (sampling.get()) {
                peakHeapBytes.accumulateAndGet(usedHeapBytes()) { old, current -> maxOf(old, current) }
                try { Thread.sleep(5L) } catch (_: InterruptedException) { return@Thread }
            }
        }
        try {
            val firstBytes = 20L * 1024L * 1024L
            val secondBytes = 11L * 1024L * 1024L
            writeDeterministicPayload(firstSource, firstBytes, 17L)
            writeDeterministicPayload(secondSource, secondBytes, 29L)
            val projectId = database.projectDao().insertProject(
                ProjectEntity(name = "Near Maximum Snapshot", createdAt = 1L, updatedAt = 2L),
            )
            database.projectDao().insertProjectFile(
                ProjectFileEntity(projectId = projectId, displayName = "large-a.txt", mimeType = "text/plain", localPath = firstSource.canonicalPath, addedAt = 3L),
            )
            database.projectDao().insertProjectFile(
                ProjectFileEntity(projectId = projectId, displayName = "large-b.txt", mimeType = "text/plain", localPath = secondSource.canonicalPath, addedAt = 4L),
            )
            repeat(64) { index ->
                database.savedKnowledgeDao().insert(
                    SavedKnowledgeEntity(
                        id = "snapshot-note-$nonce-$index", scope = "PROJECT", projectId = projectId,
                        title = "Note $index", savedText = "note-${index}-" + "n".repeat(2_000),
                        provenanceType = "USER_TEXT", sourceConversationId = null, sourceMessageId = null,
                        createdAt = 5L + index, updatedAt = 5L + index,
                    ),
                )
            }
            repeat(128) { index ->
                database.studyReviewCardDao().insert(
                    StudyReviewCardEntity(
                        id = "snapshot-card-$nonce-$index", scope = "PROJECT", projectId = projectId,
                        front = "front-$index-" + "f".repeat(180), back = "back-$index-" + "b".repeat(500),
                        sourceKind = ReviewCardOrigin.USER_TEXT.name, sourceConversationId = null,
                        sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                        sourceProjectFileId = null, reviewCount = 0, lastRating = null, dueAt = 10L,
                        lastReviewedAt = null, createdAt = 5L + index, updatedAt = 5L + index,
                    ),
                )
            }

            val repository = ProjectSnapshotRepository(context, database)
            sampling.set(true)
            peakHeapBytes.set(usedHeapBytes())
            sampler.start()
            val exported = repository.exportProject(
                projectId = projectId,
                authorizedConversationIds = emptySet(),
                authorizedConversations = emptyList(),
                includeSourceFiles = true,
            )
            assertTrue("near-maximum export should succeed: $exported", exported is ProjectSnapshotExportResult.Ready)
            val archiveFile = (exported as ProjectSnapshotExportResult.Ready).file
            exports += archiveFile
            assertTrue("archive remains inside the input byte limit", archiveFile.length() <= ProjectSnapshotBounds.MAX_ARCHIVE_INPUT_BYTES)

            val imported = repository.importSnapshot(FileInputStream(archiveFile))
            assertTrue("near-maximum import should succeed: $imported", imported is ProjectSnapshotImportResult.Imported)
            val result = imported as ProjectSnapshotImportResult.Imported
            importedProjectId = result.projectId
            assertEquals(64, result.savedItems)
            assertEquals(128, result.cardCount)
            assertEquals(2, result.fileCount)
            val restoredFiles = database.projectDao().getProjectFiles(result.projectId)
            assertEquals(listOf(firstBytes, secondBytes), restoredFiles.map { File(it.localPath).length() })
            Log.i(
                "ProjectSnapshotPeakHeap",
                "near-max round trip peak=${peakHeapBytes.get()} bytes max=${runtime.maxMemory()} bytes archive=${archiveFile.length()} bytes",
            )
            Unit
        } finally {
            sampling.set(false)
            if (sampler.state != Thread.State.NEW) sampler.join(5_000L)
            importedProjectId?.let { id ->
                database.projectDao().getProjectFiles(id).forEach { File(it.localPath).delete() }
            }
            firstSource.delete()
            secondSource.delete()
            exports.forEach(File::delete)
            database.close()
        }
    }

    @Test
    fun projectSnapshotRoundTripImportsLogicalItemsAndManagedFileAtomically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val sourceBytes = "project source bytes".toByteArray()
        val sourceFile = File(AttachmentStorageManager.getAttachmentDirectory(context), "snapshot-source-${System.nanoTime()}.txt")
        sourceFile.writeBytes(sourceBytes)
        var importedFile: File? = null
        var summaryConversationId: Long? = null
        val exportFiles = mutableListOf<File>()
        try {
            val projectId = database.projectDao().insertProject(
                ProjectEntity(name = "Portable Project", instructions = "Keep local", createdAt = 10L, updatedAt = 20L),
            )
            val conversationId = database.chatDao().insertConversation(
                ConversationEntity(title = "Research notes", createdAt = 11L, updatedAt = 21L),
            )
            summaryConversationId = conversationId
            saveConversationSummary(context, conversationId, "Brief summary")
            database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectId, 12L))
            val messageId = database.chatDao().insertMessage(
                com.projectnuke.fusion.data.MessageEntity(
                    conversationId = conversationId, role = "assistant", content = "Visible saved answer", createdAt = 13L,
                ),
            )
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = "saved-source", scope = "PROJECT", projectId = projectId,
                    title = "Saved answer", savedText = "Visible saved answer", provenanceType = "ASSISTANT_ANSWER",
                    sourceConversationId = conversationId, sourceMessageId = messageId, createdAt = 14L, updatedAt = 15L,
                ),
            )
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = "saved-web-clip", scope = "PROJECT", projectId = projectId,
                    title = "Captured paper", savedText = "Frozen page body", provenanceType = "WEB_CLIP",
                    sourceConversationId = null, sourceMessageId = null, createdAt = 23L, updatedAt = 24L,
                    sourceUrl = "https://example.com/paper", sourcePageTitle = "Example paper", retrievedAt = 22L,
                ),
            )
            database.projectTaskDao().insert(
                ProjectTaskEntity(
                    projectId = projectId, title = "Check the result", note = "One follow-up", state = "OPEN",
                    sourceConversationId = conversationId, sourceMessageId = messageId, createdAt = 16L, updatedAt = 17L,
                    reminderAt = 1234L, reminderEnabled = true,
                ),
            )
            database.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "review-source", scope = "PROJECT", projectId = projectId, front = "Question", back = "Answer",
                    sourceKind = ReviewCardOrigin.ASSISTANT_ANSWER.name, sourceConversationId = conversationId,
                    sourceMessageId = messageId, sourceSavedKnowledgeId = null, sourceProjectId = null,
                    sourceProjectFileId = null, reviewCount = 2, lastRating = "HARD", dueAt = 30L,
                    lastReviewedAt = 25L, createdAt = 18L, updatedAt = 26L, reminderAt = 1200L, reminderEnabled = true,
                ),
            )
            database.projectDao().insertProjectFile(
                ProjectFileEntity(
                    projectId = projectId, displayName = "source.txt", mimeType = "text/plain",
                    localPath = sourceFile.canonicalPath, addedAt = 19L,
                ),
            )

            val repository = ProjectSnapshotRepository(context, database)
            val exported = repository.exportProject(
                projectId = projectId,
                authorizedConversationIds = setOf(conversationId),
                authorizedConversations = listOf(ConversationSummaryMemory(conversationId, "Brief summary", 22L)),
                includeSourceFiles = true,
            )
            assertTrue("snapshot export should succeed: $exported", exported is ProjectSnapshotExportResult.Ready)
            val fullArchive = (exported as ProjectSnapshotExportResult.Ready).file
            exportFiles += fullArchive
            val encoded = fullArchive.readBytes()
            val imported = repository.importSnapshot(ByteArrayInputStream(encoded))
            assertTrue("snapshot import should succeed: $imported", imported is ProjectSnapshotImportResult.Imported)
            val outcome = imported as ProjectSnapshotImportResult.Imported
            assertEquals("Portable Project (가져옴)", outcome.name)

            val newProject = database.projectDao().getProjectById(outcome.projectId)
            assertNotNull(newProject)
            assertEquals("Keep local", newProject?.instructions)
            val newKnowledge = database.savedKnowledgeDao().getProjectItemsBounded(outcome.projectId, 10)
            assertEquals(3, newKnowledge.size)
            assertTrue(newKnowledge.all { it.sourceConversationId == null && it.sourceMessageId == null })
            val restoredClip = newKnowledge.single { it.sourceUrl == "https://example.com/paper" }
            assertEquals("Example paper", restoredClip.sourcePageTitle)
            assertEquals(22L, restoredClip.retrievedAt)
            val newTasks = database.projectTaskDao().getForProjectBounded(outcome.projectId, 10)
            assertEquals(1, newTasks.size)
            assertNull(newTasks.single().sourceConversationId)
            assertNull(newTasks.single().reminderAt)
            assertFalse(newTasks.single().reminderEnabled)
            val newCards = database.studyReviewCardDao().getProjectCardsBounded(outcome.projectId, 10)
            assertEquals(1, newCards.size)
            assertEquals(ReviewCardOrigin.SNAPSHOT_IMPORT.name, newCards.single().sourceKind)
            assertNull(newCards.single().sourceConversationId)
            assertNull(newCards.single().reminderAt)
            assertFalse(newCards.single().reminderEnabled)
            val newFile = database.projectDao().getProjectFiles(outcome.projectId).single()
            importedFile = File(newFile.localPath)
            assertTrue(File(newFile.localPath).isFile)
            assertArrayEquals(sourceBytes, File(newFile.localPath).readBytes())
            assertEquals(ReviewCardOrigin.SNAPSHOT_IMPORT.name, newCards.single().sourceKind)

            val lightweight = repository.exportProject(
                projectId = projectId,
                authorizedConversationIds = setOf(conversationId),
                authorizedConversations = listOf(ConversationSummaryMemory(conversationId, "Brief summary", 22L)),
                includeSourceFiles = false,
            )
            assertTrue("light export should succeed: $lightweight", lightweight is ProjectSnapshotExportResult.Ready)
            val lightweightFile = (lightweight as ProjectSnapshotExportResult.Ready).file
            exportFiles += lightweightFile
            val lightweightImport = repository.importSnapshot(ByteArrayInputStream(lightweightFile.readBytes()))
            assertTrue("light import should succeed: $lightweightImport", lightweightImport is ProjectSnapshotImportResult.Imported)
            val lightweightProjectId = (lightweightImport as ProjectSnapshotImportResult.Imported).projectId
            assertTrue(database.projectDao().getProjectFiles(lightweightProjectId).isEmpty())
            val lightweightKnowledge = database.savedKnowledgeDao().getProjectItemsBounded(lightweightProjectId, 10)
            assertTrue(lightweightKnowledge.any { it.provenanceType == "PORTABLE_SNAPSHOT_FILE_REFERENCE" })
        } finally {
            sourceFile.delete()
            importedFile?.delete()
            summaryConversationId?.let { deleteConversationSummary(context, it) }
            exportFiles.forEach(File::delete)
            database.close()
        }
    }

    @Test
    fun exportProjectFailsWhenConversationArchivedBeforeReadWithoutArchiveAuth() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var summaryConversationId: Long? = null
        try {
            val projectId = database.projectDao().insertProject(
                ProjectEntity(name = "Archive Test", createdAt = 1L, updatedAt = 1L),
            )
            val conversationId = database.chatDao().insertConversation(
                ConversationEntity(title = "Active", createdAt = 2L, updatedAt = 2L, isArchived = false),
            )
            summaryConversationId = conversationId
            saveConversationSummary(context, conversationId, "summary")
            database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectId, 3L))
            val repository = ProjectSnapshotRepository(context, database)
            val activeExport = repository.exportProject(
                projectId = projectId,
                authorizedConversationIds = setOf(conversationId),
                authorizedConversations = listOf(ConversationSummaryMemory(conversationId, "summary", 2L)),
                includeSourceFiles = false,
                archivedContentAuthorized = false,
            )
            assertTrue("active export succeeds before archive", activeExport is ProjectSnapshotExportResult.Ready)

            // Archive before authoritative read
            database.chatDao().setConversationArchived(conversationId, true)
            val archivedExport = repository.exportProject(
                projectId = projectId,
                authorizedConversationIds = setOf(conversationId),
                authorizedConversations = listOf(ConversationSummaryMemory(conversationId, "summary", 2L)),
                includeSourceFiles = false,
                archivedContentAuthorized = false,
            )
            assertTrue("archived without auth must fail", archivedExport is ProjectSnapshotExportResult.UnauthorizedConversation)

            // With explicit archive authorization, behavior matches contract
            val authExport = repository.exportProject(
                projectId = projectId,
                authorizedConversationIds = setOf(conversationId),
                authorizedConversations = listOf(ConversationSummaryMemory(conversationId, "summary", 2L)),
                includeSourceFiles = false,
                archivedContentAuthorized = true,
            )
            assertTrue("archived with auth must succeed", authExport is ProjectSnapshotExportResult.Ready)
        } finally {
            summaryConversationId?.let { deleteConversationSummary(context, it) }
            database.close()
        }
    }

    private fun writeDeterministicPayload(file: File, size: Long, seed: Long) {
        val random = Random(seed)
        val buffer = ByteArray(64 * 1024)
        FileOutputStream(file).use { output ->
            var remaining = size
            while (remaining > 0L) {
                random.nextBytes(buffer)
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
    }
}
