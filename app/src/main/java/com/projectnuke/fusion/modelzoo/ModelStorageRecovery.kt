package com.projectnuke.fusion.modelzoo

import com.projectnuke.fusion.util.ManagedModelPathPolicy
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic, unambiguous filename codec for hidden model-storage staging
 * artifacts. Only artifacts produced by the current codec are recoverable:
 * legacy or malformed names intentionally fail closed during recovery (they
 * are preserved, never arbitrarily deleted).
 *
 * Legacy layout (kept readable for already-created artifacts):
 *   delete staging : .fusion-delete-<tokenHex>-<basenameHex>.bak
 *   download backup: .fusion-download-<tokenHex>-<targetNameHex>.bak
 *
 * New artifacts use bounded opaque basenames and a durable UTF-8 sidecar for
 * original-target identity. This prevents valid long/Korean model names from
 * exceeding filesystem component limits while retaining crash recovery.
 */
internal object ModelStagingCodec {
    internal const val DEFAULT_ORPHAN_GRACE_MS = 5 * 60 * 1000L

    private val HEX_REGEX = Regex("^[0-9a-f]+$")

    fun randomHexToken(): String = UUID.randomUUID().toString().replace("-", "")

    fun encodeBaseName(raw: String): String =
        raw.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun decodeBaseName(hex: String): String? = runCatching {
        if (hex.isEmpty() || hex.length % 2 != 0 || !HEX_REGEX.matches(hex)) return null
        val bytes = ByteArray(hex.length / 2)
        for (j in bytes.indices) {
            val hi = hexValue(hex, 2 * j)
            val lo = hexValue(hex, 2 * j + 1)
            if (hi < 0 || lo < 0) return null
            bytes[j] = ((hi shl 4) or lo).toByte()
        }
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun hexValue(s: String, i: Int): Int = when (s[i]) {
        in '0'..'9' -> s[i] - '0'
        in 'a'..'f' -> s[i] - 'a' + 10
        else -> -1
    }

    private const val DELETE_PREFIX = ".fusion-delete-"
    private const val DELETE_SUFFIX = ".bak"

    fun encodeDeleteStaging(targetBasename: String, token: String): String =
        "$DELETE_PREFIX$token-${encodeBaseName(targetBasename)}$DELETE_SUFFIX"

    fun encodeDeleteStagingName(token: String): String = "$DELETE_PREFIX$token$DELETE_SUFFIX"

    fun deleteIdentitySidecarName(token: String): String = "$DELETE_PREFIX$token.bak.meta"

    fun decodeDeleteStaging(name: String): DeleteStagingEntry? {
        if (name.length < DELETE_PREFIX.length + DELETE_SUFFIX.length) return null
        if (!name.startsWith(DELETE_PREFIX) || !name.endsWith(DELETE_SUFFIX)) return null
        val inner = name.substring(DELETE_PREFIX.length, name.length - DELETE_SUFFIX.length)
        val dash = inner.indexOf('-')
        if (dash < 0) {
            if (inner.length != 32 || !HEX_REGEX.matches(inner)) return null
            return DeleteStagingEntry(token = inner, originalBasename = null)
        }
        if (dash <= 0 || dash == inner.lastIndex) return null
        val token = inner.substring(0, dash)
        val basenameHex = inner.substring(dash + 1)
        if (token.length != 32 || !HEX_REGEX.matches(token)) return null
        if (!HEX_REGEX.matches(basenameHex) || basenameHex.isEmpty()) return null
        val basename = decodeBaseName(basenameHex) ?: return null
        if (!isSafeBasename(basename)) return null
        return DeleteStagingEntry(token = token, originalBasename = basename)
    }

    private const val DOWNLOAD_PREFIX = ".fusion-download-"
    private const val DOWNLOAD_SUFFIX = ".bak"

    fun encodeDownloadBackup(targetName: String, token: String): String =
        "$DOWNLOAD_PREFIX$token-${encodeBaseName(targetName)}$DOWNLOAD_SUFFIX"

    fun encodeDownloadBackupName(token: String): String = "$DOWNLOAD_PREFIX$token$DOWNLOAD_SUFFIX"

    fun downloadIdentitySidecarName(token: String): String = "$DOWNLOAD_PREFIX$token.bak.meta"

    fun decodeDownloadBackup(name: String): DownloadBackupEntry? {
        if (name.length < DOWNLOAD_PREFIX.length + DOWNLOAD_SUFFIX.length) return null
        if (!name.startsWith(DOWNLOAD_PREFIX) || !name.endsWith(DOWNLOAD_SUFFIX)) return null
        val inner = name.substring(DOWNLOAD_PREFIX.length, name.length - DOWNLOAD_SUFFIX.length)
        val dash = inner.indexOf('-')
        if (dash < 0) {
            if (inner.length != 32 || !HEX_REGEX.matches(inner)) return null
            return DownloadBackupEntry(token = inner, originalTargetName = null)
        }
        if (dash <= 0 || dash == inner.lastIndex) return null
        val token = inner.substring(0, dash)
        val nameHex = inner.substring(dash + 1)
        if (token.length != 32 || !HEX_REGEX.matches(token)) return null
        if (!HEX_REGEX.matches(nameHex) || nameHex.isEmpty()) return null
        val targetName = decodeBaseName(nameHex) ?: return null
        if (!isSafeBasename(targetName)) return null
        return DownloadBackupEntry(token = token, originalTargetName = targetName)
    }

    fun writeDeleteIdentity(root: File, token: String, basename: String): File? =
        writeIdentity(root, deleteIdentitySidecarName(token), token, basename)

    fun readDeleteIdentity(root: File, token: String): String? =
        readIdentity(File(root, deleteIdentitySidecarName(token)), token)

    fun writeDownloadIdentity(root: File, token: String, basename: String): File? =
        writeIdentity(root, downloadIdentitySidecarName(token), token, basename)

    fun readDownloadIdentity(root: File, token: String): String? =
        readIdentity(File(root, downloadIdentitySidecarName(token)), token)

    fun deleteIdentitySidecar(root: File, token: String): File =
        File(root, deleteIdentitySidecarName(token))

    fun downloadIdentitySidecar(root: File, token: String): File =
        File(root, downloadIdentitySidecarName(token))

    private fun writeIdentity(root: File, name: String, token: String, basename: String): File? {
        if (!isValidToken(token) || !isSafeBasename(basename)) return null
        val basenameBytes = basename.toByteArray(Charsets.UTF_8)
        if (basenameBytes.size > MAX_COMPONENT_BYTES) return null
        val checksum = identityChecksum(token, basenameBytes).toHex()
        val header = "$IDENTITY_MAGIC\n$token\n$checksum\n".toByteArray(Charsets.US_ASCII)
        val metadataBytes = header + basenameBytes
        if (metadataBytes.size > MAX_IDENTITY_BYTES) return null
        val target = File(root, name)
        val temporary = File(root, "$name.tmp")
        if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(temporary.toPath())) return null
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(metadataBytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            target
        } catch (_: Exception) {
            runCatching { temporary.delete() }
            null
        }
    }

