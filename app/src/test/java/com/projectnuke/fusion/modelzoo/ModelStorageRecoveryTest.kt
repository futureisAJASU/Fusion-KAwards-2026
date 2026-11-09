package com.projectnuke.fusion.modelzoo

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for process-death model-storage recovery (A1-A9).
 *
 * Each scenario plants a crash artifact directly (as if the process died
 * mid-operation) and asserts the catalog-truth-driven recovery rule.
 */
class ModelStorageRecoveryTest {

    @After
    fun tearDown() {
        activeDownloadBackupTokens.clear()
        activeDownloadPartTokens.clear()
        ManagedModelDeletionCoordinator.activeStagingTokens.clear()
    }

    private fun root(): File = Files.createTempDirectory("fusion-recovery").toFile()

    private fun catalogReferencing(root: File, basename: String): FusionModelCatalog.ImportedCatalogLoad.Valid {
        val spec = FusionModelCatalog.importedSpec(
            displayName = "M",
            path = File(root, basename).absolutePath,
            family = ModelFamily.CUSTOM,
        )
        return FusionModelCatalog.ImportedCatalogLoad.Valid(listOf(spec))
    }

    private fun agePastGrace(file: File, nowMillis: Long, orphanGraceMs: Long) {
        file.setLastModified(nowMillis - orphanGraceMs - 1)
    }

    // --- Managed deletion (A2/A3) -------------------------------------------

