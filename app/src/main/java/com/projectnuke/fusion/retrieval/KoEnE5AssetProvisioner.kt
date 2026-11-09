package com.projectnuke.fusion.retrieval

import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Offline-first provisioning of the pinned KoEn-E5 assets.
 *
 * The competition build works after installation with no manual file pushes
 * and no network: APK assets (`koen-e5/model_qint8_arm64.onnx`,
 * `koen-e5/tokenizer.json`, hash-verified at build time by
 * `verifyKoEnE5Assets`) are copied once into app-private storage, because
 * ONNX Runtime opens the model from a verified file path.
 *
 * Guarantees: an already-present copy whose SHA-256 matches the pin is
 * reused untouched; otherwise the asset is streamed to a temp sibling,
 * fsynced, hash-verified, then atomically renamed. Any verification failure
 * deletes the temp file and fails closed — the caller keeps deterministic
 * rule-only Evidence behavior.
 */
object KoEnE5AssetProvisioner {

    const val ASSET_MODEL_PATH = "koen-e5/model_qint8_arm64.onnx"
    const val ASSET_TOKENIZER_PATH = "koen-e5/tokenizer.json"
    const val FILE_MODEL_NAME = "model_qint8_arm64.onnx"
    const val FILE_TOKENIZER_NAME = "tokenizer.json"

    data class Provisioned(
        val modelFile: File,
        val tokenizerFile: File,
    )

    sealed interface ProvisionResult {
        data class Ready(val files: Provisioned) : ProvisionResult
        data class Failed(val reason: String) : ProvisionResult
    }

    fun provision(assetManager: AssetManager, privateDir: File): ProvisionResult {
        if (!privateDir.isDirectory && !privateDir.mkdirs()) {
            return ProvisionResult.Failed("PRIVATE_DIR_UNAVAILABLE")
        }
        val model = copyVerified(
            assetManager = assetManager,
            assetPath = ASSET_MODEL_PATH,
            dest = File(privateDir, FILE_MODEL_NAME),
            expectedSha256 = KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SHA256,
            expectedSize = KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SIZE_BYTES,
        ) ?: return ProvisionResult.Failed("MODEL_PROVISION_FAILED")
        val tokenizer = copyVerified(
            assetManager = assetManager,
            assetPath = ASSET_TOKENIZER_PATH,
            dest = File(privateDir, FILE_TOKENIZER_NAME),
            expectedSha256 = KoEnE5ModelPolicy.TOKENIZER_JSON_SHA256,
            expectedSize = KoEnE5ModelPolicy.TOKENIZER_JSON_SIZE_BYTES,
        ) ?: return ProvisionResult.Failed("TOKENIZER_PROVISION_FAILED")
        return ProvisionResult.Ready(Provisioned(model, tokenizer))
    }

    private fun copyVerified(
        assetManager: AssetManager,
        assetPath: String,
        dest: File,
        expectedSha256: String,
        expectedSize: Long,
    ): File? {
        return copyVerifiedFromStream(
            dest = dest,
            expectedSha256 = expectedSha256,
            expectedSize = expectedSize,
            openInput = { assetManager.open(assetPath) },
        )
    }

