package com.projectnuke.fusion.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CancellationException

private const val MAX_REPLACEMENT_ATTEMPTS = 3

private data class TargetLockEntry(
    val lock: ReentrantLock = ReentrantLock(),
    var users: Int = 0,
)

private val targetLockRegistry = mutableMapOf<String, TargetLockEntry>()
private val targetLockRegistryGuard = Any()

private inline fun <T> withCanonicalTargetLock(target: File, block: () -> T): T {
    val key = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
    val entry = synchronized(targetLockRegistryGuard) {
        targetLockRegistry.getOrPut(key) { TargetLockEntry() }.also { it.users += 1 }
    }
    entry.lock.lock()
    return try {
        block()
    } finally {
        entry.lock.unlock()
        synchronized(targetLockRegistryGuard) {
            entry.users -= 1
            if (entry.users == 0) {
                targetLockRegistry.remove(key, entry)
            }
        }
    }
}

/** Read-only test seam for proving target-key lock entries are retired. */
internal fun <T> withAtomicTextMutation(target: File, block: () -> T): T =
    withCanonicalTargetLock(target, block)

internal fun atomicTextFileLockCountForTest(): Int = synchronized(targetLockRegistryGuard) {
    targetLockRegistry.size
}

internal fun writeTextAtomically(
    target: File,
    text: String,
    replace: (File, File) -> Unit = { source, destination ->
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    },
) {
    withCanonicalTargetLock(target) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            var replaced = false
            var lastFailure: Throwable? = null
            repeat(MAX_REPLACEMENT_ATTEMPTS) {
                if (replaced) return@repeat
                try {
                    replace(temporary, target)
                    replaced = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    lastFailure = failure
                }
            }
            if (!replaced) throw requireNotNull(lastFailure)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
