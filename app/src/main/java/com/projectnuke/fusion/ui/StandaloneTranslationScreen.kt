package com.projectnuke.fusion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/** First-class Translation destination owned by FusionApp. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StandaloneTranslationScreen(
    controller: StandaloneTranslationController,
    clipboard: ClipboardManager,
    onBack: () -> Unit,
    onInsertIntoComposer: () -> Unit = {},
    composerHandoffInProgress: Boolean = false,
    composerHandoffMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val semantic = LocalFusionSemanticColors.current
    val capabilities = controller.capabilities
    val languages = StandaloneTranslationPolicy.targetLanguages(capabilities)
    val state by controller.state.collectAsStateWithLifecycle()
    val displayedTranslatedText = StandaloneTranslationPolicy.displayedTranslatedText(state, capabilities)
    val composerReplacementAvailable = controller.composerReplacementText() != null
    val screenScroll = rememberScrollState()
    var languageMenu by remember { mutableStateOf(false) }
    val languageAnchor = rememberFusionPopupAnchor()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = semantic.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(screenScroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FusionPageHeader("번역", onBack, "기기에서 번역합니다.", actionDescription = "번역에서 돌아가기")

            Text(
                text = "원문",
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            BasicTextField(
                value = state.sourceText,
                onValueChange = controller::setSource,
                textStyle = TextStyle(
                    color = semantic.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(semantic.panel, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .heightIn(min = 128.dp, max = 240.dp),
                maxLines = 12,
                decorationBox = { innerTextField ->
                    Box {
                        if (state.sourceText.isBlank()) {
                            Text(
                                text = "번역할 내용을 입력하세요.",
                                color = semantic.onSurfaceVariant,
                                fontSize = 16.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "번역 언어",
                    color = semantic.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (capabilities.supportsAutomaticSourceDetection) {
                    Text(
                        text = "출발 언어: 자동 감지",
                        color = semantic.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Box {
                    TextButton(onClick = { languageMenu = true }, modifier = Modifier.fusionPopupAnchor(languageAnchor)) {
                        Text("${languages.firstOrNull { it.code == state.targetLanguage }?.label ?: state.targetLanguage} ▾")
                    }
                    FusionDropdownMenu(languageMenu, { languageMenu = false }, anchor = languageAnchor) {
                        languages.forEach { language ->
                            DropdownMenuItem(text = { Text(language.label) }, onClick = {
                                languageMenu = false
                                controller.setTargetLanguage(language.code)
                            })
                        }
                    }
                }
            }

            TranslationCompactAction(
                label = "번역하기",
                emphasized = true,
                enabled = StandaloneTranslationPolicy.canStart(state, capabilities),
                onClick = controller::translate,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            )

            StandaloneTranslationPolicy.translateDisabledReason(state, capabilities)?.let { reason ->
                Text(
                    text = reason,
                    color = semantic.warning,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            StandaloneTranslationPolicy.statusMessage(state)?.let { message ->
                Text(
                    text = message,
                    color = semantic.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }

            displayedTranslatedText?.let { translated ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "결과",
                        color = semantic.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = semantic.background,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SelectionContainer {
                            Text(
                                text = translated,
                                color = semantic.onSurface,
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                            )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TranslationCompactAction(
                                    label = "복사",
                                    onClick = { clipboard.setText(AnnotatedString(translated)) },
                                    modifier = Modifier.weight(1f),
                                )
                                TranslationCompactAction(
                                    label = "바꾸기",
                                    enabled = StandaloneTranslationPolicy.canSwap(state, capabilities),
                                    onClick = controller::swap,
                                    modifier = Modifier.weight(1f),
                                )
                                TranslationCompactAction(
                                    label = "입력창에 넣기",
                                    enabled = composerReplacementAvailable && !composerHandoffInProgress,
                                    onClick = onInsertIntoComposer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            composerHandoffMessage?.let { message ->
                                Text(
                                    text = message,
                                    color = semantic.warning,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }
            }

            TranslationCompactAction(
                label = "지우기",
                onClick = controller::clear,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun TranslationLanguageChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = if (selected) semantic.bubble else semantic.background,
        isSelected = selected,
        contentColor = semantic.onSurface,
        border = BorderStroke(1.dp, if (selected) semantic.primary else semantic.divider),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        role = Role.Button,
    ) {
        Text(
            text = label,
            color = semantic.onSurface,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TranslationCompactAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
    actionDescription: String = label,
) {
    val semantic = LocalFusionSemanticColors.current
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    FluidPressableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = actionDescription },
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        containerColor = if (emphasized) semantic.primary else semantic.background,
        contentColor = if (emphasized) onPrimary else semantic.onSurface,
        border = if (emphasized) null else BorderStroke(1.dp, semantic.divider),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        role = Role.Button,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (enabled) {
                    if (emphasized) onPrimary else semantic.onSurface
                } else {
                    semantic.onSurfaceVariant.copy(alpha = 0.45f)
                },
                fontSize = 12.sp,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