    internal fun copyVerifiedFromStream(
        dest: File,
        expectedSha256: String,
        expectedSize: Long,
        openInput: () -> InputStream,
        stagingFileFactory: (File, String) -> File = { parent, baseName ->
            File.createTempFile("$baseName.", ".tmp", parent)
        },
        verify: (File, String, Long) -> Boolean = { file, sha256, size ->
            isValid(file, sha256, size)
        },
        openOutput: (File) -> FileOutputStream = ::FileOutputStream,
        syncOutput: (FileOutputStream) -> Unit = { output -> output.fd.sync() },
        move: (File, File) -> Unit = { source, destination ->
            moveStagedAtomically(source, destination)
        },
    ): File? {
        if (expectedSize < 0L) return null
        if (verify(dest, expectedSha256, expectedSize)) return dest
        val parent = dest.parentFile ?: return null
        val ownedTemp = runCatching { stagingFileFactory(parent, dest.name) }.getOrNull() ?: return null
        try {
            var copiedWithinBound = true
            openInput().use { input ->
                openOutput(ownedTemp).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > expectedSize) {
                            copiedWithinBound = false
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    if (copiedWithinBound) syncOutput(output)
                }
            }
            if (!copiedWithinBound || !verify(ownedTemp, expectedSha256, expectedSize)) return null
            return DestinationAdoptionLocks.withLock(dest) {
                if (verify(dest, expectedSha256, expectedSize)) return@withLock dest

                try {
                    move(ownedTemp, dest)
                } catch (_: Exception) {
                    // A competing installer may have won outside this lock (for example,
                    // another process). Reuse it only after full size and digest validation.
                    return@withLock if (verify(dest, expectedSha256, expectedSize)) dest else null
                }

                if (!verify(dest, expectedSha256, expectedSize)) {
                    runCatching {
                        if (!verify(dest, expectedSha256, expectedSize)) dest.delete()
                    }
                    return@withLock null
                }
                dest
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { if (ownedTemp.exists()) ownedTemp.delete() }
        }
    }

    internal fun moveStagedAtomically(
        source: File,
        destination: File,
        atomicMove: (File, File) -> Unit = { from, to ->
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        },
        replacementMove: (File, File) -> Unit = { from, to ->
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        },
    ) {
        try {
            atomicMove(source, destination)
        } catch (_: AtomicMoveNotSupportedException) {
            replacementMove(source, destination)
        }
    }

    internal fun isValid(file: File, expectedSha256: String, expectedSize: Long): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
        return sha256Hex(file).equals(expectedSha256, ignoreCase = true)
    }

    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Serializes only final adoption for the same canonical destination. */
private object DestinationAdoptionLocks {
    private class Entry {
        val lock = ReentrantLock()
        var references = 0
    }

    private val entries = ConcurrentHashMap<Path, Entry>()

    fun <T> withLock(destination: File, action: () -> T): T {
        val identity = destination.canonicalFile.toPath().normalize()
        val entry = entries.compute(identity) { _, current ->
            (current ?: Entry()).also { it.references++ }
        } ?: error("Unable to acquire destination adoption lock")

        entry.lock.lock()
        try {
            return action()
        } finally {
            entry.lock.unlock()
            entries.computeIfPresent(identity) { _, current ->
                if (current !== entry) current
                else {
                    current.references--
                    if (current.references == 0) null else current
                }
            }
        }
    }
}

/**
 * Asset provider over provisioned files with cryptographic identity, not just
 * size windows: a file is Available only when its SHA-256 equals the pin in
 * [KoEnE5ModelPolicy]. Production wiring must use this (or equivalent).
 */
class VerifiedFileKoEnE5AssetProvider(
    private val modelFile: File,
    private val tokenizerFile: File,
) : KoEnE5AssetProvider {

    override fun modelAsset(): KoEnE5AssetState =
        if (KoEnE5AssetProvisioner.isValid(
                modelFile,
                KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SHA256,
                KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SIZE_BYTES,
            )
        ) {
            KoEnE5AssetState.Available(modelFile, modelFile.length())
        } else {
            KoEnE5AssetState.Missing("MODEL_HASH_MISMATCH")
        }

    override fun tokenizerAsset(): KoEnE5AssetState =
        if (KoEnE5AssetProvisioner.isValid(
                tokenizerFile,
                KoEnE5ModelPolicy.TOKENIZER_JSON_SHA256,
                KoEnE5ModelPolicy.TOKENIZER_JSON_SIZE_BYTES,
            )
        ) {
            KoEnE5AssetState.Available(tokenizerFile, tokenizerFile.length())
        } else {
            KoEnE5AssetState.Missing("TOKENIZER_HASH_MISMATCH")
        }
}