    @Test
    fun `managed delete crash window, catalog references target, target missing, staging restored`() {
        val root = root()
        val now = System.currentTimeMillis()
        val basename = "abc-1234-imported.litertlm"
        val target = File(root, basename)
        assertFalse(target.exists())
        val staging = File(
            root,
            ModelStagingCodec.encodeDeleteStaging(basename, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        recoverModelStorageArtifacts(root, catalogReferencing(root, basename), now, orphanGraceMs = 0L)

        assertTrue(target.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
        assertFalse(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `stale delete staging with no catalog reference is cleaned after grace`() {
        val root = root()
        val now = System.currentTimeMillis()
        val basename = "stale-target.litertlm"
        val staging = File(
            root,
            ModelStagingCodec.encodeDeleteStaging(basename, ModelStagingCodec.randomHexToken()),
        ).apply {
            writeBytes(byteArrayOf(7, 7, 7))
            agePastGrace(this, now, orphanGraceMs = 30_000L)
        }
        val truth = catalogReferencing(root, "some-other.litertlm")

        recoverModelStorageArtifacts(root, truth, now, orphanGraceMs = 30_000L)

        assertFalse(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `corrupt catalog truth preserves delete staging`() {
        val root = root()
        val basename = "model.litertlm"
        val staging = File(
            root,
            ModelStagingCodec.encodeDeleteStaging(basename, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(1)) }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Corrupt,
            System.currentTimeMillis(),
            orphanGraceMs = 0L,
        )

        assertTrue(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `oversized catalog truth preserves delete staging`() {
        val root = root()
        val basename = "model.litertlm"
        val staging = File(
            root,
            ModelStagingCodec.encodeDeleteStaging(basename, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(1)) }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Oversized,
            System.currentTimeMillis(),
            orphanGraceMs = 0L,
        )

        assertTrue(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `existing referenced target is not overwritten and staging is preserved`() {
        val root = root()
        val now = System.currentTimeMillis()
        val basename = "live.litertlm"
        val target = File(root, basename).apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val staging = File(
            root,
            ModelStagingCodec.encodeDeleteStaging(basename, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(1, 2, 3)) }

        recoverModelStorageArtifacts(root, catalogReferencing(root, basename), now, orphanGraceMs = 0L)

        assertArrayEquals(byteArrayOf(9, 9, 9), target.readBytes())
        assertTrue(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `malformed delete staging name is preserved and never deleted`() {
        val root = root()
        val malformed = File(
            root,
            ".fusion-delete-not-hex-token-${ModelStagingCodec.encodeBaseName("model.litertlm")}.bak",
        ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        // Also a garbage .bak that does not match any codec.
        val garbage = File(root, "stray.unrelated.bak").apply { writeBytes(byteArrayOf(5)) }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Corrupt,
            System.currentTimeMillis(),
            orphanGraceMs = 0L,
        )

        assertTrue(malformed.exists())
        assertTrue(garbage.exists())
        root.deleteRecursively()
    }

    @Test
    fun `same-process restore failure residual is recovered on next resume`() {
        val root = root()
        val now = System.currentTimeMillis()
        val basename = "residual.litertlm"
        // Residual hidden staging left behind by a failed same-process restore:
        // the catalog still references the target, but the original binary is gone.
        val staging = File(
            root,
            ModelStagingCodec.encodeDeleteStaging(basename, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(4, 5, 6)) }

        recoverModelStorageArtifacts(root, catalogReferencing(root, basename), now, orphanGraceMs = 0L)

        val target = File(root, basename)
        assertTrue(target.isFile)
        assertArrayEquals(byteArrayOf(4, 5, 6), target.readBytes())
        assertFalse(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `active delete staging token is excluded from recovery`() {
        val root = root()
        val now = System.currentTimeMillis()
        val basename = "inflight.litertlm"
        val token = ModelStagingCodec.randomHexToken()
        ManagedModelDeletionCoordinator.activeStagingTokens.add(token)
        val staging = File(root, ModelStagingCodec.encodeDeleteStaging(basename, token)).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            agePastGrace(this, now, orphanGraceMs = 30_000L)
        }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Missing,
            now,
            orphanGraceMs = 30_000L,
        )

        assertTrue(staging.exists())
        root.deleteRecursively()
    }

    // --- Download replacement backup (A4) -----------------------------------

    @Test
    fun `download crash window, target missing, backup restored`() {
        val root = root()
        val now = System.currentTimeMillis()
        val targetName = "gemma-4-E2B-it.litertlm"
        val target = File(root, targetName)
        assertFalse(target.exists())
        val backup = File(
            root,
            ModelStagingCodec.encodeDownloadBackup(targetName, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(8, 8, 8)) }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
            now,
            orphanGraceMs = 0L,
        )

        assertTrue(target.isFile)
        assertArrayEquals(byteArrayOf(8, 8, 8), target.readBytes())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }

    @Test
    fun `valid target present and old download backup is cleaned after grace`() {
        val root = root()
        val now = System.currentTimeMillis()
        val targetName = "phi-4-mini-instruct.litertlm"
        val target = File(root, targetName).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val backup = File(
            root,
            ModelStagingCodec.encodeDownloadBackup(targetName, ModelStagingCodec.randomHexToken()),
        ).apply {
            writeBytes(byteArrayOf(9, 9, 9))
            agePastGrace(this, now, orphanGraceMs = 30_000L)
        }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
            now,
            orphanGraceMs = 30_000L,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }

    @Test
    fun `fresh target with a fresh backup is NOT deleted before grace`() {
        val root = root()
        val now = System.currentTimeMillis()
        val targetName = "fresh.litertlm"
        val target = File(root, targetName).apply { writeBytes(byteArrayOf(1)) }
        val backup = File(
            root,
            ModelStagingCodec.encodeDownloadBackup(targetName, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(2)) }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
            now,
            orphanGraceMs = 5 * 60 * 1000L,
        )

        // Target is present -> obsolete debt, but backup is fresh -> preserved.
        assertTrue(backup.exists())
        assertArrayEquals(byteArrayOf(1), target.readBytes())
        root.deleteRecursively()
    }

    @Test
    fun `suspicious non-file target preserves download backup`() {
        val root = root()
        val now = System.currentTimeMillis()
        val targetName = "dir-target.litertlm"
        File(root, targetName).mkdir() // target exists but is not a regular file
        val backup = File(
            root,
            ModelStagingCodec.encodeDownloadBackup(targetName, ModelStagingCodec.randomHexToken()),
        ).apply { writeBytes(byteArrayOf(5)) }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
            now,
            orphanGraceMs = 0L,
        )

        assertTrue(backup.exists())
        assertTrue((root.resolve(targetName).isDirectory))
        root.deleteRecursively()
    }

    @Test
    fun `active download backup token is excluded from recovery`() {
        val root = root()
        val now = System.currentTimeMillis()
        val targetName = "inflight-dl.litertlm"
        val token = ModelStagingCodec.randomHexToken()
        activeDownloadBackupTokens.add(token)
        val backup = File(root, ModelStagingCodec.encodeDownloadBackup(targetName, token)).apply {
            writeBytes(byteArrayOf(3))
            agePastGrace(this, now, orphanGraceMs = 30_000L)
        }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
            now,
            orphanGraceMs = 30_000L,
        )

        assertTrue(backup.exists())
        root.deleteRecursively()
    }

    @Test
    fun `malformed and legacy download backup names are preserved`() {
        val root = root()
        val targetName = "gemma-4-E2B-it.litertlm"
        val legacy = File(root, ".${targetName}.${ModelStagingCodec.randomHexToken()}.bak").apply { writeBytes(byteArrayOf(1)) }
        val malformedPrefix =
            File(root, ".fusion-download-nodashhex.bak").apply { writeBytes(byteArrayOf(2)) }

        recoverModelStorageArtifacts(
            root,
            FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
            System.currentTimeMillis(),
            orphanGraceMs = 0L,
        )

        assertTrue(legacy.exists())
        assertTrue(malformedPrefix.exists())
        root.deleteRecursively()
    }

    // --- Import/download `.part` staging (P2) ----------------------------------

    private fun partFile(root: File, target: String, token: String): File =
        File(root, ".$target.$token.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun emptyCatalog() = FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList())

    @Test
    fun `aged live download part with active token is preserved`() {
        val root = root()
        val now = System.currentTimeMillis()
        val token = java.util.UUID.randomUUID().toString()
        val part = partFile(root, "model.litertlm", token)
        agePastGrace(part, now, orphanGraceMs = 30_000L)

        recoverModelStorageArtifacts(
            root,
            emptyCatalog(),
            now,
            orphanGraceMs = 30_000L,
            activeDownloadPartTokens = setOf(token),
        )

        assertTrue(part.exists())
        root.deleteRecursively()
    }

    @Test
    fun `download part is cleaned after its token is released beyond grace`() {
        val root = root()
        val now = System.currentTimeMillis()
        val token = java.util.UUID.randomUUID().toString()
        val part = partFile(root, "model.litertlm", token)
        agePastGrace(part, now, orphanGraceMs = 30_000L)

        // Same file, token no longer active: process-death-equivalent debt.
        recoverModelStorageArtifacts(
            root,
            emptyCatalog(),
            now,
            orphanGraceMs = 30_000L,
            activeDownloadPartTokens = emptySet(),
        )

        assertFalse(part.exists())
        root.deleteRecursively()
    }

    @Test
    fun `process-death old part with no active token is cleaned`() {
        val root = root()
        val now = System.currentTimeMillis()
        // Import-shaped staging whose token was lost with the old process.
        val part = partFile(
            root,
            "${java.util.UUID.randomUUID()}_shared.litertlm",
            java.util.UUID.randomUUID().toString(),
        )
        agePastGrace(part, now, orphanGraceMs = 30_000L)

        recoverModelStorageArtifacts(
            root,
            emptyCatalog(),
            now,
            orphanGraceMs = 30_000L,
        )

        assertFalse(part.exists())
        root.deleteRecursively()
    }

    @Test
    fun `active import part is still preserved`() {
        val root = root()
        val now = System.currentTimeMillis()
        val token = java.util.UUID.randomUUID().toString()
        val part = partFile(root, "${java.util.UUID.randomUUID()}_shared.litertlm", token)
        agePastGrace(part, now, orphanGraceMs = 30_000L)

        recoverModelStorageArtifacts(
            root,
            emptyCatalog(),
            now,
            orphanGraceMs = 30_000L,
            activeImportTokens = setOf(token),
        )

        assertTrue(part.exists())
        root.deleteRecursively()
    }

    @Test
    fun `import and download token domains never protect unrelated part files`() {
        val root = root()
        val now = System.currentTimeMillis()
        val importToken = java.util.UUID.randomUUID().toString()
        val downloadToken = java.util.UUID.randomUUID().toString()

        // The import domain alone saves the import part but not the
        // download part carrying a different token.
        val importPart = partFile(root, "imported.litertlm", importToken)
        val downloadPart = partFile(root, "downloaded.litertlm", downloadToken)
        agePastGrace(importPart, now, orphanGraceMs = 30_000L)
        agePastGrace(downloadPart, now, orphanGraceMs = 30_000L)
        recoverModelStorageArtifacts(
            root,
            emptyCatalog(),
            now,
            orphanGraceMs = 30_000L,
            activeImportTokens = setOf(importToken),
        )
        assertTrue(importPart.exists())
        assertFalse(downloadPart.exists())

        // The download domain alone saves the download part but not the
        // import part carrying a different token.
        val importPart2 = partFile(root, "imported2.litertlm", importToken)
        val downloadPart2 = partFile(root, "downloaded2.litertlm", downloadToken)
        agePastGrace(importPart2, now, orphanGraceMs = 30_000L)
        agePastGrace(downloadPart2, now, orphanGraceMs = 30_000L)
        recoverModelStorageArtifacts(
            root,
            emptyCatalog(),
            now,
            orphanGraceMs = 30_000L,
            activeDownloadPartTokens = setOf(downloadToken),
        )
        assertFalse(importPart2.exists())
        assertTrue(downloadPart2.exists())

        // A loose substring resemblance never protects: "tok" is contained
        // in this name but is not its exact trailing token segment.
        val unrelated = File(root, ".model.notok.part").apply { writeBytes(byteArrayOf(9)) }
        agePastGrace(unrelated, now, orphanGraceMs = 30_000L)
        recoverModelStorageArtifacts(
            root,
            emptyCatalog(),
            now,
            orphanGraceMs = 30_000L,
            activeImportTokens = setOf("tok"),
            activeDownloadPartTokens = setOf("tok"),
        )
        assertFalse(unrelated.exists())
        root.deleteRecursively()
    }

    // --- Codec unit checks --------------------------------------------------

    @Test
    fun `delete staging codec round-trips basename and token exactly`() {
        val token = ModelStagingCodec.randomHexToken()
        val name = ModelStagingCodec.encodeDeleteStaging("uuid_prefix_model.litertlm", token)
        val decoded = ModelStagingCodec.decodeDeleteStaging(name)
        assertEquals(token, decoded?.token)
        assertEquals("uuid_prefix_model.litertlm", decoded?.originalBasename)
    }

    @Test
    fun `download backup codec round-trips target name and token exactly`() {
        val token = ModelStagingCodec.randomHexToken()
        val name = ModelStagingCodec.encodeDownloadBackup("gemma-4-E2B-it.litertlm", token)
        val decoded = ModelStagingCodec.decodeDownloadBackup(name)
        assertEquals(token, decoded?.token)
        assertEquals("gemma-4-E2B-it.litertlm", decoded?.originalTargetName)
    }

    @Test
    fun `opaque deletion staging restores a near-limit UTF8 basename from durable identity`() {
        val root = root()
        val now = System.currentTimeMillis()
        val basename = "모".repeat(82) + ".litertlm"
        assertEquals(255, basename.toByteArray(Charsets.UTF_8).size)
        val token = ModelStagingCodec.randomHexToken()
        val staging = File(root, ModelStagingCodec.encodeDeleteStagingName(token))
            .apply { writeBytes(byteArrayOf(4, 5, 6)) }
        assertTrue(staging.name.toByteArray(Charsets.UTF_8).size < 255)
        assertTrue(ModelStagingCodec.writeDeleteIdentity(root, token, basename) != null)
        assertEquals(
            null,
            ModelStagingCodec.writeDeleteIdentity(root, ModelStagingCodec.randomHexToken(), basename + "x"),
        )

        recoverModelStorageArtifacts(root, catalogReferencing(root, basename), now, orphanGraceMs = 0L)

        assertArrayEquals(byteArrayOf(4, 5, 6), File(root, basename).readBytes())
        assertFalse(staging.exists())
        assertFalse(ModelStagingCodec.deleteIdentitySidecar(root, token).exists())
        root.deleteRecursively()
    }

    @Test
    fun `identity sidecar round trips mixed ASCII Korean and supplementary Unicode`() {
        val root = root()
        val basename = "한🙂Mix_".repeat(22) + ".litertlm"
        val token = ModelStagingCodec.randomHexToken()
        val backup = File(root, ModelStagingCodec.encodeDownloadBackupName(token))
            .apply { writeBytes(byteArrayOf(3, 2, 1)) }
        assertTrue(ModelStagingCodec.writeDownloadIdentity(root, token, basename) != null)
        assertEquals(251, basename.toByteArray(Charsets.UTF_8).size)

        assertTrue(ModelStagingCodec.encodeDownloadBackupName(token).toByteArray(Charsets.UTF_8).size <= 255)
        recoverModelStorageArtifacts(root, emptyCatalog(), System.currentTimeMillis(), orphanGraceMs = 0L)
        assertArrayEquals(byteArrayOf(3, 2, 1), File(root, basename).readBytes())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }

    @Test
    fun `tampered or token-swapped identity sidecars never restore to another target`() {
        val root = root()
        val now = System.currentTimeMillis()

        val expectedDelete = "target.litertlm"
        val wrongDelete = "victim.litertlm"
        val deleteToken = ModelStagingCodec.randomHexToken()
        val deleteStaging = File(root, ModelStagingCodec.encodeDeleteStagingName(deleteToken))
            .apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val deleteSidecar = ModelStagingCodec.writeDeleteIdentity(root, deleteToken, expectedDelete)!!
        val deleteMetadata = deleteSidecar.readBytes()
        deleteSidecar.writeBytes(deleteMetadata.dropLast(expectedDelete.toByteArray().size).toByteArray() + wrongDelete.toByteArray())

        val expectedDownload = "download.litertlm"
        val firstToken = ModelStagingCodec.randomHexToken()
        val downloadToken = ModelStagingCodec.randomHexToken()
        File(root, ModelStagingCodec.encodeDownloadBackupName(downloadToken)).writeBytes(byteArrayOf(7, 8, 9))
        val sourceSidecar = ModelStagingCodec.writeDownloadIdentity(root, firstToken, expectedDownload)!!
        File(root, ModelStagingCodec.downloadIdentitySidecar(root, downloadToken).name)
            .writeBytes(sourceSidecar.readBytes())

        recoverModelStorageArtifacts(root, catalogReferencing(root, expectedDelete), now, orphanGraceMs = 0L)

        assertTrue("tampered delete staging is preserved", deleteStaging.isFile)
        assertFalse(File(root, expectedDelete).exists())
        assertFalse(File(root, wrongDelete).exists())
        assertTrue(File(root, ModelStagingCodec.encodeDownloadBackupName(downloadToken)).isFile)
        assertFalse(File(root, expectedDownload).exists())
        root.deleteRecursively()
    }

    @Test
    fun `opaque download backup restores exact target identity after process death`() {
        val root = root()
        val now = System.currentTimeMillis()
        val targetName = "한".repeat(80) + ".litertlm"
        val token = ModelStagingCodec.randomHexToken()
        val backup = File(root, ModelStagingCodec.encodeDownloadBackupName(token))
            .apply { writeBytes(byteArrayOf(8, 9, 10)) }
        assertTrue(ModelStagingCodec.writeDownloadIdentity(root, token, targetName) != null)

        recoverModelStorageArtifacts(root, emptyCatalog(), now, orphanGraceMs = 0L)

        assertArrayEquals(byteArrayOf(8, 9, 10), File(root, targetName).readBytes())
        assertFalse(backup.exists())
        assertFalse(ModelStagingCodec.downloadIdentitySidecar(root, token).exists())
        root.deleteRecursively()
    }

    @Test
    fun `compact staging without trustworthy identity is preserved`() {
        val root = root()
        val now = System.currentTimeMillis()
        val token = ModelStagingCodec.randomHexToken()
        val staging = File(root, ModelStagingCodec.encodeDeleteStagingName(token))
            .apply { writeBytes(byteArrayOf(1)) }
        agePastGrace(staging, now, orphanGraceMs = 0L)

        recoverModelStorageArtifacts(root, emptyCatalog(), now, orphanGraceMs = 0L)

        assertTrue(staging.exists())
        root.deleteRecursively()
    }

    @Test
    fun `decodeDeleteStaging rejects names that cannot be unambiguously mapped`() {
        val rejects = listOf(
            ".fusion-delete-.bak",
            ".fusion-delete-not-hex-token-bak.bak",
            ".fusion-delete-abc-model.litertlm.bak",
            ".fusion-backup-something.bak",
            "model.litertlm",
        )
        rejects.forEach { assertDecodeRejectsDelete(it) }
    }

    @Test
    fun `decodeDownloadBackup rejects names that cannot be unambiguously mapped`() {
        val rejects = listOf(
            ".fusion-download-.bak",
            ".fusion-download-zz.bak",
            ".fusion-download-.bak",
            ".fusion-download-nodashhex.bak",
            ".model.litertlm.abc.bak",
            "model.litertlm",
        )
        rejects.forEach { assertDecodeRejectsDownload(it) }
    }

    private fun assertDecodeRejectsDelete(name: String) {
        assertNull("should reject $name", ModelStagingCodec.decodeDeleteStaging(name))
    }

    private fun assertDecodeRejectsDownload(name: String) {
        assertNull("should reject $name", ModelStagingCodec.decodeDownloadBackup(name))
    }
}
