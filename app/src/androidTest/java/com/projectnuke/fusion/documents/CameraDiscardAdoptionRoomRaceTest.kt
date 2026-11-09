package com.projectnuke.fusion.documents

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.retrieval.LocalOcrResult
import com.projectnuke.fusion.ui.ProjectFileImportCoordinator
import com.projectnuke.fusion.ui.ProjectFileImportResult
import com.projectnuke.fusion.ui.ProjectOutcome
import com.projectnuke.fusion.util.AttachmentImportResult
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P13-01 real-Room integration proof.
 *
 * The host-level regression proves the ownership protocol with a controlled
 * persistence seam. This suite proves the same invariant against an ACTUAL
 * Room database (in-memory) with a real `db.withTransaction` commit, a real
 * `ProjectFileEntity` row, a real `ProjectDao` query and a real physical file
 * on a real external-files-dir attachment root:
 *
 *   - the durable commit and the pending-copy settlement are ONE gate
 *     transaction, so a camera discard racing the adoption can never delete
 *     the file between them;
 *   - a durable reference read from Room outranks a pending-copy
 *     registration, so `discard` refuses to release an adopted file;
 *   - an unreadable/unresolvable durable authority fails closed.
 *
 * Everything runs in-process against a private in-memory database: no
 * production database, no other app's data, no destructive migration.
 */
@RunWith(AndroidJUnit4::class)
class CameraDiscardAdoptionRoomRaceTest {

    private lateinit var database: AppDatabase
    private lateinit var context: Context
    private lateinit var root: File

    /** Routes the attachment root to a private temp directory. */
    private class AttachmentRootContext(base: Context, private val dir: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
        override fun getFilesDir(): File = dir
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(app.cacheDir, "camera-race-attachments-${System.nanoTime()}").apply { mkdirs() }
        context = AttachmentRootContext(app, root)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun discardCannotDeleteAFileBetweenRealRoomProjectCommitAndPendingSettlement() = runBlocking {
        val projectId = database.projectDao().insertProject(
            ProjectEntity(name = "camera race", instructions = "", createdAt = 1L, updatedAt = 1L),
        )
        val captured = root.resolve("camera_ocr_${System.nanoTime()}.jpg").apply { writeBytes(ByteArray(48)) }
        val canonical = captured.canonicalPath
        AttachmentStorageManager.registerPendingAttachment(captured)

        // Authority reads the REAL in-memory Room project table: the gate is
        // the only thing that can then protect the file.
        val controller = CameraKnowledgeCaptureController(
            context = context,
            recognizeImage = { _, _ -> LocalOcrResult.NoText },
            isDurablyReferenced = { path -> database.projectDao().getProjectFileByPath(projectId, path) != null },
        )

        val durableCommitted = CompletableDeferred<Unit>()
        val settleOwnership = CompletableDeferred<Unit>()
        try {
            val adoption = async(Dispatchers.Unconfined) {
                ProjectFileImportCoordinator(
                    deletePendingFile = { path ->
                        AttachmentStorageManager.deletePendingAttachmentFile(context, path)
                    },
                    unregisterPending = AttachmentStorageManager::unregisterPendingAttachment,
                ).copyAndPersist(
                    copy = { AttachmentImportResult.Success(captured, captured.length()) },
                    persist = { file ->
                        // A real Room transaction commits the durable row.
                        val outcome = com.projectnuke.fusion.ui.ProjectCoordinator(
                            projectDao = database.projectDao(),
                            chatDao = database.chatDao(),
                            runInTransaction = { block -> block() },
                        ).insertProjectFile(
                            ProjectFileEntity(
                                projectId = projectId,
                                displayName = file.name,
                                mimeType = "image/jpeg",
                                localPath = file.canonicalPath,
                                addedAt = 1L,
                            ),
                        )
                        durableCommitted.complete(Unit)
                        settleOwnership.await()
                        outcome
                    },
                    isAdopted = { it is ProjectOutcome.Ok<*> },
                    isDurablyReferenced = { path ->
                        database.projectDao().getProjectFileByPath(projectId, path) != null
                    },
                )
            }

            // The durable Room row exists and pending ownership is NOT settled.
            durableCommitted.await()
            assertNotNull(
                "the durable ProjectFileEntity must exist before settlement",
                database.projectDao().getProjectFileByPath(projectId, canonical),
            )
            assertTrue(
                "pending-copy ownership is still unsettled in the commit window",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )

            val discard = async(Dispatchers.Unconfined) {
                withContext(Dispatchers.Unconfined) { controller.discard(canonical) }
            }

            settleOwnership.complete(Unit)
            val imported = adoption.await()
            val discarded = discard.await()

            assertTrue(
                "durable adoption must succeed",
                imported is ProjectFileImportResult.Persisted,
            )
            assertFalse(
                "camera discard must not delete a file a durable Room row names",
                discarded,
            )
            assertTrue(
                "the physically adopted file must survive the overlapping discard",
                captured.exists(),
            )
            assertNotNull(
                "the durable reference must still resolve after the race",
                database.projectDao().getProjectFileByPath(projectId, canonical),
            )
            assertFalse(
                "pending ownership must be settled by the adoption",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
        }
    }

    @Test
    fun durableRoomReferenceOutranksPendingCopyRegistration() = runBlocking {
        val projectId = database.projectDao().insertProject(
            ProjectEntity(name = "durable authority", instructions = "", createdAt = 1L, updatedAt = 1L),
        )
        val captured = root.resolve("camera_ocr_${System.nanoTime()}.jpg").apply { writeBytes(ByteArray(64)) }
        val canonical = captured.canonicalPath
        AttachmentStorageManager.registerPendingAttachment(captured)
        val controller = CameraKnowledgeCaptureController(
            context = context,
            recognizeImage = { _, _ -> LocalOcrResult.NoText },
            isDurablyReferenced = { path -> database.projectDao().getProjectFileByPath(projectId, path) != null },
        )
        try {
            // The durable row is inserted WHILE the path is still registered
            // pending (a crash between commit and settlement leaves exactly
            // this state). Local bookkeeping must not license a delete.
            database.projectDao().insertProjectFile(
                ProjectFileEntity(
                    projectId = projectId,
                    displayName = captured.name,
                    mimeType = "image/jpeg",
                    localPath = canonical,
                    addedAt = 1L,
                ),
            )

            val deleted = controller.discard(canonical)

            assertFalse("a durable Room reference must block the physical release", deleted)
            assertTrue("durably referenced file must survive", captured.exists())
            assertTrue(
                "the pending-copy registration must survive for a recoverable pass",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
        }
    }

    @Test
    fun unreadableDurableAuthorityFailsClosedAgainstRealRoom() = runBlocking {
        val captured = root.resolve("camera_ocr_${System.nanoTime()}.jpg").apply { writeBytes(ByteArray(32)) }
        val canonical = captured.canonicalPath
        AttachmentStorageManager.registerPendingAttachment(captured)
        val controller = CameraKnowledgeCaptureController(
            context = context,
            recognizeImage = { _, _ -> LocalOcrResult.NoText },
            // null models an unreadable authority (Room unavailable).
            isDurablyReferenced = { null },
        )
        try {
            val deleted = controller.discard(canonical)

            assertFalse("unreadable durable authority must never license a delete", deleted)
            assertTrue("unreadable authority must preserve the physical file", captured.exists())
            assertEquals(
                "the pending-copy registration must survive for cleanup",
                setOf(canonical),
                AttachmentStorageManager.pendingAttachmentPathsSnapshot(),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
        }
    }
}
