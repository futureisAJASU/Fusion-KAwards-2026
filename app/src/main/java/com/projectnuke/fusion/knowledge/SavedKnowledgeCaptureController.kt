package com.projectnuke.fusion.knowledge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SavedKnowledgeCaptureState(
    val draft: SavedKnowledgeDraft? = null,
    val title: String = "",
    val scope: SavedKnowledgeScope = SavedKnowledgeScope.PERSONAL,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val archiveAccessIncluded: Boolean = false,
)

/** Owns preview and durable acceptance outside the Compose answer renderer. */
class SavedKnowledgeCaptureController(
    private val repository: SavedKnowledgeWriter,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SavedKnowledgeCaptureState())
    val state = mutableState.asStateFlow()
    private var writeJob: Job? = null
    private var revision = 0L

    fun begin(draft: SavedKnowledgeDraft, archiveAccessIncluded: Boolean) {
        revision++
        writeJob?.cancel()
        val suggested = draft.visibleText.lineSequence().firstOrNull()?.trim()?.take(SavedKnowledgePolicy.MAX_TITLE_CHARS)
            ?.ifBlank { "저장한 답변" } ?: "저장한 답변"
        mutableState.value = SavedKnowledgeCaptureState(
            draft = draft,
            title = suggested,
            archiveAccessIncluded = archiveAccessIncluded,
        )
    }

    fun updateTitle(value: String) {
        mutableState.value = mutableState.value.copy(title = value.take(SavedKnowledgePolicy.MAX_TITLE_CHARS), error = null)
    }

    fun updateScope(value: SavedKnowledgeScope) {
        val current = mutableState.value
        if (value == SavedKnowledgeScope.PROJECT && current.draft?.projectId == null) return
        mutableState.value = current.copy(scope = value, error = null)
    }

    fun save() {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.saving || current.saved) return
        val prepared = SavedKnowledgePolicy.prepare(draft, current.title, current.scope, System.currentTimeMillis())
        if (prepared !is SavedKnowledgePreparation.Ready) {
            mutableState.value = current.copy(error = "저장할 제목과 답변을 확인해 주세요.")
            return
        }
        mutableState.value = current.copy(saving = true, error = null)
        val ownerDraft = draft
        val archiveAuthorized = current.archiveAccessIncluded
        val ownerRevision = revision
        writeJob = scope.launch {
            val result = try {
                repository.save(prepared.proposal, archiveAuthorized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (revision == ownerRevision && mutableState.value.draft == ownerDraft && mutableState.value.saving) {
                    mutableState.value = mutableState.value.copy(saving = false)
                }
            }
            if (revision != ownerRevision || mutableState.value.draft != ownerDraft) return@launch
            mutableState.value = when (result) {
                SavedKnowledgeWriteResult.Stored -> mutableState.value.copy(saving = false, saved = true, error = null)
                SavedKnowledgeWriteResult.SourceUnavailable -> mutableState.value.copy(
                    saving = false, error = "원본 답변을 확인할 수 없어 저장하지 않았습니다.",
                )
                SavedKnowledgeWriteResult.ProjectUnavailable -> mutableState.value.copy(
                    saving = false, error = "프로젝트 연결이 바뀌어 저장하지 않았습니다.",
                )
                SavedKnowledgeWriteResult.AccessUnavailable -> mutableState.value.copy(
                    saving = false, error = "아카이브 접근이 만료되어 저장하지 않았습니다.",
                )
                SavedKnowledgeWriteResult.SensitiveContent -> mutableState.value.copy(
                    saving = false, error = "민감한 키나 내부 경로가 포함되어 저장하지 않았습니다.",
                )
                SavedKnowledgeWriteResult.Failed -> mutableState.value.copy(
                    saving = false, error = "저장하지 못했습니다. 다시 시도해 주세요.",
                )
            }
        }
    }

    fun dismiss() {
        revision++
        writeJob?.cancel()
        writeJob = null
        mutableState.value = SavedKnowledgeCaptureState()
    }
}