    private fun readIdentity(file: File, expectedToken: String): String? {
        if (!isValidToken(expectedToken) || !file.isFile || Files.isSymbolicLink(file.toPath()) ||
            file.length() !in MIN_IDENTITY_BYTES.toLong()..MAX_IDENTITY_BYTES.toLong()
        ) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val firstNewline = bytes.indexOfByte('\n'.code.toByte(), 0)
        if (firstNewline < 0) return null
        val secondNewline = bytes.indexOfByte('\n'.code.toByte(), firstNewline + 1)
        if (secondNewline < 0) return null
        val thirdNewline = bytes.indexOfByte('\n'.code.toByte(), secondNewline + 1)
        if (thirdNewline < 0 || thirdNewline + 1 >= bytes.size) return null
        val header = runCatching {
            String(bytes, 0, thirdNewline + 1, Charsets.US_ASCII)
        }.getOrNull() ?: return null
        val hashText = header.substring(secondNewline + 1, thirdNewline)
        if (header != "$IDENTITY_MAGIC\n$expectedToken\n$hashText\n" ||
            hashText.length != SHA256_HEX_CHARS || hashText.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) return null
        val basenameBytes = bytes.copyOfRange(thirdNewline + 1, bytes.size)
        if (basenameBytes.isEmpty() || basenameBytes.size > MAX_COMPONENT_BYTES) return null
        val declaredDigest = decodeHexDigest(hashText) ?: return null
        if (!MessageDigest.isEqual(declaredDigest, identityChecksum(expectedToken, basenameBytes))) return null
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(basenameBytes)).toString()
        }.getOrNull()?.takeIf(::isSafeBasename)
    }

    private fun identityChecksum(token: String, basenameBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(token.toByteArray(Charsets.US_ASCII))
            update(0)
            digest(basenameBytes)
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.indexOfByte(value: Byte, fromIndex: Int): Int {
        for (index in fromIndex.coerceAtLeast(0) until size) {
            if (this[index] == value) return index
        }
        return -1
    }

    private fun decodeHexDigest(value: String): ByteArray? = runCatching {
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()

    private fun isValidToken(token: String): Boolean = token.length == 32 &&
        token.all { it in '0'..'9' || it in 'a'..'f' }

    private fun isSafeBasename(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." &&
            value.indexOfAny(charArrayOf('/', '\\', '\u0000')) < 0

    private const val MAX_COMPONENT_BYTES = 255
    private const val IDENTITY_MAGIC = "FUSION-MODEL-ID-V1"
    private const val SHA256_HEX_CHARS = 64
    private const val MIN_IDENTITY_BYTES = IDENTITY_MAGIC.length + 1 + 32 + 1 + SHA256_HEX_CHARS + 1 + 1
    private const val MAX_IDENTITY_BYTES = MAX_COMPONENT_BYTES + MIN_IDENTITY_BYTES
}

internal data class DeleteStagingEntry(val token: String, val originalBasename: String?)
internal data class DownloadBackupEntry(val token: String, val originalTargetName: String?)

/**
 * Bounded, constant-memory reconciliation of process-death model-storage
 * artifacts against durable Fusion imported-catalog truth.
 *
 * Recovery order:
 *  1. delete-staging: restore if the catalog still references the original
 *     target, otherwise clean as obsolete after grace.
 *  2. download backup: restore if the replacement target is absent, otherwise
 *     clean as obsolete after grace.
 *  3. import/download `.part` staging: clean after grace unless the exact
 *     trailing token is still owned by a live operation.
 *
 * Corrupt/Oversized/Missing catalog truth fails closed: no delete-staging
 * artifact is removed. Download backups are reconciled against target file
 * existence (built-in download targets are not catalog entries), so catalog
 * state does not gate them.
 *
 * Active operation exclusion is enforced via the process-scoped token
 * registries on [ManagedModelDeletionCoordinator] and
 * [ModelDownloadCoordinator], plus the explicitly passed live `.part` token
 * sets below; artifacts owned by a live operation are never touched. Live
 * ownership is decided by exact trailing-token match only, never by file
 * age: a live download that outlasts the grace window keeps its `.part`,
 * while a process-death leftover (no active token) is still cleaned.
 */
internal fun recoverModelStorageArtifacts(
    modelDirectory: File,
    catalogLoad: FusionModelCatalog.ImportedCatalogLoad,
    nowMillis: Long,
    orphanGraceMs: Long = ModelStagingCodec.DEFAULT_ORPHAN_GRACE_MS,
    activeImportTokens: Set<String> = emptySet(),
    activeDownloadPartTokens: Set<String> = emptySet(),
    log: (String) -> Unit = {},
) {
    val root = runCatching { modelDirectory.canonicalFile }.getOrNull() ?: return
    if (!root.isDirectory) return
    val failClosedOnCorruptTruth = catalogLoad !is FusionModelCatalog.ImportedCatalogLoad.Valid
    val catalogRefs = truthCanonicalPaths(catalogLoad, root)
    val files = root.listFiles() ?: return

    for (file in files) {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) continue
        val name = file.name

        ModelStagingCodec.decodeDeleteStaging(name)?.let { entry ->
            if (ManagedModelDeletionCoordinator.isActiveStaging(file)) return@let
            val basename = entry.originalBasename
                ?: ModelStagingCodec.readDeleteIdentity(root, entry.token)
            if (basename == null) {
                log("Delete staging identity missing or malformed; preserving: $name")
                return@let
            }
            recoverDeleteStaging(
                file, root, entry.copy(originalBasename = basename), catalogRefs, failClosedOnCorruptTruth,
                nowMillis, orphanGraceMs, log,
            )
            return@let
        }

        ModelStagingCodec.decodeDownloadBackup(name)?.let { entry ->
            if (isActiveDownloadBackup(file)) return@let
            val targetName = entry.originalTargetName
                ?: ModelStagingCodec.readDownloadIdentity(root, entry.token)
            if (targetName == null) {
                log("Download backup identity missing or malformed; preserving: $name")
                return@let
            }
            recoverDownloadBackup(
                file, root, entry.copy(originalTargetName = targetName), nowMillis, orphanGraceMs, log
            )
            return@let
        }

        // Import/download `.part` staging is never catalog-referenced;
        // clean by grace unless a live operation still owns the exact token.
        if (name.startsWith(".") && name.endsWith(".part")) {
            val ownedByActive = isOwnedPartStaging(name, activeImportTokens, activeDownloadPartTokens)
            if (!ownedByActive && file.lastModified() <= nowMillis - orphanGraceMs) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    log("Deleted orphaned part staging: $name")
                }
            }
        }
        // Any other hidden `.bak` / unrecognized artifact is preserved (fail closed).
    }

    // A crash can leave identity metadata before the corresponding rename.
    // Once the matching staging binary is absent and the sidecar has aged past
    // the grace period, there is no user data to recover from that metadata.
    for (sidecar in files) {
        if (!sidecar.isFile || Files.isSymbolicLink(sidecar.toPath())) continue
        val name = sidecar.name
        when {
            name.startsWith(".fusion-delete-") && name.endsWith(".bak.meta") -> {
                val token = name.removePrefix(".fusion-delete-").removeSuffix(".bak.meta")
                if (token.length == 32 && token.all { it in '0'..'9' || it in 'a'..'f' } &&
                    token !in ManagedModelDeletionCoordinator.activeStagingTokens &&
                    !File(root, ModelStagingCodec.encodeDeleteStagingName(token)).exists() &&
                    sidecar.lastModified() <= nowMillis - orphanGraceMs
                ) sidecar.delete()
            }
            name.startsWith(".fusion-download-") && name.endsWith(".bak.meta") -> {
                val token = name.removePrefix(".fusion-download-").removeSuffix(".bak.meta")
                if (token.length == 32 && token.all { it in '0'..'9' || it in 'a'..'f' } &&
                    token !in activeDownloadBackupTokens &&
                    !File(root, ModelStagingCodec.encodeDownloadBackupName(token)).exists() &&
                    sidecar.lastModified() <= nowMillis - orphanGraceMs
                ) sidecar.delete()
            }
        }
    }
}

