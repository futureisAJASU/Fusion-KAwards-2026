package com.projectnuke.fusion.documents

import android.content.Context
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.inbox.InboxCapture
import com.projectnuke.fusion.inbox.InboxCaptureType
import com.projectnuke.fusion.inbox.InboxRepository
import com.projectnuke.fusion.inbox.InboxWriteResult
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Persists OCR text and its managed camera source as one durable Inbox-owned capture.
 *
 * The durable Inbox row and the pending-copy ownership transfer of the SAME
 * physical file are one release point: both run inside
 * [AttachmentReferenceMutationCoordinator], serialized with every destructive
 * physical release (camera discard, removed-draft release, unreferenced
 * GC). Without that, a discard could observe "durable Inbox row committed AND
 * pending copy still registered" as delete permission and orphan the row.
 */
internal class CameraOcrInboxCaptureController(
    context: Context,
    database: AppDatabase,
    private val cameraController: CameraKnowledgeCaptureController,
    private val chatViewModel: ChatViewModel,
) {
    private val appContext = context.applicationContext
    private val repository = InboxRepository(database)

    suspend fun save(
        eventId: String,
        conversationId: Long,
        path: String,
        title: String,
        recognizedText: String,
        now: Long = System.currentTimeMillis(),
        onCancellationResolved: suspend () -> Unit = {},
    ): InboxWriteResult = withContext(Dispatchers.IO) {
        val managedFile = AttachmentStorageManager.resolveManagedAttachment(appContext, path)
            ?: return@withContext InboxWriteResult.Failed
        val canonicalPath = managedFile.canonicalPath
        try {
            if (!chatViewModel.pendingPickerImportMatches(conversationId, eventId)) {
                return@withContext InboxWriteResult.Failed
            }
            val capture = InboxCapture(
                type = InboxCaptureType.OCR,
                title = title.ifBlank { File(canonicalPath).name },
                content = recognizedText,
                managedFileId = canonicalPath,
                managedMimeType = "image/jpeg",
                sourceHandoffEventId = eventId,
            )
            // Durable commit + pending-ownership settlement are ONE gate
            // transaction, so the camera discard (and every other destructive
            // release) can never land inside the commit->settle gap.
            //
            // P14-04: the preflight above (managed-file resolve + picker-owner
            // check) ran BEFORE the gate was acquired, so it is a stale
            // snapshot by the time the commit runs: a camera abandonment can
            // settle the owner and physically delete the file in between, and
            // the resumed commit would then store an Inbox row naming a
            // deleted source (`InboxCapturePolicy.prepare` validates metadata
            // only). The source, the pending registration and the picker
            // owner are therefore revalidated INSIDE the gate, under the same
            // serialization seam as the commit. Only the short
            // admission->commit->settlement transition is guarded; OCR
            // generation stays outside the gate.
            val result = commitValidatedCapture(
                conversationId = conversationId,
                eventId = eventId,
                path = path,
                canonicalPath = canonicalPath,
                capture = capture,
                now = now,
                ownerMatches = { chatViewModel.pendingPickerImportMatches(conversationId, eventId) },
                captureStored = { repository.capture(it, now = now, id = eventId) },
                resolveManaged = { AttachmentStorageManager.resolveManagedAttachment(appContext, it) },
                isPendingRegistered = { AttachmentStorageManager.isPendingAttachmentRegistered(it) },
                transferOwnership = { transferOwnership(path) },
            )
            if (result is InboxWriteResult.Stored) {
                chatViewModel.settleAttachmentImportAfterCallback(conversationId, eventId)
            }
            result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    val durable = repository.get(eventId)
                    if (durable?.managedFileId == canonicalPath) transferOwnership(path)
                    else cameraController.discard(path)
                } catch (_: Exception) {
                    // Unknown DB state: retain the pending file, but clear the transient OCR preview.
                    cameraController.adopted(path)
                }
                chatViewModel.settleAttachmentImportAfterCallback(conversationId, eventId)
                onCancellationResolved()
            }
            throw cancelled
        }
    }

    private fun transferOwnership(path: String) {
        AttachmentStorageManager.unregisterPendingAttachment(path)
        cameraController.adopted(path)
    }
}

/**
 * Gated Inbox admission with in-gate source revalidation (P14-04).
 *
 * The caller's preflight (managed-file resolve + picker-owner check) runs
 * before the reference-mutation gate is acquired, so it may already be
 * stale: an overlapping camera abandonment settles the picker owner first
 * and then deletes the pending file under the same gate. Committing from
 * the stale snapshot would store a durable Inbox row whose `managedFileId`
 * names a physically deleted source.
 *
 * Everything that licenses the commit is therefore re-established under
 * the gate, in order: the managed source still resolves to the SAME
 * canonical file (present, regular, inside the attachment root), the
 * pending-copy registration still exists (a physical discard settles it
 * even on the already-absent path), and the picker session still
 * authorizes this admission. Any failed revalidation returns [Failed]
 * without touching durable state: no row is committed, no ownership is
 * transferred, and the (possibly already deleted) file is left for the
 * abandonment owner that deleted it.
 *
 * Top-level with explicit seams (no controller/database receiver) so a
 * deterministic host test can place a barrier between the caller's
 * preflight and this gated commit while driving the real production
 * revalidation and the real serialization seam.
 */
internal suspend fun commitValidatedCapture(
    conversationId: Long,
    eventId: String,
    path: String,
    canonicalPath: String,
    capture: InboxCapture,
    now: Long,
    ownerMatches: suspend () -> Boolean,
    captureStored: suspend (InboxCapture) -> InboxWriteResult,
    resolveManaged: (String) -> File?,
    isPendingRegistered: (String) -> Boolean,
    transferOwnership: () -> Unit,
): InboxWriteResult = AttachmentReferenceMutationCoordinator.withMutation {
    withContext(NonCancellable) {
        val fresh = resolveManaged(path) ?: return@withContext InboxWriteResult.Failed
        if (fresh.canonicalPath != canonicalPath) return@withContext InboxWriteResult.Failed
        if (!isPendingRegistered(canonicalPath)) return@withContext InboxWriteResult.Failed
        if (!ownerMatches()) return@withContext InboxWriteResult.Failed
        val stored = captureStored(capture)
        if (stored is InboxWriteResult.Stored) transferOwnership()
        stored
    }
}
