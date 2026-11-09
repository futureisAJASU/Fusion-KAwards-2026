package com.projectnuke.fusion.handoff

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F11-01 on a REAL Room database: an over-capacity project document batch must
 * roll back atomically.
 *
 * The host JVM suite proves the commit-seam contract; this one proves it against
 * real SQLite, where a transaction body that RETURNS normally is COMMITTED. The
 * pre-fix implementation returned false from inside `withTransaction` when an
 * insert was refused, so Room committed the rows inserted before the refusal.
 * The caller then reported a failed import and its cleanup deleted the files
 * those committed rows pointed at.
 */
@RunWith(AndroidJUnit4::class)
class ProjectHandoffBatchRoomRollbackTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var root: File
    private val deletedFiles = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        root = File(context.cacheDir, "p11-room-batch-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun overCapacityBatchRollsBackEveryRowOnRealRoom() = runBlocking {
        val projectId = database.projectDao().insertProject(
            ProjectEntity(name = "p11", createdAt = 1, updatedAt = 1),
        )
        // Occupy seven of the eight durable slots with genuinely owned files.
        repeat(7) { index ->
            val path = File(root, "owned-$index.txt").apply { writeText("owned") }.absolutePath
            database.projectDao().insertProjectFile(
                com.projectnuke.fusion.data.ProjectFileEntity(
                    projectId = projectId,
                    displayName = "owned-$index",
                    mimeType = "text/plain",
                    localPath = path,
                    addedAt = 1,
                ),
            )
        }
        assertEquals(7, database.projectDao().countProjectFiles(projectId))

        val handoff = ExternalDocumentHandoff(
            id = "p11-room-capacity",
            uriStrings = listOf("content://p11/a.txt", "content://p11/b.txt"),
            declaredMimeType = null,
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        )

        val outcome = coordinator(projectId).handleToProject(handoff, projectId)

        assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
        val rows = database.projectDao().getProjectFiles(projectId)
        assertEquals(
            "a refused batch must leave no partial rows behind",
            7,
            rows.size,
        )
        rows.forEach { row ->
            assertTrue(
                "every durable row must still own an existing file: ${row.localPath}",
                File(row.localPath).isFile,
            )
        }
        assertFalse(
            "cleanup must never delete a file a durable row references",
            deletedFiles.any { path -> rows.any { it.localPath == path } },
        )
    }

    @Test
    fun batchThatFitsCommitsEveryRowOnRealRoom() = runBlocking {
        val projectId = database.projectDao().insertProject(
            ProjectEntity(name = "p11-fits", createdAt = 1, updatedAt = 1),
        )
        repeat(5) { index ->
            val path = File(root, "owned-$index.txt").apply { writeText("owned") }.absolutePath
            database.projectDao().insertProjectFile(
                com.projectnuke.fusion.data.ProjectFileEntity(
                    projectId = projectId,
                    displayName = "owned-$index",
                    mimeType = "text/plain",
                    localPath = path,
                    addedAt = 1,
                ),
            )
        }

        val handoff = ExternalDocumentHandoff(
            id = "p11-room-fits",
            uriStrings = listOf("content://p11/a.txt", "content://p11/b.txt", "content://p11/c.txt"),
            declaredMimeType = null,
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        )

        val outcome = coordinator(projectId).handleToProject(handoff, projectId)

        assertEquals(HandoffImportOutcome.IMPORTED, outcome)
        val rows = database.projectDao().getProjectFiles(projectId)
        assertEquals(8, rows.size)
        rows.forEach { row ->
            assertTrue(
                "every committed row must own an existing file: ${row.localPath}",
                File(row.localPath).isFile,
            )
        }
    }

    @Test
    fun replayAfterAFreedSlotImportsInsteadOfAssumingAPriorImport() = runBlocking {
        val projectId = database.projectDao().insertProject(
            ProjectEntity(name = "p11-replay", createdAt = 1, updatedAt = 1),
        )
        repeat(7) { index ->
            val path = File(root, "owned-$index.txt").apply { writeText("owned") }.absolutePath
            database.projectDao().insertProjectFile(
                com.projectnuke.fusion.data.ProjectFileEntity(
                    projectId = projectId,
                    displayName = "owned-$index",
                    mimeType = "text/plain",
                    localPath = path,
                    addedAt = 1,
                ),
            )
        }
        val handoff = ExternalDocumentHandoff(
            id = "p11-room-replay",
            uriStrings = listOf("content://p11/a.txt", "content://p11/b.txt"),
            declaredMimeType = null,
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        )
        val underTest = coordinator(projectId)

        assertEquals(HandoffImportOutcome.FAILED_IMPORT, underTest.handleToProject(handoff, projectId))

        // Free one durable slot, then replay the same event.
        val victim = database.projectDao().getProjectFiles(projectId).first()
        assertEquals(1, database.projectDao().deleteProjectFile(victim.id))

        assertEquals(HandoffImportOutcome.IMPORTED, underTest.handleToProject(handoff, projectId))
        assertEquals(8, database.projectDao().countProjectFiles(projectId))
    }

    private fun coordinator(projectId: Long) = DocumentHandoffCoordinator(
        attachmentRoot = root,
        coordinatorProvider = {
            AttachmentImportCoordinator(
                attachmentRoot = root,
                inputFactory = { source -> CONTENT[source]?.let(::ByteArrayInputStream) },
            )
        },
        metadataProvider = { uri -> METADATA[uri] },
        deletePendingFile = { path ->
            path?.let { File(it).delete().also { done -> if (done) deletedFiles += it } } ?: false
        },
        releaseRemovedDraftPath = { RemovedAttachmentReleaseResult.Released },
        notify = { },
        projectSource = RoomProjectFileCommitSource(database),
    )

    private companion object {
        const val A = "content://p11/a.txt"
        const val B = "content://p11/b.txt"
        const val C = "content://p11/c.txt"
        val CONTENT = mapOf(A to "alpha", B to "beta", C to "gamma").mapValues { (_, v) ->
            v.toByteArray(Charsets.UTF_8)
        }
        val METADATA = mapOf(
            A to SharedDocumentMetadata("a.txt", 5L, "text/plain"),
            B to SharedDocumentMetadata("b.txt", 4L, "text/plain"),
            C to SharedDocumentMetadata("c.txt", 5L, "text/plain"),
        )
    }
}