private fun recoverDeleteStaging(
    file: File,
    root: File,
    entry: DeleteStagingEntry,
    catalogRefs: Set<String>,
    failClosedOnCorruptTruth: Boolean,
    nowMillis: Long,
    orphanGraceMs: Long,
    log: (String) -> Unit,
) {
    val originalBasename = entry.originalBasename ?: return
    val target = resolveManagedChild(root, originalBasename)
    if (target == null) {
        log("Delete staging basename unresolvable; preserving: ${file.name}")
        return
    }
    val canonicalTarget = target.canonicalPath

    // CASE 3 / A2: Corrupt/Oversized/Missing catalog truth -> fail closed, preserve.
    if (failClosedOnCorruptTruth) {
        log("Preserving delete staging: catalog truth not Valid")
        return
    }

    if (canonicalTarget in catalogRefs) {
        // CASE 1 / A3: metadata removal did NOT commit. The hidden binary is the
        // only copy; it must be restored to the referenced managed target.
        if (!target.exists()) {
            if (moveStagingAtomic(file, target)) {
                ModelStagingCodec.deleteIdentitySidecar(root, entry.token).delete()
                log("Restored delete staging to referenced target: ${file.name}")
            } else {
                log("Delete staging restore failed; preserving: ${file.name}")
            }
        } else {
            // CASE 5: target already exists while catalog references it. Never
            // overwrite a live target; classify the staging as redundant debt.
            log("Target exists for referenced delete staging; preserved without overwrite: ${file.name}")
        }
    } else {
        // CASE 2: catalog no longer references the original path -> unreferenced
        // cleanup debt. Remove once it has aged beyond the grace window.
        if (file.lastModified() <= nowMillis - orphanGraceMs) {
            if (runCatching { file.delete() }.getOrDefault(false)) {
                ModelStagingCodec.deleteIdentitySidecar(root, entry.token).delete()
                log("Deleted obsolete delete staging: ${file.name}")
            } else {
                log("Failed to delete obsolete delete staging: ${file.name}")
            }
        }
    }
}

