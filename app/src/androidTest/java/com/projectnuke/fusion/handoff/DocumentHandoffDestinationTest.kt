package com.projectnuke.fusion.handoff

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.util.AttachmentImportBudget
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentHandoffDestinationTest {
    @Test
    fun inboxPartialBatchPersistsTruthfulReceiptAndDoesNotDuplicateOnReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "p7-inbox-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val toasts = mutableListOf<String>()
            val handoff = handoff("inbox-partial", listOf(GOOD, FAILED, UNSUPPORTED, GOOD))
            val coordinator = coordinator(root, database, toasts)

            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, coordinator.handleToInbox(handoff))
            val stored = database.inboxItemDao().getForHandoff(handoff.id, 10)
            assertEquals(1, stored.size)
            assertTrue(File(stored.single().managedFileId!!).isFile)
            assertTrue(toasts.single().contains("문서 3개 중 1개를 Inbox에 저장했어요"))
            assertTrue(toasts.single().contains("지원하지 않는 문서 1개"))
            assertTrue(toasts.single().contains("가져오지 못한 문서 1개"))

            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, coordinator.handleToInbox(handoff))
            assertEquals(1, database.inboxItemDao().getForHandoff(handoff.id, 10).size)
            assertTrue(toasts.last().contains("나머지 2개는 이 공유에서 보관되지 않았어요"))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun projectPartialBatchCannotBeReassignedBySameEventReplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "p7-project-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val firstProject = database.projectDao().insertProject(
                ProjectEntity(name = "first", createdAt = 1, updatedAt = 1),
            )
            val secondProject = database.projectDao().insertProject(
                ProjectEntity(name = "second", createdAt = 1, updatedAt = 1),
            )
            val toasts = mutableListOf<String>()
            val handoff = handoff("project-partial", listOf(GOOD, FAILED, UNSUPPORTED))
            val coordinator = coordinator(root, database, toasts)

            assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, coordinator.handleToProject(handoff, firstProject))
            val stored = database.projectDao().getProjectFilesByHandoffPrefix("${handoff.id}:%", 10)
            assertEquals(1, stored.size)
            assertEquals(firstProject, stored.single().projectId)
            assertTrue(File(stored.single().localPath).isFile)
            assertTrue(toasts.single().contains("문서 3개 중 1개를 Project에 저장했어요"))

            assertEquals(HandoffImportOutcome.FAILED_IMPORT, coordinator.handleToProject(handoff, secondProject))
            val afterReplay = database.projectDao().getProjectFilesByHandoffPrefix("${handoff.id}:%", 10)
            assertEquals(1, afterReplay.size)
            assertEquals(firstProject, afterReplay.single().projectId)
            assertFalse(afterReplay.any { it.projectId == secondProject })
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun handoff(id: String, uris: List<String>) = ExternalDocumentHandoff(
        id = id,
        uriStrings = uris,
        declaredMimeType = null,
        source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
    )

    private fun coordinator(
        root: File,
        database: AppDatabase,
        toasts: MutableList<String>,
    ) = DocumentHandoffCoordinator(
        attachmentRoot = root,
        coordinatorProvider = {
            AttachmentImportCoordinator(
                attachmentRoot = root,
                inputFactory = { source -> CONTENT[source]?.let(::ByteArrayInputStream) },
                budget = AttachmentImportBudget(perFileBytes = 1_000_000, perBatchBytes = 2_000_000, reserveBytes = 0),
            )
        },
        metadataProvider = { uri -> METADATA[uri] },
        deletePendingFile = { path -> path?.let { !File(it).exists() || File(it).delete() } ?: false },
        releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.Released },
        notify = { toasts += it },
        projectSource = RoomProjectFileCommitSource(database),
    )

    private companion object {
        const val GOOD = "content://p7/good.txt"
        const val FAILED = "content://p7/copy-failed.txt"
        const val UNSUPPORTED = "content://p7/unsupported.png"
        val CONTENT = mapOf(GOOD to "문서 내용".toByteArray(Charsets.UTF_8))
        val METADATA = mapOf(
            GOOD to SharedDocumentMetadata("good.txt", "문서 내용".toByteArray(Charsets.UTF_8).size.toLong(), "text/plain"),
            FAILED to SharedDocumentMetadata("copy-failed.txt", null, "text/plain"),
            UNSUPPORTED to SharedDocumentMetadata("unsupported.png", null, "image/png"),
        )
    }
}
