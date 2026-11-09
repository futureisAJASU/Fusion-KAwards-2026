package com.projectnuke.fusion.knowledge

import android.content.Context
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.SavedKnowledgeNoteSourceEntity
import com.projectnuke.fusion.ui.RoomSourceAccessLookups
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class ProjectKnowledgeAssistantSourceProof(
    val conversationId: Long?,
    val conversationArchived: Boolean,
    val messageId: Long?,
    val messageConversationId: Long?,
    val messageRole: String?,
)

internal data class ProjectKnowledgeDocumentSourceProof(
    val sourceProjectId: Long,
    val sourceFileId: Long,
    val fileProjectId: Long?,
    val projectExists: Boolean,
    val fileAvailable: Boolean,
)

/** Narrow read boundary makes health classification and its failure behavior deterministic in tests. */
internal interface ProjectKnowledgeHealthDataSource {
    suspend fun projectItems(projectId: Long, limit: Int): List<SavedKnowledgeEntity>
    suspend fun assistantSourceProof(row: SavedKnowledgeEntity): ProjectKnowledgeAssistantSourceProof
    suspend fun documentSourceProof(row: SavedKnowledgeEntity): ProjectKnowledgeDocumentSourceProof?
    suspend fun noteSources(noteId: String, limit: Int): List<SavedKnowledgeNoteSourceEntity>
    suspend fun noteSourceAccessible(source: SavedKnowledgeNoteSourceEntity, archiveAllowed: Boolean): Boolean
    suspend fun savedKnowledge(id: String): SavedKnowledgeEntity?
    suspend fun rowAccessible(row: SavedKnowledgeEntity, archiveAllowed: Boolean): Boolean
}

internal class RoomProjectKnowledgeHealthDataSource(
    private val context: Context,
    private val database: AppDatabase,
) : ProjectKnowledgeHealthDataSource {
    private val lookups = RoomSourceAccessLookups(context.applicationContext, database)

    override suspend fun projectItems(projectId: Long, limit: Int) =
        database.savedKnowledgeDao().getProjectItemsBounded(projectId, limit)

    override suspend fun assistantSourceProof(row: SavedKnowledgeEntity): ProjectKnowledgeAssistantSourceProof {
        val conversationId = row.sourceConversationId
        val messageId = row.sourceMessageId
        if (conversationId == null || messageId == null) {
            return ProjectKnowledgeAssistantSourceProof(null, false, null, null, null)
        }
        val conversation = database.chatDao().getConversationById(conversationId)
            ?: return ProjectKnowledgeAssistantSourceProof(null, false, null, null, null)
        val message = try {
            database.chatDao().getMessageById(messageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return ProjectKnowledgeAssistantSourceProof(
            conversation.id,
            conversation.isArchived,
            message?.id,
            message?.conversationId,
            message?.role,
        )
    }

    override suspend fun documentSourceProof(row: SavedKnowledgeEntity): ProjectKnowledgeDocumentSourceProof? {
        val source = database.documentHighlightSourceDao().getByKnowledgeId(row.id) ?: return null
        val file = database.projectDao().getProjectFileById(source.projectFileId) ?: return null
        val projectExists = database.projectDao().getProjectById(source.projectId)?.id == source.projectId
        val fileAvailable = AttachmentStorageManager.resolveManagedAttachment(context, file.localPath)
            ?.canRead() == true
        return ProjectKnowledgeDocumentSourceProof(
            source.projectId,
            source.projectFileId,
            file.projectId,
            projectExists,
            fileAvailable,
        )
    }

    override suspend fun noteSources(noteId: String, limit: Int) =
        database.savedKnowledgeNoteSourceDao().getForNote(noteId, limit)

    override suspend fun noteSourceAccessible(
        source: SavedKnowledgeNoteSourceEntity,
        archiveAllowed: Boolean,
    ): Boolean {
        val identity = when (source.sourceKind) {
            SelectionSourceKind.CONVERSATION.name -> source.conversationId?.let {
                SelectionSourceIdentity(SelectionSourceKind.CONVERSATION, conversationId = it)
            }
            SelectionSourceKind.PROJECT.name -> source.projectId?.let {
                SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = it)
            }
            SelectionSourceKind.PROJECT_DOCUMENT.name -> if (source.projectId != null && source.projectFileId != null) {
                SelectionSourceIdentity(SelectionSourceKind.PROJECT_DOCUMENT, projectId = source.projectId, fileId = source.projectFileId)
            } else null
            SelectionSourceKind.SAVED_KNOWLEDGE.name -> source.savedKnowledgeId?.let {
                SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = it)
            }
            else -> null
        } ?: return false
        return SourceAccessAuthority({ archiveAllowed }, lookups).isAccessible(identity)
    }

    override suspend fun savedKnowledge(id: String): SavedKnowledgeEntity? = database.savedKnowledgeDao().getById(id)

    override suspend fun rowAccessible(row: SavedKnowledgeEntity, archiveAllowed: Boolean): Boolean =
        SavedKnowledgeAccessGate.isRowAccessible(database, row, archiveAllowed)
}

