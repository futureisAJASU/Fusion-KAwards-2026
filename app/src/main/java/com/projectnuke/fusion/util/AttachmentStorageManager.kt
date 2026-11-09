package com.projectnuke.fusion.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.util.AttachmentMessageCodec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed class AttachmentClassification {
    data class Trusted(val file: File) : AttachmentClassification()
    data class Unavailable(val canonicalPath: String) : AttachmentClassification()
    data object Suspicious : AttachmentClassification()
}

data class AttachmentStorageStats(
    val totalBytes: Long,
    val totalFiles: Int,
    val referencedFiles: Int,
    val unreferencedFiles: Int
)

data class CleanupResult(
    val deletedFiles: Int
)

data class UriMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
)

internal sealed class PendingAttachmentDiscardResult {
    data object Deleted : PendingAttachmentDiscardResult()
    data object AlreadyAbsent : PendingAttachmentDiscardResult()
    data object InvalidPath : PendingAttachmentDiscardResult()
    data object InvalidTarget : PendingAttachmentDiscardResult()
    data object DeletionFailed : PendingAttachmentDiscardResult()
}

internal sealed class RemovedAttachmentReleaseResult {
    /** Physical file deleted (and its derived index), pending ownership settled. */
    data object Released : RemovedAttachmentReleaseResult()
    /** No physical file to release; pending ownership settled. */
    data object AlreadyAbsent : RemovedAttachmentReleaseResult()
    /** Durable draft removal failed: nothing was unregistered or deleted. */
    data object DurableRemovalFailed : RemovedAttachmentReleaseResult()
    /** No process pending-copy ownership: shared-by-reference file, reference-only. */
    data object ReferenceOnly : RemovedAttachmentReleaseResult()
    /** Pending ownership settled; a durable reference still names the path. */
    data object PreservedSharedReference : RemovedAttachmentReleaseResult()
    /** Pending ownership settled; an authority scan was unreadable, file kept. */
    data object PreservedAuthorityIncomplete : RemovedAttachmentReleaseResult()
    data object InvalidPath : RemovedAttachmentReleaseResult()
    data object InvalidTarget : RemovedAttachmentReleaseResult()
    /** Physical delete failed; path re-registered so cleanup keeps it. */
    data object DeletionFailed : RemovedAttachmentReleaseResult()
}

internal data class AttachmentCandidate(
    val name: String,
    val mimeType: String,
    val localPath: String
)

/**
 * True when a removed-draft release outcome needs no later physical retry:
 * the file was deleted, was already absent, or was intentionally preserved
 * (shared durable reference / reference-only). False outcomes must keep the
 * path in conservative cleanup bookkeeping (deletion debt, retry records)
 * so no component can claim a physical cleanup that did not settle.
 */
internal fun RemovedAttachmentReleaseResult.isPhysicalCleanupSettled(): Boolean = when (this) {
    RemovedAttachmentReleaseResult.Released,
    RemovedAttachmentReleaseResult.AlreadyAbsent,
    RemovedAttachmentReleaseResult.ReferenceOnly,
    RemovedAttachmentReleaseResult.PreservedSharedReference -> true
    RemovedAttachmentReleaseResult.DurableRemovalFailed,
    RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete,
    RemovedAttachmentReleaseResult.InvalidPath,
    RemovedAttachmentReleaseResult.InvalidTarget,
    RemovedAttachmentReleaseResult.DeletionFailed -> false
}

internal sealed interface AttachmentReferenceAuthority {
    data class Complete(val paths: Set<String>) : AttachmentReferenceAuthority
    data object IncompleteAuthority : AttachmentReferenceAuthority
}

internal fun extractAttachmentReferenceAuthority(
    contents: List<String>,
    attachmentRoot: File,
): AttachmentReferenceAuthority {
    val result = mutableSetOf<String>()
    for (raw in contents) {
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, attachmentRoot)
        if (parsed.suspiciousEnvelope) {
            // Do not salvage trusted-looking records from a mixed envelope:
            // the durable message reference authority is incomplete.
            return AttachmentReferenceAuthority.IncompleteAuthority
        }
        parsed.records.forEach { record ->
            result += record.localPath
        }
    }
    return AttachmentReferenceAuthority.Complete(result)
}

