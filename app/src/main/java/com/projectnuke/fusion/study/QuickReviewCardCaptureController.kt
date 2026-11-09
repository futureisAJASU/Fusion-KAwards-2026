package com.projectnuke.fusion.study

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QuickReviewCardCaptureState(
    val draft: QuickReviewCardDraft? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/** Freezes provenance at entry and ignores an old save completion after the user changes owner. */
class QuickReviewCardCaptureController(
    private val writer: QuickReviewCardWriter,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(QuickReviewCardCaptureState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var archiveAccessIncluded = false

    fun begin(draft: QuickReviewCardDraft, archiveAccessIncluded: Boolean) {
        generation += 1
        this.archiveAccessIncluded = archiveAccessIncluded
        mutableState.value = QuickReviewCardCaptureState(draft = draft)
    }

    fun updateFront(value: String) = updateDraft { copy(front = value.take(QuickReviewCardPolicy.MAX_FRONT_CHARS)) }
    fun updateBack(value: String) = updateDraft { copy(back = value.take(QuickReviewCardPolicy.MAX_BACK_CHARS)) }
    fun updateScope(value: ReviewCardScope) = updateDraft { copy(scope = value) }

    private fun updateDraft(transform: QuickReviewCardDraft.() -> QuickReviewCardDraft) {
        val state = mutableState.value
        if (state.saving || state.saved) return
        val draft = state.draft ?: return
        mutableState.value = state.copy(draft = draft.transform(), error = null)
    }

    fun save(now: Long = System.currentTimeMillis()) {
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.saving || current.saved) return
        when (val prepared = QuickReviewCardPolicy.prepare(draft, draft.front, draft.back, now)) {
            QuickReviewCardPreparation.Invalid -> mutableState.value = current.copy(error = "카드 앞면과 뒷면을 확인해 주세요.")
            QuickReviewCardPreparation.SensitiveContent -> mutableState.value = current.copy(error = "민감한 값이나 내부 경로를 제거해 주세요.")
            is QuickReviewCardPreparation.Ready -> {
                val requestGeneration = generation
                val acceptedArchiveAccess = archiveAccessIncluded
                mutableState.value = current.copy(saving = true, error = null)
                scope.launch {
                    val result = try {
                        writer.save(prepared.proposal, acceptedArchiveAccess)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        QuickReviewCardWriteResult.Failed
                    }
                    if (generation == requestGeneration) {
                        mutableState.value = when (result) {
                            QuickReviewCardWriteResult.Stored -> current.copy(draft = draft, saved = true)
                            QuickReviewCardWriteResult.Invalid -> current.copy(error = "카드 내용을 확인해 주세요.")
                            QuickReviewCardWriteResult.SensitiveContent -> current.copy(error = "민감한 값이나 내부 경로를 제거해 주세요.")
                            QuickReviewCardWriteResult.SourceUnavailable -> current.copy(error = "원본 자료를 찾을 수 없어 카드를 저장하지 않았습니다.")
                            QuickReviewCardWriteResult.AccessUnavailable -> current.copy(error = "아카이브 접근이 만료되어 카드를 저장하지 않았습니다.")
                            QuickReviewCardWriteResult.ProjectUnavailable -> current.copy(error = "프로젝트를 확인할 수 없어 카드를 저장하지 않았습니다.")
                            QuickReviewCardWriteResult.CapacityReached -> current.copy(error = "저장할 수 있는 복습 카드 수에 도달했습니다.")
                            QuickReviewCardWriteResult.Failed -> current.copy(error = "복습 카드를 저장하지 못했습니다.")
                        }
                    }
                }.invokeOnCompletion { failure ->
                    if (failure != null && failure !is CancellationException && generation == requestGeneration) {
                        mutableState.value = mutableState.value.copy(saving = false, error = "복습 카드를 저장하지 못했습니다.")
                    }
                }
            }
        }
    }

    fun dismiss() {
        generation += 1
        mutableState.value = QuickReviewCardCaptureState()
    }
}
