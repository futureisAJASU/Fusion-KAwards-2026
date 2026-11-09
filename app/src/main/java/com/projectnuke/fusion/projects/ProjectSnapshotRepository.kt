package com.projectnuke.fusion.projects

import android.content.Context
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.MAX_STUDY_REVIEW_CARDS
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.ui.ConversationSummaryMemory
import com.projectnuke.fusion.ui.loadConversationSummary
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.room.withTransaction

internal sealed interface ProjectSnapshotExportResult {
    data class Ready(val file: File) : ProjectSnapshotExportResult
    data object ProjectUnavailable : ProjectSnapshotExportResult
    data object UnauthorizedConversation : ProjectSnapshotExportResult
    data object SummaryUnavailable : ProjectSnapshotExportResult
    data object ContentUnavailable : ProjectSnapshotExportResult
    data object SnapshotTooLarge : ProjectSnapshotExportResult
    data object Failed : ProjectSnapshotExportResult
}

internal sealed interface ProjectSnapshotImportResult {
    data class Imported(val projectId: Long, val name: String, val savedItems: Int, val taskCount: Int, val cardCount: Int, val fileCount: Int) : ProjectSnapshotImportResult
    data object InvalidArchive : ProjectSnapshotImportResult
    data object SnapshotTooLarge : ProjectSnapshotImportResult
    data object ContentRejected : ProjectSnapshotImportResult
    data object Failed : ProjectSnapshotImportResult
}

private sealed interface ProjectSnapshotRowsRead {
    data class Loaded(
        val project: ProjectEntity,
        val conversations: List<Pair<ConversationSummaryMemory, ConversationEntity>>,
        val knowledge: List<SavedKnowledgeEntity>,
        val tasks: List<ProjectTaskEntity>,
        val cards: List<StudyReviewCardEntity>,
        val files: List<ProjectFileEntity>,
    ) : ProjectSnapshotRowsRead
    data object ProjectMissing : ProjectSnapshotRowsRead
    data object UnauthorizedConversation : ProjectSnapshotRowsRead
    data object SummaryInvalid : ProjectSnapshotRowsRead
}