internal fun extractReferencedAttachmentPaths(
    contents: List<String>,
    attachmentRoot: File
): Set<String> = when (val authority = extractAttachmentReferenceAuthority(contents, attachmentRoot)) {
    is AttachmentReferenceAuthority.Complete -> authority.paths
    AttachmentReferenceAuthority.IncompleteAuthority -> emptySet()
}

internal fun canonicalAttachmentPath(path: String): String? =
    runCatching { File(path).canonicalPath }.getOrNull()

/**
 * Fail-closed durable reference authority probe for ONE managed path.
 *
 * A pending-copy registration proves that a process owner still claims the
 * copy. It does NOT prove exclusive physical ownership: a committed
 * ProjectFileEntity row, an Inbox row, a durable composer draft, or a
 * committed message envelope may name the very same physical file. Pending
 * registration is therefore never a licence to delete.
 *
 * @return true when an authoritative durable source names [path]; false when
 *   the complete authority is readable and names nothing; null when any
 *   authoritative source could not be read. Callers MUST treat null exactly
 *   like true — a physical release is never permitted from a partial
 *   authority read.
 */
internal suspend fun isManagedAttachmentDurablyReferenced(
    context: Context,
    path: String,
): Boolean? {
    val canonical = canonicalAttachmentPath(path) ?: return null
    val database = try {
        com.projectnuke.fusion.data.AppDatabase.getInstance(context)
    } catch (_: Exception) {
        return null
    }
    val projectPaths = try {
        database.projectDao().getAllProjectFilePaths()
    } catch (_: Exception) {
        return null
    }
    if (canonical in projectPaths.mapNotNull(::canonicalAttachmentPath)) return true
    val inboxPaths = try {
        database.inboxItemDao().getAllManagedFileIds()
    } catch (_: Exception) {
        return null
    }
    if (canonical in inboxPaths.mapNotNull(::canonicalAttachmentPath)) return true
    val draftPaths = when (
        val refs = com.projectnuke.fusion.chat.PersistentComposerDraftStore.durableDraftReferences(context)
    ) {
        is com.projectnuke.fusion.chat.DurableDraftReferences.Available -> refs.paths
        com.projectnuke.fusion.chat.DurableDraftReferences.Unknown -> return null
    }
    if (canonical in draftPaths.mapNotNull(::canonicalAttachmentPath)) return true
    val root = AttachmentStorageManager.getAttachmentDirectory(context)
    return when (val scan = scanMessageAttachmentReferences(database.chatDao(), root)) {
        is MessageAttachmentReferenceScan.Complete -> canonical in scan.paths
        MessageAttachmentReferenceScan.Failed,
        MessageAttachmentReferenceScan.IncompleteAuthority -> null
    }
}

/**
 * One durable managed-file reference set shared by stats, cleanup, and
 * recovery. Project rows are references just like message envelopes; a path
 * is live when any authoritative source names it.
 */
internal fun collectCanonicalAttachmentReferences(
    attachmentRoot: File,
    messageContents: List<String>,
    projectFilePaths: Collection<String> = emptyList(),
    durableDraftPaths: Collection<String> = emptyList(),
    pendingPaths: Collection<String> = emptyList(),
): Set<String> = (
    extractReferencedAttachmentPaths(messageContents, attachmentRoot) +
        projectFilePaths +
        durableDraftPaths +
        pendingPaths
    ).mapNotNull(::canonicalAttachmentPath).toSet()

internal const val ATTACHMENT_MESSAGE_PAGE_SIZE = 512

private sealed interface MessageAttachmentReferenceScan {
    data class Complete(val paths: Set<String>) : MessageAttachmentReferenceScan
    data object IncompleteAuthority : MessageAttachmentReferenceScan
    data object Failed : MessageAttachmentReferenceScan
}

private suspend fun scanMessageAttachmentReferences(
    dao: ChatDao,
    attachmentRoot: File,
): MessageAttachmentReferenceScan {
    val paths = mutableSetOf<String>()
    var afterId = 0L
    return try {
        while (true) {
            val page: List<MessageContentPageRow> = dao.getMessageContentPage(
                afterId = afterId,
                limit = ATTACHMENT_MESSAGE_PAGE_SIZE,
            )
            if (page.isEmpty()) return MessageAttachmentReferenceScan.Complete(paths)
            var pageAfterId = afterId
            for (row in page) {
                if (row.id <= pageAfterId) return MessageAttachmentReferenceScan.Failed
                pageAfterId = row.id
                when (val authority = extractAttachmentReferenceAuthority(listOf(row.content), attachmentRoot)) {
                    is AttachmentReferenceAuthority.Complete -> paths += authority.paths
                    AttachmentReferenceAuthority.IncompleteAuthority ->
                        return MessageAttachmentReferenceScan.IncompleteAuthority
                }
            }
            afterId = pageAfterId
        }
        MessageAttachmentReferenceScan.Failed
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        MessageAttachmentReferenceScan.Failed
    }
}

