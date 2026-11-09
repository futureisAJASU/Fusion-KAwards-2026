package com.projectnuke.fusion.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class AttachmentImportBudget(
    val perFileBytes: Long = 64L * 1024L * 1024L,
    val perBatchBytes: Long = 128L * 1024L * 1024L,
    val reserveBytes: Long = 128L * 1024L * 1024L,
)

internal enum class AttachmentImportFailure {
    FILE_TOO_LARGE,
    BATCH_TOO_LARGE,
    STORAGE_FULL,
    SOURCE_UNAVAILABLE,
    INVALID_TARGET,
    IO,
}

internal sealed interface AttachmentImportResult {
    data class Success(val file: File, val bytes: Long) : AttachmentImportResult
    data class Failure(val kind: AttachmentImportFailure) : AttachmentImportResult
}

internal fun interface AtomicAttachmentAdopter {
    fun adopt(source: Path, target: Path)
}

internal class AttachmentImportCoordinator(
    private val attachmentRoot: File,
    private val inputFactory: (String) -> InputStream?,
    private val budget: AttachmentImportBudget = AttachmentImportBudget(),
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val atomicAdopter: AtomicAttachmentAdopter = AtomicAttachmentAdopter { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    },
    private val registerPending: (File) -> String? = AttachmentStorageManager::registerPendingAttachment,
    private val unregisterPending: (String?) -> Unit = AttachmentStorageManager::unregisterPendingAttachment,
) {
    private var batchBytes: Long = 0L

    constructor(
        context: Context,
        budget: AttachmentImportBudget = AttachmentImportBudget(),
    ) : this(
        attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
        inputFactory = { source -> context.contentResolver.openInputStream(Uri.parse(source)) },
        budget = budget,
    )

    suspend fun copy(
        source: String,
        displayName: String,
        declaredLength: Long?,
    ): AttachmentImportResult = withContext(Dispatchers.IO) outer@ {
        val knownLength = declaredLength?.takeIf { it >= 0L }
        if (knownLength != null && knownLength > budget.perFileBytes) {
            return@outer AttachmentImportResult.Failure(AttachmentImportFailure.FILE_TOO_LARGE)
        }
        if (knownLength != null && batchBytes + knownLength > budget.perBatchBytes) {
            return@outer AttachmentImportResult.Failure(AttachmentImportFailure.BATCH_TOO_LARGE)
        }
        val preflightBytes = knownLength ?: minOf(
            budget.perFileBytes,
            (budget.perBatchBytes - batchBytes).coerceAtLeast(0L),
        )
        if (!hasSpace(preflightBytes)) {
            return@outer AttachmentImportResult.Failure(AttachmentImportFailure.STORAGE_FULL)
        }

        val rootCanonical = runCatching { attachmentRoot.canonicalFile }.getOrNull()
            ?: return@outer AttachmentImportResult.Failure(AttachmentImportFailure.INVALID_TARGET)
        if (!(rootCanonical.exists() || rootCanonical.mkdirs()) || !rootCanonical.isDirectory) {
            return@outer AttachmentImportResult.Failure(AttachmentImportFailure.INVALID_TARGET)
        }

        val safeName = displayName
            .replace(Regex("""[\u0000-\u001F\u007F\\/:*?"<>|]"""), "_")
            .take(48)
            .ifBlank { "attachment" }
        val finalFile = File(rootCanonical, "${UUID.randomUUID()}_$safeName")
        val partFile = File(rootCanonical, ".${finalFile.name}.part")
        if (!isDirectChild(rootCanonical, finalFile) || !isDirectChild(rootCanonical, partFile)) {
            return@outer AttachmentImportResult.Failure(AttachmentImportFailure.INVALID_TARGET)
        }

        var registeredPath: String? = null
        var movedToTarget = false
        try {
            val input = inputFactory(source)
                ?: return@outer AttachmentImportResult.Failure(
                    AttachmentImportFailure.SOURCE_UNAVAILABLE
                )
            var copied = 0L
            input.use { source ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        if (copied > budget.perFileBytes) {
                            return@outer AttachmentImportResult.Failure(
                                AttachmentImportFailure.FILE_TOO_LARGE
                            )
                        }
                        if (batchBytes + copied > budget.perBatchBytes) {
                            return@outer AttachmentImportResult.Failure(
                                AttachmentImportFailure.BATCH_TOO_LARGE
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (knownLength != null && copied != knownLength) {
                return@outer AttachmentImportResult.Failure(AttachmentImportFailure.IO)
            }
            if (copied <= 0L || !partFile.isFile) {
                return@outer AttachmentImportResult.Failure(AttachmentImportFailure.IO)
            }
            currentCoroutineContext().ensureActive()

            return@outer withContext(NonCancellable) adopt@ {
                try {
                    if (finalFile.exists()) {
                        return@adopt AttachmentImportResult.Failure(
                            AttachmentImportFailure.INVALID_TARGET,
                        )
                    }
                    val sourcePath = partFile.toPath()
                    val targetPath = finalFile.toPath()
                    try {
                        atomicAdopter.adopt(sourcePath, targetPath)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        // The fallback is deliberately same-directory and
                        // has no replace option. Arbitrary filesystem errors
                        // must not be reinterpreted as unsupported atomic move.
                        Files.move(sourcePath, targetPath)
                    } catch (_: UnsupportedOperationException) {
                        // Some providers surface unsupported atomic moves as
                        // UnsupportedOperationException instead.
                        Files.move(sourcePath, targetPath)
                    }
                    movedToTarget = true
                } catch (_: Exception) {
                    return@adopt AttachmentImportResult.Failure(
                        AttachmentImportFailure.INVALID_TARGET
                    )
                }
                if (!finalFile.isFile || finalFile.length() != copied) {
                    finalFile.delete()
                    return@adopt AttachmentImportResult.Failure(AttachmentImportFailure.IO)
                }
                registeredPath = registerPending(finalFile)
                if (registeredPath == null) {
                    finalFile.delete()
                    return@adopt AttachmentImportResult.Failure(
                        AttachmentImportFailure.INVALID_TARGET
                    )
                }
                batchBytes += copied
                AttachmentImportResult.Success(finalFile, copied)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@outer AttachmentImportResult.Failure(AttachmentImportFailure.IO)
        } finally {
            if (partFile.exists()) partFile.delete()
            if (registeredPath == null && movedToTarget && finalFile.exists()) finalFile.delete()
            if (registeredPath != null && !finalFile.exists()) unregisterPending(registeredPath)
        }
    }

    internal fun copiedBatchBytes(): Long = batchBytes

    private fun hasSpace(incomingBytes: Long): Boolean {
        val free = usableSpace(attachmentRoot)
        return free > 0L && free >= incomingBytes + budget.reserveBytes
    }

    private fun isDirectChild(root: File, child: File): Boolean {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val childCanonical = runCatching { child.canonicalFile }.getOrNull() ?: return false
        if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)) return false
        return childCanonical.parentFile?.canonicalPath == rootPath
    }
}
