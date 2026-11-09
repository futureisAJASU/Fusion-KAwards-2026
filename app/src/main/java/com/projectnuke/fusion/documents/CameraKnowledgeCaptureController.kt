package com.projectnuke.fusion.documents

import android.content.Context
import com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider
import com.projectnuke.fusion.retrieval.LocalOcrResult
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.isManagedAttachmentDurablyReferenced
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class CameraOcrStatus { IDLE, PROCESSING, PREVIEW, FAILED }

internal enum class CameraOcrFailure {
    INVALID_CAPTURE,
    TOO_LARGE,
    NO_TEXT,
    PARTIAL,
    TOO_MANY_PAGES,
    TOO_MUCH_TEXT,
    TIMEOUT,
    UNAVAILABLE,
}

internal data class CameraKnowledgeCaptureState(
    val path: String? = null,
    val displayName: String = "",
    val status: CameraOcrStatus = CameraOcrStatus.IDLE,
    val previewText: String = "",
    val previewTruncated: Boolean = false,
    val failure: CameraOcrFailure? = null,
)

internal enum class CameraCaptureDiscardResult {
    DELETED,
    ALREADY_ABSENT,
    NOT_PENDING,
    DURABLY_REFERENCED,
    AUTHORITY_UNAVAILABLE,
    DELETE_FAILED,
    INVALID_TARGET,
}

internal object CameraKnowledgeCapturePolicy {
    const val MAX_CAPTURE_BYTES = 20L * 1024L * 1024L
    const val MAX_PREVIEW_CHARS = 12_000

    fun acceptsCaptureBytes(bytes: Long): Boolean = bytes in 1..MAX_CAPTURE_BYTES

    fun preview(text: String): Pair<String, Boolean> =
        text.take(MAX_PREVIEW_CHARS) to (text.length > MAX_PREVIEW_CHARS)
}

/**
 * Coordinates a private camera file with Fusion's existing local OCR and managed-file owner.
 *
 * Lock order (single, process-wide):
 *
 *     AttachmentReferenceMutationCoordinator.mutationMutex
 *         -> CameraKnowledgeCaptureController.operationMutex
 *
 * `operationMutex` is only ever acquired INSIDE the reference-mutation gate,
 * never the other way round, so no controller lock can invert the global
 * ownership gate and no lock-order deadlock is reachable.
 */
