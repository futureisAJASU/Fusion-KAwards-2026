package com.projectnuke.fusion.modelzoo

import android.util.Log
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ManagedModelDeletionFailure {
    INVALID_TARGET,
    STAGING_FAILED,
    METADATA_FAILED,
    RESTORE_FAILED,
}

internal sealed interface ManagedModelDeletionResult {
    data object Committed : ManagedModelDeletionResult
    data class Failed(val reason: ManagedModelDeletionFailure) : ManagedModelDeletionResult
}

/**
 * Removes a managed model without making a missing binary observable as a
 * durable catalog state. The hidden staging file is deliberately not a
 * catalog reference and is cleaned only after the metadata commit wins.
 */
internal class ManagedModelDeletionCoordinator(
    private val modelDirectory: File,
    private val moveFile: (File, File) -> Boolean = ::moveSafely,
) {
    suspend fun delete(
        path: String?,
        removeMetadata: suspend () -> Boolean,
    ): ManagedModelDeletionResult = withContext(Dispatchers.IO) {
        val root = try {
            modelDirectory.canonicalFile
        } catch (_: Exception) {
            null
        } ?: return@withContext ManagedModelDeletionResult.Failed(
            ManagedModelDeletionFailure.INVALID_TARGET
        )
        val target = ManagedModelPathPolicy.resolveManagedTarget(root, path)
            ?: return@withContext ManagedModelDeletionResult.Failed(
                ManagedModelDeletionFailure.INVALID_TARGET
            )
        if (!(root.exists() || root.mkdirs()) || !root.isDirectory) {
            return@withContext ManagedModelDeletionResult.Failed(
                ManagedModelDeletionFailure.INVALID_TARGET
            )
        }

        val token = ModelStagingCodec.randomHexToken()
        val staged = File(root, ModelStagingCodec.encodeDeleteStagingName(token))
        if (!isDirectChild(root, staged) || Files.isSymbolicLink(staged.toPath())) {
            return@withContext ManagedModelDeletionResult.Failed(
                ManagedModelDeletionFailure.STAGING_FAILED
            )
        }
        activeStagingTokens.add(token)
        var committed = false
        var stagedBinary = false
        var outcome: ManagedModelDeletionResult? = null
        var cancellationToRethrow: CancellationException? = null
        var restoreFailed = false
        try {
            if (ModelStagingCodec.writeDeleteIdentity(root, token, target.name) == null) {
                return@withContext ManagedModelDeletionResult.Failed(
                    ManagedModelDeletionFailure.STAGING_FAILED
                )
            }
            outcome = run {
                if (target.exists()) {
                    if (!target.isFile || Files.isSymbolicLink(target.toPath()) || !moveFile(target, staged)) {
                        return@run ManagedModelDeletionResult.Failed(
                            ManagedModelDeletionFailure.STAGING_FAILED
                        )
                    }
                    // The source has moved; retain restoration ownership even if
                    // a concurrent filesystem change makes verification fail.
                    stagedBinary = true
                    if (!staged.isFile || Files.isSymbolicLink(staged.toPath())) {
                        return@run ManagedModelDeletionResult.Failed(
                            ManagedModelDeletionFailure.STAGING_FAILED
                        )
                    }
                }

                // A cancellation before this checkpoint is still allowed to
                // abort. Metadata mutation itself is the durable commit boundary.
                currentCoroutineContext().ensureActive()
                val removed = try {
                    withContext(NonCancellable) { removeMetadata() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
                if (!removed) {
                    return@run ManagedModelDeletionResult.Failed(
                        ManagedModelDeletionFailure.METADATA_FAILED
                    )
                }

                committed = true
                withContext(NonCancellable) {
                    if (staged.exists() && !try {
                            staged.delete()
                        } catch (_: Exception) {
                            false
                        }
                    ) {
                        // The catalog is already authoritative. Retain the hidden
                        // unreferenced artifact for bounded abandoned cleanup.
                        staged.deleteOnExit()
                        Log.w(TAG, "Managed model delete staging cleanup deferred: ${staged.absolutePath}")
                    }
                    if (!staged.exists()) {
                        ModelStagingCodec.deleteIdentitySidecar(root, token).delete()
                    }
                }
                ManagedModelDeletionResult.Committed
            }
        } catch (cancellation: CancellationException) {
            if (committed) {
                outcome = ManagedModelDeletionResult.Committed
            } else {
                cancellationToRethrow = cancellation
            }
        } catch (_: Exception) {
            outcome = if (committed) {
                ManagedModelDeletionResult.Committed
            } else {
                ManagedModelDeletionResult.Failed(ManagedModelDeletionFailure.STAGING_FAILED)
            }
        } finally {
            withContext(NonCancellable) {
                if (!committed && stagedBinary && staged.exists()) {
                    val restored = try {
                        if (target.exists()) false else moveFile(staged, target)
                    } catch (_: Exception) {
                        false
                    }
                    if (!restored && staged.exists()) {
                        restoreFailed = true
                        Log.e(TAG, "Managed model delete restore failed: ${staged.absolutePath}")
                    } else if (restored) {
                        ModelStagingCodec.deleteIdentitySidecar(root, token).delete()
                    }
                } else if (!staged.exists()) {
                    ModelStagingCodec.deleteIdentitySidecar(root, token).delete()
                }
                activeStagingTokens.remove(token)
            }
        }

        cancellationToRethrow?.let { throw it }
        if (restoreFailed) {
            ManagedModelDeletionResult.Failed(ManagedModelDeletionFailure.RESTORE_FAILED)
        } else {
            outcome ?: ManagedModelDeletionResult.Failed(ManagedModelDeletionFailure.STAGING_FAILED)
        }
    }

    companion object {
        private const val TAG = "FusionModel"

        /**
         * Process-scoped set of delete-staging tokens currently owned by a live
         * in-process deletion. Recovery consults this to avoid touching staging
         * that an active operation still owns. Tokens are opaque hex strings and
         * are matched by exact decode (never by loose filename substring).
         */
        internal val activeStagingTokens = ConcurrentHashMap.newKeySet<String>()

        internal fun isActiveStaging(file: File): Boolean {
            val entry = ModelStagingCodec.decodeDeleteStaging(file.name) ?: return false
            return activeStagingTokens.contains(entry.token)
        }

        private fun moveSafely(from: File, to: File): Boolean {
            return try {
                Files.move(
                    from.toPath(),
                    to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
                true
            } catch (_: Exception) {
                try {
                    Files.move(
                        from.toPath(),
                        to.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        private fun isDirectChild(root: File, child: File): Boolean =
            child.canonicalFile.parentFile?.canonicalPath == root.canonicalPath
    }
}
