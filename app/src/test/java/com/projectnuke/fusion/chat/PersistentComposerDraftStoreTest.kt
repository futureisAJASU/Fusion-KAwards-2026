package com.projectnuke.fusion.chat

import com.projectnuke.fusion.util.DebtQuarantineOutcome
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentComposerDraftStoreTest {
    @Test
    fun `rapid writes are ordered and stale write cannot replace newer snapshot`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        val newer = mapOf(1L to ComposerDraftState(rawInput = "new", version = 2))
        val older = mapOf(1L to ComposerDraftState(rawInput = "old", version = 1))

        assertTrue(store.write(2, newer))
        assertFalse(store.write(1, older))
        assertEquals("new", store.load()[1L]?.rawInput)
        file.delete()
    }

    @Test
    fun `corrupt store is typed corrupt and new write quarantines before replacing`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        val before = "not-json".also { file.writeText(it) }
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        // In-memory view is empty but typed state is NOT "no drafts".
        assertTrue(store.load().isEmpty())
        assertTrue(store.write(mapOf(1L to ComposerDraftState(rawInput = "new", version = 1))))
        // Evidence preserved in bounded quarantine.
        assertTrue(File(file.parentFile, file.name + ".corrupt.bak").isFile)
        assertEquals("new", store.load()[1L]?.rawInput)
        file.delete()
        File(file.parentFile, file.name + ".corrupt.bak").delete()
    }

    @Test
    fun `oversized store is typed oversized and not interpreted as empty`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        file.writeText("x".repeat(PersistentComposerDraftStore.MAX_FILE_BYTES + 1))
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.loadResult() is DraftStoreLoad.Oversized)
        file.delete()
    }

    @Test
    fun `failed quarantine leaves original bytes intact and fails the write`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-quarantine").toFile()
        val file = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        store.quarantineHook = { _, _ -> DebtQuarantineOutcome.Failed }
        try {
            assertFalse(store.write(mapOf(1L to ComposerDraftState(rawInput = "new", version = 1))))
            assertEquals("not-json", file.readText())
            assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        } finally {
            store.quarantineHook = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `explicit recovery secures corrupt evidence before writing current drafts`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-explicit-recovery").toFile()
        val file = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        try {
            assertEquals(
                DraftStoreRecoveryResult.Recovered,
                store.recoverUnreadable(mapOf(7L to ComposerDraftState(rawInput = "current visible input", version = 1))),
            )
            assertEquals("not-json", File(dir, file.name + ".corrupt.bak").readText())
            val recovered = store.loadResult() as DraftStoreLoad.Valid
            assertEquals("current visible input", recovered.drafts[7L]?.rawInput)
            assertNull(recovered.drafts[7L]?.activeSubmissionToken)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `explicit recovery refuses replacement when quarantine fails`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-explicit-recovery-fail").toFile()
        val file = File(dir, "composer_drafts_v2.json").apply { writeText("not-json") }
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        store.quarantineHook = { _, _ -> DebtQuarantineOutcome.Failed }
        try {
            assertEquals(
                DraftStoreRecoveryResult.Failed,
                store.recoverUnreadable(mapOf(7L to ComposerDraftState(rawInput = "must not replace", version = 1))),
            )
            assertEquals("not-json", file.readText())
        } finally {
            store.quarantineHook = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `explicit oversized recovery requests move-only quarantine`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-oversized-recovery").toFile()
        val file = File(dir, "composer_drafts_v2.json").apply {
            writeText("x".repeat(PersistentComposerDraftStore.MAX_FILE_BYTES + 1))
        }
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        var allowCopyFallback: Boolean? = null
        store.quarantineHook = { _, allowCopy ->
            allowCopyFallback = allowCopy
            DebtQuarantineOutcome.Failed
        }
        try {
            assertEquals(
                DraftStoreRecoveryResult.Failed,
                store.recoverUnreadable(mapOf(7L to ComposerDraftState(rawInput = "new", version = 1))),
            )
            assertEquals(false, allowCopyFallback)
            assertTrue(file.isFile)
        } finally {
            store.quarantineHook = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `one malformed entry makes the whole draft file corrupt`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        file.writeText(
            """[{"id":1,"input":"ok","version":1,"attachments":[]},{"id":"bad","input":"x","version":1,"attachments":[]}]""",
        )
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        file.delete()
    }

    @Test
    fun `too many drafts is corrupt, not truncated`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-count").toFile()
        val file = File(dir, "composer_drafts_v2.json")
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        val many = (1L..(PersistentComposerDraftStore.MAX_DRAFT_COUNT + 1)).associateWith {
            ComposerDraftState(rawInput = "x", version = 1)
        }
        // Writer path itself takes MAX, so build the over-count artifact directly.
        val array = org.json.JSONArray()
        many.forEach { (id, draft) ->
            array.put(org.json.JSONObject().put("id", id).put("input", draft.rawInput).put("version", 1L).put("attachments", org.json.JSONArray()))
        }
        file.writeText(array.toString())
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `submission ownership token survives restart for reconciliation retry`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        val token = "submission-token-1"

        store.write(mapOf(4L to ComposerDraftState(
            rawInput = "draft",
            activeSubmissionToken = token,
            version = 2,
        )))

        val restored = store.load()[4L]!!
        assertEquals(token, restored.activeSubmissionToken)
        assertEquals("draft", restored.rawInput)
        assertEquals(2L, restored.version)
        file.delete()
    }

    @Test
    fun `legacy composer JSON without external handoff receipt remains readable`() = runTest {
        val file = Files.createTempFile("fusion-drafts-legacy", ".json").toFile()
        file.writeText(
            """[{"id":0,"input":"legacy","version":3,"submissionToken":null,"attachments":[]}]""",
        )
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })

        val result = store.loadResult()
        assertTrue(result is DraftStoreLoad.Valid)
        assertEquals("legacy", store.load()[0L]?.rawInput)
        assertNull(store.load()[0L]?.lastExternalHandoffId)
        file.delete()
    }

    @Test
    fun `partial handoff notice is durable and requires its matching event identity`() = runTest {
        val file = Files.createTempFile("fusion-drafts-partial-handoff", ".json").toFile()
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        val notice = "문서 2개 중 1개를 새 채팅에 저장했어요. 가져오지 못한 문서 1개."
        assertTrue(store.write(mapOf(
            0L to ComposerDraftState(
                pendingAttachments = emptyList(),
                lastExternalHandoffId = "evt-partial",
                lastExternalHandoffPartialNotice = notice,
            ),
        )))
        assertEquals(notice, store.load()[0L]?.lastExternalHandoffPartialNotice)

        file.writeText(
            """[{"id":0,"input":"","version":0,"lastExternalHandoffPartialNotice":"orphaned","attachments":[]}]""",
        )
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        file.delete()
    }

    @Test
    fun `malformed or oversized external handoff receipt is corrupt`() = runTest {
        val file = Files.createTempFile("fusion-drafts-receipt", ".json").toFile()
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        file.writeText(
            """[{"id":0,"input":"x","version":1,"lastExternalHandoffId":7,"attachments":[]}]""",
        )
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)

        file.writeText(
            """[{"id":0,"input":"x","version":1,"lastExternalHandoffId":"${"x".repeat(PreparedExternalHandoff.MAX_EVENT_ID_CHARS + 1)}","attachments":[]}]""",
        )
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        file.delete()
    }

    @Test
    fun `restoration keeps only managed regular attachments and re-registers them`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        val managed = requireNotNull(file.parentFile).resolve("managed.txt").apply { writeText("ok") }
        val registered = mutableListOf<String>()
        val store = PersistentComposerDraftStore(
            file,
            resolveManagedAttachment = { path -> if (path == managed.absolutePath) managed else null },
            registerPendingAttachment = { registered += it.absolutePath; it.absolutePath },
        )
        store.write(mapOf(4L to ComposerDraftState(
            rawInput = "draft",
            pendingAttachments = listOf(
                PendingAttachmentIdentity("ok", "text/plain", managed.absolutePath),
                PendingAttachmentIdentity("outside", "text/plain", "C:/outside.txt"),
            ),
            version = 1,
        )))
        val restored = store.load()[4L]!!
        assertEquals(listOf(managed.absolutePath), restored.pendingAttachments.map { it.localPath })
        assertEquals(listOf(managed.absolutePath), registered)
        managed.delete()
        file.delete()
    }

    @Test
    fun `overlong rawInput write fails without truncation and preserves bytes`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.write(mapOf(1L to ComposerDraftState(rawInput = "good", version = 1L))))
        val goodBytes = file.readBytes()
        assertFalse(store.write(mapOf(1L to ComposerDraftState(rawInput = "x".repeat(PersistentComposerDraftStore.MAX_INPUT_CHARS + 1), version = 2L))))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        assertEquals("good", store.load()[1L]?.rawInput)
        file.delete()
    }

    @Test
    fun `over-count drafts write fails and preserves bytes`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-full").toFile()
        val file = File(dir, "composer_drafts_v2.json")
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.write(mapOf(1L to ComposerDraftState(rawInput = "good", version = 1L))))
        val goodBytes = file.readBytes()
        val many = (0L until (PersistentComposerDraftStore.MAX_DRAFT_COUNT + 1)).associateWith {
            ComposerDraftState(rawInput = "x", version = 1L)
        }
        assertFalse(store.write(many))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        dir.deleteRecursively()
    }

    @Test
    fun `over-count attachments and overlong path token fail without truncation`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.write(mapOf(1L to ComposerDraftState(rawInput = "good", version = 1L))))
        val goodBytes = file.readBytes()
        val tooMany = (0 until (PersistentComposerDraftStore.MAX_ATTACHMENT_COUNT + 1)).map {
            PendingAttachmentIdentity("a$it", "text/plain", "/p/$it")
        }
        assertFalse(store.write(mapOf(1L to ComposerDraftState(rawInput = "x", pendingAttachments = tooMany, version = 1L))))
        assertFalse(store.write(mapOf(1L to ComposerDraftState(rawInput = "x", pendingAttachments = listOf(PendingAttachmentIdentity("a", "text/plain", "p".repeat(PersistentComposerDraftStore.MAX_PATH_CHARS + 1))), version = 1L))))
        assertFalse(store.write(mapOf(1L to ComposerDraftState(rawInput = "x", activeSubmissionToken = "t".repeat(PersistentComposerDraftStore.MAX_TOKEN_CHARS + 1), version = 1L))))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        // No truncated artifact was installed.
        assertEquals("good", store.load()[1L]?.rawInput)
        file.delete()
    }

    @Test
    fun `aggregate above byte cap fails and valid below cap round-trips exactly`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-bytes").toFile()
        val file = File(dir, "composer_drafts_v2.json")
        val attachmentFile = File(dir, "a.bin").apply { writeBytes(ByteArray(8)) }
        val store = PersistentComposerDraftStore(
            file,
            resolveManagedAttachment = { path -> if (path == attachmentFile.absolutePath) attachmentFile else null },
            registerPendingAttachment = { it.absolutePath },
        )
        val small = mapOf(1L to ComposerDraftState(rawInput = "exact", pendingAttachments = listOf(PendingAttachmentIdentity("n", "m", attachmentFile.absolutePath)), version = 7L, activeSubmissionToken = "tok"))
        assertTrue(store.write(small))
        val restored = store.load()[1L]!!
        assertEquals("exact", restored.rawInput)
        assertEquals(listOf(attachmentFile.absolutePath), restored.pendingAttachments.map { it.localPath })
        assertEquals(7L, restored.version)
        assertEquals("tok", restored.activeSubmissionToken)
        val goodBytes = file.readBytes()
        // Field-valid but aggregate-oversize: many max-input drafts exceed 2MB.
        val big = (0L until PersistentComposerDraftStore.MAX_DRAFT_COUNT).associateWith {
            ComposerDraftState(rawInput = "z".repeat(PersistentComposerDraftStore.MAX_INPUT_CHARS), version = 1L)
        }
        assertFalse(store.write(big))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        dir.deleteRecursively()
    }

    @Test
    fun `floating numeric id and version are corrupt`() = runTest {
        val file = Files.createTempFile("fusion-drafts", ".json").toFile()
        file.writeText("""[{"id":1.5,"input":"x","version":1,"attachments":[]}]""")
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        file.writeText("""[{"id":1,"input":"x","version":2.5,"attachments":[]}]""")
        assertTrue(store.loadResult() is DraftStoreLoad.Corrupt)
        file.delete()
    }

    @Test
    fun `missing store loads as missing and valid round-trips`() = runTest {
        val dir = Files.createTempDirectory("fusion-drafts-missing").toFile()
        val file = File(dir, "composer_drafts_v2.json")
        val store = PersistentComposerDraftStore(file, { null }, { it.absolutePath })
        assertTrue(store.loadResult() is DraftStoreLoad.Missing)
        assertTrue(store.write(mapOf(1L to ComposerDraftState(rawInput = "hi", version = 1))))
        assertTrue(store.loadResult() is DraftStoreLoad.Valid)
        dir.deleteRecursively()
    }
}
