package com.projectnuke.fusion.knowledge

import androidx.room.withTransaction
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.SavedKnowledgeNoteSourceEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MAX_SAVED_NOTE_TITLE_CHARS = 160
const val MAX_SAVED_NOTE_BODY_CHARS = 20_000
const val MAX_SAVED_NOTE_REFERENCES = 8

data class SavedKnowledgeNoteReferenceDraft(
    val identity: SelectionSourceIdentity,
    val title: String,
)

data class SavedKnowledgeNoteEditorDraft(
    val id: String,
    val title: String,
    val body: String,
    val references: List<SavedKnowledgeNoteReferenceDraft>,
    val sourceSavedKnowledgeId: String? = null,
    val projectId: Long? = null,
    val projectName: String? = null,
)

sealed interface SavedKnowledgeNoteSaveResult {
    data class Saved(val noteId: String) : SavedKnowledgeNoteSaveResult
    data object NoteUnavailable : SavedKnowledgeNoteSaveResult
    data object InvalidDraft : SavedKnowledgeNoteSaveResult
    data object SourceUnavailable : SavedKnowledgeNoteSaveResult
    data object ProjectUnavailable : SavedKnowledgeNoteSaveResult
}

object SavedKnowledgeNotePolicy {
    fun normalize(
        title: String,
        body: String,
        references: List<SavedKnowledgeNoteReferenceDraft>,
        projectId: Long? = null,
    ): SavedKnowledgeNoteEditorDraft? {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank() || normalizedTitle.length > MAX_SAVED_NOTE_TITLE_CHARS ||
            body.length > MAX_SAVED_NOTE_BODY_CHARS || references.size > MAX_SAVED_NOTE_REFERENCES
        ) return null
        val normalizedRefs = references.map { ref ->
            val sourceTitle = ref.title.trim()
            if (!ref.identity.isWellFormed() || !isDurableNoteSource(ref.identity.kind) ||
                sourceTitle.isBlank() || sourceTitle.length > SelectionContextSnapshot.MAX_TITLE_CHARS
            ) return null
            SavedKnowledgeNoteReferenceDraft(ref.identity, sourceTitle)
        }.distinctBy { it.identity.stableKey() }
        if (normalizedRefs.size != references.size) return null
        return SavedKnowledgeNoteEditorDraft("", normalizedTitle, body, normalizedRefs, projectId = projectId)
    }

    fun isDurableNoteSource(kind: SelectionSourceKind): Boolean = kind in setOf(
        SelectionSourceKind.CONVERSATION,
        SelectionSourceKind.PROJECT,
        SelectionSourceKind.PROJECT_DOCUMENT,
        SelectionSourceKind.SAVED_KNOWLEDGE,
    )
}

/** Locked archive content must not become discoverable through a note's linked provenance. */
internal object SavedKnowledgeNoteArchivePolicy {
    fun isVisible(
        includeArchived: Boolean,
        ownSourceArchived: Boolean,
        linkedSourcesArchived: List<Boolean>,
    ): Boolean = includeArchived || (!ownSourceArchived && linkedSourcesArchived.none { it })
}

