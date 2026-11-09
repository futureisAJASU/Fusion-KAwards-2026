package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.data.BoundedProjectFileInsertResult
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileContract
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.InboxItemDao
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.InboxInsertResult
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F11-01: a project document batch that cannot fit the durable capacity limit
 * must not commit a partial subset while reporting a failed import.
 *
 * The Room semantics that made the original code wrong: a transaction body that
 * RETURNS normally is COMMITTED, whatever value it returns. `return false` is
 * NOT a rollback. The original `handleToProject` loop returned false as soon as
 * one insert was refused, which committed every earlier insert in that batch.
 * The caller then read `persisted == false` as a failed import, and its cleanup
 * deleted the files those committed rows point at -- producing durable database
 * rows that reference missing files.
 *
 * [FakeProjectDao] reproduces the durable effects the contract depends on (row
 * set, capacity, unique indices). The production fix routes every mid-batch
 * rejection through [ProjectFileBatchRollback], which the real
 * [RoomProjectFileBatchCommit] converts into a rollback.
 */
class ProjectFileBatchRollbackTest {

    private lateinit var root: File
    private lateinit var dao: FakeProjectDao
    private val deletedFiles = mutableListOf<String>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("fusion-p11-batch").toFile()
        dao = FakeProjectDao()
        dao.projects[PROJECT_ID] = ProjectEntity(id = PROJECT_ID, name = "p", createdAt = 1, updatedAt = 1)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Fills `count` of the eight durable slots with pre-existing owned files. */
    private fun prefill(count: Int) {
        (1..count).forEach { index ->
            val path = File(root, "existing-$index.txt").apply { writeText("old") }.absolutePath
            dao.rows["existing-$index"] = ProjectFileEntity(
                id = index.toLong(), projectId = PROJECT_ID, displayName = "e$index",
                mimeType = "text/plain", localPath = path, addedAt = 1,
            )
        }
    }

    @Test
    fun `over-capacity batch commits no partial rows`() = runTest {
        prefill(7)
        val outcome = coordinator().handleToProject(docEvent("evt-capacity", listOf(A, B)), PROJECT_ID)

        assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
        assertEquals("no partial row may survive a rejected batch", 7, dao.rows.size)
        assertEquals(
            "no durable row may reference a deleted file",
            0,
            dao.rows.values.count { !File(it.localPath).isFile },
        )
    }

    @Test
    fun `batch that exactly fits capacity commits every document`() = runTest {
        prefill(5)
        val outcome = coordinator().handleToProject(docEvent("evt-exact", listOf(A, B, C)), PROJECT_ID)

        assertEquals(HandoffImportOutcome.IMPORTED, outcome)
        assertEquals(8, dao.rows.size)
        dao.rows.values.forEach { row ->
            assertTrue("every durable row must own an existing file", File(row.localPath).isFile)
        }
    }

    @Test
    fun `batch one over capacity commits nothing`() = runTest {
        prefill(6)
        // Six taken plus three documents = 9, which exceeds the eight-file limit.
        val outcome = coordinator().handleToProject(docEvent("evt-one-over", listOf(A, B, C)), PROJECT_ID)

        assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
        assertEquals(6, dao.rows.size)
        dao.rows.values.forEach { row ->
            assertTrue("pre-existing owned files must survive", File(row.localPath).isFile)
        }
    }

