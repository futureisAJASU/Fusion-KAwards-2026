package com.projectnuke.fusion.handoff

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PreparedExternalHandoff
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxInsertResult
import com.projectnuke.fusion.data.ProjectFileContract
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.inbox.InboxCapture
import com.projectnuke.fusion.inbox.InboxCapturePolicy
import com.projectnuke.fusion.inbox.InboxCaptureType
import com.projectnuke.fusion.inbox.InboxPreparation
import androidx.room.withTransaction
import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.retrieval.LocalRagInputPolicy
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.AttachmentImportResult
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import com.projectnuke.fusion.util.isPhysicalCleanupSettled
import com.projectnuke.fusion.util.normalizeUserVisibleName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Lightweight ContentResolver metadata for one shared document URI. */
data class SharedDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
)

/**
 * Non-destructive Android document handoff.
 *
 * URI inspection, managed copies, and typed admission happen while the old
 * NEW_CHAT draft remains untouched. Only a complete prepared payload reaches
 * ChatViewModel's one durable adoption boundary.
 */
internal class DocumentHandoffCoordinator(
    private val attachmentRoot: File,
    private val coordinatorProvider: () -> AttachmentImportCoordinator,
    private val metadataProvider: suspend (uriString: String) -> SharedDocumentMetadata?,
    private val deletePendingFile: suspend (String?) -> Boolean,
    private val releaseRemovedDraftPath: suspend (String?) -> RemovedAttachmentReleaseResult,
    private val notify: (String) -> Unit,
    private val canonicalPathOf: (File) -> String = { it.canonicalPath },
    private val admission:
        suspend (displayName: String, mimeType: String?, localPath: String) ->
            com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult =
        { _, mimeType, localPath ->
            com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.admit(
                path = localPath,
                mimeType = mimeType,
                fileName = localPath.substringAfterLast('/'),
                pdfAdmission = null,
                strictTextLimit = true,
            )
        },
    private val projectSource: ProjectFileCommitSource? = null,
) {
    constructor(context: Context) : this(
        attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
        coordinatorProvider = {
            AttachmentImportCoordinator(
                attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
                inputFactory = { source ->
                    try {
                        context.contentResolver.openInputStream(Uri.parse(source))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                },
            )
        },
        metadataProvider = { uriString ->
            try {
                val uri = Uri.parse(uriString)
                AttachmentStorageManager.queryUriMetadata(context, uri).let {
                    SharedDocumentMetadata(it.displayName, it.sizeBytes, it.mimeType)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        },
        deletePendingFile = { path -> AttachmentStorageManager.deletePendingAttachmentFile(context, path) },
        releaseRemovedDraftPath = { path ->
            val database = com.projectnuke.fusion.data.AppDatabase.getInstance(context)
            AttachmentStorageManager.releaseRemovedDraftAttachment(
                context = context,
                dao = database.chatDao(),
                projectDao = database.projectDao(),
                path = path,
            )
        },
        notify = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
        admission = { displayName, mimeType, localPath ->
            com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.admit(
                path = localPath,
                mimeType = mimeType,
                fileName = displayName,
                pdfAdmission = { path, mime ->
                    com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider.pdfAdmission(
                        context,
                        path,
                        mime,
                    )
                },
                strictTextLimit = true,
            )
        },
        projectSource = RoomProjectFileCommitSource(
            com.projectnuke.fusion.data.AppDatabase.getInstance(context),
        ),
    )

    suspend fun handle(
        handoff: ExternalDocumentHandoff,
        chatViewModel: ChatViewModel,
    ): HandoffImportOutcome {
        if (chatViewModel.isExternalHandoffAdopted(handoff.id)) {
            val partialNotice = chatViewModel.externalHandoffPartialNotice(handoff.id)
            if (partialNotice != null) notify(partialNotice)
            return if (partialNotice != null) HandoffImportOutcome.PARTIALLY_IMPORTED
            else HandoffImportOutcome.IMPORTED
        }

        val distinct = handoff.uriStrings.filter { it.isNotBlank() }.distinct()
        val total = handoff.totalDistinctUriCount.coerceAtLeast(distinct.size)
        if (distinct.isEmpty()) {
            notify("공유된 문서를 가져오지 못했습니다. 다시 시도해 주세요.")
            return HandoffImportOutcome.FAILED_IMPORT
        }
        val acceptedSlice = distinct.take(ExternalDocumentHandoff.MAX_HANDOFF_DOCS)
        val excessSkipped = (total - acceptedSlice.size).coerceAtLeast(0)
        val generatedPaths = mutableListOf<String>()
        val adoptedIdentities = mutableListOf<PendingAttachmentIdentity>()
        var unsupportedSkipped = 0
        var failedSkipped = 0
        var adopted = false
        try {
            // One shared batch budget across the bounded slice. No composer
            // import owner is acquired during this coordinator-owned stage.
            val batchCoordinator = coordinatorProvider()
            for (uriString in acceptedSlice) {
                when (importOne(
                    uriString = uriString,
                    declaredMimeType = handoff.declaredMimeType,
                    batchCoordinator = batchCoordinator,
                    generatedPaths = generatedPaths,
                    adoptedIdentities = adoptedIdentities,
                )) {
                    ImportOneOutcome.IMPORTED -> Unit
                    ImportOneOutcome.UNSUPPORTED -> unsupportedSkipped++
                    ImportOneOutcome.FAILED -> failedSkipped++
                }
            }
            if (adoptedIdentities.isEmpty()) {
                notify(
                    HandoffDocumentBatchCounts(
                        total = total,
                        attempted = acceptedSlice.size,
                        imported = 0,
                        unsupported = unsupportedSkipped,
                        failed = failedSkipped,
                        excess = excessSkipped,
                    ).userMessage("새 채팅"),
                )
                return HandoffImportOutcome.FAILED_IMPORT
            }

            val counts = HandoffDocumentBatchCounts(
                total = total,
                attempted = acceptedSlice.size,
                imported = adoptedIdentities.size,
                unsupported = unsupportedSkipped,
                failed = failedSkipped,
                excess = excessSkipped,
            )
            val partialNotice = counts.takeIf { it.isPartial }?.userMessage("새 채팅")
            val prepared = PreparedExternalHandoff(
                eventId = handoff.id,
                rawInput = "",
                pendingAttachments = adoptedIdentities.toList(),
                generatedPaths = generatedPaths.toList(),
                partialOutcomeNotice = partialNotice,
            )
            if (!prepared.isBounded()) {
                notify("공유된 문서를 가져오지 못했습니다.")
                return HandoffImportOutcome.FAILED_IMPORT
            }

            val adoption = try {
                chatViewModel.adoptPreparedExternalHandoff(prepared)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                // Bounded recoverable failure only: fatal Errors propagate
                // per the fatal-error policy.
                notify("새 채팅에 공유된 문서를 반영하지 못했습니다.")
                return HandoffImportOutcome.FAILED_IMPORT
            }
            if (!adoption.success) {
                notify("새 채팅에 공유된 문서를 반영하지 못했습니다.")
                return HandoffImportOutcome.FAILED_IMPORT
            }
            adopted = true

            // Publish the durable partial result before any suspending cleanup
            // so Compose cancellation after adoption cannot erase the only
            // user-visible count summary.
            if (counts.isPartial) {
                // Persist the truthful partial outcome immediately after the
                // durable composer adoption. Compose cancellation during later
                // cleanup must not let event recreation misreport it as full.
                val receiptDb = (projectSource as? RoomProjectFileCommitSource)
                receiptDb?.let { db ->
                    withContext(NonCancellable) {
                        ExternalHandoffSelectionRepository(db.database)
                            .settle(handoff.id, HandoffImportOutcome.PARTIALLY_IMPORTED.name)
                    }
                }
                notify(checkNotNull(partialNotice))
            }

            // Successful document admission is durable. Any staged file that
            // failed typed admission is still coordinator-owned and is cleaned
            // after adoption; it was never placed in the durable draft.
            cleanupPaths(generatedPaths - adoptedIdentities.map { it.localPath }.toSet())
            cleanupOldPaths(
                oldPaths = adoption.oldPendingAttachmentPaths,
                adoptedPaths = adoptedIdentities.map { it.localPath }.toSet(),
            )
            return if (counts.isPartial) HandoffImportOutcome.PARTIALLY_IMPORTED else HandoffImportOutcome.IMPORTED
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // Bounded recoverable failure only: fatal Errors (LinkageError /
            // VirtualMachineError) propagate per the fatal-error policy so a
            // broken runtime is never reported as FAILED_IMPORT with the
            // one-shot event consumed.
            notify("공유된 문서를 가져오지 못했습니다.")
            return HandoffImportOutcome.FAILED_IMPORT
        } finally {
            if (!adopted) cleanupPaths(generatedPaths)
        }
    }

    /** Routes the existing bounded document admission directly into durable Inbox ownership. */
    suspend fun handleToInbox(handoff: ExternalDocumentHandoff): HandoffImportOutcome {
        val source = projectSource ?: return HandoffImportOutcome.FAILED_IMPORT
        val inboxDao = source.inboxDao()
        val distinctUris = handoff.uriStrings.filter(String::isNotBlank).distinct()
        val uris = distinctUris.take(ExternalDocumentHandoff.MAX_HANDOFF_DOCS)
        val total = handoff.totalDistinctUriCount.coerceAtLeast(distinctUris.size)
        val excess = (total - uris.size).coerceAtLeast(0)
        if (distinctUris.isEmpty()) return HandoffImportOutcome.FAILED_IMPORT
        val existing = inboxDao.getForHandoff(handoff.id, ExternalDocumentHandoff.MAX_HANDOFF_DOCS)
        if (existing.isNotEmpty()) {
            if (existing.size > uris.size) return HandoffImportOutcome.FAILED_IMPORT
            val stored = existing.size
            val counts = HandoffDocumentBatchCounts(
                total = total, attempted = uris.size, imported = stored, unsupported = 0,
                failed = uris.size - stored, excess = excess,
            )
            if (counts.isPartial) notify(counts.userMessage("Inbox"))
            return if (counts.isPartial) HandoffImportOutcome.PARTIALLY_IMPORTED else HandoffImportOutcome.IMPORTED
        }
        val generatedPaths = mutableListOf<String>()
        val identities = mutableListOf<PendingAttachmentIdentity>()
        val sourceUris = mutableListOf<String>()
        val durablePaths = mutableSetOf<String>()
        var persisted = false
        var unsupported = 0
        var failed = 0
        var storedCount = 0
        try {
            if (uris.isEmpty()) return HandoffImportOutcome.FAILED_IMPORT
            val batch = coordinatorProvider()
            uris.forEach { uri ->
                when (importOne(uri, handoff.declaredMimeType, batch, generatedPaths, identities, sourceUris)) {
                    ImportOneOutcome.IMPORTED -> Unit
                    ImportOneOutcome.UNSUPPORTED -> unsupported++
                    ImportOneOutcome.FAILED -> failed++
                }
            }
            if (identities.isEmpty()) {
                notify(
                    HandoffDocumentBatchCounts(
                        total = total, attempted = uris.size, imported = 0,
                        unsupported = unsupported, failed = failed, excess = excess,
                    ).userMessage("Inbox"),
                )
                return HandoffImportOutcome.FAILED_IMPORT
            }
            val now = System.currentTimeMillis()
            val items = identities.mapIndexed { index, identity ->
                val capture = InboxCapture(
                    type = InboxCaptureType.DOCUMENT,
                    title = identity.name,
                    content = "",
                    originalUri = sourceUris[index],
                    managedFileId = identity.localPath,
                    managedMimeType = identity.mimeType,
                    sourceHandoffEventId = handoff.id,
                )
                when (val ready = InboxCapturePolicy.prepare(capture, now, handoffInboxItemId(handoff.id, index))) {
                    is InboxPreparation.Ready -> ready.item
                    else -> {
                        notify(
                            HandoffDocumentBatchCounts(
                                total = total, attempted = uris.size, imported = 0,
                                unsupported = unsupported, failed = uris.size - unsupported, excess = excess,
                            ).userMessage("Inbox"),
                        )
                        return HandoffImportOutcome.FAILED_IMPORT
                    }
                }
            }
            persisted = withContext(NonCancellable) {
                source.inSettlementTransaction {
                    val existing = inboxDao.getForHandoff(handoff.id, ExternalDocumentHandoff.MAX_HANDOFF_DOCS)
                    if (existing.isNotEmpty()) {
                        durablePaths += existing.mapNotNull { it.managedFileId }
                        storedCount = existing.size
                        true
                    } else if (inboxDao.insertAllBounded(items) == InboxInsertResult.Inserted) {
                        durablePaths += items.mapNotNull { it.managedFileId }
                        storedCount = items.size
                        true
                    } else false
                }
            }
            if (!persisted) {
                notify(
                    HandoffDocumentBatchCounts(
                        total = total, attempted = uris.size, imported = 0,
                        unsupported = unsupported, failed = uris.size - unsupported, excess = excess,
                    ).userMessage("Inbox"),
                )
                return HandoffImportOutcome.FAILED_IMPORT
            }
            val imported = storedCount.coerceAtMost(uris.size)
            val unsupportedCount = unsupported.coerceAtMost(uris.size - imported)
            val failedCount = if (storedCount == items.size) {
                failed.coerceAtMost(uris.size - imported - unsupportedCount)
            } else (uris.size - imported - unsupportedCount).coerceAtLeast(0)
            val counts = HandoffDocumentBatchCounts(
                total = total,
                attempted = uris.size,
                imported = imported,
                unsupported = unsupportedCount,
                failed = failedCount,
                excess = excess,
            )
            if (counts.isPartial) notify(counts.userMessage("Inbox"))
            durablePaths.forEach(AttachmentStorageManager::unregisterPendingAttachment)
            cleanupPaths(generatedPaths.filterNot(durablePaths::contains))
            return if (counts.isPartial) HandoffImportOutcome.PARTIALLY_IMPORTED else HandoffImportOutcome.IMPORTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            notify("공유 문서를 Inbox에 보관하지 못했습니다.")
            return HandoffImportOutcome.FAILED_IMPORT
        } finally {
            if (!persisted) cleanupPaths(generatedPaths)
        }
    }

    /** Adds a document batch to Project Knowledge while retaining a stable event receipt. */
    suspend fun handleToProject(handoff: ExternalDocumentHandoff, projectId: Long): HandoffImportOutcome {
        val source = projectSource ?: return HandoffImportOutcome.FAILED_IMPORT
        if (projectId <= 0L) return HandoffImportOutcome.FAILED_IMPORT
        val projectDao = source.projectDao()
        val commit = source.commit
        val eventPrefix = "${handoff.id}:%"
        val distinctUris = handoff.uriStrings.filter(String::isNotBlank).distinct()
        val uris = distinctUris.take(ExternalDocumentHandoff.MAX_HANDOFF_DOCS)
        val total = handoff.totalDistinctUriCount.coerceAtLeast(distinctUris.size)
        val excess = (total - uris.size).coerceAtLeast(0)
        if (distinctUris.isEmpty()) return HandoffImportOutcome.FAILED_IMPORT
        val existing = projectDao.getProjectFilesByHandoffPrefix(eventPrefix, ExternalDocumentHandoff.MAX_HANDOFF_DOCS)
        if (existing.isNotEmpty()) {
            if (existing.size > uris.size) return HandoffImportOutcome.FAILED_IMPORT
            if (!existing.all { it.projectId == projectId }) return HandoffImportOutcome.FAILED_IMPORT
            val stored = existing.size
            val counts = HandoffDocumentBatchCounts(
                total = total, attempted = uris.size, imported = stored, unsupported = 0,
                failed = uris.size - stored, excess = excess,
            )
            if (counts.isPartial) notify(counts.userMessage("Project"))
            return if (counts.isPartial) HandoffImportOutcome.PARTIALLY_IMPORTED else HandoffImportOutcome.IMPORTED
        }
        val generatedPaths = mutableListOf<String>()
        val identities = mutableListOf<PendingAttachmentIdentity>()
        val durablePaths = mutableSetOf<String>()
        var persisted = false
        var unsupported = 0
        var failed = 0
        var storedCount = 0
        try {
            if (uris.isEmpty() || projectDao.getProjectById(projectId) == null) return HandoffImportOutcome.FAILED_IMPORT
            val batch = coordinatorProvider()
            uris.forEach { uri ->
                when (importOne(uri, handoff.declaredMimeType, batch, generatedPaths, identities)) {
                    ImportOneOutcome.IMPORTED -> Unit
                    ImportOneOutcome.UNSUPPORTED -> unsupported++
                    ImportOneOutcome.FAILED -> failed++
                }
            }
            if (identities.isEmpty()) {
                notify(
                    HandoffDocumentBatchCounts(
                        total = total, attempted = uris.size, imported = 0,
                        unsupported = unsupported, failed = failed, excess = excess,
                    ).userMessage("Project"),
                )
                return HandoffImportOutcome.FAILED_IMPORT
            }
            // Atomic batch contract: every admitted document commits, or none
            // does. A normal return from a Room transaction COMMITS it, so a
            // mid-batch rejection throws [ProjectFileBatchRollback] and the
            // commit seam rolls the whole batch back. Returning false instead
            // previously committed the documents inserted before the rejection
            // while the caller reported a failed import and the cleanup path
            // deleted their still-owned files, leaving durable rows that point
            // at missing files.
            persisted = withContext(NonCancellable) {
                commit.inTransaction {
                    if (projectDao.getProjectById(projectId) == null) return@inTransaction false
                    val prior = projectDao.getProjectFilesByHandoffPrefix(eventPrefix, ExternalDocumentHandoff.MAX_HANDOFF_DOCS)
                    if (prior.isNotEmpty()) {
                        durablePaths += prior.map { it.localPath }
                        storedCount = prior.size
                        return@inTransaction prior.all { it.projectId == projectId }
                    }
                    // Preflight the whole batch against the durable capacity limit
                    // before any INSERT, so an over-capacity batch rolls back instead
                    // of committing a partial subset. The check stays inside the
                    // transaction so concurrent imports cannot both observe room.
                    val capacityBefore = projectDao.countProjectFiles(projectId)
                    val newSlots = identities
                        .map { it.localPath }
                        .distinct()
                        .count { path -> projectDao.getProjectFileByPath(projectId, path) == null }
                    if (capacityBefore + newSlots > ProjectFileContract.MAX_PROJECT_FILES) {
                        throw ProjectFileBatchRollback()
                    }
                    identities.forEachIndexed { index, identity ->
                        val file = ProjectFileEntity(
                            projectId = projectId,
                            displayName = identity.name,
                            mimeType = identity.mimeType,
                            localPath = identity.localPath,
                            addedAt = System.currentTimeMillis(),
                            sourceHandoffEventId = "${handoff.id}:$index",
                        )
                        when (projectDao.insertHandoffProjectFileBounded(file)) {
                            is com.projectnuke.fusion.data.BoundedProjectFileInsertResult.Inserted,
                            is com.projectnuke.fusion.data.BoundedProjectFileInsertResult.Existing,
                            -> durablePaths += identity.localPath
                            // Authority vanished or capacity raced us: roll back the
                            // whole batch rather than commit a partial subset.
                            else -> throw ProjectFileBatchRollback()
                        }
                    }
                    storedCount = identities.size
                    true
                }
            }
            if (!persisted) {
                notify(
                    HandoffDocumentBatchCounts(
                        total = total, attempted = uris.size, imported = 0,
                        unsupported = unsupported, failed = uris.size - unsupported, excess = excess,
                    ).userMessage("Project"),
                )
                return HandoffImportOutcome.FAILED_IMPORT
            }
            val imported = storedCount.coerceAtMost(uris.size)
            val unsupportedCount = unsupported.coerceAtMost(uris.size - imported)
            val failedCount = if (storedCount == identities.size) {
                failed.coerceAtMost(uris.size - imported - unsupportedCount)
            } else (uris.size - imported - unsupportedCount).coerceAtLeast(0)
            val counts = HandoffDocumentBatchCounts(
                total = total,
                attempted = uris.size,
                imported = imported,
                unsupported = unsupportedCount,
                failed = failedCount,
                excess = excess,
            )
            if (counts.isPartial) notify(counts.userMessage("Project"))
            durablePaths.forEach(AttachmentStorageManager::unregisterPendingAttachment)
            cleanupPaths(generatedPaths.filterNot(durablePaths::contains))
            return if (counts.isPartial) HandoffImportOutcome.PARTIALLY_IMPORTED else HandoffImportOutcome.IMPORTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            notify("공유 문서를 Project에 추가하지 못했습니다.")
            return HandoffImportOutcome.FAILED_IMPORT
        } finally {
            if (!persisted) cleanupPaths(generatedPaths)
        }
    }

    private enum class ImportOneOutcome { IMPORTED, UNSUPPORTED, FAILED }

    private suspend fun importOne(
        uriString: String,
        declaredMimeType: String?,
        batchCoordinator: AttachmentImportCoordinator,
        generatedPaths: MutableList<String>,
        adoptedIdentities: MutableList<PendingAttachmentIdentity>,
        acceptedSourceUris: MutableList<String>? = null,
    ): ImportOneOutcome {
        val metadata = try {
            metadataProvider(uriString)
        } catch (_: CancellationException) {
            throw CancellationException("document metadata cancelled")
        } catch (_: RuntimeException) {
            null
        }
        val rawName = metadata?.displayName
            ?: uriString.substringAfterLast('/').substringAfterLast(':').substringAfterLast('%')
        val displayName = normalizeUserVisibleName(rawName.ifBlank { null }, "문서", 48)
        val effectiveMime = metadata?.mimeType ?: declaredMimeType
        if (!LocalRagInputPolicy.isSupported(effectiveMime, displayName)) {
            return ImportOneOutcome.UNSUPPORTED
        }
        val result = try {
            batchCoordinator.copy(
                source = uriString,
                displayName = displayName,
                declaredLength = metadata?.sizeBytes,
            )
        } catch (_: CancellationException) {
            throw CancellationException("document copy cancelled")
        } catch (_: RuntimeException) {
            return ImportOneOutcome.FAILED
        }
        if (result !is AttachmentImportResult.Success) return ImportOneOutcome.FAILED
        // Explicit fatal-policy boundary on this narrow `File.canonicalPath`
        // conversion: structural cancellation rethrows, ordinary (Exception)
        // failures keep the existing bounded FAILED route, and fatal Errors
        // propagate instead of being converted into an ordinary failure.
        val canonical = try {
            canonicalPathOf(result.file)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            generatedPaths += result.file.absolutePath
            return ImportOneOutcome.FAILED
        }
        // From this point onward the coordinator owns the generated file even
        // when metadata admission or later durable adoption fails.
        generatedPaths += canonical

        val admissionResult = try {
            admission(displayName, effectiveMime, canonical)
        } catch (_: CancellationException) {
            throw CancellationException("document admission cancelled")
        } catch (_: RuntimeException) {
            com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected(
                com.projectnuke.fusion.retrieval.DocumentExtractionStatus.UNREADABLE,
            )
        }
        if (admissionResult !is com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted) {
            return ImportOneOutcome.FAILED
        }
        adoptedIdentities += PendingAttachmentIdentity(
            name = displayName,
            mimeType = effectiveMime ?: "text/plain",
            localPath = canonical,
        )
        acceptedSourceUris?.add(uriString)
        return ImportOneOutcome.IMPORTED
    }

    private fun handoffInboxItemId(eventId: String, index: Int): String = "${eventId.take(65)}-$index"

    private suspend fun cleanupPaths(paths: Collection<String>) = withContext(NonCancellable) {
        // Coordinator-owned staging copies (failed typed admission, failed
        // durable adoption): genuinely disposable, destructive cleanup is
        // the correct contract.
        paths.distinct().forEach { path ->
            val deleted = boundedHandoffOutcome { deletePendingFile(path) } ?: false
            if (deleted) AttachmentStorageManager.unregisterPendingAttachment(path)
        }
    }

    private suspend fun cleanupOldPaths(
        oldPaths: Collection<String>,
        adoptedPaths: Set<String>,
    ) = withContext(NonCancellable) {
        // Old-draft references removed by the durable replacement: they must
        // settle through the authority-aware release seam, never the
        // destructive staging cleanup above. Fatal Errors from the release
        // propagate (the adoption is durable and its receipt keeps any
        // replay idempotent); bounded failures retry conservatively later.
        oldPaths.asSequence()
            .distinct()
            .filterNot { it in adoptedPaths }
            .forEach { path ->
                val outcome = boundedHandoffOutcome { releaseRemovedDraftPath(path) }
                if (outcome != null && !outcome.isPhysicalCleanupSettled()) {
                    notify("이전 첨부 파일 정리를 나중에 다시 시도합니다.")
                }
            }
    }
}