private fun recoverDownloadBackup(
    file: File,
    root: File,
    entry: DownloadBackupEntry,
    nowMillis: Long,
    orphanGraceMs: Long,
    log: (String) -> Unit,
) {
    // Built-in download targets are not imported-catalog entries, so recovery
    // is driven by target file existence/validity rather than catalog truth.
    val originalTargetName = entry.originalTargetName ?: return
    val target = resolveManagedChild(root, originalTargetName)
    if (target == null) {
        log("Download backup target unresolvable; preserving: ${file.name}")
        return
    }

    val exists = target.exists()
    val targetIsRegular = target.isFile && !Files.isSymbolicLink(target.toPath())

    if (!exists) {
        // CASE A: replacement target is absent -> the backup may be the only
        // valid binary. Restore it (the move never overwrites an existing target).
        if (moveStagingAtomic(file, target)) {
            ModelStagingCodec.downloadIdentitySidecar(root, entry.token).delete()
            log("Restored download backup to missing target: ${file.name}")
        } else {
            log("Download backup restore failed; preserving: ${file.name}")
        }
    } else if (targetIsRegular) {
        // CASE B: current target won -> the backup is obsolete cleanup debt.
        if (file.lastModified() <= nowMillis - orphanGraceMs) {
            if (runCatching { file.delete() }.getOrDefault(false)) {
                ModelStagingCodec.downloadIdentitySidecar(root, entry.token).delete()
                log("Deleted obsolete download backup: ${file.name}")
            }
        }
    } else {
        // CASE C: target exists but is a symlink / non-file / suspicious.
        // Preserve the backup; never overwrite on suspicion.
        log("Suspicious target for download backup; preserving: ${file.name}")
    }
}

