package com.projectnuke.fusion.ui

import com.projectnuke.fusion.util.AttachmentImportFailure
import com.projectnuke.fusion.util.AttachmentImportResult
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Owns the gap between a managed copy and its ProjectFileEntity adoption. */
internal sealed interface ProjectFileImportResult<out T> {
    data class ImportFailed(val kind: AttachmentImportFailure) : ProjectFileImportResult<Nothing>
    data class Persisted<T>(val value: T) : ProjectFileImportResult<T>
    data object PersistenceFailed : ProjectFileImportResult<Nothing>
}

internal class ProjectFileImportCoordinator(
    private val deletePendingFile: suspend (String) -> Boolean,
    private val unregisterPending: (String) -> Unit,
) {
    suspend fun <T> copyAndPersist(
        copy: suspend () -> AttachmentImportResult,
        persist: suspend (File) -> T,
        isAdopted: (T) -> Boolean,
        isDurablyReferenced: (suspend (String) -> Boolean)? = null,
    ): ProjectFileImportResult<T> {
        var generatedPath: String? = null
        var settled = false
        try {
            val imported = copy()
            if (imported is AttachmentImportResult.Failure) {
                return ProjectFileImportResult.ImportFailed(imported.kind)
            }
            val generatedFile = (imported as AttachmentImportResult.Success).file
            generatedPath = runCatching { generatedFile.canonicalPath }
                .getOrElse { generatedFile.absolutePath }
            // The durable ownership transfer (pending registration ->
            // ProjectFileEntity -> pending ownership settlement) is serialized
            // with destructive attachment GC under the process-wide
            // reference-mutation gate: no authority snapshot may observe
            // "pending registration gone AND the project row not yet durable"
            // as deletion permission. The copy itself stays outside the gate;
            // its pending registration protects the file throughout.
            // Durability: persist + settlement run NonCancellable once the gate
            // is held, so a commit-then-cancel race cannot leave a committed
            // row with a deleted file. Cancellation still propagates after.
            val persisted = AttachmentReferenceMutationCoordinator.withMutation {
                withContext(NonCancellable) {
                    val value = persist(generatedFile)
                    if (isAdopted(value)) {
                        unregisterPending(generatedPath!!)
                        settled = true
                    }
                    value
                }
            }
            // persist(), adoption, and pending-settlement all ran inside
            // withContext(NonCancellable), so a cancellation requested during the
            // commit cannot interrupt them: the ProjectFileEntity row is committed
            // and pending ownership is settled before this point. withContext(
            // NonCancellable) does NOT throw CancellationException on exit for a
            // cancelled caller (the cancellation only surfaces at the caller's
            // next *cancellable* suspension), so the durable Persisted outcome is
            // delivered truthfully here. A re-check that throws here would merely
            // relabel a committed success as a failure (P11-07). The caller's job
            // remains cancelled and is observed at its own next checkpoint.
            return ProjectFileImportResult.Persisted(persisted)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return ProjectFileImportResult.PersistenceFailed
        } finally {
            val path = generatedPath
            if (path != null && !settled) {
                withContext(NonCancellable) {
                    AttachmentReferenceMutationCoordinator.withMutation {
                        val referenced = try {
                            isDurablyReferenced?.invoke(path) ?: false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Fail closed: unreadable authority preserves the file.
                            true
                        }
                        if (referenced) {
                            // The DB row committed despite the caller observing
                            // cancellation/failure: settle pending ownership and
                            // preserve the adopted file.
                            runCatching { unregisterPending(path) }
                        } else {
                            // AttachmentStorageManager removes the pending
                            // registration when deletion succeeds and keeps it on
                            // a failed delete so cleanup cannot race the file.
                            runCatching { deletePendingFile(path) }
                        }
                    }
                }
            }
        }
    }
}
