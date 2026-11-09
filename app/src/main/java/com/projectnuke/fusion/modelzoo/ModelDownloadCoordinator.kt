package com.projectnuke.fusion.modelzoo

import android.system.ErrnoException
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ModelDownloadFailure {
    HTTP,
    AUTHORIZATION_REQUIRED,
    REDIRECT,
    TIMEOUT,
    TOO_LARGE,
    STORAGE_FULL,
    INVALID_PAYLOAD,
    CHECKSUM_MISMATCH,
    ATOMIC_ADOPTION,
    NETWORK,
}

internal sealed interface ModelDownloadResult {
    data class Success(val file: File, val bytes: Long) : ModelDownloadResult
    data class Failure(
        val kind: ModelDownloadFailure,
        val httpStatus: Int? = null,
    ) : ModelDownloadResult
}

/** Narrow evidence-based classifier for storage exhaustion during staging I/O. */
internal fun isStorageExhaustionFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    repeat(MAX_STORAGE_FAILURE_CAUSE_DEPTH) {
        val candidate = current ?: return false
        if (candidate is ErrnoException && candidate.errno == OsConstants.ENOSPC) return true

        val evidence = when (candidate) {
            is FileSystemException -> listOfNotNull(candidate.reason, candidate.message)
            is IOException -> listOfNotNull(candidate.message)
            else -> emptyList()
        }
        if (evidence.any(::containsStorageExhaustionEvidence)) return true
        current = candidate.cause
    }
    return false
}

private fun containsStorageExhaustionEvidence(text: String): Boolean {
    val normalized = text.lowercase().replace(Regex("\\s+"), " ")
    return "enospc" in normalized || "no space left on device" in normalized
}

private const val MAX_STORAGE_FAILURE_CAUSE_DEPTH = 8

internal fun interface ModelConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

internal fun interface AtomicModelAdopter {
    fun adopt(source: Path, target: Path)
}

