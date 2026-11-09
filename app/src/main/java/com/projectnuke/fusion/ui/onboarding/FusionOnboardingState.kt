package com.projectnuke.fusion.ui.onboarding

import android.content.SharedPreferences

/**
 * Single authoritative onboarding-completion owner.
 *
 * No other screen owns first-run flags. Completion is durable in
 * SharedPreferences; the surface can always be reopened manually from
 * Settings/Help without touching the stored completion.
 */
class FusionOnboardingCompletionStore(
    private val prefs: SharedPreferences,
) {
    fun isCompleted(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun complete() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETED = "fusion_onboarding_completed"
    }
}

enum class FusionOnboardingPathChoice(
    val label: String,
    val subtitle: String,
) {
    LOCAL_MODEL(
        label = "기기에서 실행",
        subtitle = "모델을 기기에 저장하고 가능한 처리를 로컬에서 수행해요.",
    ),
    EXTERNAL_AI(
        label = "외부 AI 연결",
        subtitle = "API 제공자를 연결해 클라우드 모델을 사용할 수 있어요.",
    ),
}

enum class FusionOnboardingPage {
    INTRO,
    PATH_CHOICE,
    NEXT_ACTION,
}

/** What the caller must do after the user finishes or dismisses the flow.
 *
 * The three COMPLETE_* outcomes are DURABLE: the screen persists onboarding
 * completion before emitting them. DISMISS_FOR_NOW is intentionally
 * NON-durable: it closes the current surface only, and onboarding may appear
 * again on a later cold start. It must never be treated as, or named,
 * "skip" (which would imply permanent suppression).
 */
enum class FusionOnboardingAction {
    COMPLETE_LOCAL_ROUTE,
    COMPLETE_EXTERNAL_ROUTE,
    COMPLETE_BROWSE,
    DISMISS_FOR_NOW,
}

data class FusionOnboardingUiState(
    val page: FusionOnboardingPage = FusionOnboardingPage.INTRO,
    val pathChoice: FusionOnboardingPathChoice? = null,
)

object FusionOnboardingPolicy {
    const val PAGE_COUNT = 3

    fun primaryActionLabel(state: FusionOnboardingUiState): String = when (state.page) {
        FusionOnboardingPage.INTRO -> "시작하기"
        FusionOnboardingPage.PATH_CHOICE -> "다음"
        FusionOnboardingPage.NEXT_ACTION -> "바로 시작"
    }

    fun secondaryActionLabel(state: FusionOnboardingUiState): String? = when (state.page) {
        FusionOnboardingPage.INTRO -> "나중에"
        FusionOnboardingPage.PATH_CHOICE -> "이전"
        else -> null
    }

    /** Returns the next UI state, or null when the action dismisses the flow. */
    fun onPrimary(state: FusionOnboardingUiState): FusionOnboardingUiState? = when (state.page) {
        FusionOnboardingPage.INTRO -> state.copy(page = FusionOnboardingPage.PATH_CHOICE)
        FusionOnboardingPage.PATH_CHOICE ->
            state.copy(page = FusionOnboardingPage.NEXT_ACTION)
        FusionOnboardingPage.NEXT_ACTION -> state
    }

    /** Returns null when the flow must remain on the same page (no selection yet). */
    fun canProceedToNextAction(state: FusionOnboardingUiState): Boolean =
        state.page == FusionOnboardingPage.PATH_CHOICE && state.pathChoice != null

    /** Secondary action result: null keeps the flow, DISMISS_FOR_NOW closes
     *  the current surface without persisting completion. */
    fun onSecondary(state: FusionOnboardingUiState): FusionOnboardingAction? = when (state.page) {
        FusionOnboardingPage.INTRO -> FusionOnboardingAction.DISMISS_FOR_NOW
        FusionOnboardingPage.PATH_CHOICE -> null
        FusionOnboardingPage.NEXT_ACTION -> null
    }

    /** Final action after the user commits the last page. */
    fun resolveCompletionAction(state: FusionOnboardingUiState): FusionOnboardingAction? {
        if (state.page != FusionOnboardingPage.NEXT_ACTION) return null
        return when (state.pathChoice) {
            FusionOnboardingPathChoice.LOCAL_MODEL -> FusionOnboardingAction.COMPLETE_LOCAL_ROUTE
            FusionOnboardingPathChoice.EXTERNAL_AI -> FusionOnboardingAction.COMPLETE_EXTERNAL_ROUTE
            null -> FusionOnboardingAction.COMPLETE_BROWSE
        }
    }

    fun title(state: FusionOnboardingUiState): String? = when (state.page) {
        FusionOnboardingPage.INTRO -> "Fusion"
        FusionOnboardingPage.PATH_CHOICE -> "어떻게 사용할까요?"
        FusionOnboardingPage.NEXT_ACTION -> null
    }

    fun introBody(): String =
        "Fusion은 로컬 모델을 기기에서 실행하거나, 원하는 외부 AI API를 연결해 사용할 수 있어요."

    fun nextActionIntro(): String =
        "지금 설정하지 않아도 괜찮아요. 언제든지 나중에 바꿀 수 있어요."

    fun browseOnlyLabel(): String = "설정 없이 둘러보기"

    fun localPathBody(): String =
        "로컬 모델은 가능한 처리를 기기 안에서 수행해요. 선택한 모델에 따라 다운로드가 필요할 수 있어요."

    fun externalPathBody(): String =
        "외부 AI를 사용하면 대화 내용이 선택한 제공자로 전송될 수 있어요."

    fun privacyNote(): String =
        "외부 AI 제공자는 사용자가 직접 연결한 서비스예요. Fusion은 요청을 중계하지 않아요."

    fun progressLabel(page: FusionOnboardingPage): String = when (page) {
        FusionOnboardingPage.INTRO -> "1/3"
        FusionOnboardingPage.PATH_CHOICE -> "2/3"
        FusionOnboardingPage.NEXT_ACTION -> "3/3"
    }
}