/** Owns portable archive IO and the staged Room/file adoption boundary. */
internal class ProjectSnapshotRepository(
    context: Context,
    private val database: AppDatabase,
) {
    private val appContext = context.applicationContext

    suspend fun exportProject(
        projectId: Long,
        authorizedConversationIds: Set<Long>,
        authorizedConversations: List<ConversationSummaryMemory>,
        includeSourceFiles: Boolean,
        archivedContentAuthorized: Boolean = false,
    ): ProjectSnapshotExportResult = withContext(Dispatchers.IO) {
        var output: File? = null
        try {
            val projectDao = database.projectDao()
            val rows = database.withTransaction {
                val project = projectDao.getProjectById(projectId)
                    ?: return@withTransaction ProjectSnapshotRowsRead.ProjectMissing
                if (authorizedConversations.size > ProjectSnapshotBounds.MAX_CONVERSATIONS ||
                    authorizedConversations.map { it.conversationId }.distinct().size != authorizedConversations.size
                ) return@withTransaction ProjectSnapshotRowsRead.SummaryInvalid
                val conversations = mutableListOf<Pair<ConversationSummaryMemory, ConversationEntity>>()
                for (summary in authorizedConversations) {
                    if (summary.conversationId <= 0L || summary.conversationId !in authorizedConversationIds ||
                        projectDao.getProjectIdForConversation(summary.conversationId) != projectId
                    ) return@withTransaction ProjectSnapshotRowsRead.UnauthorizedConversation
                    val authoritativeSummary = loadConversationSummary(appContext, summary.conversationId)
                        ?: return@withTransaction ProjectSnapshotRowsRead.SummaryInvalid
                    if (authoritativeSummary.summary.length > ProjectSnapshotBounds.MAX_SUMMARY_CHARS ||
                        summary.summary.isBlank() || summary.summary.length > ProjectSnapshotBounds.MAX_SUMMARY_CHARS
                    ) {
                        return@withTransaction ProjectSnapshotRowsRead.SummaryInvalid
                    }
                    val conversation = database.chatDao().getConversationById(summary.conversationId)
                        ?: return@withTransaction ProjectSnapshotRowsRead.UnauthorizedConversation
                    if (conversation.isArchived && !archivedContentAuthorized) {
                        return@withTransaction ProjectSnapshotRowsRead.UnauthorizedConversation
                    }
                    conversations += summary to conversation
                }
                ProjectSnapshotRowsRead.Loaded(
                    project = project,
                    conversations = conversations,
                    knowledge = emptyList(), // computed after transaction via selectAccessibleBounded
                    tasks = database.projectTaskDao().getForProjectBounded(projectId, ProjectSnapshotBounds.MAX_TASKS + 1),
                    cards = database.studyReviewCardDao().getProjectCardsBounded(
                        projectId, ProjectSnapshotBounds.MAX_REVIEW_CARDS + 1,
                    ),
                    files = projectDao.getProjectFilesBounded(projectId, ProjectSnapshotBounds.MAX_FILES + 1),
                )
            }
            when (rows) {
                ProjectSnapshotRowsRead.ProjectMissing -> return@withContext ProjectSnapshotExportResult.ProjectUnavailable
                ProjectSnapshotRowsRead.UnauthorizedConversation -> return@withContext ProjectSnapshotExportResult.UnauthorizedConversation
                ProjectSnapshotRowsRead.SummaryInvalid -> return@withContext ProjectSnapshotExportResult.SummaryUnavailable
                is ProjectSnapshotRowsRead.Loaded -> Unit
            }
            rows as ProjectSnapshotRowsRead.Loaded
            val project = rows.project
            // Category-aware fetch: importer-generated SUMMARY and
            // FILE_REFERENCE rows share the saved_knowledge table but belong
            // to the conversation/file schema categories. The fetch covers the
            // genuine knowledge over-cap signal (256+1) plus the bounded
            // auxiliary maximums (32 summaries + 8 file references, the per
            // import limits), so genuine rows can never hide behind auxiliary
            // rows and auxiliary rows are never mistaken for knowledge.
            val accessibleRows = SavedKnowledgeAccessGate.selectAccessibleBounded(
                database,
                projectId,
                pageSize = 24,
                requiredCount = ProjectSnapshotBounds.MAX_KNOWLEDGE_ITEMS + 1 +
                    ProjectSnapshotBounds.MAX_CONVERSATIONS + ProjectSnapshotBounds.MAX_FILES,
                archiveAllowed = archivedContentAuthorized,
            )
            val partitioned = partitionProjectKnowledge(accessibleRows)
            val knowledgeRows = partitioned.knowledge
            val taskRows = rows.tasks
            val cardRows = rows.cards
            val fileRows = rows.files
            if (knowledgeRows.size > ProjectSnapshotBounds.MAX_KNOWLEDGE_ITEMS ||
                taskRows.size > ProjectSnapshotBounds.MAX_TASKS || cardRows.size > ProjectSnapshotBounds.MAX_REVIEW_CARDS ||
                fileRows.size > ProjectSnapshotBounds.MAX_FILES
            ) return@withContext ProjectSnapshotExportResult.SnapshotTooLarge
            if (SavedKnowledgePolicy.containsSensitiveMaterial(project.name + "\n" + project.instructions)) {
                return@withContext ProjectSnapshotExportResult.ContentUnavailable
            }
            val knowledge = knowledgeRows.map { item ->
                if (SavedKnowledgePolicy.containsSensitiveMaterial(item.title + "\n" + item.savedText + "\n" + item.sourceUrl.orEmpty())) {
                    return@withContext ProjectSnapshotExportResult.ContentUnavailable
                }
                ProjectSnapshotKnowledge(
                    title = item.title,
                    text = item.savedText,
                    provenanceType = item.provenanceType,
                    sourceConversationId = item.sourceConversationId?.takeIf { id -> id in authorizedConversationIds },
                    sourceMessageId = item.sourceMessageId?.takeIf { item.sourceConversationId in authorizedConversationIds },
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    sourceUrl = item.sourceUrl,
                    sourcePageTitle = item.sourcePageTitle,
                    retrievedAt = item.retrievedAt,
                )
            }
            val tasks = taskRows.map { item ->
                if (SavedKnowledgePolicy.containsSensitiveMaterial(item.title + "\n" + item.note)) {
                    return@withContext ProjectSnapshotExportResult.ContentUnavailable
                }
                ProjectSnapshotTask(
                    item.title, item.note, item.state,
                    item.sourceConversationId?.takeIf { id -> id in authorizedConversationIds },
                    item.sourceMessageId?.takeIf { item.sourceConversationId in authorizedConversationIds },
                    item.createdAt, item.updatedAt,
                )
            }
            val cards = cardRows.map {
                if (SavedKnowledgePolicy.containsSensitiveMaterial(it.front + "\n" + it.back)) {
                    return@withContext ProjectSnapshotExportResult.ContentUnavailable
                }
                ProjectSnapshotReviewCard(
                    front = it.front,
                    back = it.back,
                    sourceKind = it.sourceKind,
                    reviewCount = it.reviewCount,
                    lastRating = it.lastRating,
                    dueAt = it.dueAt,
                    lastReviewedAt = it.lastReviewedAt,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
            val payloads = linkedMapOf<Int, ByteArray>()
            var totalPayloadBytes = 0L
            val reconstructedRefs = reconstructSnapshotFiles(partitioned.fileReferences)
                ?: return@withContext ProjectSnapshotExportResult.Failed
            val snapshotFiles = buildList {
                fileRows.forEachIndexed { index, row ->
                    val source = AttachmentStorageManager.resolveManagedAttachment(appContext, row.localPath)
                        ?: return@withContext ProjectSnapshotExportResult.ContentUnavailable
                    val size = source.length()
                    var payloadIndex: Int? = null
                    var hash: String? = null
                    if (includeSourceFiles) {
                        if (size !in 1..ProjectSnapshotBounds.MAX_SOURCE_FILE_BYTES) {
                            return@withContext ProjectSnapshotExportResult.SnapshotTooLarge
                        }
                        val bytes = readFileBounded(source, ProjectSnapshotBounds.MAX_SOURCE_FILE_BYTES)
                            ?: return@withContext ProjectSnapshotExportResult.ContentUnavailable
                        totalPayloadBytes += bytes.size
                        if (totalPayloadBytes > ProjectSnapshotBounds.MAX_ARCHIVE_UNCOMPRESSED_BYTES - ProjectSnapshotBounds.MAX_MANIFEST_BYTES) {
                            return@withContext ProjectSnapshotExportResult.SnapshotTooLarge
                        }
                        payloadIndex = index
                        hash = sha256(bytes)
                        payloads[index] = bytes
                    }
                    add(ProjectSnapshotFile(row.displayName, row.mimeType, row.addedAt, size, hash, payloadIndex))
                }
                reconstructedRefs.forEach { ref ->
                    add(ProjectSnapshotFile(ref.displayName, ref.mimeType, ref.addedAt, ref.sizeBytes, null, null))
                }
            }
            if (snapshotFiles.size > ProjectSnapshotBounds.MAX_FILES) {
                return@withContext ProjectSnapshotExportResult.SnapshotTooLarge
            }
            val suppliedConversations = rows.conversations.map { (summary, conversation) ->
                val clean = summary.summary.trim()
                if (SavedKnowledgePolicy.containsSensitiveMaterial(conversation.title + "\n" + clean)) {
                    return@withContext ProjectSnapshotExportResult.ContentUnavailable
                }
                ProjectSnapshotConversation(summary.conversationId, conversation.title.take(200), clean, conversation.updatedAt)
            }
            val reconstructedConversations = reconstructSnapshotConversations(
                partitioned.summaries,
                suppliedConversations.map { it.sourceId }.toSet(),
            )
            reconstructedConversations.forEach { item ->
                if (SavedKnowledgePolicy.containsSensitiveMaterial(item.title + "\n" + item.summary)) {
                    return@withContext ProjectSnapshotExportResult.ContentUnavailable
                }
            }
            val allConversations = suppliedConversations + reconstructedConversations
            if (allConversations.size > ProjectSnapshotBounds.MAX_CONVERSATIONS) {
                return@withContext ProjectSnapshotExportResult.SnapshotTooLarge
            }
            val snapshot = ProjectSnapshot(
                name = project.name.trim().take(ProjectSnapshotBounds.MAX_PROJECT_NAME_CHARS),
                instructions = project.instructions,
                createdAt = project.createdAt,
                exportedAt = System.currentTimeMillis(),
                conversations = allConversations,
                knowledge = knowledge,
                tasks = tasks,
                reviewCards = cards,
                files = snapshotFiles,
            )
            val stagingDir = File(appContext.cacheDir, "project-snapshot-exports").apply { mkdirs() }
            output = File(stagingDir, "fusion-project-${UUID.randomUUID()}.zip")
            FileOutputStream(output).use { stream -> ProjectSnapshotArchiveCodec.write(snapshot, payloads, stream) }
            if (output.length() > ProjectSnapshotBounds.MAX_ARCHIVE_INPUT_BYTES) {
                output.delete()
                return@withContext ProjectSnapshotExportResult.SnapshotTooLarge
            }
            ProjectSnapshotExportResult.Ready(output)
        } catch (cancelled: CancellationException) {
            output?.delete()
            throw cancelled
        } catch (_: IllegalArgumentException) {
            output?.delete()
            ProjectSnapshotExportResult.SnapshotTooLarge
        } catch (_: Exception) {
            output?.delete()
            ProjectSnapshotExportResult.Failed
        }
    }

    suspend fun importSnapshot(input: java.io.InputStream): ProjectSnapshotImportResult = withContext(Dispatchers.IO) {
        val pendingFiles = mutableListOf<String>()
        try {
            val archive = try {
                ProjectSnapshotArchiveCodec.read(input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext ProjectSnapshotImportResult.InvalidArchive
            }
            val snapshot = archive.snapshot
            if (containsSensitiveContent(snapshot)) return@withContext ProjectSnapshotImportResult.ContentRejected
            val now = System.currentTimeMillis()
            var importedName = ""
            val uniqueDisplayNames = ProjectSnapshotImportPolicy.uniqueFileNames(snapshot.files.map { it.displayName })
            val managedFiles = snapshot.files.mapIndexedNotNull { index, source ->
                val payloadIndex = source.payloadIndex ?: return@mapIndexedNotNull null
                if (!ProjectSnapshotImportPolicy.isEligibleFile(source.mimeType, source.displayName)) {
                    return@withContext ProjectSnapshotImportResult.InvalidArchive
                }
                val bytes = archive.payloads[payloadIndex] ?: return@withContext ProjectSnapshotImportResult.InvalidArchive
                val root = AttachmentStorageManager.getAttachmentDirectory(appContext).canonicalFile
                val extension = safeExtension(source.displayName)
                val target = File(root, "snapshot_${UUID.randomUUID()}$extension")
                require(target.parentFile?.canonicalPath == root.canonicalPath)
                val registered = AttachmentReferenceMutationCoordinator.withMutation {
                    require(target.createNewFile())
                    AttachmentStorageManager.registerPendingAttachment(target).also { path ->
                        if (path == null) target.delete() else pendingFiles += path
                    }
                }
                if (registered == null) return@withContext ProjectSnapshotImportResult.Failed
                try {
                    FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
                } catch (failure: Throwable) {
                    target.delete()
                    throw failure
                }
                ImportedManagedFile(
                    displayName = uniqueDisplayNames[index],
                    mimeType = source.mimeType,
                    localPath = registered,
                    addedAt = source.addedAt,
                )
            }
            val importedId = AttachmentReferenceMutationCoordinator.withMutation {
                withContext(NonCancellable) {
                    database.withTransaction {
                        if (database.studyReviewCardDao().count() + snapshot.reviewCards.size > MAX_STUDY_REVIEW_CARDS) {
                            throw SnapshotCapacityException()
                        }
                        importedName = ProjectSnapshotImportPolicy.availableName(snapshot.name) {
                            database.projectDao().hasProjectName(it)
                        }
                        val projectId = database.projectDao().insertProject(
                            ProjectEntity(name = importedName, instructions = snapshot.instructions,
                                createdAt = snapshot.createdAt, updatedAt = now),
                        )
                        snapshot.knowledge.forEach { item ->
                            database.savedKnowledgeDao().insert(
                                SavedKnowledgeEntity(
                                    id = UUID.randomUUID().toString(), scope = "PROJECT", projectId = projectId,
                                    title = item.title, savedText = item.text,
                                    provenanceType = SNAPSHOT_PROVENANCE_KNOWLEDGE, sourceConversationId = null, sourceMessageId = null,
                                    createdAt = item.createdAt, updatedAt = item.updatedAt,
                                    sourceUrl = item.sourceUrl, sourcePageTitle = item.sourcePageTitle, retrievedAt = item.retrievedAt,
                                ),
                            )
                        }
                        snapshot.conversations.forEach { conversation ->
                            val title = (conversation.title + " · 요약").take(ProjectSnapshotBounds.MAX_KNOWLEDGE_TITLE_CHARS)
                            database.savedKnowledgeDao().insert(
                                SavedKnowledgeEntity(
                                    id = UUID.randomUUID().toString(), scope = "PROJECT", projectId = projectId,
                                    title = title, savedText = conversation.summary,
                                    provenanceType = SNAPSHOT_PROVENANCE_SUMMARY, sourceConversationId = null, sourceMessageId = null,
                                    createdAt = conversation.updatedAt, updatedAt = conversation.updatedAt,
                                ),
                            )
                        }
                        snapshot.files.forEach { file ->
                            if (file.payloadIndex == null) {
                                database.savedKnowledgeDao().insert(
                                    SavedKnowledgeEntity(
                                        id = UUID.randomUUID().toString(), scope = "PROJECT", projectId = projectId,
                                        title = ("자료 참조 · " + file.displayName).take(ProjectSnapshotBounds.MAX_KNOWLEDGE_TITLE_CHARS),
                                        savedText = "원본 자료는 스냅샷에 포함되지 않았습니다. 형식: ${file.mimeType}, 크기: ${file.sizeBytes} bytes",
                                        provenanceType = SNAPSHOT_PROVENANCE_FILE_REFERENCE,
                                        sourceConversationId = null, sourceMessageId = null,
                                        createdAt = file.addedAt, updatedAt = file.addedAt,
                                    ),
                                )
                            }
                        }
                        snapshot.tasks.forEach { task ->
                            database.projectTaskDao().insert(
                                ProjectTaskEntity(
                                    projectId = projectId, title = task.title, note = task.note, state = task.state,
                                    sourceConversationId = null, sourceMessageId = null,
                                    createdAt = task.createdAt, updatedAt = task.updatedAt,
                                    reminderAt = null, reminderEnabled = false,
                                ),
                            )
                        }
                        snapshot.reviewCards.forEach { card ->
                            database.studyReviewCardDao().insert(
                                StudyReviewCardEntity(
                                    id = UUID.randomUUID().toString(), scope = "PROJECT", projectId = projectId,
                                    front = card.front, back = card.back, sourceKind = ReviewCardOrigin.SNAPSHOT_IMPORT.name,
                                    sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null,
                                    sourceProjectId = null, sourceProjectFileId = null,
                                    reviewCount = card.reviewCount, lastRating = card.lastRating, dueAt = card.dueAt,
                                    lastReviewedAt = card.lastReviewedAt, createdAt = card.createdAt, updatedAt = card.updatedAt,
                                    reminderAt = null, reminderEnabled = false,
                                ),
                            )
                        }
                        managedFiles.forEach { file ->
                            database.projectDao().insertProjectFile(
                                ProjectFileEntity(
                                    projectId = projectId, displayName = file.displayName, mimeType = file.mimeType,
                                    localPath = file.localPath, addedAt = file.addedAt,
                                ),
                            )
                        }
                        projectId
                    }.also {
                        // Pending ownership is released to the durable owner on
                        // commit; cleanupPending re-queries authority in finally
                        // instead of trusting a local committed flag.
                        pendingFiles.forEach(AttachmentStorageManager::unregisterPendingAttachment)
                    }
                }
            }
            // The commit already happened and pending ownership is settled.
            // Reporting failure here would falsely claim no project was created
            // while the durable row exists, so the truthful imported result is
            // returned even when the caller's job was canceled after commit.
            // Cancellation is not swallowed wholesale: a cancellation arriving
            // BEFORE this point still propagates, and only an outcome that is
            // already durable is delivered instead of a false failure.
            ProjectSnapshotImportResult.Imported(
                importedId, importedName,
                snapshot.knowledge.size + snapshot.conversations.size + snapshot.files.count { it.payloadIndex == null },
                snapshot.tasks.size, snapshot.reviewCards.size, managedFiles.size,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SnapshotCapacityException) {
            ProjectSnapshotImportResult.SnapshotTooLarge
        } catch (_: Exception) {
            ProjectSnapshotImportResult.Failed
        } finally {
            // Authority-only cleanup gate: cleanupPending re-queries durable
            // Project/Inbox references and preserves any file still named by a
            // durable row, so it is safe and idempotent to run unconditionally.
            cleanupPending(pendingFiles)
            runCatching { input.close() }
        }
    }

    private suspend fun cleanupPending(paths: List<String>) = withContext(NonCancellable + Dispatchers.IO) {
        AttachmentReferenceMutationCoordinator.withMutation {
            val referenced = try {
                database.projectDao().getAllProjectFilePaths().toSet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Fail closed: unreadable authority preserves all pending files.
                null
            }
            paths.forEach { path ->
                if (referenced != null && path in referenced) {
                    // The row committed despite the caller observing failure:
                    // settle pending ownership and preserve the adopted file.
                    runCatching { AttachmentStorageManager.unregisterPendingAttachment(path) }
                } else if (referenced != null) {
                    AttachmentStorageManager.deletePendingAttachmentFile(appContext, path)
                } else {
                    // Authority unreadable: keep pending registration so later
                    // cleanup passes remain eligible instead of deleting blindly.
                    Unit
                }
            }
        }
    }

    private fun containsSensitiveContent(snapshot: ProjectSnapshot): Boolean =
        SavedKnowledgePolicy.containsSensitiveMaterial(snapshot.name + "\n" + snapshot.instructions) ||
            snapshot.knowledge.any { SavedKnowledgePolicy.containsSensitiveMaterial(it.title + "\n" + it.text) } ||
            snapshot.tasks.any { SavedKnowledgePolicy.containsSensitiveMaterial(it.title + "\n" + it.note) } ||
            snapshot.reviewCards.any { SavedKnowledgePolicy.containsSensitiveMaterial(it.front + "\n" + it.back) } ||
            snapshot.conversations.any { SavedKnowledgePolicy.containsSensitiveMaterial(it.title + "\n" + it.summary) } ||
            snapshot.files.any { SavedKnowledgePolicy.containsSensitiveMaterial(it.displayName) }

    private fun readFileBounded(file: File, maxBytes: Long): ByteArray? {
        if (!file.isFile || file.length() !in 1..maxBytes) return null
        FileInputStream(file).use { input ->
            val output = java.io.ByteArrayOutputStream(file.length().toInt())
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return null
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun safeExtension(name: String): String {
        val candidate = name.substringAfterLast('.', "").lowercase()
        return if (candidate.matches(Regex("[a-z0-9]{1,8}"))) ".$candidate" else ".bin"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class ImportedManagedFile(val displayName: String, val mimeType: String, val localPath: String, val addedAt: Long)
    private class SnapshotCapacityException : Exception()
}

internal object ProjectSnapshotImportPolicy {
    suspend fun availableName(raw: String, isTaken: suspend (String) -> Boolean): String {
        val base = baseName(raw)
        if (!isTaken(base)) return base
        for (suffix in 1..999) {
            val candidate = suffixedName(base, suffix)
            if (!isTaken(candidate)) return candidate
        }
        error("Project name collision limit reached")
    }

    fun resolveAvailableName(raw: String, existing: List<String>): String {
        val occupied = existing.map(String::lowercase).toSet()
        val base = baseName(raw)
        if (base.lowercase() !in occupied) return base
        for (suffix in 1..999) {
            val candidate = suffixedName(base, suffix)
            if (candidate.lowercase() !in occupied) return candidate
        }
        error("Project name collision limit reached")
    }

    private fun baseName(raw: String): String =
        raw.trim().take(ProjectSnapshotBounds.MAX_PROJECT_NAME_CHARS).ifBlank { "Project" }

    private fun suffixedName(base: String, suffix: Int): String {
        val marker = if (suffix == 1) " (가져옴)" else " (가져옴 $suffix)"
        return base.take(ProjectSnapshotBounds.MAX_PROJECT_NAME_CHARS - marker.length).trimEnd() + marker
    }

    fun uniqueFileNames(names: List<String>): List<String> {
        val maxChars = ProjectSnapshotBounds.MAX_FILE_DISPLAY_NAME_CHARS
        val used = mutableSetOf<String>()
        return names.map { rawName ->
            val name = rawName.take(maxChars)
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            var candidate = name
            var suffix = 2
            while (!used.add(candidate.lowercase())) {
                val marker = " ($suffix)"
                val extensionBudget = (maxChars - marker.length).coerceAtLeast(0)
                val boundedExtension = extension.take(extensionBudget)
                val stemBudget = (maxChars - marker.length - boundedExtension.length).coerceAtLeast(0)
                candidate = stem.take(stemBudget) + marker + boundedExtension
                suffix++
            }
            candidate
        }
    }

    fun isEligibleFile(mimeType: String, displayName: String): Boolean =
        com.projectnuke.fusion.ui.ProjectFilePolicy.isSupported(mimeType, displayName)
}
