package com.projectnuke.fusion.modelzoo

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.projectnuke.fusion.util.ManagedModelPathPolicy

private const val DEFAULT_BUFFER_SIZE = 1024 * 1024

internal enum class ModelImportFailure {
    TOO_LARGE,
    STORAGE_FULL,
    SOURCE_UNAVAILABLE,
    CANCELLED,
    INVALID_MODEL,
    ADOPTION_FAILED,
    METADATA_FAILED,
    URI_PERMISSION_LOST,
}

internal sealed interface ModelImportResult {
    data class Success(val file: File, val bytes: Long, val token: String) : ModelImportResult
    data class Failure(val kind: ModelImportFailure, val token: String) : ModelImportResult
}

internal data class ModelImportRequest(
    val token: String = UUID.randomUUID().toString(),
    val sourceIdentity: String,
    val displayName: String,
)

internal fun interface LiteRtLmValidator {
    fun validate(file: File): Boolean
}

internal fun defaultValidator(): LiteRtLmValidator = LiteRtLmValidator { file ->
    file.length() >= 1024L * 1024L && LiteRtLmPackageValidator.validate(file).isValid
}

private enum class ImportFinalizationResult {
    COMMITTED,
    INVALID_MODEL,
    ADOPTION_FAILED,
    METADATA_FAILED,
}

/** The source claim remains present until the caller-owned operation terminates. */
internal data class ActiveModelImport(
    val token: String,
)

/**
 * Process-owned, single-adoption coordinator for imported local models.
 *
 * Ownership model:
 * - Each [sourceIdentity] can have at most one active import at a time.
 * - A second import for the same [sourceIdentity] is rejected (even with the same token).
 * - The registry is process-scoped, but each copy is owned by its calling coroutine.
 * - Stale completions are impossible because ownership is decided by atomic putIfAbsent.
 * - Abandoned staging files are cleaned up on startup/foreground via [cleanupAbandoned].
 */
