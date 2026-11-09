package com.projectnuke.fusion.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.ui.FluidPressableSurface
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/**
 * Short, skippable, resumable first-run flow.
 *
 * Completion is owned by [FusionOnboardingCompletionStore]; routing events are
 * emitted through [onAction]. The flow never blocks the app: every page can be
 * dismissed.
 */
@Composable
fun FusionOnboardingScreen(
    store: FusionOnboardingCompletionStore,
    onAction: (FusionOnboardingAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    var state by remember { mutableStateOf(FusionOnboardingUiState()) }

    BackHandler(enabled = state.page != FusionOnboardingPage.INTRO) {
        if (state.page == FusionOnboardingPage.PATH_CHOICE) {
            state = state.copy(page = FusionOnboardingPage.INTRO)
        } else if (state.page == FusionOnboardingPage.NEXT_ACTION) {
            state = state.copy(page = FusionOnboardingPage.PATH_CHOICE)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = semantic.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = FusionOnboardingPolicy.progressLabel(state.page),
                        color = semantic.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Fusion 시작하기",
                        color = semantic.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }

                when (state.page) {
                    FusionOnboardingPage.INTRO -> IntroPage()
                    FusionOnboardingPage.PATH_CHOICE -> PathChoicePage(
                        selected = state.pathChoice,
                        onSelect = { state = state.copy(pathChoice = it) },
                    )
                    FusionOnboardingPage.NEXT_ACTION -> NextActionPage(
                        choice = state.pathChoice,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val canProceed = when (state.page) {
                    FusionOnboardingPage.PATH_CHOICE ->
                        FusionOnboardingPolicy.canProceedToNextAction(state)
                    else -> true
                }
                OnboardingPrimaryAction(
                    label = FusionOnboardingPolicy.primaryActionLabel(state),
                    enabled = canProceed,
                    onClick = {
                        when (state.page) {
                            FusionOnboardingPage.INTRO,
                            FusionOnboardingPage.PATH_CHOICE,
                            -> {
                                state = FusionOnboardingPolicy.onPrimary(state) ?: return@OnboardingPrimaryAction
                            }
                            FusionOnboardingPage.NEXT_ACTION -> {
                                val action = FusionOnboardingPolicy.resolveCompletionAction(state)
                                if (action != null) {
                                    store.complete()
                                    onAction(action)
                                }
                            }
                        }
                    },
                )

                FusionOnboardingPolicy.secondaryActionLabel(state)?.let { label ->
                    OnboardingCompactTextAction(
                        label = label,
                        onClick = {
                            val action = FusionOnboardingPolicy.onSecondary(state)
                            if (action == FusionOnboardingAction.DISMISS_FOR_NOW) {
                                // Non-durable dismissal: completion is NOT
                                // persisted, so onboarding may reappear on a
                                // later cold start.
                                onDismiss()
                            } else {
                                state = state.copy(page = FusionOnboardingPage.INTRO)
                            }
                        },
                    )
                }

                if (state.page == FusionOnboardingPage.NEXT_ACTION) {
                    OnboardingCompactTextAction(
                        label = FusionOnboardingPolicy.browseOnlyLabel(),
                        onClick = {
                            store.complete()
                            onAction(FusionOnboardingAction.COMPLETE_BROWSE)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroPage() {
    val semantic = LocalFusionSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Fusion",
            color = semantic.onSurface,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = FusionOnboardingPolicy.introBody(),
            color = semantic.onSurfaceVariant,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = FusionOnboardingPolicy.privacyNote(),
            color = semantic.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun PathChoicePage(
    selected: FusionOnboardingPathChoice?,
    onSelect: (FusionOnboardingPathChoice) -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "어떻게 사용할까요?",
            color = semantic.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "선택한 방식이 영구적인 약속은 아니에요. 언제든지 둘 다 사용할 수 있어요.",
            color = semantic.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FusionOnboardingPathChoice.values().forEach { choice ->
            OnboardingChoiceCard(
                title = choice.label,
                subtitle = choice.subtitle,
                selected = selected == choice,
                onClick = { onSelect(choice) },
            )
        }
    }
}

@Composable
private fun NextActionPage(choice: FusionOnboardingPathChoice?) {
    val semantic = LocalFusionSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "다음 단계",
            color = semantic.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        when (choice) {
            FusionOnboardingPathChoice.LOCAL_MODEL -> {
                Text(
                    text = "모델을 선택하고 다운로드한 뒤 대화를 시작해요.",
                    color = semantic.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Text(
                    text = FusionOnboardingPolicy.localPathBody(),
                    color = semantic.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            FusionOnboardingPathChoice.EXTERNAL_AI -> {
                Text(
                    text = "제공자를 고르고 API 키를 연결하면 바로 사용할 수 있어요.",
                    color = semantic.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Text(
                    text = FusionOnboardingPolicy.externalPathBody(),
                    color = semantic.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            null -> {
                Text(
                    text = FusionOnboardingPolicy.nextActionIntro(),
                    color = semantic.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        }
        Text(
            text = "“바로 시작”을 누르면 해당 설정 화면으로 이동해요.",
            color = semantic.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun OnboardingChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    val choiceSelected = selected
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = choiceSelected },
        shape = RoundedCornerShape(16.dp),
        containerColor = if (selected) semantic.bubble else semantic.panel,
        contentColor = semantic.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) semantic.primary else semantic.divider,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        role = Role.RadioButton,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    color = semantic.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    color = semantic.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Text(
                text = if (selected) "✓" else "○",
                color = if (selected) semantic.primary else semantic.onSurfaceVariant,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun OnboardingPrimaryAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        containerColor = if (enabled) semantic.primary else semantic.bubble,
        contentColor = if (enabled) semantic.onPrimary else semantic.onSurfaceVariant,
        role = Role.Button,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = if (enabled) semantic.onPrimary else semantic.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OnboardingCompactTextAction(
    label: String,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = semantic.background,
        contentColor = semantic.onSurfaceVariant,
        role = Role.Button,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = semantic.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}