class SavedKnowledgeNoteRepository(private val database: AppDatabase) {
    suspend fun prepareFromSavedKnowledge(
        sourceId: String,
        includeArchived: Boolean,
    ): SavedKnowledgeNoteEditorDraft? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val source = database.savedKnowledgeDao().getById(sourceId) ?: return@withTransaction null
            if (source.provenanceType == SavedKnowledgeProvenance.USER_NOTE.name ||
                !accessibleSavedKnowledge(source, includeArchived)
            ) return@withTransaction null
            val reference = SavedKnowledgeNoteReferenceDraft(
                SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = source.id),
                source.title,
            )
            SavedKnowledgeNoteEditorDraft("", source.title.take(MAX_SAVED_NOTE_TITLE_CHARS),
                source.savedText.take(MAX_SAVED_NOTE_BODY_CHARS), listOf(reference), source.id)
        }
    }

    suspend fun load(noteId: String, includeArchived: Boolean = false): SavedKnowledgeNoteEditorDraft? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val note = database.savedKnowledgeDao().getById(noteId)
                ?.takeIf { it.provenanceType == SavedKnowledgeProvenance.USER_NOTE.name }
                ?: return@withTransaction null
            if (!noteArchiveVisible(note, includeArchived)) return@withTransaction null
            val references = database.savedKnowledgeNoteSourceDao().getForNote(noteId, MAX_SAVED_NOTE_REFERENCES)
                .mapNotNull(SavedKnowledgeNoteSourceEntity::toDraft)
            SavedKnowledgeNoteEditorDraft(
                note.id, note.title, note.savedText, references,
                projectId = note.projectId,
                projectName = note.projectId?.let { database.projectDao().getProjectById(it)?.name },
            )
        }
    }

    suspend fun save(
        noteId: String,
        title: String,
        body: String,
        references: List<SavedKnowledgeNoteReferenceDraft>,
        now: Long,
        includeArchived: Boolean,
        sourceSavedKnowledgeId: String? = null,
        projectId: Long? = null,
    ): SavedKnowledgeNoteSaveResult = withContext(Dispatchers.IO) {
        val normalized = SavedKnowledgeNotePolicy.normalize(title, body, references, projectId)
            ?: return@withContext SavedKnowledgeNoteSaveResult.InvalidDraft
        if (noteId.isBlank() && sourceSavedKnowledgeId != null && normalized.references.none {
                it.identity.kind == SelectionSourceKind.SAVED_KNOWLEDGE && it.identity.savedKnowledgeId == sourceSavedKnowledgeId
            }) return@withContext SavedKnowledgeNoteSaveResult.InvalidDraft
        if (noteId.isBlank() && sourceSavedKnowledgeId == null && normalized.references.isEmpty()) {
            return@withContext SavedKnowledgeNoteSaveResult.InvalidDraft
        }
        database.withTransaction {
            val existingNote = if (noteId.isBlank()) null else database.savedKnowledgeDao().getById(noteId)
                ?.takeIf { it.provenanceType == SavedKnowledgeProvenance.USER_NOTE.name }
            if (!noteId.isBlank() && existingNote == null) return@withTransaction SavedKnowledgeNoteSaveResult.NoteUnavailable
            if (existingNote != null && !noteArchiveVisible(existingNote, includeArchived)) {
                return@withTransaction SavedKnowledgeNoteSaveResult.NoteUnavailable
            }
            val existingSourceKeys = existingNote?.let {
                database.savedKnowledgeNoteSourceDao().getForNote(it.id, MAX_SAVED_NOTE_REFERENCES).map { source -> source.sourceKey }.toSet()
            }.orEmpty()
            if (normalized.references.any { it.identity.stableKey() !in existingSourceKeys && !sourceAccessible(it.identity, includeArchived) }) {
                return@withTransaction SavedKnowledgeNoteSaveResult.SourceUnavailable
            }
            if (noteId.isBlank() && projectId != null && database.projectDao().getProjectById(projectId) == null) {
                return@withTransaction SavedKnowledgeNoteSaveResult.ProjectUnavailable
            }
            val effectiveNoteId: String
            if (noteId.isBlank()) {
                effectiveNoteId = UUID.randomUUID().toString()
                if (sourceSavedKnowledgeId != null) {
                    val source = database.savedKnowledgeDao().getById(sourceSavedKnowledgeId)
                        ?.takeIf { accessibleSavedKnowledge(it, includeArchived) }
                        ?: return@withTransaction SavedKnowledgeNoteSaveResult.SourceUnavailable
                    database.savedKnowledgeDao().insert(
                        source.copy(
                            id = effectiveNoteId,
                            scope = "PERSONAL",
                            projectId = null,
                            title = normalized.title,
                            savedText = normalized.body,
                            provenanceType = SavedKnowledgeProvenance.USER_NOTE.name,
                            sourceUrl = null,
                            sourcePageTitle = null,
                            retrievedAt = null,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else {
                    database.savedKnowledgeDao().insert(
                        SavedKnowledgeEntity(
                            id = effectiveNoteId,
                            scope = if (projectId == null) "PERSONAL" else "PROJECT",
                            projectId = projectId,
                            title = normalized.title,
                            savedText = normalized.body,
                            provenanceType = SavedKnowledgeProvenance.USER_NOTE.name,
                            sourceConversationId = null,
                            sourceMessageId = null,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            } else {
                val existing = existingNote ?: return@withTransaction SavedKnowledgeNoteSaveResult.NoteUnavailable
                if (database.savedKnowledgeDao().updateUserNote(noteId, normalized.title, normalized.body, now) != 1) {
                    return@withTransaction SavedKnowledgeNoteSaveResult.NoteUnavailable
                }
                effectiveNoteId = existing.id
            }
            database.savedKnowledgeNoteSourceDao().deleteForNote(effectiveNoteId)
            if (normalized.references.isNotEmpty()) {
                database.savedKnowledgeNoteSourceDao().insertAll(normalized.references.mapIndexed { index, reference ->
                    reference.toEntity(effectiveNoteId, index, now)
                })
            }
            SavedKnowledgeNoteSaveResult.Saved(effectiveNoteId)
        }
    }

    private suspend fun sourceAccessible(identity: SelectionSourceIdentity, includeArchived: Boolean): Boolean = when (identity.kind) {
        SelectionSourceKind.CONVERSATION -> database.chatDao().getConversationById(identity.conversationId!!)
            ?.let { includeArchived || !it.isArchived } == true
        SelectionSourceKind.PROJECT -> database.projectDao().getProjectById(identity.projectId!!) != null
        SelectionSourceKind.PROJECT_DOCUMENT -> database.projectDao().getProjectFileById(identity.fileId!!)
            ?.let { it.projectId == identity.projectId && database.projectDao().getProjectById(it.projectId) != null } == true
        SelectionSourceKind.SAVED_KNOWLEDGE -> database.savedKnowledgeDao().getById(identity.savedKnowledgeId!!)
            ?.let { it.provenanceType != SavedKnowledgeProvenance.USER_NOTE.name && accessibleSavedKnowledge(it, includeArchived) } == true
        SelectionSourceKind.ATTACHED_DOCUMENT,
        SelectionSourceKind.MEMORY -> false
    }

    private suspend fun accessibleSavedKnowledge(item: SavedKnowledgeEntity, includeArchived: Boolean): Boolean =
        item.sourceConversationId?.let { sourceId ->
            database.chatDao().getConversationById(sourceId)?.let { includeArchived || !it.isArchived } ?: false
        } ?: true

    private suspend fun noteArchiveVisible(note: SavedKnowledgeEntity, includeArchived: Boolean): Boolean {
        val ownSourceArchived = note.sourceConversationId?.let { sourceId ->
            database.chatDao().getConversationById(sourceId)?.isArchived
        } == true
        val linkedSourceArchiveStates = database.savedKnowledgeNoteSourceDao()
            .getForNote(note.id, MAX_SAVED_NOTE_REFERENCES).mapNotNull { source ->
                when (source.sourceKind) {
                    SelectionSourceKind.CONVERSATION.name -> source.conversationId?.let { id ->
                        database.chatDao().getConversationById(id)?.isArchived
                    }
                    SelectionSourceKind.SAVED_KNOWLEDGE.name -> source.savedKnowledgeId?.let { id ->
                        database.savedKnowledgeDao().getById(id)?.sourceConversationId?.let { conversationId ->
                            database.chatDao().getConversationById(conversationId)?.isArchived
                        }
                    }
                    else -> null
                }
            }
        return SavedKnowledgeNoteArchivePolicy.isVisible(includeArchived, ownSourceArchived, linkedSourceArchiveStates)
    }
}

private fun SavedKnowledgeNoteReferenceDraft.toEntity(noteId: String, orderIndex: Int, now: Long): SavedKnowledgeNoteSourceEntity {
    val identity = identity
    return SavedKnowledgeNoteSourceEntity(
        noteId = noteId,
        orderIndex = orderIndex,
        sourceKey = identity.stableKey(),
        sourceKind = identity.kind.name,
        conversationId = identity.conversationId,
        projectId = identity.projectId,
        projectFileId = identity.fileId,
        savedKnowledgeId = identity.savedKnowledgeId,
        sourceTitle = title.take(SelectionContextSnapshot.MAX_TITLE_CHARS),
        createdAt = now,
    )
}

private fun SavedKnowledgeNoteSourceEntity.toDraft(): SavedKnowledgeNoteReferenceDraft? {
    val kind = runCatching { SelectionSourceKind.valueOf(sourceKind) }.getOrNull() ?: return null
    val identity = when (kind) {
        SelectionSourceKind.CONVERSATION -> SelectionSourceIdentity(kind, conversationId = conversationId)
        SelectionSourceKind.PROJECT -> SelectionSourceIdentity(kind, projectId = projectId)
        SelectionSourceKind.PROJECT_DOCUMENT -> SelectionSourceIdentity(kind, projectId = projectId, fileId = projectFileId)
        SelectionSourceKind.SAVED_KNOWLEDGE -> SelectionSourceIdentity(kind, savedKnowledgeId = savedKnowledgeId)
        SelectionSourceKind.ATTACHED_DOCUMENT, SelectionSourceKind.MEMORY -> return null
    }
    if (!identity.isWellFormed() || identity.stableKey() != sourceKey) return null
    return SavedKnowledgeNoteReferenceDraft(identity, sourceTitle)
}