internal class CameraKnowledgeCaptureController(
    context: Context,
    private val recognizeImage: suspend (String, (Int, Int) -> Unit) -> LocalOcrResult = { path, progress ->
        DocumentIntelligenceProvider.indexRepository(context.applicationContext)
            .recognizeText(path, "image/jpeg", progress)
    },
    /**
     * Durable managed-attachment reference authority for one path.
     *
     * A pending-copy registration proves a process owner still claims the copy;
     * it never proves exclusive physical ownership, so a destructive release
     * must ask the durable authorities before deleting. true = a durable owner
     * names the path; false = the complete authority names nothing; null = the
     * authority could not be read. null MUST fail closed (preserve the file and
     * its pending-copy registration for a later authority-complete cleanup).
     */
    private val isDurablyReferenced: suspend (String) -> Boolean? = { path ->
        isManagedAttachmentDurablyReferenced(context, path)
    },
) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(CameraKnowledgeCaptureState())
    val state = mutableState.asStateFlow()

    fun createOutputFile(): File {
        val root = AttachmentStorageManager.getAttachmentDirectory(appContext).canonicalFile
        val file = File(root, "camera_ocr_${UUID.randomUUID()}.jpg")
        check(file.parentFile?.canonicalPath == root.canonicalPath)
        check(file.createNewFile()) { "Could not prepare the private camera output" }
        return file
    }

    suspend fun recognize(path: String): CameraKnowledgeCaptureState = operationMutex.withLock {
        val file = AttachmentStorageManager.resolveManagedAttachment(appContext, path)
            ?: return fail(path, CameraOcrFailure.INVALID_CAPTURE)
        val canonical = file.canonicalPath
        if (!CameraKnowledgeCapturePolicy.acceptsCaptureBytes(file.length())) {
            return fail(canonical, if (file.length() > CameraKnowledgeCapturePolicy.MAX_CAPTURE_BYTES)
                CameraOcrFailure.TOO_LARGE else CameraOcrFailure.INVALID_CAPTURE)
        }
        val pendingPath = AttachmentStorageManager.registerPendingAttachment(file)
            ?: return fail(canonical, CameraOcrFailure.UNAVAILABLE)
        mutableState.value = CameraKnowledgeCaptureState(
            path = pendingPath,
            displayName = file.name,
            status = CameraOcrStatus.PROCESSING,
        )
        try {
            when (val result = withTimeout(OCR_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { recognizeImage(pendingPath) { _, _ -> } }
            }) {
                is LocalOcrResult.Complete -> {
                    val text = result.pages.asSequence().map { it.text.trim() }.filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    if (text.isBlank()) fail(pendingPath, CameraOcrFailure.NO_TEXT)
                    else {
                        val (preview, truncated) = CameraKnowledgeCapturePolicy.preview(text)
                        CameraKnowledgeCaptureState(
                            path = pendingPath,
                            displayName = file.name,
                            status = CameraOcrStatus.PREVIEW,
                            previewText = preview,
                            previewTruncated = truncated,
                        ).also { mutableState.value = it }
                    }
                }
                is LocalOcrResult.Partial -> fail(pendingPath, CameraOcrFailure.PARTIAL)
                LocalOcrResult.TooManyPages -> fail(pendingPath, CameraOcrFailure.TOO_MANY_PAGES)
                LocalOcrResult.TooMuchText -> fail(pendingPath, CameraOcrFailure.TOO_MUCH_TEXT)
                LocalOcrResult.NoText -> fail(pendingPath, CameraOcrFailure.NO_TEXT)
            }
        } catch (_: TimeoutCancellationException) {
            fail(pendingPath, CameraOcrFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail(pendingPath, CameraOcrFailure.UNAVAILABLE)
        }
    }
    /**
     * Physically discards only a file that no durable owner still references.
     *
     * Two independent conditions used to make this unsafe, and both are now
     * enforced under ONE serialization seam:
     *
     * 1. GATE. A durable adoption (camera -> Project via
     *    [ProjectFileImportCoordinator], camera -> Inbox via
     *    [CameraOcrInboxCaptureController]) commits its durable row first and
     *    settles pending ownership afterwards. Between those two steps the
     *    file is BOTH durably referenced AND still pending-registered, so a
     *    discard that ran on a plain pending-registration snapshot deleted a
     *    file the committed row named. `discard` therefore runs inside the
     *    process-wide reference-mutation gate, the same gate every other
     *    destructive release (removed-draft release, unreferenced-attachment
     *    GC) and every creation/transfer (project promotion, project import,
     *    user-message submission transfer) already uses. The ownership
     *    transition "no durable reference <-> durable reference <-> physical
     *    deletion" is linearizable: `discard` either runs before the adoption
     *    commits (the adoption then re-validates the source and rejects) or
     *    after pending ownership is settled (the pending registration is gone
     *    and nothing is deleted).
     * 2. AUTHORITY. Even a file that is still pending-registered must not be
     *    deleted while any durable authority names it (P8/P12 handoff and
     *    pending-registration protections are additive to this, never a
     *    replacement). The durable authority is therefore re-read INSIDE the
     *    gate, never from a snapshot taken before it, and an unreadable
     *    authority fails closed: the physical file and its pending-copy
     *    registration survive, and the authority-complete cleanup GC settles
     *    the file instead.
     */
    suspend fun discard(path: String): Boolean = when (discardWithOutcome(path)) {
        CameraCaptureDiscardResult.DELETED,
        CameraCaptureDiscardResult.ALREADY_ABSENT -> true
        else -> false
    }

    /** Typed result lets the abandonment owner retry only unresolved cleanup. */
    internal suspend fun discardWithOutcome(path: String): CameraCaptureDiscardResult = withContext(NonCancellable) {
        AttachmentReferenceMutationCoordinator.withMutation {
            operationMutex.withLock {
                if (mutableState.value.path == path) mutableState.value = CameraKnowledgeCaptureState()
                if (!AttachmentStorageManager.isPendingAttachmentRegistered(path)) {
                    return@withLock CameraCaptureDiscardResult.NOT_PENDING
                }
                val durablyReferenced = try {
                    isDurablyReferenced(path)
                } catch (_: CancellationException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (durablyReferenced == true) {
                    return@withLock CameraCaptureDiscardResult.DURABLY_REFERENCED
                }
                if (durablyReferenced == null) {
                    // Unknown authority is never permission to delete. Keep
                    // the pending registration for a later foreground retry.
                    return@withLock CameraCaptureDiscardResult.AUTHORITY_UNAVAILABLE
                }
                when (AttachmentStorageManager.discardPendingAttachmentFile(appContext, path)) {
                    com.projectnuke.fusion.util.PendingAttachmentDiscardResult.Deleted ->
                        CameraCaptureDiscardResult.DELETED
                    com.projectnuke.fusion.util.PendingAttachmentDiscardResult.AlreadyAbsent ->
                        CameraCaptureDiscardResult.ALREADY_ABSENT
                    com.projectnuke.fusion.util.PendingAttachmentDiscardResult.DeletionFailed ->
                        CameraCaptureDiscardResult.DELETE_FAILED
                    com.projectnuke.fusion.util.PendingAttachmentDiscardResult.InvalidPath,
                    com.projectnuke.fusion.util.PendingAttachmentDiscardResult.InvalidTarget ->
                        CameraCaptureDiscardResult.INVALID_TARGET
                }
            }
        }
    }
    fun adopted(path: String) {
        if (mutableState.value.path == path) mutableState.value = CameraKnowledgeCaptureState()
    }

    private fun fail(path: String, reason: CameraOcrFailure) = CameraKnowledgeCaptureState(
        path = path,
        displayName = File(path).name,
        status = CameraOcrStatus.FAILED,
        failure = reason,
    ).also { mutableState.value = it }

    private companion object {
        const val OCR_TIMEOUT_MS = 120_000L
    }
}
