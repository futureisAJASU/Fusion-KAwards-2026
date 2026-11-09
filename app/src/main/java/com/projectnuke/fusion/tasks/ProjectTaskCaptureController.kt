package com.projectnuke.fusion.tasks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProjectTaskCaptureState(
    val draft: ProjectTaskDraft? = null,
    val title: String = "",
    val note: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val archiveAccessIncluded: Boolean = false,
)

/** The answer-to-task preview is frozen per source and writes only on accept. */
class ProjectTaskCaptureController(
    private val writer: ProjectTaskWriter,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ProjectTaskCaptureState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var revision = 0L

    fun begin(draft: ProjectTaskDraft, archiveAccessIncluded: Boolean) {
        revision++
        job?.cancel()
        mutableState.value = ProjectTaskCaptureState(
            draft = draft,
            title = draft.title.take(ProjectTaskPolicy.MAX_TITLE_CHARS),
            note = draft.note.take(ProjectTaskPolicy.MAX_NOTE_CHARS),
            archiveAccessIncluded = archiveAccessIncluded,
        )
    }

    fun updateTitle(value: String) {
        mutableState.value = mutableState.value.copy(title = value.take(ProjectTaskPolicy.MAX_TITLE_CHARS), error = null)
    }

    fun updateNote(value: String) {
        mutableState.value = mutableState.value.copy(note = value.take(ProjectTaskPolicy.MAX_NOTE_CHARS), error = null)
    }

    fun save() {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.saving || current.saved) return
        when (val prepared = ProjectTaskPolicy.prepare(draft, current.title, current.note, System.currentTimeMillis())) {
            ProjectTaskPreparation.Invalid -> mutableState.value = current.copy(error = "제목과 내용을 확인해 주세요.")
            ProjectTaskPreparation.SensitiveContent -> mutableState.value = current.copy(error = "민감한 값이 포함되어 저장하지 않았습니다.")
            is ProjectTaskPreparation.Ready -> {
                val ownerRevision = revision
                val ownerDraft = draft
                val archiveAuthorized = current.archiveAccessIncluded
                mutableState.value = current.copy(saving = true, error = null)
                job = scope.launch {
                    val result = try {
                        writer.save(prepared.proposal, archiveAuthorized)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } finally {
                        if (revision == ownerRevision && mutableState.value.draft == ownerDraft && mutableState.value.saving) {
                            mutableState.value = mutableState.value.copy(saving = false)
                        }
                    }
                    if (revision != ownerRevision || mutableState.value.draft != ownerDraft) return@launch
                    mutableState.value = when (result) {
                        ProjectTaskWriteResult.Stored -> mutableState.value.copy(saving = false, saved = true)
                        ProjectTaskWriteResult.ProjectUnavailable -> mutableState.value.copy(saving = false, error = "프로젝트가 없어 저장하지 않았습니다.")
                        ProjectTaskWriteResult.SourceUnavailable -> mutableState.value.copy(saving = false, error = "원본 답변을 확인할 수 없어 저장하지 않았습니다.")
                        ProjectTaskWriteResult.AccessUnavailable -> mutableState.value.copy(saving = false, error = "아카이브 접근이 만료되어 저장하지 않았습니다.")
                        ProjectTaskWriteResult.SensitiveContent -> mutableState.value.copy(saving = false, error = "민감한 값이 포함되어 저장하지 않았습니다.")
                        ProjectTaskWriteResult.Invalid -> mutableState.value.copy(saving = false, error = "제목과 내용을 확인해 주세요.")
                        ProjectTaskWriteResult.CapacityReached -> mutableState.value.copy(saving = false, error = "프로젝트에 저장할 수 있는 할 일 수에 도달했습니다.")
                        ProjectTaskWriteResult.Failed -> mutableState.value.copy(saving = false, error = "할 일을 저장하지 못했습니다. 다시 시도해 주세요.")
                    }
                }
            }
        }
    }

    fun dismiss() {
        revision++
        job?.cancel()
        job = null
        mutableState.value = ProjectTaskCaptureState()
    }
}