    @Test
    fun `concurrent imports near capacity cannot overfill durable project limit`() = runTest {
        prefill(ProjectFileContract.MAX_PROJECT_FILES - 1)
        val underTest = coordinator()

        val first = async { underTest.handleToProject(docEvent("evt-near-capacity-a", listOf(A)), PROJECT_ID) }
        val second = async { underTest.handleToProject(docEvent("evt-near-capacity-b", listOf(B)), PROJECT_ID) }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, outcomes.count { it == HandoffImportOutcome.IMPORTED })
        assertEquals(1, outcomes.count { it == HandoffImportOutcome.FAILED_IMPORT })
        assertEquals(ProjectFileContract.MAX_PROJECT_FILES, dao.rows.size)
        dao.rows.values.forEach { row -> assertTrue(File(row.localPath).isFile) }
    }

    @Test
    fun `failed-batch cleanup never deletes a durably referenced file`() = runTest {
        prefill(7)
        coordinator().handleToProject(docEvent("evt-nodelete", listOf(A, B)), PROJECT_ID)

        // Staging copies for a rejected batch are legitimately disposable, but
        // no file a durable row references may be touched.
        (1..7).forEach { index ->
            assertTrue("existing file $index must survive", File(root, "existing-$index.txt").isFile)
        }
        assertEquals(
            "cleanup must not delete a file a durable row references",
            0,
            deletedFiles.count { path -> dao.rows.values.any { it.localPath == path } },
        )
    }

    @Test
    fun `replay of a rejected batch is not mistaken for a completed prior import`() = runTest {
        prefill(7)
        val handoff = docEvent("evt-replay", listOf(A, B))
        val underTest = coordinator()

        assertEquals(HandoffImportOutcome.FAILED_IMPORT, underTest.handleToProject(handoff, PROJECT_ID))
        // Free a slot, then replay: the event must import rather than being
        // mistaken for an authoritative earlier import.
        dao.rows.remove("existing-7")
        assertEquals(HandoffImportOutcome.IMPORTED, underTest.handleToProject(handoff, PROJECT_ID))
        assertEquals(8, dao.rows.size)
    }

    @Test
    fun `missing project rolls back the whole batch`() = runTest {
        val outcome = coordinator().handleToProject(docEvent("evt-noproject", listOf(A, B)), 999L)

        assertEquals(HandoffImportOutcome.FAILED_IMPORT, outcome)
        assertTrue("no row may be created for an unknown project", dao.rows.isEmpty())
    }

    @Test
    fun `restored twenty document event with five URIs imports partially without invalid counts`() = runTest {
        val handoff = docEvent("evt-restored-subset", listOf(A, B, C, D, E), totalDistinctUriCount = 20)
        val notices = mutableListOf<String>()

        val outcome = coordinator(notices).handleToProject(handoff, PROJECT_ID)

        assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, outcome)
        assertEquals(5, dao.rows.size)
        assertTrue("summary must expose the original total", notices.any { it.contains("20") })
        assertEquals("partial outcomes must not settle as full success", "PARTIALLY_IMPORTED", outcome.receiptValue())
        assertEquals(0, dao.rows.values.count { !File(it.localPath).isFile })
    }

    @Test
    fun `existing import replay of restored subset is partial rather than false full success`() = runTest {
        val eventId = "evt-replay-restored-subset"
        val handoff = docEvent(eventId, listOf(A, B, C, D, E), totalDistinctUriCount = 20)
        val notices = mutableListOf<String>()
        listOf(A, B, C, D, E).forEachIndexed { index, uri ->
            val path = File(root, "existing-$index.txt").apply { writeText(uri) }.absolutePath
            dao.rows["replay-$index"] = ProjectFileEntity(
                id = (index + 1).toLong(), projectId = PROJECT_ID, displayName = "existing-$index",
                mimeType = "text/plain", localPath = path, addedAt = 1,
                sourceHandoffEventId = "$eventId:$index",
            )
        }

        val outcome = coordinator(notices).handleToProject(handoff, PROJECT_ID)

        assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, outcome)
        assertTrue("replay notice must report original count", notices.any { it.contains("20") })
        assertEquals("PARTIALLY_IMPORTED", outcome.receiptValue())
    }

    @Test
    fun `inbox import and replay preserve partial outcome for restored subset`() = runTest {
        val eventId = "evt-inbox-restored-subset"
        val handoff = docEvent(eventId, listOf(A, B, C, D, E), totalDistinctUriCount = 20)
        val rows = mutableListOf<InboxItemEntity>()
        val notices = mutableListOf<String>()
        val underTest = coordinator(notices, inboxSource(rows))

        assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, underTest.handleToInbox(handoff))
        assertEquals(5, rows.size)
        assertTrue(notices.any { it.contains("20") })
        assertEquals("PARTIALLY_IMPORTED", HandoffImportOutcome.PARTIALLY_IMPORTED.receiptValue())

        notices.clear()
        assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED, underTest.handleToInbox(handoff))
        assertTrue("replay must retain the original partial summary", notices.any { it.contains("20") })
    }

    @Test
    fun `rollback marker converts a refused batch into a false result`() = runTest {
        // Direct seam check: a body that throws the rollback marker must be
        // reported as not-persisted, never as a partial success.
        val persisted = dao.rollbackAwareCommit.inTransaction {
            dao.insertRow(PROJECT_ID, "first", File(root, "first.txt").absolutePath)
            throw ProjectFileBatchRollback()
        }

        assertFalse(persisted)
        assertEquals("the throw must roll the batch back", 0, dao.rows.size)
    }

    private fun coordinator(
        notices: MutableList<String> = mutableListOf(),
        source: ProjectFileCommitSource = TestCommitSource(dao),
    ) = DocumentHandoffCoordinator(
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
        notify = { notices += it },
        projectSource = source,
    )

    private fun inboxSource(rows: MutableList<InboxItemEntity>): ProjectFileCommitSource {
        val dao = Proxy.newProxyInstance(
            InboxItemDao::class.java.classLoader,
            arrayOf(InboxItemDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getForHandoff" -> rows
                    .filter { it.sourceHandoffEventId == args[0] }
                    .take(args[1] as Int)
                "insertAllBounded" -> {
                    @Suppress("UNCHECKED_CAST")
                    rows.addAll(args[0] as List<InboxItemEntity>)
                    InboxInsertResult.Inserted
                }
                else -> error("Unexpected InboxItemDao method ${method.name}")
            }
        } as InboxItemDao
        return object : ProjectFileCommitSource {
            override fun projectDao() = error("project DAO is not exercised")
            override fun inboxDao(): InboxItemDao = dao
            override val commit: ProjectFileBatchCommit get() = error("project commit is not exercised")
            override suspend fun inSettlementTransaction(block: suspend () -> Boolean): Boolean = block()
        }
    }

    private fun docEvent(id: String, uris: List<String>, totalDistinctUriCount: Int = uris.distinct().size) = ExternalDocumentHandoff(
        id = id,
        uriStrings = uris,
        declaredMimeType = null,
        source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        totalDistinctUriCount = totalDistinctUriCount,
    )

    private class TestCommitSource(private val dao: FakeProjectDao) : ProjectFileCommitSource {
        override fun projectDao(): ProjectDao = dao.handle
        override fun inboxDao() = error("inbox is not exercised by these tests")
        override val commit: ProjectFileBatchCommit get() = dao.rollbackAwareCommit
        override suspend fun inSettlementTransaction(block: suspend () -> Boolean): Boolean = block()
    }
    private companion object {
        const val PROJECT_ID = 42L
        const val A = "content://t/a.txt"
        const val B = "content://t/b.txt"
        const val C = "content://t/c.txt"
        const val D = "content://t/d.txt"
        const val E = "content://t/e.txt"
        val CONTENT = mapOf(A to "alpha", B to "beta", C to "gamma", D to "delta", E to "epsilon").mapValues { (_, v) ->
            v.toByteArray(Charsets.UTF_8)
        }
        val METADATA = mapOf(
            A to SharedDocumentMetadata("a.txt", 5L, "text/plain"),
            B to SharedDocumentMetadata("b.txt", 4L, "text/plain"),
            C to SharedDocumentMetadata("c.txt", 5L, "text/plain"),
            D to SharedDocumentMetadata("d.txt", 5L, "text/plain"),
            E to SharedDocumentMetadata("e.txt", 7L, "text/plain"),
        )
    }
}