internal class ModelImportCoordinator(
    private val modelDirectory: File,
    private val openSource: (String) -> InputStream?,
    private val sourceLength: (String) -> Long? = { null },
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val maximumBytes: Long = 12L * 1024L * 1024L * 1024L,
    private val reserveBytes: Long = 512L * 1024L * 1024L,
    private val validator: LiteRtLmValidator = defaultValidator(),
) {
    /** Maps sourceIdentity -> active import operation. Only one import per sourceIdentity at a time. */
    private val activeImports = ConcurrentHashMap<String, ActiveModelImport>()

    /** Single-flight gate for asynchronous abandoned-staging recovery. */
    private val cleanupInFlight = AtomicBoolean(false)

    suspend fun import(
        request: ModelImportRequest,
        onProgress: suspend (Int) -> Unit = {},
        commitMetadata: suspend (File) -> Boolean = { true },
    ): ModelImportResult = withContext(Dispatchers.IO) {
        val candidate = ActiveModelImport(request.token)
        val existing = activeImports.putIfAbsent(request.sourceIdentity, candidate)
        if (existing != null) {
            return@withContext ModelImportResult.Failure(ModelImportFailure.ADOPTION_FAILED, request.token)
        }
        try {
            runImportInternal(request, onProgress, commitMetadata)
        } finally {
            activeImports.remove(request.sourceIdentity, candidate)
        }
    }

    private suspend fun runImportInternal(
        request: ModelImportRequest,
        onProgress: suspend (Int) -> Unit,
        commitMetadata: suspend (File) -> Boolean,
    ): ModelImportResult = withContext(Dispatchers.IO) {
        val root = modelDirectory.canonicalFile
        var part: File? = null
        var adopted: File? = null
        var copied = 0L
        var operationCommitted = false
        try {
            if (!(root.exists() || root.mkdirs()) || !root.isDirectory) {
                return@withContext ModelImportResult.Failure(ModelImportFailure.STORAGE_FULL, request.token)
            }
            val declared = sourceLength(request.sourceIdentity)
            val progressTotal = declared?.takeIf { it > 0L }
            if (declared != null && (declared < 0L || declared > maximumBytes || !hasSpace(root, declared))) {
                return@withContext ModelImportResult.Failure(
                    if (declared > maximumBytes) ModelImportFailure.TOO_LARGE else ModelImportFailure.STORAGE_FULL,
                    request.token,
                )
            }
            val safeName = sanitize(request.displayName)
            val target = File(root, "${UUID.randomUUID()}_$safeName")
            val staging = File(root, ".${target.name}.${request.token}.part")
            if (target.name.toByteArray(Charsets.UTF_8).size > MAX_FILESYSTEM_COMPONENT_BYTES ||
                staging.name.toByteArray(Charsets.UTF_8).size > MAX_FILESYSTEM_COMPONENT_BYTES
            ) {
                return@withContext ModelImportResult.Failure(ModelImportFailure.ADOPTION_FAILED, request.token)
            }
            if (!isDirectChild(root, target) || !isDirectChild(root, staging)) {
                return@withContext ModelImportResult.Failure(ModelImportFailure.ADOPTION_FAILED, request.token)
            }
            part = staging
            val input = openSource(request.sourceIdentity)
                ?: return@withContext ModelImportResult.Failure(ModelImportFailure.SOURCE_UNAVAILABLE, request.token)
            copied = 0L
            input.use { source ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgress = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        if (copied > maximumBytes) {
                            return@withContext ModelImportResult.Failure(ModelImportFailure.TOO_LARGE, request.token)
                        }
                        if (!hasSpace(root, read.toLong())) {
                            return@withContext ModelImportResult.Failure(ModelImportFailure.STORAGE_FULL, request.token)
                        }
                        output.write(buffer, 0, read)
                        val prog = if (progressTotal != null) {
                            (copied * 100L / progressTotal).toInt().coerceIn(0, 99)
                        } else -1
                        if (prog >= 0 && prog != lastProgress) {
                            lastProgress = prog
                            onProgress(prog)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (progressTotal != null) onProgress(99)
            if (!validator.validate(staging)) {
                return@withContext ModelImportResult.Failure(ModelImportFailure.INVALID_MODEL, request.token)
            }
            currentCoroutineContext().ensureActive()
            val finalization = withContext(NonCancellable) {
                try {
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    try {
                        Files.move(staging.toPath(), target.toPath())
                    } catch (_: Exception) {
                        return@withContext ImportFinalizationResult.ADOPTION_FAILED
                    }
                }
                if (!target.isFile || target.length() != copied || !validator.validate(target)) {
                    target.delete()
                    return@withContext ImportFinalizationResult.INVALID_MODEL
                }
                adopted = target
                if (!commitMetadata(target)) {
                    return@withContext ImportFinalizationResult.METADATA_FAILED
                }
                // The file and its metadata are now one committed terminal state.
                // Cancellation remains a separate caller contract and is observed
                // immediately after this NonCancellable transaction returns.
                operationCommitted = true
                ImportFinalizationResult.COMMITTED
            }
            when (finalization) {
                ImportFinalizationResult.INVALID_MODEL ->
                    return@withContext ModelImportResult.Failure(ModelImportFailure.INVALID_MODEL, request.token)
                ImportFinalizationResult.ADOPTION_FAILED ->
                    return@withContext ModelImportResult.Failure(ModelImportFailure.ADOPTION_FAILED, request.token)
                ImportFinalizationResult.METADATA_FAILED ->
                    return@withContext ModelImportResult.Failure(ModelImportFailure.METADATA_FAILED, request.token)
                ImportFinalizationResult.COMMITTED -> Unit
            }
            // The file and catalog metadata are already durable. Progress is
            // best-effort and must not turn that committed state into a
            // cancellation or failure result.
            withContext(NonCancellable) {
                try {
                    onProgress(100)
                } catch (_: CancellationException) {
                    // Terminal progress is best-effort after the commit boundary.
                } catch (_: Exception) {
                    // Terminal progress must not downgrade a committed import.
                }
            }
            ModelImportResult.Success(adopted!!, copied, request.token)
        } catch (e: CancellationException) {
            if (operationCommitted) {
                ModelImportResult.Success(checkNotNull(adopted), copied, request.token)
            } else {
                // finally performs rollback while the durable transaction did
                // not commit. Cancellation remains truthful before commit.
                throw e
            }
        } catch (_: SecurityException) {
            ModelImportResult.Failure(ModelImportFailure.URI_PERMISSION_LOST, request.token)
        } catch (e: java.io.IOException) {
            if (e.message?.contains("permission", true) == true ||
                e.message?.contains("Permission denied", true) == true) {
                ModelImportResult.Failure(ModelImportFailure.URI_PERMISSION_LOST, request.token)
            } else {
                ModelImportResult.Failure(ModelImportFailure.ADOPTION_FAILED, request.token)
            }
        } catch (_: Exception) {
            ModelImportResult.Failure(ModelImportFailure.ADOPTION_FAILED, request.token)
        } finally {
            withContext(NonCancellable) {
                if (!operationCommitted) {
                    part?.takeIf { it.exists() }?.let { residual ->
                        val deleted = try {
                            residual.delete()
                        } catch (_: Exception) {
                            false
                        }
                        if (!deleted && residual.exists()) {
                            Log.e("FusionModel", "Residual model import staging orphan: ${residual.absolutePath}")
                        }
                    }
                    adopted?.takeIf { it.exists() }?.let { residual ->
                        val deleted = try {
                            residual.delete()
                        } catch (_: Exception) {
                            false
                        }
                        if (!deleted && residual.exists()) {
                            Log.e("FusionModel", "Residual copied model orphan after metadata failure: ${residual.absolutePath}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Cleans up abandoned process-death model-storage artifacts in a single,
     * catalog-aware pass. Call from app startup/foreground (e.g. on resume).
     *
     * Recovery is reconciled against durable Fusion imported-catalog truth so
     * that a delete-staging backup is only removed when the catalog no longer
     * references its original managed target, and a download replacement backup
     * is only dropped when its target is present and valid. Corrupt/Oversized
     * catalog truth fails closed. Active in-process operations are excluded via
     * the process-scoped token registries on [ManagedModelDeletionCoordinator]
     * and [ModelDownloadCoordinator].
     */
    fun cleanupAbandoned(context: Context) {
        val activeTokens = activeImportTokensSnapshot()
        val catalogLoad = FusionModelCatalog.loadImportedResult(context)
        recoverModelStorageArtifacts(
            modelDirectory = modelDirectory,
            catalogLoad = catalogLoad,
            nowMillis = System.currentTimeMillis(),
            activeImportTokens = activeTokens,
            activeDownloadPartTokens = activeDownloadPartTokens.toSet(),
            log = { Log.w("FusionModel", it) },
        )
    }

    /**
     * Coordinator-owned asynchronous cleanup request for foreground resume.
     * The recovery body runs on [Dispatchers.IO] (never on the lifecycle
     * thread); exactly one recovery pass runs at a time and a resume that
     * arrives while a pass is active is refused (returns false) instead of
     * queueing unbounded work, so catalog/filesystem reconciliation never
     * runs concurrently. Ownership is released in `finally`, hence after
     * success, failure or cancellation alike, and a later resume may retry.
     * No GlobalScope: the caller supplies its own scope.
     */
    fun requestCleanupAbandoned(context: Context, scope: CoroutineScope): Boolean =
        requestCleanup(scope) {
            withContext(Dispatchers.IO) { cleanupAbandoned(context) }
        }

    internal fun requestCleanup(
        scope: CoroutineScope,
        recoveryBody: suspend () -> Unit,
    ): Boolean {
        if (!cleanupInFlight.compareAndSet(false, true)) return false
        // The ownership claim is claimed before the coroutine starts, so release
        // it from Job completion rather than from inside the body. A scope whose
        // job is already canceled, or one canceled between launch and dispatch,
        // may never enter the body at all: a body-local `finally` would then
        // never run and the flag would stay occupied forever, permanently
        // refusing every later recovery request. invokeOnCompletion fires for
        // completion, failure and cancellation alike, and runs exactly once, so
        // the release can never overlap a still-active recovery body.
        val job = scope.launch { recoveryBody() }
        job.invokeOnCompletion { cleanupInFlight.set(false) }
        return true
    }

    internal fun activeImportTokensSnapshot(): Set<String> =
        activeImports.values.map { it.token }.toSet()


    /**
     * Checks if there is enough space for [incoming] bytes.
     * Reserve is added exactly once per check.
     */
    private fun hasSpace(root: File, incoming: Long): Boolean =
        usableSpace(root) > incoming + reserveBytes

    private fun sanitize(value: String): String {
        val cleaned = value.replace(Regex("[\\u0000-\\u001F\\u007F\\\\/:*?\"<>|]"), "_")
        val extension = cleaned.takeIf { it.endsWith(".litertlm", ignoreCase = true) }
            ?.takeLast(".litertlm".length)
            .orEmpty()
        val stem = if (extension.isEmpty()) cleaned else cleaned.dropLast(extension.length)
        val maxStemBytes = MAX_IMPORTED_DISPLAY_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
        val bounded = StringBuilder()
        var byteCount = 0
        var offset = 0
        while (offset < stem.length) {
            val codePoint = stem.codePointAt(offset)
            val next = String(Character.toChars(codePoint))
            val bytes = next.toByteArray(Charsets.UTF_8).size
            if (byteCount + bytes > maxStemBytes) break
            bounded.append(next)
            byteCount += bytes
            offset += Character.charCount(codePoint)
        }
        bounded.append(extension)
        return bounded.toString().ifBlank { "custom_model.litertlm" }
    }

    private companion object {
        // target name adds a 36-byte UUID and underscore; `.target.<UUID>.part`
        // adds 43 bytes. Keeping the display basename at 175 bytes guarantees
        // both target and import staging components stay below 255 UTF-8 bytes.
        const val MAX_IMPORTED_DISPLAY_NAME_BYTES = 175
        const val MAX_FILESYSTEM_COMPONENT_BYTES = 255
    }

    private fun isDirectChild(root: File, child: File): Boolean =
        child.canonicalFile.parentFile?.canonicalPath == root.canonicalPath &&
            !Files.isSymbolicLink(child.toPath())
}

internal object ModelImportCoordinatorRegistry {
    private val coordinators = ConcurrentHashMap<String, ModelImportCoordinator>()

    fun forContext(context: Context): ModelImportCoordinator {
        val app = context.applicationContext
        val key = app.filesDir.canonicalPath
        return coordinators.computeIfAbsent(key) {
            ModelImportCoordinator(
                modelDirectory = ManagedModelPathPolicy.getModelDirectory(app),
                openSource = { source -> app.contentResolver.openInputStream(Uri.parse(source)) },
                sourceLength = { source ->
                    try {
                        app.contentResolver.query(
                            Uri.parse(source), null, null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                                    cursor.getLong(sizeIndex)
                                } else null
                            } else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                },
            )
        }
    }
}