object AttachmentStorageManager {
    private const val recentFileGracePeriodMs = 5 * 60 * 1000L
    private val pendingAttachmentPaths = ConcurrentHashMap.newKeySet<String>()

    private suspend fun durableExternalManagedFilePaths(
        context: Context,
        projectDao: ProjectDao?,
    ): Collection<String>? {
        // A null ProjectDao is the existing isolated file-store mode used by
        // utility callers; do not discover the application database from an
        // arbitrary Context wrapper. Product cleanup paths pass the durable
        // ProjectDao and then include both Project and Inbox ownership.
        val durableProjectDao = projectDao ?: return emptyList()
        return try {
            durableProjectDao.getAllProjectFilePaths() +
                com.projectnuke.fusion.data.AppDatabase.getInstance(context).inboxItemDao().getAllManagedFileIds()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    fun getAttachmentDirectory(context: Context): File {
        return (context.getExternalFilesDir("attachments") ?: File(context.filesDir, "attachments")).apply {
            if (!exists()) mkdirs()
        }
    }

    fun registerPendingAttachment(file: File): String? {
        val canonical = safeCanonicalPath(file.absolutePath) ?: return null
        pendingAttachmentPaths.add(canonical)
        return canonical
    }

    fun unregisterPendingAttachment(path: String?) {
        if (path.isNullOrBlank()) return
        safeCanonicalPath(path)?.let { pendingAttachmentPaths.remove(it) }
    }

    internal suspend fun discardPendingAttachmentFile(
        context: Context,
        path: String?
    ): PendingAttachmentDiscardResult = withContext(Dispatchers.IO) {
        discardPendingAttachmentFile(
            attachmentRoot = getAttachmentDirectory(context),
            path = path,
            // The physical managed copy is gone: its derived document index
            // (if any) is garbage and becomes cleanup-eligible with it.
            onDeletedFile = {
                runCatching {
                    com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider
                        .indexRepository(context)
                        .deleteIndex(it.canonicalPath)
                }
            },
        )
    }

    suspend fun deletePendingAttachmentFile(
        context: Context,
        path: String?
    ): Boolean {
        return when (discardPendingAttachmentFile(context, path)) {
            PendingAttachmentDiscardResult.Deleted,
            PendingAttachmentDiscardResult.AlreadyAbsent -> true
            else -> false
        }
    }

    internal fun discardPendingAttachmentFile(
        attachmentRoot: File,
        path: String?,
        deleteFile: (File) -> Boolean = { it.delete() },
        onDeletedFile: (File) -> Unit = {},
    ): PendingAttachmentDiscardResult {
        if (path.isNullOrBlank()) return PendingAttachmentDiscardResult.InvalidPath

        val rootCanonical = runCatching { attachmentRoot.canonicalFile }.getOrNull()
            ?: return PendingAttachmentDiscardResult.InvalidPath
        val suppliedFile = runCatching { File(path) }.getOrNull()
            ?: return PendingAttachmentDiscardResult.InvalidPath
        val suppliedPath = runCatching { suppliedFile.toPath() }.getOrNull()
            ?: return PendingAttachmentDiscardResult.InvalidPath
        if (Files.isSymbolicLink(suppliedPath)) {
            return PendingAttachmentDiscardResult.InvalidTarget
        }

        val targetCanonical = runCatching { suppliedFile.canonicalFile }.getOrNull()
            ?: return PendingAttachmentDiscardResult.InvalidPath
        if (targetCanonical.parentFile?.canonicalPath != rootCanonical.canonicalPath) {
            return PendingAttachmentDiscardResult.InvalidPath
        }

        val targetPath = targetCanonical.canonicalPath
        if (!targetCanonical.exists()) {
            unregisterPendingAttachment(targetPath)
            return PendingAttachmentDiscardResult.AlreadyAbsent
        }
        if (!targetCanonical.isFile) {
            return PendingAttachmentDiscardResult.InvalidTarget
        }

        return if (deleteFile(targetCanonical)) {
            unregisterPendingAttachment(targetPath)
            onDeletedFile(targetCanonical)
            PendingAttachmentDiscardResult.Deleted
        } else {
            // Keep the registration so cleanup cannot race a file that failed to delete.
            PendingAttachmentDiscardResult.DeletionFailed
        }
    }

    internal fun isPendingAttachmentRegistered(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val canonical = safeCanonicalPath(path) ?: return false
        return canonical in pendingAttachmentPaths
    }

    internal fun pendingAttachmentPathsSnapshot(): Set<String> = pendingAttachmentPaths.toSet()

    /**
     * Targeted physical release of a path whose durable draft reference has
     * ALREADY been removed successfully (callers must never invoke this
     * before knowing the durable removal committed).
     *
     * Ordered contract (never reordered):
     * 1. Only a path with process pending-copy ownership may be physically
     *    released. A shared-by-reference managed file (e.g. an inventory
     *    reattach of a historical message's file) settles reference-only and
     *    the physical source is never touched.
     * 2. The draft's pending ownership is released before the durable
     *    reference scan; that registration is this draft's disposable-copy
     *    claim and is not a durable owner.
     * 3. The physical file is deleted only when the COMPLETE durable
     *    reference authority (message + project + durable draft + remaining
     *    pending) is readable and no source names the path. Any unreadable
     *    source fails closed and preserves the file.
     */
    internal fun releaseRemovedDraftAttachment(
        attachmentRoot: File,
        path: String?,
        messageAuthority: AttachmentReferenceAuthority,
        projectPaths: Collection<String>?,
        durableDraftPaths: Collection<String>?,
        deleteFile: (File) -> Boolean = { it.delete() },
        onDeletedFile: (File) -> Unit = {},
    ): RemovedAttachmentReleaseResult {
        if (path.isNullOrBlank()) return RemovedAttachmentReleaseResult.InvalidPath
        val canonical = safeCanonicalPath(path) ?: return RemovedAttachmentReleaseResult.InvalidPath
        if (!isPendingAttachmentRegistered(canonical)) {
            return RemovedAttachmentReleaseResult.ReferenceOnly
        }
        unregisterPendingAttachment(canonical)

        val messagePaths = when (val authority = messageAuthority) {
            is AttachmentReferenceAuthority.Complete -> authority.paths
            AttachmentReferenceAuthority.IncompleteAuthority ->
                return RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete
        }
        val projectFilePaths = projectPaths
            ?: return RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete
        val draftPaths = durableDraftPaths
            ?: return RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete

        val referenced = (
            messagePaths + projectFilePaths + draftPaths + pendingAttachmentPathsSnapshot()
            ).mapNotNull(::safeCanonicalPath).toSet()
        if (canonical in referenced) {
            return RemovedAttachmentReleaseResult.PreservedSharedReference
        }

        return when (val outcome = discardPendingAttachmentFile(
            attachmentRoot = attachmentRoot,
            path = canonical,
            deleteFile = deleteFile,
            onDeletedFile = onDeletedFile,
        )) {
            PendingAttachmentDiscardResult.Deleted -> RemovedAttachmentReleaseResult.Released
            PendingAttachmentDiscardResult.AlreadyAbsent -> RemovedAttachmentReleaseResult.AlreadyAbsent
            PendingAttachmentDiscardResult.InvalidPath -> RemovedAttachmentReleaseResult.InvalidPath
            PendingAttachmentDiscardResult.InvalidTarget -> RemovedAttachmentReleaseResult.InvalidTarget
            PendingAttachmentDiscardResult.DeletionFailed -> {
                // Re-settle protection: a file that failed to delete stays
                // pending-registered so cleanup cannot take it while the
                // physical release is unsettled.
                registerPendingAttachment(File(canonical))
                RemovedAttachmentReleaseResult.DeletionFailed
            }
        }
    }

    internal suspend fun releaseRemovedDraftAttachment(
        context: Context,
        dao: ChatDao,
        projectDao: ProjectDao?,
        path: String?,
    ): RemovedAttachmentReleaseResult {
        if (path.isNullOrBlank()) return RemovedAttachmentReleaseResult.InvalidPath
        return releaseRemovedDraftAttachments(context, dao, projectDao, listOf(path))[path]
            ?: RemovedAttachmentReleaseResult.InvalidPath
    }

    /**
     * Authority-aware batch release for one or more paths that were
     * successfully removed from a durable draft (fresh New Chat reset,
     * external handoff draft replacement, conversation deletion settlement,
     * cleanup-debt retry). Per-path semantics match
     * [releaseRemovedDraftAttachment] exactly; one authority scan is shared
     * across the batch. "Removed from a draft" never proves exclusive
     * physical ownership, so every path settles through the same
     * reference-authority contract as composer chip removal. The whole
     * authority read + physical deletion decision runs under the process-wide
     * reference-mutation gate, serialized with durable reference
     * creation/transfer.
     */
    internal suspend fun releaseRemovedDraftAttachments(
        context: Context,
        dao: ChatDao,
        projectDao: ProjectDao?,
        paths: Collection<String?>,
    ): Map<String, RemovedAttachmentReleaseResult> = withContext(Dispatchers.IO) {
        releaseRemovedDraftAttachmentsUnderMutationGate(
            attachmentRootProvider = { getAttachmentDirectory(context) },
            paths = paths,
            messageAuthority = {
                when (val scan = scanMessageAttachmentReferences(dao, getAttachmentDirectory(context))) {
                    is MessageAttachmentReferenceScan.Complete ->
                        AttachmentReferenceAuthority.Complete(scan.paths)
                    MessageAttachmentReferenceScan.IncompleteAuthority,
                    MessageAttachmentReferenceScan.Failed ->
                        AttachmentReferenceAuthority.IncompleteAuthority
                }
            },
            projectPaths = {
                durableExternalManagedFilePaths(context, projectDao)
            },
            durableDraftPaths = {
                when (
                    val refs = com.projectnuke.fusion.chat.PersistentComposerDraftStore.durableDraftReferences(context)
                ) {
                    is com.projectnuke.fusion.chat.DurableDraftReferences.Available -> refs.paths
                    com.projectnuke.fusion.chat.DurableDraftReferences.Unknown -> null
                }
            },
            // The physical managed copy is gone: its derived document index
            // (if any) is garbage and becomes cleanup-eligible with it.
            onDeletedFile = {
                runCatching {
                    com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider
                        .indexRepository(context)
                        .deleteIndex(it.canonicalPath)
                }
            },
        )
    }

    /**
     * Gate-serialized destructive release core: classification, the durable
     * reference authority reads, and the physical deletion decision all run
     * inside [AttachmentReferenceMutationCoordinator.withMutation], so the
     * scan can never be assembled across a durable reference
     * creation/transfer for the same files.
     */
    internal suspend fun releaseRemovedDraftAttachmentsUnderMutationGate(
        attachmentRootProvider: suspend () -> File,
        paths: Collection<String?>,
        messageAuthority: suspend () -> AttachmentReferenceAuthority,
        projectPaths: suspend () -> Collection<String>?,
        durableDraftPaths: suspend () -> Collection<String>?,
        deleteFile: (File) -> Boolean = { it.delete() },
        onDeletedFile: (File) -> Unit = {},
    ): Map<String, RemovedAttachmentReleaseResult> =
        AttachmentReferenceMutationCoordinator.withMutation {
            val distinct = paths.filterNotNull().filter { it.isNotBlank() }.distinct()
            if (distinct.isEmpty()) return@withMutation emptyMap()
            val outcomes = linkedMapOf<String, RemovedAttachmentReleaseResult>()
            val registered = mutableListOf<String>()
            for (path in distinct) {
                val canonical = safeCanonicalPath(path)
                when {
                    canonical == null -> outcomes[path] = RemovedAttachmentReleaseResult.InvalidPath
                    // No process pending-copy ownership (e.g. an inventory
                    // reattach sharing a historical file by reference):
                    // reference-only settlement, no authority scan needed.
                    !isPendingAttachmentRegistered(canonical) ->
                        outcomes[path] = RemovedAttachmentReleaseResult.ReferenceOnly
                    else -> registered += path
                }
            }
            if (registered.isEmpty()) return@withMutation outcomes
            val root = attachmentRootProvider()
            val messageAuthorityValue = messageAuthority()
            val projectPathsValue = projectPaths()
            val draftPathsValue = durableDraftPaths()
            registered.forEach { path ->
                outcomes[path] = releaseRemovedDraftAttachment(
                    attachmentRoot = root,
                    path = path,
                    messageAuthority = messageAuthorityValue,
                    projectPaths = projectPathsValue,
                    durableDraftPaths = draftPathsValue,
                    deleteFile = deleteFile,
                    onDeletedFile = onDeletedFile,
                )
            }
            outcomes
        }

    /**
     * Core batch release over precomputed durable reference authority.
     * Paths without process pending-copy ownership settle reference-only
     * without any authority mutation; shared, unreadable-authority, and
     * undeletable paths fail closed and preserve the physical file.
     */
    internal fun releaseRemovedDraftAttachments(
        attachmentRoot: File,
        paths: Collection<String?>,
        messageAuthority: AttachmentReferenceAuthority,
        projectPaths: Collection<String>?,
        durableDraftPaths: Collection<String>?,
        deleteFile: (File) -> Boolean = { it.delete() },
        onDeletedFile: (File) -> Unit = {},
    ): Map<String, RemovedAttachmentReleaseResult> {
        val outcomes = linkedMapOf<String, RemovedAttachmentReleaseResult>()
        val registered = mutableListOf<String>()
        for (path in paths.filterNotNull().filter { it.isNotBlank() }.distinct()) {
            val canonical = safeCanonicalPath(path)
            when {
                canonical == null -> outcomes[path] = RemovedAttachmentReleaseResult.InvalidPath
                !isPendingAttachmentRegistered(canonical) ->
                    outcomes[path] = RemovedAttachmentReleaseResult.ReferenceOnly
                else -> registered += path
            }
        }
        registered.forEach { path ->
            outcomes[path] = releaseRemovedDraftAttachment(
                attachmentRoot = attachmentRoot,
                path = path,
                messageAuthority = messageAuthority,
                projectPaths = projectPaths,
                durableDraftPaths = durableDraftPaths,
                deleteFile = deleteFile,
                onDeletedFile = onDeletedFile,
            )
        }
        return outcomes
    }

    internal fun classifyAttachment(attachmentRoot: File, path: String): AttachmentClassification {
        if (path.isBlank()) return AttachmentClassification.Suspicious
        val suppliedPath = runCatching { File(path).toPath() }.getOrNull()
            ?: return AttachmentClassification.Suspicious
        if (Files.isSymbolicLink(suppliedPath)) return AttachmentClassification.Suspicious
        val targetCanonical = safeCanonicalPath(path) ?: return AttachmentClassification.Suspicious
        val dirCanonical = safeCanonicalPath(attachmentRoot.absolutePath)
            ?: return AttachmentClassification.Suspicious
        if (targetCanonical == dirCanonical) return AttachmentClassification.Suspicious
        val prefix = "$dirCanonical${File.separator}"
        if (!targetCanonical.startsWith(prefix)) return AttachmentClassification.Suspicious
        val targetFile = File(targetCanonical)
        if (!targetFile.exists()) return AttachmentClassification.Unavailable(targetCanonical)
        if (!targetFile.isFile) return AttachmentClassification.Suspicious
        return AttachmentClassification.Trusted(targetFile)
    }

    internal fun resolveManagedAttachment(attachmentRoot: File, path: String): File? {
        return when (val result = classifyAttachment(attachmentRoot, path)) {
            is AttachmentClassification.Trusted -> result.file
            else -> null
        }
    }

    fun resolveManagedAttachment(context: Context, path: String): File? {
        return resolveManagedAttachment(getAttachmentDirectory(context), path)
    }

    suspend fun calculateAttachmentStorageStats(
        context: Context,
        dao: ChatDao,
        projectDao: ProjectDao? = null,
    ): AttachmentStorageStats = withContext(Dispatchers.IO) {
        val dir = getAttachmentDirectory(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val messagePaths = when (val scan = scanMessageAttachmentReferences(dao, dir)) {
            is MessageAttachmentReferenceScan.Complete -> scan.paths
            MessageAttachmentReferenceScan.IncompleteAuthority,
            MessageAttachmentReferenceScan.Failed -> {
                // A partial message scan cannot establish that any file is
                // dead. Keep statistics conservative until the next pass.
                return@withContext AttachmentStorageStats(
                    totalBytes = files.sumOf { it.length() },
                    totalFiles = files.size,
                    referencedFiles = files.size,
                    unreferencedFiles = 0,
                )
            }
        }
        val projectPaths = durableExternalManagedFilePaths(context, projectDao) ?: run {
            // A storage view must not mislabel project-owned files as dead if
            // the project reference authority is temporarily unreadable.
            return@withContext AttachmentStorageStats(
                totalBytes = files.sumOf { it.length() },
                totalFiles = files.size,
                referencedFiles = files.size,
                unreferencedFiles = 0,
            )
        }
        // Draft-only and pending-registration files are preserved by cleanup,
        // so statistics must count them as referenced instead of labeling
        // live draft attachments "unreferenced". An unreadable draft is an
        // incomplete authority and therefore keeps statistics fail-safe.
        val draftRefs = when (val refs = com.projectnuke.fusion.chat.PersistentComposerDraftStore.durableDraftReferences(context)) {
            is com.projectnuke.fusion.chat.DurableDraftReferences.Available -> refs.paths
            is com.projectnuke.fusion.chat.DurableDraftReferences.Unknown -> {
                return@withContext AttachmentStorageStats(
                    totalBytes = files.sumOf { it.length() },
                    totalFiles = files.size,
                    referencedFiles = files.size,
                    unreferencedFiles = 0,
                )
            }
        }
        val referencedCanonical = (
            messagePaths + projectPaths + draftRefs + pendingAttachmentPathsSnapshot()
            ).mapNotNull(::canonicalAttachmentPath).toSet()

        val referencedCount = files.count { file ->
            safeCanonicalPath(file.absolutePath)?.let { it in referencedCanonical } == true
        }

        AttachmentStorageStats(
            totalBytes = files.sumOf { it.length() },
            totalFiles = files.size,
            referencedFiles = referencedCount,
            unreferencedFiles = (files.size - referencedCount).coerceAtLeast(0)
        )
    }

    suspend fun queryUriMetadata(context: Context, uri: Uri): UriMetadata = withContext(Dispatchers.IO) {
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex).takeIf { it > 0L }
                    }
                }
            }
        }
        UriMetadata(
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull(),
        )
    }

    suspend fun cleanupUnreferencedAttachments(
        context: Context,
        dao: ChatDao,
        projectDao: ProjectDao? = null,
    ): CleanupResult {
        return withContext(Dispatchers.IO) {
            val dir = getAttachmentDirectory(context)
            safeCanonicalPath(dir.absolutePath) ?: return@withContext CleanupResult(deletedFiles = 0)
            val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            // The whole authority read + destructive sweep runs under the
            // process-wide reference-mutation gate, serialized with durable
            // reference creation/transfer (promotion, project import,
            // submission transfer), so no cross-time authority sampling can
            // manufacture a fake orphan during a draft->message transfer.
            val deleted = cleanupUnreferencedAttachmentsUnderMutationGate(
                attachmentDir = dir,
                listFiles = { files },
                messageAuthority = {
                    when (val scan = scanMessageAttachmentReferences(dao, dir)) {
                        is MessageAttachmentReferenceScan.Complete ->
                            AttachmentReferenceAuthority.Complete(scan.paths)
                        MessageAttachmentReferenceScan.IncompleteAuthority,
                        MessageAttachmentReferenceScan.Failed ->
                            AttachmentReferenceAuthority.IncompleteAuthority
                    }
                },
                projectPaths = {
                    durableExternalManagedFilePaths(context, projectDao)
                },
                durableDraftPaths = {
                    when (
                        val refs = com.projectnuke.fusion.chat.PersistentComposerDraftStore.durableDraftReferences(context)
                    ) {
                        is com.projectnuke.fusion.chat.DurableDraftReferences.Available -> refs.paths
                        com.projectnuke.fusion.chat.DurableDraftReferences.Unknown -> null
                    }
                },
                pendingPaths = ::pendingAttachmentPathsSnapshot,
            )

            // Derived document indexes are disposable artifacts that follow
            // their physical managed source: after unreferenced-file cleanup,
            // sweep every index whose source no longer exists (source authority
            // = physically present files + registered pending copies). This
            // never touches source attachments.
            runCatching {
                val live = (files.filter { it.exists() } +
                    pendingAttachmentPaths.mapNotNull(::safeCanonicalPath).map(::File))
                    .toSet()
                com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider
                    .indexRepository(context)
                    .cleanupOrphans(live)
            }

            CleanupResult(deletedFiles = deleted)
        }
    }

    /**
     * Gate-serialized orphan GC core: every durable reference authority read
     * (messages, projects, durable drafts, pending registrations), the file
     * listing, and the destructive delete loop run inside
     * [AttachmentReferenceMutationCoordinator.withMutation]. Any unreadable
     * authority fails closed and preserves every file.
     */
    internal suspend fun cleanupUnreferencedAttachmentsUnderMutationGate(
        attachmentDir: File,
        listFiles: () -> List<File>,
        messageAuthority: suspend () -> AttachmentReferenceAuthority,
        projectPaths: suspend () -> Collection<String>?,
        durableDraftPaths: suspend () -> Collection<String>?,
        pendingPaths: () -> Set<String>,
        now: () -> Long = System::currentTimeMillis,
        gracePeriodMs: Long = recentFileGracePeriodMs,
        deleteFile: (File) -> Boolean = { it.delete() },
    ): Int = AttachmentReferenceMutationCoordinator.withMutation {
        val messagePaths = when (val authority = messageAuthority()) {
            is AttachmentReferenceAuthority.Complete -> authority.paths
            // Never make destructive decisions from a partial Room
            // authority scan.
            AttachmentReferenceAuthority.IncompleteAuthority -> return@withMutation 0
        }
        // Project files are a durable reference authority. If that
        // authority cannot be read, preserve all managed files.
        val projectFilePaths = projectPaths() ?: return@withMutation 0
        // Fail-safe: unreadable draft references must not be read as "no
        // drafts". DB-unreferenced files are preserved until drafts become
        // readable again.
        val draftPaths = durableDraftPaths() ?: return@withMutation 0
        val referencedCanonical = (
            messagePaths + projectFilePaths + draftPaths + pendingPaths()
            ).mapNotNull(::canonicalAttachmentPath).toSet()
        val dirCanonical = runCatching { attachmentDir.canonicalFile }.getOrNull()
            ?: return@withMutation 0
        val currentTime = now()

        var deleted = 0
        listFiles().forEach { file ->
            if (!file.isFile) return@forEach
            val canonical = safeCanonicalPath(file.absolutePath) ?: return@forEach
            if (!isInAttachmentDirectory(canonical, dirCanonical.canonicalPath)) return@forEach
            if (canonical in referencedCanonical) return@forEach
            if (currentTime - file.lastModified() <= gracePeriodMs) return@forEach
            if (deleteFile(file)) {
                deleted += 1
            }
        }
        deleted
    }

    private fun extractReferencedAttachmentPaths(
        context: Context,
        contents: List<String>
    ): Set<String> {
        val attachmentRoot = getAttachmentDirectory(context)
        return extractReferencedAttachmentPaths(contents, attachmentRoot)
    }

    private fun safeCanonicalPath(path: String): String? {
        return runCatching { File(path).canonicalPath }.getOrNull()
    }

    private fun isInAttachmentDirectory(path: String, attachmentDirCanonical: String): Boolean {
        return path == attachmentDirCanonical || path.startsWith("$attachmentDirCanonical${File.separator}")
    }
}