/** Read-only bounded Project provenance/access inspection. */
internal class ProjectKnowledgeHealthRepository(
    private val source: ProjectKnowledgeHealthDataSource,
    private val includeArchived: () -> Boolean,
) {
    constructor(context: Context, database: AppDatabase, includeArchived: () -> Boolean) : this(
        RoomProjectKnowledgeHealthDataSource(context.applicationContext, database),
        includeArchived,
    )

    suspend fun scanProjectHealth(projectId: Long): List<ProjectKnowledgeHealthPolicy.HealthObservation> =
        scanProjectHealthDetailed(projectId).observations

    suspend fun scanProjectHealthDetailed(projectId: Long): ProjectKnowledgeHealthScanResult =
        withContext(Dispatchers.IO) {
            require(projectId > 0L)
            coroutineContext.ensureActive()
            val archiveAllowed = includeArchived()
            val fetchedRows = source.projectItems(projectId, ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_SCAN + 1)
                .filter { it.projectId == projectId }
            val rows = fetchedRows.take(ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_SCAN)
            val results = mutableListOf<ProjectKnowledgeHealthPolicy.HealthObservation>()

            for (row in rows) {
                coroutineContext.ensureActive()
                when (row.provenanceType) {
                    "ASSISTANT_ANSWER" -> {
                        val proof = source.assistantSourceProof(row)
                        when (ProjectKnowledgeHealthPolicy.assistantStatus(row, proof, archiveAllowed)) {
                            ProjectKnowledgeHealthPolicy.AssistantStatus.AVAILABLE -> Unit
                            ProjectKnowledgeHealthPolicy.AssistantStatus.ARCHIVE_RESTRICTED -> results += observation(
                                ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.ARCHIVE_RESTRICTED,
                                row,
                                "보관함이 잠겨 원본을 열 수 없음",
                                archiveProtected = proof.conversationArchived,
                            )
                            ProjectKnowledgeHealthPolicy.AssistantStatus.MISSING -> results += observation(
                                ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.SOURCE_UNAVAILABLE,
                                row,
                                "원본 답변을 찾을 수 없음",
                                archiveProtected = proof.conversationArchived,
                            )
                        }
                    }
                    "WEB_CLIP" -> when {
                        row.sourceUrl.isNullOrBlank() -> results += observation(
                            ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.WEB_SOURCE_MISSING,
                            row,
                            "웹 출처 주소가 없음",
                        )
                        !isSupportedWebUri(row.sourceUrl) -> results += observation(
                            ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.WEB_SOURCE_INVALID,
                            row,
                            "웹 출처 주소 형식이 올바르지 않음",
                        )
                    }
                    "DOCUMENT_HIGHLIGHT" -> if (!ProjectKnowledgeHealthPolicy.documentSourceAvailable(
                            projectId,
                            source.documentSourceProof(row),
                        )
                    ) {
                        results += observation(
                            ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.DOCUMENT_SOURCE_UNAVAILABLE,
                            row,
                            "원본 문서를 찾을 수 없음",
                        )
                    }
                    "USER_NOTE" -> {
                        val linkedSources = source.noteSources(row.id, ProjectKnowledgeHealthPolicy.MAX_NOTE_SOURCES_PER_ITEM)
                        if (linkedSources.isEmpty()) {
                            results += observation(
                                ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.INDEPENDENT_COPY,
                                row,
                                "원본과 별도로 저장된 사본",
                            )
                        } else if (linkedSources.any { linked ->
                                coroutineContext.ensureActive()
                                !source.noteSourceAccessible(linked, archiveAllowed)
                            }
                        ) {
                            results += observation(
                                ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.NOTE_LINK_UNAVAILABLE,
                                row,
                                "연결된 출처 일부를 열 수 없음",
                            )
                        }
                    }
                    else -> results += observation(
                        ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.INDEPENDENT_COPY,
                        row,
                        "원본과 별도로 저장된 사본",
                    )
                }
            }

            val distinct = results.distinctBy { it.savedKnowledgeId + it.statement }
            ProjectKnowledgeHealthScanResult(
                observations = distinct.take(ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_VISIBLE),
                scanLimitReached = fetchedRows.size > ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_SCAN,
                findingsTruncated = distinct.size > ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_VISIBLE,
            )
        }

    suspend fun loadState(projectId: Long): ProjectKnowledgeHealthLoadState = try {
        scanProjectHealthDetailed(projectId).let { result ->
            ProjectKnowledgeHealthLoadState.Ready(
                observations = result.observations,
                scanLimitReached = result.scanLimitReached,
                findingsTruncated = result.findingsTruncated,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ProjectKnowledgeHealthLoadState.Failed
    }

    /** Exact, project-bound and access-authorized presentation lookup. Any read failure propagates to caller. */
    suspend fun resolveCurrentTarget(savedKnowledgeId: String, expectedProjectId: Long): SavedKnowledgeEntity? =
        withContext(Dispatchers.IO) {
          try {
            if (savedKnowledgeId.isBlank() || expectedProjectId <= 0L) return@withContext null
            val row = source.savedKnowledge(savedKnowledgeId) ?: return@withContext null
            if (row.id != savedKnowledgeId || row.projectId != expectedProjectId) return@withContext null
            if (!source.rowAccessible(row, includeArchived())) return@withContext null
            row
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (_: Exception) {
              null
          }
        }

    private fun observation(
        kind: ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind,
        row: SavedKnowledgeEntity,
        statement: String,
        archiveProtected: Boolean = false,
    ) = ProjectKnowledgeHealthPolicy.HealthObservation(
        kind = kind,
        savedKnowledgeId = row.id,
        knowledgeTitle = if (archiveProtected && !includeArchived()) "접근 제한된 자료" else row.title,
        statement = statement,
        archiveProtected = archiveProtected,
    )

    private fun isSupportedWebUri(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.isAbsolute && uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) {
        false
    }
}
