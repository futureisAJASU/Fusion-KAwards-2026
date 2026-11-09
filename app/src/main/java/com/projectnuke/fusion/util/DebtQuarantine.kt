package com.projectnuke.fusion.util

import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption

internal sealed interface DebtQuarantineOutcome {
    data class Secured(val quarantine: File) : DebtQuarantineOutcome
    data object SourceMissing : DebtQuarantineOutcome
    data object Failed : DebtQuarantineOutcome
}

/**
 * Bounded single-file quarantine for corrupt recovery evidence.
 *
 * Semantics:
 * - move succeeds and is verified -> Secured, original no longer exists;
 * - move fails, copy succeeds and verifies, original deleted -> Secured;
 * - otherwise -> Failed and the original MUST remain untouched.
 *
 * Never logs "quarantined" on failure; callers must fail closed on [Failed].
 */
internal object DebtQuarantine {
    fun quarantine(
        source: File,
        suffix: String = ".corrupt.bak",
        allowCopyFallback: Boolean = true,
        move: (File, File) -> Boolean = ::defaultMove,
        copy: (File, File) -> Boolean = ::defaultCopy,
        deleteOriginal: (File) -> Boolean = { file -> runCatching { file.delete() }.getOrDefault(false) },
        verifyIdentical: (File, File) -> Boolean = ::verifyByteIdentical,
    ): DebtQuarantineOutcome {
        if (!source.isFile) return DebtQuarantineOutcome.SourceMissing
        val parent = source.parentFile ?: return DebtQuarantineOutcome.Failed
        val quarantine = File(parent, source.name + suffix)
        // Bounded policy: single quarantine file, replace any previous one.
        runCatching { if (quarantine.isFile) quarantine.delete() }

        val moved = runCatching { move(source, quarantine) }.getOrDefault(false)
        if (moved) {
            val verified = quarantine.isFile && !source.exists()
            if (verified) return DebtQuarantineOutcome.Secured(quarantine)
            // Move claimed success but verification failed: do not touch anything further.
            return DebtQuarantineOutcome.Failed
        }

        if (!allowCopyFallback) return DebtQuarantineOutcome.Failed

        val copied = runCatching { copy(source, quarantine) }.getOrDefault(false)
        if (!copied) return DebtQuarantineOutcome.Failed
        // Verify destination represents the source before deleting the original.
        val verified = runCatching { verifyIdentical(source, quarantine) }.getOrDefault(false)
        if (!verified || !quarantine.isFile) {
            runCatching { if (quarantine.isFile) quarantine.delete() }
            return DebtQuarantineOutcome.Failed
        }
        val deleted = runCatching { deleteOriginal(source) }.getOrDefault(false)
        if (!deleted || source.exists()) return DebtQuarantineOutcome.Failed
        return DebtQuarantineOutcome.Secured(quarantine)
    }

    private fun defaultMove(source: File, dest: File): Boolean = runCatching {
        try {
            java.nio.file.Files.move(
                source.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            java.nio.file.Files.move(
                source.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        true
    }.getOrDefault(false)

    private fun defaultCopy(source: File, dest: File): Boolean = runCatching {
        BufferedInputStream(FileInputStream(source)).use { input ->
            FileOutputStream(dest, false).use { rawOutput ->
                val output = BufferedOutputStream(rawOutput)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                }
                output.flush()
                rawOutput.fd.sync()
            }
        }
        true
    }.getOrDefault(false)

    private fun verifyByteIdentical(source: File, dest: File): Boolean = try {
        if (!source.isFile || !dest.isFile || source.length() != dest.length()) {
            false
        } else {
            var identical = true
            BufferedInputStream(FileInputStream(source)).use { sourceInput ->
                BufferedInputStream(FileInputStream(dest)).use { destInput ->
                    val sourceBuffer = ByteArray(64 * 1024)
                    val destBuffer = ByteArray(64 * 1024)
                    while (identical) {
                        val sourceRead = sourceInput.read(sourceBuffer)
                        val destRead = destInput.read(destBuffer)
                        if (sourceRead != destRead) {
                            identical = false
                        } else if (sourceRead < 0) {
                            break
                        } else {
                            for (index in 0 until sourceRead) {
                                if (sourceBuffer[index] != destBuffer[index]) {
                                    identical = false
                                    break
                                }
                            }
                        }
                    }
                }
            }
            identical
        }
    } catch (_: Exception) {
        false
    }
}