/**
 * Ordered composer attachment removal: the durable draft reference is
 * removed first, and the physical pending-copy release runs ONLY after the
 * durable removal is known to have committed. A failed durable removal
 * settles with no pending unregistration and no physical mutation.
 *
 * Once the durable removal has committed, the physical release settles under
 * [NonCancellable]: process pending ownership cannot leak indefinitely while
 * the enclosing coroutine is torn down. Cancellation never turns uncertain
 * authority into a delete — the release seam fails closed on physical data,
 * and a cancellation arriving during the release still records on the job
 * while the physical decision runs to completion (no CE is swallowed).
 */
internal object ComposerAttachmentRelease {
    suspend fun remove(
        removeDurableReference: suspend () -> Boolean,
        releasePhysicalCopy: suspend () -> RemovedAttachmentReleaseResult,
    ): RemovedAttachmentReleaseResult {
        if (!removeDurableReference()) return RemovedAttachmentReleaseResult.DurableRemovalFailed
        return withContext(NonCancellable) { releasePhysicalCopy() }
    }
}

internal fun validateAttachmentBatch(
    candidates: List<AttachmentCandidate>,
    attachmentRoot: File
): List<AttachmentRecord>? {
    val result = mutableListOf<AttachmentRecord>()
    for (candidate in candidates) {
        when (val classification = AttachmentStorageManager.classifyAttachment(attachmentRoot, candidate.localPath)) {
            is AttachmentClassification.Trusted -> {
                result.add(AttachmentRecord(
                    name = candidate.name,
                    mimeType = candidate.mimeType,
                    localPath = classification.file.canonicalPath
                ))
            }
            else -> return null
        }
    }
    return result
}
