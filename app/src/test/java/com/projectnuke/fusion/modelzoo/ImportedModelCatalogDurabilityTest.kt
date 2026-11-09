package com.projectnuke.fusion.modelzoo

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportedModelCatalogDurabilityTest {
    /** JVM-file-backed fake mirroring the documented base/`.bak` layout. */
    private class JvmFileIo(
        var writeFails: Boolean = false,
        var readGate: CountDownLatch? = null,
    ) : FusionModelCatalog.CatalogFileIo {
        var primaryReads = 0
        var backupReads = 0
        private fun backupOf(file: File) = File(file.parent, "${file.name}.bak")

        override fun hasArtifact(file: File): Boolean =
            file.isFile || backupOf(file).isFile

        override fun readPrimary(file: File): ByteArray? {
            primaryReads++
            readGate?.await(10, TimeUnit.SECONDS)
            return runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
        }

        override fun readBackup(file: File): ByteArray? {
            backupReads++
            return runCatching { backupOf(file).takeIf { it.isFile }?.readBytes() }.getOrNull()
        }

        override fun writeArtifact(file: File, bytes: ByteArray) {
            if (writeFails) throw IOException("injected write failure")
            file.writeBytes(bytes)
        }
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    private lateinit var dir: File
    private lateinit var context: Context
    private lateinit var io: JvmFileIo

    private fun catalogFile(): File = File(dir, FusionModelCatalog.ImportedMetadataFileName)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("fusion-catalog").toFile()
        context = TestContext(dir)
        io = JvmFileIo()
        FusionModelCatalog.overrideCatalogFileIoForTests(io)
    }

    @After
    fun tearDown() {
        FusionModelCatalog.resetCatalogFileIoForTests()
        dir.deleteRecursively()
    }

    private fun entry(
        id: String,
        path: String = "/models/$id.litertlm",
        displayName: String = "Model $id",
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("family", "CUSTOM")
        .put("parameterLabel", "Custom")
        .put("path", path)
        .put("runtimeFormat", "LITERT_LM")
        .put("originalFileName", "$id.litertlm")
        .put("uri", "")
        .put("fileSizeBytes", 10L)
        .put("addedAt", 5L)
        .put("lastCheckedAt", 6L)
        .put("externallyReferenced", false)
        .put("copiedInternally", true)

    private fun spec(id: String, path: String = "/models/$id.litertlm") =
        FusionModelCatalog.importedSpec("Model $id", path, ModelFamily.CUSTOM).copy(id = id)

    @Test
    fun `corrupt base with valid backup recovers backup models`() {
        catalogFile().writeText("[{broken")
        File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.bak").writeText(
            JSONArray().put(entry("kept", "/models/kept.litertlm")).toString(),
        )
        val load = FusionModelCatalog.loadImportedResult(context)
        assertTrue("expected Valid, was $load", load is FusionModelCatalog.ImportedCatalogLoad.Valid)
        assertEquals(listOf("kept"), (load as FusionModelCatalog.ImportedCatalogLoad.Valid).models.map { it.id })
    }

    @Test fun `distinct external URIs survive save and durable reload`() {
        val first = FusionModelCatalog.externalLinkedSpec("first", "first.litertlm", "content://models/first", 10, ModelFamily.CUSTOM)
        val second = FusionModelCatalog.externalLinkedSpec("second", "second.litertlm", "content://models/second", 10, ModelFamily.CUSTOM)
        assertTrue(FusionModelCatalog.saveImported(context, first))
        assertTrue(FusionModelCatalog.saveImported(context, second))
        assertEquals(listOf(first.id, second.id), FusionModelCatalog.loadImported(context).map { it.id })
    }

    @Test fun `same URI registration replaces metadata while preserving catalog identity`() {
        val first = FusionModelCatalog.externalLinkedSpec("first", "first.litertlm", "content://models/same", 10, ModelFamily.CUSTOM)
        val renamed = FusionModelCatalog.externalLinkedSpec("renamed", "first.litertlm", "content://models/same", 10, ModelFamily.CUSTOM)
        assertTrue(FusionModelCatalog.saveImported(context, first))
        assertTrue(FusionModelCatalog.saveImported(context, renamed))
        val saved = FusionModelCatalog.loadImported(context).single()
        assertEquals(first.id, saved.id)
        assertEquals("renamed", saved.displayName)
    }

    @Test fun `URI release preserves existing owner and fails closed on unreadable catalog`() {
        val linked = FusionModelCatalog.externalLinkedSpec("linked", "file.litertlm", "content://models/owned", 10, ModelFamily.CUSTOM)
        assertTrue(FusionModelCatalog.saveImported(context, linked))
        var releases = 0
        assertFalse(FusionModelCatalog.releaseUriPermissionIfUnowned(context, linked.uriString!!) { releases++ })
        assertEquals(0, releases)
        assertTrue(FusionModelCatalog.removeImported(context, linked))
        assertTrue(FusionModelCatalog.releaseUriPermissionIfUnowned(context, linked.uriString!!) { releases++ })
        assertEquals(1, releases)
        catalogFile().writeText("broken")
        assertFalse(FusionModelCatalog.releaseUriPermissionIfUnowned(context, linked.uriString!!) { releases++ })
        assertEquals(1, releases)
    }

    @Test
    fun `fully corrupt catalog renders empty and save quarantines before replacing`() {
        catalogFile().writeText("[{broken")
        assertTrue(FusionModelCatalog.loadImported(context).isEmpty())
        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Corrupt)
        assertTrue(FusionModelCatalog.saveImported(context, spec("fresh")))
        assertEquals("[{broken", File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.corrupt.bak").readText())
        assertEquals(listOf("fresh"), FusionModelCatalog.loadImported(context).map { it.id })
    }

    @Test
    fun `remove on corrupt catalog fails closed without touching evidence`() {
        catalogFile().writeText("[{broken")
        assertFalse(FusionModelCatalog.removeImported(context, spec("fresh")))
        assertEquals("[{broken", catalogFile().readText())
        assertTrue(FusionModelCatalog.loadImported(context).isEmpty())
    }

    @Test
    fun `concurrent imports do not lose models`() {
        io.readGate = CountDownLatch(1)
        val first = Thread { FusionModelCatalog.saveImported(context, spec("con-a")) }
        val second = Thread { FusionModelCatalog.saveImported(context, spec("con-b")) }
        first.start()
        // Let both threads enter the read path before either may proceed, so an
        // unsynchronized read-modify-write would deterministically lose one entry.
        Thread.sleep(200)
        second.start()
        Thread.sleep(200)
        io.readGate!!.countDown()
        first.join(10_000)
        second.join(10_000)
        val ids = FusionModelCatalog.loadImported(context).map { it.id }.toSet()
        assertTrue("expected both imports, was $ids", ids.contains("con-a") && ids.contains("con-b"))
    }

    @Test
    fun `concurrent deletes do not resurrect models`() {
        assertTrue(FusionModelCatalog.saveImported(context, spec("del-a")))
        assertTrue(FusionModelCatalog.saveImported(context, spec("del-b")))
        io.readGate = CountDownLatch(1)
        val first = Thread { FusionModelCatalog.removeImported(context, spec("del-a")) }
        val second = Thread { FusionModelCatalog.removeImported(context, spec("del-b")) }
        first.start()
        Thread.sleep(200)
        second.start()
        Thread.sleep(200)
        io.readGate!!.countDown()
        first.join(10_000)
        second.join(10_000)
        assertTrue(FusionModelCatalog.loadImported(context).isEmpty())
    }

    @Test
    fun `duplicate ids and duplicate paths are corrupt, blank external paths exempt`() {
        catalogFile().writeText(
            JSONArray().put(entry("same")).put(entry("same", "/models/other.litertlm")).toString(),
        )
        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Corrupt)

        catalogFile().writeText(
            JSONArray().put(entry("a", "/models/shared.litertlm")).put(entry("b", "/models/shared.litertlm")).toString(),
        )
        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Corrupt)

        // External links share a blank path; that sharing is writer-expressible.
        val externals = JSONArray()
            .put(entry("ext-a", "").apply { put("uri", "content://a") })
            .put(entry("ext-b", "").apply { put("uri", "content://b") })
        catalogFile().writeText(externals.toString())
        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Valid)
    }

    @Test
    fun `writer-impossible field types are corrupt instead of defaulted`() {
        catalogFile().writeText(
            JSONArray().put(entry("bad").apply {
                put("fileSizeBytes", "10")
            }).toString(),
        )
        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Corrupt)
        assertFalse(FusionModelCatalog.removeImported(context, spec("bad")))
        assertTrue(catalogFile().isFile)
    }

    @Test
    fun `failed write keeps previous bytes`() {
        assertTrue(FusionModelCatalog.saveImported(context, spec("good")))
        val goodBytes = catalogFile().readBytes()
        io.writeFails = true
        assertFalse(FusionModelCatalog.saveImported(context, spec("doomed")))
        assertTrue(goodBytes.contentEquals(catalogFile().readBytes()))
        assertEquals(listOf("good"), FusionModelCatalog.loadImported(context).map { it.id })
    }

    @Test
    fun `legacy missing-id migration is deterministic across loads`() {
        val legacy = JSONArray().put(entry("", "/models/legacy.litertlm", "Legacy Model")).toString()
        catalogFile().writeText(legacy)
        val first = FusionModelCatalog.loadImportedResult(context)
        assertTrue(first is FusionModelCatalog.ImportedCatalogLoad.Valid)
        val firstId = (first as FusionModelCatalog.ImportedCatalogLoad.Valid).models.single().id
        assertTrue(firstId.isNotBlank())
        // The migration wrote the assigned identity back.
        val second = FusionModelCatalog.loadImportedResult(context)
        assertTrue(second is FusionModelCatalog.ImportedCatalogLoad.Valid)
        assertEquals(firstId, (second as FusionModelCatalog.ImportedCatalogLoad.Valid).models.single().id)
        // Same bytes always yield the same identity: reset to legacy and reload.
        catalogFile().writeText(legacy)
        val third = FusionModelCatalog.loadImportedResult(context)
        assertEquals(firstId, (third as FusionModelCatalog.ImportedCatalogLoad.Valid).models.single().id)
    }

    @Test
    fun `duplicate blank-ID same-path legacy catalog fails migration closed`() {
        val legacy = JSONArray()
            .put(entry("", "/models/foo.litertlm", "Legacy A"))
            .put(entry("", "/models/foo.litertlm", "Legacy B"))
            .toString()
        catalogFile().writeText(legacy)
        val beforeBytes = catalogFile().readBytes()

        // Migrating both blank IDs yields distinct IDs over a duplicated path:
        // the final representation is out-of-contract, so the migration must
        // fail closed instead of writing a catalog the next load calls CORRUPT.
        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Corrupt)
        assertTrue(beforeBytes.contentEquals(catalogFile().readBytes()))
    }

    @Test
    fun `migration write failure does not expose phantom identities and removal fails closed`() {
        catalogFile().writeText(
            JSONArray().put(entry("", "/models/legacy.litertlm", "Legacy Model")).toString(),
        )
        val legacyBytes = catalogFile().readBytes()
        io.writeFails = true
        // The migrated identity was never durably adopted: exposing it would
        // let a later remove report success while the blank-ID entry remains.
        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Corrupt)

        val phantomId = "custom-anything"
        assertFalse(FusionModelCatalog.removeImported(context, spec(phantomId)))
        // The durable legacy entry is untouched.
        assertTrue(legacyBytes.contentEquals(catalogFile().readBytes()))
    }

    @Test
    fun `primary absent with corrupt backup is quarantined and catalog stays writable`() {
        File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.bak").writeText("[{broken")
        assertTrue(FusionModelCatalog.loadImported(context).isEmpty())

        // The corrupt backup is the only durable evidence: secure it, then
        // the mutation must proceed instead of bricking the catalog forever.
        assertTrue(FusionModelCatalog.saveImported(context, spec("recovered")))
        assertEquals(
            "[{broken",
            File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.bak.corrupt.bak").readText(),
        )
        assertEquals(listOf("recovered"), FusionModelCatalog.loadImported(context).map { it.id })
    }

    @Test
    fun `oversized primary is not read and valid backup still recovers`() {
        catalogFile().writeBytes(ByteArray(FusionModelCatalog.MAX_IMPORTED_CATALOG_BYTES.toInt() + 1) { 7 })
        File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.bak").writeText(
            JSONArray().put(entry("backup")).toString(),
        )

        val load = FusionModelCatalog.loadImportedResult(context)
        assertTrue(load is FusionModelCatalog.ImportedCatalogLoad.Valid)
        assertEquals(listOf("backup"), (load as FusionModelCatalog.ImportedCatalogLoad.Valid).models.map { it.id })
        assertEquals(0, io.primaryReads)
        assertEquals(1, io.backupReads)
    }

    @Test
    fun `oversized catalog is typed and can be quarantined without whole-file duplication`() {
        val bytes = ByteArray(FusionModelCatalog.MAX_IMPORTED_CATALOG_BYTES.toInt() + 1) { (it % 251).toByte() }
        catalogFile().writeBytes(bytes)

        assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Oversized)
        assertTrue(FusionModelCatalog.saveImported(context, spec("after-oversized")))
        val quarantine = File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.corrupt.bak")
        assertEquals(bytes.size.toLong(), quarantine.length())
        assertEquals(listOf("after-oversized"), FusionModelCatalog.loadImported(context).map { it.id })
    }

    @Test
    fun `writer rejects final representation above reader byte cap`() {
        val huge = spec("too-large").copy(displayName = "x".repeat(FusionModelCatalog.MAX_IMPORTED_CATALOG_BYTES.toInt()))
        assertFalse(FusionModelCatalog.saveImported(context, huge))
        assertFalse(catalogFile().exists())
    }

    @Test
    fun `every writer success round-trips valid`() {
        listOf("a", "b", "c").forEach { id ->
            assertTrue(FusionModelCatalog.saveImported(context, spec(id)))
            assertTrue(FusionModelCatalog.loadImportedResult(context) is FusionModelCatalog.ImportedCatalogLoad.Valid)
        }
        assertEquals(listOf("a", "b", "c"), FusionModelCatalog.loadImported(context).map { it.id })
    }

    @Test
    fun `catalog invariant helper rejects duplicate ids and paths`() {
        assertTrue(FusionModelCatalog.isValidImportedCatalog(listOf(spec("a"), spec("b"))))

        val sharedPath = spec("dup").copy(id = "other")
        assertFalse(FusionModelCatalog.isValidImportedCatalog(listOf(spec("dup"), sharedPath)))
        assertFalse(
            FusionModelCatalog.isValidImportedCatalog(
                listOf(spec("dup"), spec("other").copy(localPath = "/models/dup.litertlm")),
            ),
        )
        assertFalse(FusionModelCatalog.isValidImportedCatalog(listOf(spec("dup").copy(id = ""))))
    }

    @Test
    fun `save at entry cap fails instead of evicting`() {
        repeat(FusionModelCatalog.MAX_IMPORTED_ENTRIES) { i ->
            assertTrue(FusionModelCatalog.saveImported(context, spec("bulk-$i")))
        }
        assertEquals(FusionModelCatalog.MAX_IMPORTED_ENTRIES, FusionModelCatalog.loadImported(context).size)
        val fullBytes = catalogFile().readBytes()
        assertFalse(FusionModelCatalog.saveImported(context, spec("one-too-many")))
        assertTrue(fullBytes.contentEquals(catalogFile().readBytes()))
        // Same-identity replacement still succeeds at capacity.
        assertTrue(FusionModelCatalog.saveImported(context, spec("bulk-0").copy(displayName = "Renamed")))
        assertEquals(FusionModelCatalog.MAX_IMPORTED_ENTRIES, FusionModelCatalog.loadImported(context).size)
    }

    @Test
    fun `strict UTF-8 rejects malformed input instead of replacement chars`() {
        // Primary has invalid UTF-8 (lone surrogate half 0xED 0xA0).
        // Must be treated as Corrupt, not silently decoded with U+FFFD.
        val badJson = byteArrayOf(
            0x7B, 0x22, 0x69, 0x64, 0x22, 0x3A, 0x22, 0x74, 0x65, 0x73, 0x74, 0x22, 0x2C,
            0x22, 0x64, 0x69, 0x73, 0x70, 0x6C, 0x61, 0x79, 0x4E, 0x61, 0x6D, 0x65, 0x22,
            0x3A, 0x22, 0x54, 0x65, 0x73, 0x74, 0xED.toByte(), 0xA0.toByte(), 0x22, 0x7D
        )
        catalogFile().writeBytes(badJson)
        // Valid backup should be ignored because primary is corrupt, not missing.
        File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.bak").writeText(
            JSONArray().put(entry("backup")).toString(),
        )
        val load = FusionModelCatalog.loadImportedResult(context)
        assertTrue("expected Corrupt for malformed UTF-8, was $load", load is FusionModelCatalog.ImportedCatalogLoad.Corrupt)
    }

    @Test
    fun `fatal read error propagates without backup fallback or follow-up save`() {
        catalogFile().writeText(JSONArray().put(entry("primary")).toString())
        File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.bak").writeText(
            JSONArray().put(entry("backup")).toString(),
        )
        FusionModelCatalog.overrideCatalogFileIoForTests(object : FusionModelCatalog.CatalogFileIo {
            override fun hasArtifact(file: File): Boolean = true
            override fun readPrimary(file: File): ByteArray? = throw OutOfMemoryError("catalog read OOM")
            override fun readBackup(file: File): ByteArray? {
                throw AssertionError("backup must not be consulted after a fatal primary failure")
            }
            override fun writeArtifact(file: File, bytes: ByteArray) {
                throw AssertionError("no follow-up save after a fatal read failure")
            }
        })
        try {
            FusionModelCatalog.loadImportedResult(context)
            org.junit.Assert.fail("expected fatal Error")
        } catch (error: OutOfMemoryError) {
            // Coroutine stack recovery may copy the instance across dispatch,
            // so the invariant is class + message, never backup fallback.
            assertEquals("catalog read OOM", error.message)
        }
    }

    @Test
    fun `recoverable read failure still falls back to backup`() {
        catalogFile().writeText(JSONArray().put(entry("primary")).toString())
        File(dir, "${FusionModelCatalog.ImportedMetadataFileName}.bak").writeText(
            JSONArray().put(entry("backup")).toString(),
        )
        FusionModelCatalog.overrideCatalogFileIoForTests(object : FusionModelCatalog.CatalogFileIo {
            override fun hasArtifact(file: File): Boolean = true
            override fun readPrimary(file: File): ByteArray? = throw IOException("injected read failure")
            override fun readBackup(file: File): ByteArray? =
                File(file.parent, "${file.name}.bak").takeIf { it.isFile }?.readBytes()
            override fun writeArtifact(file: File, bytes: ByteArray) {
                file.writeBytes(bytes)
            }
        })
        val load = FusionModelCatalog.loadImportedResult(context)
        assertTrue("expected Valid backup fallback, was $load", load is FusionModelCatalog.ImportedCatalogLoad.Valid)
        assertEquals(listOf("backup"), (load as FusionModelCatalog.ImportedCatalogLoad.Valid).models.map { it.id })
}
}