internal class ModelDownloadCoordinator(
    private val connectionFactory: ModelConnectionFactory = ModelConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    },
    private val stagingOutputFactory: (File) -> FileOutputStream = { FileOutputStream(it) },
    private val atomicAdopter: AtomicModelAdopter = AtomicModelAdopter { source, target ->
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maximumBytes: Long = 12L * 1024L * 1024L * 1024L,
    private val reserveBytes: Long = 512L * 1024L * 1024L,
    private val minimumPlausibleBytes: Long = 1024L * 1024L,
    private val progressIntervalMs: Long = 500L,
    private val payloadValidator: (File) -> Boolean = {
        LiteRtLmPackageValidator.validate(it).isValid
    },
) {
    suspend fun download(
        sourceUrl: String,
        target: File,
        onProgress: suspend (Int) -> Unit = {},
    ): ModelDownloadResult = downloadInternal(sourceUrl, target, expectedSha256 = null, onProgress = onProgress)

    suspend fun downloadWithExpectedSha256(
        sourceUrl: String,
        target: File,
        expectedSha256: String,
        onProgress: suspend (Int) -> Unit = {},
    ): ModelDownloadResult = downloadInternal(sourceUrl, target, expectedSha256, onProgress)

    private suspend fun downloadInternal(
        sourceUrl: String,
        target: File,
        expectedSha256: String?,
        onProgress: suspend (Int) -> Unit,
    ): ModelDownloadResult {
        val key = target.canonicalPath
        val entry = targetLocks.compute(key) { _, existing ->
            (existing ?: TargetLockEntry()).also { it.users.incrementAndGet() }
        }!!
        return try {
            entry.mutex.withLock {
                downloadLocked(sourceUrl, target, expectedSha256, onProgress)
            }
        } finally {
            // Acquire and release both happen through the map's per-key
            // atomic operation. This prevents a third caller from creating
            // a second mutex while an existing waiter still owns the entry.
            targetLocks.computeIfPresent(key) { _, current ->
                if (current !== entry) {
                    current
                } else if (entry.users.decrementAndGet() == 0) {
                    null
                } else {
                    current
                }
            }
        }
    }

    internal fun targetLockRegistrySizeForTests(): Int = targetLocks.size

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun downloadLocked(
        sourceUrl: String,
        target: File,
        expectedSha256: String?,
        onProgress: suspend (Int) -> Unit,
    ): ModelDownloadResult {
        val expectedDigest = expectedSha256?.let(::decodeExpectedSha256)
        if (expectedSha256 != null && expectedDigest == null) {
            return ModelDownloadResult.Failure(ModelDownloadFailure.CHECKSUM_MISMATCH)
        }
        val parent = target.parentFile ?: return ModelDownloadResult.Failure(
            ModelDownloadFailure.ATOMIC_ADOPTION
        )
        if (!(parent.exists() || parent.mkdirs()) || !parent.isDirectory) {
            return ModelDownloadResult.Failure(ModelDownloadFailure.STORAGE_FULL)
        }

        val partToken = UUID.randomUUID().toString()
        val part = File(parent, ".${target.name}.$partToken.part")
        if (target.name.toByteArray(Charsets.UTF_8).size > MAX_FILESYSTEM_COMPONENT_BYTES ||
            part.name.toByteArray(Charsets.UTF_8).size > MAX_FILESYSTEM_COMPONENT_BYTES
        ) {
            return ModelDownloadResult.Failure(ModelDownloadFailure.ATOMIC_ADOPTION)
        }
        // Claim `.part` ownership BEFORE the staging file is created or
        // opened, so concurrent recovery can never mistake the live part
        // for an abandoned process-death artifact in the
        // network-write/validation/adoption window. A real process death
        // naturally loses this registry, so genuinely old parts are still
        // cleaned after grace. Released in the NonCancellable finally after
        // the part is adopted or deleted.
        activeDownloadPartTokens.add(partToken)
        val backupToken = ModelStagingCodec.randomHexToken()
        val backup = File(parent, ModelStagingCodec.encodeDownloadBackupName(backupToken))
        val backupIdentity = ModelStagingCodec.downloadIdentitySidecar(parent, backupToken)
        try {
            if (part.exists()) part.delete()
        } catch (_: Exception) {
            // Stale staging cleanup is best-effort; fatal errors still propagate.
        }

        var connection: HttpURLConnection? = null
        var hadPrevious = false
        var previousMoved = false
        var targetAdopted = false
        var operationCommitted = false
        var copied = 0L
        val requestContext = currentCoroutineContext()
        val cancellationConnection = AtomicReference<HttpURLConnection?>(null)
        // Completion is too late for a blocked read; close the current socket
        // at cancellation entry, including connect/redirect response waits.
        val cancellationHandle = requestContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) {
                try { cancellationConnection.get()?.disconnect() } catch (_: Exception) { /* finally retries cleanup */ }
            }
        }
        try {
            val connected = openFinalConnection(sourceUrl) { candidate ->
                cancellationConnection.set(candidate)
                requestContext.ensureActive()
            }
            if (connected is OpenResult.Failure) {
                return connected.result
            }
            connection = (connected as OpenResult.Ready).connection

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                readBoundedErrorBody(connection)
                return ModelDownloadResult.Failure(
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        status == HttpURLConnection.HTTP_FORBIDDEN
                    ) ModelDownloadFailure.AUTHORIZATION_REQUIRED else ModelDownloadFailure.HTTP,
                    status,
                )
            }

            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.INVALID_PAYLOAD, status)
            }

            val declaredLength = connection.contentLengthLong
            if (declaredLength > maximumBytes) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.TOO_LARGE, status)
            }
            val availableAtStart = usableSpace(parent)
            val spaceBudget = try {
                Math.subtractExact(availableAtStart, reserveBytes)
            } catch (_: ArithmeticException) {
                null
            }
            if (availableAtStart <= reserveBytes || spaceBudget == null || spaceBudget < 0L) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.STORAGE_FULL, status)
            }
            if (declaredLength > 0L && declaredLength > spaceBudget) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.STORAGE_FULL, status)
            }

            var lastPercent = -1
            var lastProgressAt = nowMillis()
            val header = ByteArray(PAYLOAD_SNIFF_BYTES)
            var headerCount = 0
            val digest = expectedDigest?.let { MessageDigest.getInstance("SHA-256") }

            BufferedInputStream(connection.inputStream).use { input ->
                stagingOutputFactory(part).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val projected = try {
                            Math.addExact(copied, read.toLong())
                        } catch (_: ArithmeticException) {
                            null
                        }
                        if (projected == null || projected > maximumBytes) {
                            return ModelDownloadResult.Failure(ModelDownloadFailure.TOO_LARGE, status)
                        }
                        if (declaredLength > 0L && projected > declaredLength) {
                            return ModelDownloadResult.Failure(ModelDownloadFailure.INVALID_PAYLOAD, status)
                        }
                        if (projected > spaceBudget) {
                            return ModelDownloadResult.Failure(ModelDownloadFailure.STORAGE_FULL, status)
                        }
                        if (headerCount < header.size) {
                            val headerRead = minOf(read, header.size - headerCount)
                            buffer.copyInto(header, headerCount, 0, headerRead)
                            headerCount += headerRead
                        }
                        digest?.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied = projected

                        val percent = if (declaredLength > 0L) {
                            ((copied * 100L) / declaredLength).toInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        val now = nowMillis()
                        if ((percent >= 0 && percent != lastPercent) || now - lastProgressAt >= progressIntervalMs) {
                            onProgress(percent)
                            lastPercent = percent
                            lastProgressAt = now
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (declaredLength > 0L && copied != declaredLength) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.INVALID_PAYLOAD, status)
            }
            if (expectedDigest != null && !MessageDigest.isEqual(expectedDigest, checkNotNull(digest).digest())) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.CHECKSUM_MISMATCH, status)
            }
            if (copied < minimumPlausibleBytes || looksLikeHtml(header, headerCount)) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.INVALID_PAYLOAD, status)
            }
            if (!hasSpace(parent, 0L)) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.STORAGE_FULL, status)
            }
            if (!payloadValidator(part)) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.INVALID_PAYLOAD, status)
            }

            currentCoroutineContext().ensureActive()
            hadPrevious = target.isFile
            if (hadPrevious) {
                // Claim ownership BEFORE the backup file becomes visible, so
                // concurrent recovery can never mistake the live backup for a
                // process-death artifact in the move-to-adoption window. The
                // token is released in the NonCancellable finally after the
                // backup is committed, restored or deleted.
                if (ModelStagingCodec.writeDownloadIdentity(parent, backupToken, target.name) == null) {
                    return ModelDownloadResult.Failure(ModelDownloadFailure.ATOMIC_ADOPTION, status)
                }
                activeDownloadBackupTokens.add(backupToken)
                try {
                    Files.move(target.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    backupIdentity.delete()
                    return ModelDownloadResult.Failure(ModelDownloadFailure.ATOMIC_ADOPTION, status)
                }
                previousMoved = true
            }
            try {
                atomicAdopter.adopt(part.toPath(), target.toPath())
                targetAdopted = true
            } catch (_: Exception) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.ATOMIC_ADOPTION, status)
            }
            if (!target.isFile || target.length() != copied) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.ATOMIC_ADOPTION, status)
            }
            if (headerCount > 0 && !stagedHeaderMatches(target, header, headerCount)) {
                return ModelDownloadResult.Failure(ModelDownloadFailure.INVALID_PAYLOAD, status)
            }

            // The target is now validated and any previous target has been
            // durably moved to the hidden backup disposition. From this
            // point onward the replacement is authoritative. A leftover
            // backup is cleanup debt, never a reason to resurrect metadata or
            // the previous target.
            currentCoroutineContext().ensureActive()
            operationCommitted = true
            withContext(NonCancellable) {
                try {
                    if (backup.exists() && backup.delete()) backupIdentity.delete()
                } catch (_: Exception) {
                    // Committed replacement wins; backup cleanup is deferred debt.
                }
                try {
                    onProgress(100)
                } catch (_: CancellationException) {
                    // Terminal progress is best-effort after the commit boundary.
                } catch (_: Exception) {
                    // Terminal progress must not downgrade a committed replacement.
                }
            }
            return ModelDownloadResult.Success(target, copied)
        } catch (cancellation: CancellationException) {
            if (operationCommitted) {
                return ModelDownloadResult.Success(target, copied)
            } else {
                throw cancellation
            }
        } catch (_: SocketTimeoutException) {
            requestContext.ensureActive()
            return ModelDownloadResult.Failure(ModelDownloadFailure.TIMEOUT)
        } catch (error: Exception) {
            requestContext.ensureActive()
            return ModelDownloadResult.Failure(
                if (isStorageExhaustionFailure(error)) {
                    ModelDownloadFailure.STORAGE_FULL
                } else {
                    ModelDownloadFailure.NETWORK
                }
            )
        } finally {
            cancellationHandle?.dispose()
            withContext(NonCancellable) {
                connection?.disconnect()
                if (part.exists()) {
                    val deleted = try {
                        part.delete()
                    } catch (_: Exception) {
                        false
                    }
                    if (!deleted) part.deleteOnExit()
                }
                if (!operationCommitted) {
                    if (targetAdopted) {
                        try {
                            if (target.exists()) target.delete()
                        } catch (_: Exception) {
                            // Recovery below still has the previous backup as authority.
                        }
                    }
                    if (previousMoved && backup.exists()) {
                        try {
                            Files.move(backup.toPath(), target.toPath())
                            backupIdentity.delete()
                        } catch (_: Exception) {
                            // Process-death recovery owns any surviving backup debt.
                        }
                    } else if (backup.exists()) {
                        try {
                            backup.delete()
                            if (!backup.exists()) backupIdentity.delete()
                        } catch (_: Exception) {
                            // Preserve cleanup debt for the recovery pass.
                        }
                    }
                } else if (backup.exists()) {
                    val deleted = try {
                        backup.delete()
                    } catch (_: Exception) {
                        false
                    }
                    if (!deleted) backup.deleteOnExit() else backupIdentity.delete()
                } else {
                    backupIdentity.delete()
                }
                // Release active ownership only after the backup has been
                // committed, restored or deleted, so concurrent recovery can
                // never observe a half-finished replacement. The `.part`
                // token is released after part cleanup/settlement for the
                // same reason.
                activeDownloadBackupTokens.remove(backupToken)
                activeDownloadPartTokens.remove(partToken)
            }
        }
    }

    private fun hasSpace(parent: File, incomingBytes: Long): Boolean {
        val available = usableSpace(parent)
        if (available <= 0L) return false
        if (incomingBytes < 0L || reserveBytes < 0L) return false
        val required = try {
            Math.addExact(incomingBytes, reserveBytes)
        } catch (_: ArithmeticException) {
            return false
        }
        return available >= required
    }

    private suspend fun openFinalConnection(sourceUrl: String, onConnection: (HttpURLConnection) -> Unit): OpenResult {
        var current = try {
            URL(sourceUrl)
        } catch (_: Exception) {
            null
        } ?: return OpenResult.Failure(ModelDownloadResult.Failure(ModelDownloadFailure.NETWORK))

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            val candidate = connectionFactory.open(current).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                requestMethod = "GET"
            }
            var handedOff = false
            try {
                onConnection(candidate)
                candidate.connect()
                val status = candidate.responseCode
                if (status !in REDIRECT_STATUSES) {
                    handedOff = true
                    return OpenResult.Ready(candidate)
                }
                if (redirectCount >= MAX_REDIRECTS) {
                    return OpenResult.Failure(
                        ModelDownloadResult.Failure(ModelDownloadFailure.REDIRECT, status)
                    )
                }
                currentCoroutineContext().ensureActive()
                val location = candidate.getHeaderField("Location")
                    ?: return OpenResult.Failure(
                        ModelDownloadResult.Failure(ModelDownloadFailure.REDIRECT, status)
                    )
                val next = try {
                    URL(current, location)
                } catch (_: Exception) {
                    null
                } ?: return OpenResult.Failure(
                    ModelDownloadResult.Failure(ModelDownloadFailure.REDIRECT, status)
                )
                if (next.protocol !in setOf("https", "http") ||
                    (current.protocol == "https" && next.protocol != "https")
                ) {
                    return OpenResult.Failure(
                        ModelDownloadResult.Failure(ModelDownloadFailure.REDIRECT, status)
                    )
                }
                current = next
            } finally {
                if (!handedOff) {
                    candidate.disconnect()
                }
            }
        }
        return OpenResult.Failure(ModelDownloadResult.Failure(ModelDownloadFailure.REDIRECT))
    }

    private fun readBoundedErrorBody(connection: HttpURLConnection) {
        try {
            connection.errorStream?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = MAX_ERROR_BODY_BYTES
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    remaining -= read
                }
            }
        } catch (_: Exception) {
            // Error-body diagnostics are best-effort only.
        }
    }

    private fun looksLikeHtml(header: ByteArray, count: Int): Boolean {
        if (count <= 0) return false
        val prefix = header.copyOf(count).toString(Charsets.UTF_8).trimStart().lowercase()
        return prefix.startsWith("<!doctype html") ||
            prefix.startsWith("<html") ||
            prefix.startsWith("<?xml") && "<html" in prefix
    }

    private fun decodeExpectedSha256(value: String): ByteArray? {
        val normalized = value.lowercase()
        if (normalized.length != 64 || normalized.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(32) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun stagedHeaderMatches(target: File, sniffsHeader: ByteArray, sniffsCount: Int): Boolean {
        if (sniffsCount <= 0) return true
        if (!target.isFile) return false
        val targetBytes = ByteArray(sniffsCount)
        var read = 0
        target.inputStream().use { input ->
            while (read < sniffsCount) {
                val got = input.read(targetBytes, read, sniffsCount - read)
                if (got <= 0) break
                read += got
            }
        }
        if (read < sniffsCount) return false
        for (i in 0 until sniffsCount) {
            if (targetBytes[i] != sniffsHeader[i]) return false
        }
        return true
    }

    private sealed interface OpenResult {
        data class Ready(val connection: HttpURLConnection) : OpenResult
        data class Failure(val result: ModelDownloadResult.Failure) : OpenResult
    }

    private companion object {
        const val MAX_FILESYSTEM_COMPONENT_BYTES = 255

        class TargetLockEntry(
            val mutex: Mutex = Mutex(),
            val users: AtomicInteger = AtomicInteger(0),
        )

        val targetLocks = ConcurrentHashMap<String, TargetLockEntry>()

        const val MAX_REDIRECTS = 5
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val PAYLOAD_SNIFF_BYTES = 512
        val REDIRECT_STATUSES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}

/**
 * Process-scoped set of download `.part` tokens currently owned by a live
 * in-process download. The token is the exact trailing segment of the live
 * `.${target}.<token>.part` staging name and is registered before the file
 * is created or opened; recovery excludes it via its typed input set while
 * the download owns it. The token is released in the download's
 * NonCancellable finally after the part is adopted or deleted. Never
 * persisted: a real process death loses the registry so old abandoned parts
 * are still cleaned after grace. Kept distinct from the download-backup
 * token domain below: backup tokens must never protect parts and part
 * tokens must never protect backups.
 */
internal val activeDownloadPartTokens = ConcurrentHashMap.newKeySet<String>()

/**
 * Process-scoped set of download-backup tokens currently owned by a live
 * in-process download replacement. Recovery consults this to skip backups that
 * a download still owns; the token is released in the download's
 * NonCancellable finally after the backup is committed, restored or deleted.
 */
internal val activeDownloadBackupTokens = ConcurrentHashMap.newKeySet<String>()

internal fun isActiveDownloadBackup(file: File): Boolean {
    val entry = ModelStagingCodec.decodeDownloadBackup(file.name) ?: return false
    return activeDownloadBackupTokens.contains(entry.token)
}