/**
 * Live `.part` ownership by exact trailing-token match. Import and download
 * staging share one filename shape (`.<target>.<token>.part`); the two
 * typed token domains stay separate inputs so neither can silently cover
 * the other's files, and a token protects only the file that embeds it as
 * its exact trailing segment — a loose substring would let an unrelated
 * token preserve foreign staging (e.g. token `tok` must not own
 * `.model.notok.part`).
 */
private fun isOwnedPartStaging(
    name: String,
    activeImportTokens: Set<String>,
    activeDownloadPartTokens: Set<String>,
): Boolean {
    if (activeImportTokens.isEmpty() && activeDownloadPartTokens.isEmpty()) return false
    return (activeImportTokens.asSequence() + activeDownloadPartTokens.asSequence())
        .any { token -> token.isNotEmpty() && name.endsWith(".$token.part") }
}

/** Resolves a direct-child basename to a canonical managed file, or null if it
 * cannot be proven to be a regular, non-symlink direct child of [root]. */
private fun resolveManagedChild(root: File, basename: String): File? = runCatching {
    val candidate = File(root, basename)
    val canonical = candidate.canonicalFile
    if (canonical.parentFile?.canonicalPath != root.canonicalPath) return null
    if (Files.isSymbolicLink(candidate.toPath())) return null
    if (canonical.exists() && !canonical.isFile) return null
    canonical
}.getOrNull()

/**
 * Catalog truth as a set of canonical managed binary paths that are durably
 * referenced by the imported catalog. Uses the typed
 * [FusionModelCatalog.ImportedCatalogLoad] so Corrupt/Oversized/Missing never
 * collapse into an "empty but valid" set.
 */
internal fun truthCanonicalPaths(
    load: FusionModelCatalog.ImportedCatalogLoad,
    root: File,
): Set<String> = when (load) {
    is FusionModelCatalog.ImportedCatalogLoad.Valid ->
        buildSet {
            for (model in load.models) {
                val raw = model.localPath
                if (raw.isNullOrBlank()) continue
                val target = ManagedModelPathPolicy.resolveManagedTarget(root, raw)
                if (target != null) add(target.canonicalPath)
            }
        }
    else -> emptySet()
}

/** Atomic-or-plain (never overwriting) move used by recovery restores. */
private fun moveStagingAtomic(from: File, to: File): Boolean = try {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    true
} catch (_: Exception) {
    runCatching {
        Files.move(from.toPath(), to.toPath())
        true
    }.getOrDefault(false)
}
