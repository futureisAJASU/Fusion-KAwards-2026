package com.projectnuke.fusion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.ComposerDraftPolicy
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.model.ComposerQuickGenerationMode
import com.projectnuke.fusion.model.ComposerQuickPanelSurface
import com.projectnuke.fusion.model.ComposerQuickSettingsPolicy
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import com.projectnuke.fusion.util.AttachmentRecord
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.FusionThumbnailCache
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    isSubmittingMessage: Boolean,
    hasAttachments: Boolean,
    isImportingAttachments: Boolean,
    onPlusClick: () -> Unit,
    onCameraCaptureClick: () -> Unit = {},
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    pendingAttachments: List<LocalAttachment>,
    attachmentRefreshKey: Int,
    onRemoveAttachment: (LocalAttachment) -> Unit,
    onAppendQuickPrompt: (String) -> Unit,
    isStopping: Boolean = false,
    quickPanelSurface: ComposerQuickPanelSurface = ComposerQuickPanelSurface.NONE,
    quickSettingsMode: ComposerQuickGenerationMode = ComposerQuickGenerationMode.LOCAL_MODEL,
    onToggleQuickPrompt: () -> Unit = {},
    onToggleQuickSettings: () -> Unit = {},
    quickSettingsAnchor: FusionPopupAnchor? = null,
    contextControls: @Composable () -> Unit = {},
    quickPromptPresets: List<String> = QuickPromptPresets,
    interactionMode: com.projectnuke.fusion.chat.ChatInteractionMode = com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL,
    interactionModeScope: com.projectnuke.fusion.chat.ChatInteractionModeScope,
    modeSwitchEnabled: Boolean = true,
    onSelectInteractionMode: (com.projectnuke.fusion.chat.ChatInteractionMode) -> Unit = {},
    dictationState: DictationState = DictationState.IDLE,
    onMicClick: () -> Unit = {},
    voiceModeActive: Boolean = false,
    onVoiceModeClick: () -> Unit = {},
    onVoiceModeExit: () -> Unit = {},
) {
    val quickPromptExpanded = quickPanelSurface == ComposerQuickPanelSurface.QUICK_PROMPTS
    val showQuickSettings = ComposerQuickSettingsPolicy.shouldShowQuickSettings(quickSettingsMode)
    val isStudyInteractionMode = interactionMode == com.projectnuke.fusion.chat.ChatInteractionMode.STUDY
    val interactionLabel = when (interactionMode) {
        com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL -> "일반"
        com.projectnuke.fusion.chat.ChatInteractionMode.STUDY -> "학습 모드"
        com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH -> "조사 모드"
    }
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var plusExpanded by remember { mutableStateOf(false) }
    val modeAnchor = rememberFusionPopupAnchor()
    val plusAnchor = rememberFusionPopupAnchor()
    // A stale open selector must never linger across scope or availability
    // changes: a conversation switch, epoch change or a starting critical
    // operation closes it instead of applying a stale mode tap.
    LaunchedEffect(interactionModeScope, modeSwitchEnabled, interactionMode) {
        // The selector belongs to this exact conversation/epoch and mode
        // ownership. Any change invalidates the transient presentation state.
        modeMenuExpanded = false
        plusExpanded = false
    }
    LaunchedEffect(enabled) {
        if (!enabled) plusExpanded = false
    }
    val semanticColors = LocalFusionSemanticColors.current
    Surface(color = semanticColors.background) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(semanticColors.divider)
            )
            if (pendingAttachments.isNotEmpty()) {
                PendingAttachmentTray(
                    attachments = pendingAttachments,
                    onRemove = onRemoveAttachment,
                    enabled = enabled,
                    attachmentRefreshKey = attachmentRefreshKey,
                )
            }
            if (voiceModeActive) {
                TextButton(onClick = onVoiceModeExit) { Text("보이스 모드 사용 중 · 종료") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FusionSpacing.screen, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    FluidPressableSurface(
                        onClick = { modeMenuExpanded = true },
                        enabled = enabled && modeSwitchEnabled,
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.Transparent,
                        contentColor = semanticColors.onSurfaceVariant,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        role = Role.Button,
                        modifier = Modifier.heightIn(min = 48.dp).fusionPopupAnchor(modeAnchor).semantics {
                            contentDescription = "대화 모드 선택, 현재 $interactionLabel"
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = interactionLabel,
                                color = if (enabled && modeSwitchEnabled) semanticColors.onSurfaceVariant else semanticColors.onSurfaceVariant.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                tint = semanticColors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    FusionDropdownMenu(
                        expanded = modeMenuExpanded,
                        anchor = modeAnchor,
                        onDismissRequest = { modeMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("일반") },
                            enabled = modeSwitchEnabled,
                            onClick = {
                                modeMenuExpanded = false
                                onSelectInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("학습") },
                            enabled = modeSwitchEnabled,
                            onClick = {
                                modeMenuExpanded = false
                                onSelectInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.STUDY)
                            },
                        )
                        DropdownMenuItem(text = { Text("조사") }, enabled = modeSwitchEnabled,
                            onClick = { modeMenuExpanded = false; onSelectInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH) })
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                contextControls()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = FusionSpacing.objectShape,
                color = semanticColors.panel
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                        Box {
                            FluidPressableSurface(onClick = { plusExpanded = true }, enabled = enabled,
                                modifier = Modifier.size(48.dp).fusionPopupAnchor(plusAnchor), role = Role.Button) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("+", modifier = Modifier.semantics { contentDescription = "자료와 입력 도구 추가" }, fontSize = 24.sp)
                                }
                            }
                            FusionDropdownMenu(plusExpanded, { plusExpanded = false }, anchor = plusAnchor,
                                modifier = Modifier.fillMaxWidth().testTag("composer-add-popup"),
                                scrollContent = false, preferredHeight = 360.dp) {
                                ComposerAddMenu(
                                    enabled = enabled,
                                    quickPromptsAllowed = com.projectnuke.fusion.chat.ChatInteractionModePolicy.allowsQuickPrompt(interactionMode),
                                    quickPromptPresets = quickPromptPresets,
                                    dictationActive = dictationState == DictationState.LISTENING || dictationState == DictationState.PROCESSING,
                                    voiceModeActive = voiceModeActive,
                                    onCamera = { plusExpanded = false; onCameraCaptureClick() },
                                    onFile = { plusExpanded = false; onPlusClick() },
                                    onDictation = { plusExpanded = false; onMicClick() },
                                    onVoiceMode = { plusExpanded = false; if (voiceModeActive) onVoiceModeExit() else onVoiceModeClick() },
                                    onQuickPrompt = { preset -> plusExpanded = false; onAppendQuickPrompt(preset) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))

                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            enabled = enabled,
                            textStyle = TextStyle(
                                color = semanticColors.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 10.dp),
                            maxLines = 4,
                            decorationBox = { innerTextField ->
                                if (value.isBlank()) {
                                    Text(
                                        text = com.projectnuke.fusion.chat.ChatInteractionModePolicy.composerPlaceholder(interactionMode),
                                        color = semanticColors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        if (showQuickSettings) {
                            val quickSettingsOpen = quickPanelSurface == ComposerQuickPanelSurface.QUICK_SETTINGS
                            FluidPressableSurface(
                                onClick = onToggleQuickSettings,
                                modifier = Modifier.size(48.dp).then(if (quickSettingsAnchor != null) Modifier.fusionPopupAnchor(quickSettingsAnchor) else Modifier),
                                enabled = enabled,
                                shape = FusionSpacing.objectShape,
                                containerColor = Color.Transparent,
                                contentColor = semanticColors.onSurfaceVariant,
                                border = null,
                                contentPadding = PaddingValues(0.dp),
                                role = Role.Button,
                                isSelected = quickSettingsOpen,
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Tune,
                                        contentDescription = if (quickSettingsOpen) "빠른 응답 설정 닫기" else "빠른 응답 설정 열기",
                                        tint = semanticColors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        when (resolveComposerPrimaryAction(
                            isGenerating = isGenerating,
                            isStopping = isStopping,
                            isSubmittingMessage = isSubmittingMessage,
                            isImportingAttachments = isImportingAttachments,
                            composerText = value,
                            hasAttachments = hasAttachments
                        )) {
                            ComposerPrimaryAction.ImportProgress,
                            ComposerPrimaryAction.SubmitProgress -> {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = CircleShape,
                                    color = PanelBg
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            color = TextSecondary,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                            ComposerPrimaryAction.Stop -> {
                                FluidPressableSurface(
                                    onClick = onStopClick,
                                    modifier = Modifier.size(48.dp),
                                    enabled = !isStopping,
                                    shape = CircleShape,
                                    containerColor = DangerRed,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .semantics { contentDescription = "응답 중지" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "■",
                                            color = MaterialTheme.colorScheme.onError,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            ComposerPrimaryAction.Stopping -> {
                                FluidPressableSurface(
                                    onClick = {},
                                    modifier = Modifier.size(48.dp),
                                    enabled = false,
                                    shape = CircleShape,
                                    containerColor = DangerRed,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .semantics { contentDescription = "응답 중지 중" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            color = MaterialTheme.colorScheme.onError,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                            ComposerPrimaryAction.VoiceActions -> {
                                FluidPressableSurface(onClick = {}, enabled = false, modifier = Modifier.size(48.dp),
                                    shape = CircleShape, containerColor = semanticColors.primary.copy(alpha = .35f)) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("↑", color = semanticColors.onPrimary, fontSize = 24.sp,
                                            modifier = Modifier.semantics { contentDescription = "메시지 보내기, 입력 필요" })
                                    }
                                }
                            }
                            ComposerPrimaryAction.Send -> {
                                FluidPressableSurface(
                                    onClick = onSendClick,
                                    modifier = Modifier.size(48.dp),
                                    enabled = enabled,
                                    shape = CircleShape,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    role = androidx.compose.ui.semantics.Role.Button,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .semantics {
                                                contentDescription = "메시지 보내기"
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "↑",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun QuickPromptChips(
    presets: List<String>,
    onChipClick: (String) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            FluidPressableSurface(
                onClick = { onChipClick(preset) },
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                containerColor = if (enabled) PanelBg else PanelBg.copy(alpha = 0.5f),
                contentColor = if (enabled) TextPrimary else TextSecondary,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                role = Role.Button,
            ) {
                Text(
                    text = preset,
                    color = if (enabled) TextPrimary else TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

internal enum class ComposerPrimaryAction {
    Stop,
    Stopping,
    SubmitProgress,
    ImportProgress,
    VoiceActions,
    Send
}

internal fun resolveComposerPrimaryAction(
    isGenerating: Boolean,
    isSubmittingMessage: Boolean,
    isImportingAttachments: Boolean,
    composerText: String,
    hasAttachments: Boolean,
    isStopping: Boolean = false,
): ComposerPrimaryAction {
    if (isImportingAttachments) return ComposerPrimaryAction.ImportProgress
    if (isSubmittingMessage) return ComposerPrimaryAction.SubmitProgress
    if (isStopping) return ComposerPrimaryAction.Stopping
    if (isGenerating) return ComposerPrimaryAction.Stop
    if (composerText.isBlank() && !hasAttachments) return ComposerPrimaryAction.VoiceActions
    return ComposerPrimaryAction.Send
}

internal fun computePickerCapacity(
    pendingCount: Int,
    selectedCount: Int,
    maxTotal: Int = 5
): PickerCapacity {
    val remainingSlots = (maxTotal - pendingCount).coerceAtLeast(0)
    val toProcess = minOf(selectedCount, remainingSlots)
    val skipped = (selectedCount - toProcess).coerceAtLeast(0)
    return PickerCapacity(
        remainingSlots = remainingSlots,
        toProcess = toProcess,
        skipped = skipped
    )
}

internal data class PickerCapacity(
    val remainingSlots: Int,
    val toProcess: Int,
    val skipped: Int
)

internal fun shouldBlockExternalAttachments(
    generationMode: ChatGenerationMode,
    hasAttachments: Boolean
): Boolean {
    return generationMode == ChatGenerationMode.EXTERNAL_AI_API && hasAttachments
}

internal fun shouldBlockRetryForUnavailableAttachments(
    unavailableCount: Int,
    isStyleRegeneration: Boolean
): Boolean {
    return !isStyleRegeneration && unavailableCount > 0
}

internal fun appendQuickPrompt(currentInput: String, preset: String): String =
    com.projectnuke.fusion.chat.appendPromptToDraft(currentInput, preset)

@Composable
internal fun CircleTextButton(
    text: String,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        containerColor = Color.Transparent,
        contentColor = semantic.onSurface,
        role = Role.Button,
    ) {
        Box(
            modifier = if (contentDescription != null) {
                Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription }
            } else {
                Modifier.fillMaxSize()
            },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = semantic.onSurface,
                fontSize = 24.sp
            )
        }
    }
}
@Composable
private fun PendingAttachmentTray(
    attachments: List<LocalAttachment>,
    onRemove: (LocalAttachment) -> Unit,
    enabled: Boolean,
    attachmentRefreshKey: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            AttachmentCard(
                attachment = attachment,
                onRemove = if (enabled) { { onRemove(attachment) } } else null,
                lifecycleRefreshKey = attachmentRefreshKey,
            )
        }
    }
}

@Composable
internal fun AttachmentCard(
    attachment: LocalAttachment,
    onRemove: (() -> Unit)? = null,
    lifecycleRefreshKey: Int = 0,
) {
    val context = LocalContext.current
    var localRefreshKey by remember(attachment.localPath) { mutableStateOf(0) }
    val previewState by rememberAttachmentPreviewState(
        attachment = attachment,
        refreshKey = lifecycleRefreshKey to localRefreshKey,
    )
    val isAvailable = previewState !is AttachmentPreviewLoadState.Unavailable
    val canOpen = isAvailable && previewState !is AttachmentPreviewLoadState.Loading

    FluidPressableSurface(
        onClick = {
            if (!openAttachmentFile(context, attachment)) {
                localRefreshKey++
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = canOpen,
        shape = RoundedCornerShape(14.dp),
        containerColor = LocalFusionSemanticColors.current.bubble,
        role = Role.Button,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AttachmentPreview(
                attachment = attachment,
                state = previewState,
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                val displayName = attachment.name.ifBlank {
                    attachment.localPath.substringAfterLast(File.separator).ifBlank { "첨부 파일" }
                }
                val displayMime = attachment.mimeType.ifBlank { "application/octet-stream" }
                Text(
                    text = displayName,
                    color = if (isAvailable) TextPrimary else TextSecondary.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAvailable) displayMime else "첨부 파일을 찾을 수 없습니다.",
                        color = if (isAvailable) TextSecondary else TextSecondary.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val contextLabel = LocalAttachmentContextPolicy.localContextLabel(
                        attachment.mimeType,
                        displayName,
                    )
                    if (isAvailable && contextLabel != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        LocalContextAffordanceChip(label = contextLabel)
                    }
                }
            }

            if (onRemove != null) {
                Spacer(modifier = Modifier.width(4.dp))
                val semantic = LocalFusionSemanticColors.current
                FluidPressableSurface(
                    onClick = onRemove,
                    modifier = Modifier.semantics { contentDescription = "첨부 제거: ${attachment.name.ifBlank { "첨부 파일" }}" },
                    shape = CircleShape,
                    containerColor = Color.Transparent,
                    contentColor = semantic.onSurfaceVariant,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    role = Role.Button,
                ) {
                    Text(
                        text = "×",
                        color = semantic.onSurfaceVariant,
                        fontSize = 22.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalContextAffordanceChip(label: String) {
    val semantic = LocalFusionSemanticColors.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = semantic.background,
        border = BorderStroke(1.dp, semantic.divider)
    ) {
        Text(
            text = label,
            color = semantic.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun UnavailableAttachmentCard(
    attachment: LocalAttachment
) {
    val displayName = attachment.name.ifBlank {
        attachment.localPath.substringAfterLast(File.separator).ifBlank { "첨부 파일" }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = LocalFusionSemanticColors.current.bubble
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LocalFusionSemanticColors.current.divider),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚠",
                    color = TextSecondary.copy(alpha = 0.5f),
                    fontSize = 22.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayName,
                    color = TextSecondary.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "첨부 파일을 찾을 수 없습니다.",
                    color = TextSecondary.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun attachmentIcon(mimeType: String): String {
    return when {
        mimeType.startsWith("image/") -> "🖼"
        mimeType == "application/pdf" -> "📄"
        mimeType.startsWith("text/") -> "📝"
        else -> "📎"
    }
}

internal fun isImageAttachment(
    attachment: AttachmentRecord
): Boolean = isImageAttachment(LocalAttachment(attachment.name, attachment.mimeType, attachment.localPath))

internal fun isImageAttachment(
    attachment: LocalAttachment
): Boolean {
    val name = attachment.name.lowercase(Locale.ROOT)
    return attachment.mimeType.startsWith("image/") ||
        name.endsWith(".png") ||
        name.endsWith(".jpg") ||
        name.endsWith(".jpeg") ||
        name.endsWith(".webp") ||
        name.endsWith(".gif") ||
        name.endsWith(".bmp")
}

internal fun buildImageUserInstruction(
    userInput: String
): String {
    return userInput.trim().ifBlank {
        "이 이미지를 자세히 설명해 주세요."
    }
}
private sealed interface AttachmentPreviewLoadState {
    data object Loading : AttachmentPreviewLoadState
    data object Generic : AttachmentPreviewLoadState
    data object Unavailable : AttachmentPreviewLoadState
    data class Image(val bitmap: android.graphics.Bitmap) : AttachmentPreviewLoadState
}

@Composable
private fun rememberAttachmentPreviewState(
    attachment: LocalAttachment,
    refreshKey: Any,
): androidx.compose.runtime.State<AttachmentPreviewLoadState> {
    val context = LocalContext.current
    return produceState<AttachmentPreviewLoadState>(
        initialValue = AttachmentPreviewLoadState.Loading,
        key1 = attachment.localPath,
        key2 = attachment.mimeType,
        key3 = refreshKey,
    ) {
        value = AttachmentPreviewLoadState.Loading
        value = withContext(Dispatchers.IO) {
            val resolvedFile = AttachmentStorageManager.resolveManagedAttachment(
                AttachmentStorageManager.getAttachmentDirectory(context),
                attachment.localPath,
            )
            when {
                resolvedFile == null -> AttachmentPreviewLoadState.Unavailable
                !attachment.mimeType.startsWith("image/") -> AttachmentPreviewLoadState.Generic
                else -> FusionThumbnailCache.get(resolvedFile.canonicalPath)
                    ?.let(AttachmentPreviewLoadState::Image)
                    ?: AttachmentPreviewLoadState.Unavailable
            }
        }
    }
}

@Composable
private fun AttachmentPreview(
    attachment: LocalAttachment,
    state: AttachmentPreviewLoadState,
) {
    when (state) {
        is AttachmentPreviewLoadState.Image -> {
            Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = attachment.name.ifBlank { "첨부 이미지" },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        AttachmentPreviewLoadState.Loading -> {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2B2B2B)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AccentBlue,
                )
            }
        }
        AttachmentPreviewLoadState.Unavailable -> {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2B2B2B)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⚠",
                    color = TextSecondary,
                    fontSize = 20.sp,
                )
            }
        }
        AttachmentPreviewLoadState.Generic -> {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2B2B2B)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = attachmentIcon(attachment.mimeType),
                    color = TextPrimary,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

@Composable
internal fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        containerColor = Color.Transparent,
        contentColor = semantic.onSurface,
        role = Role.Button,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = semantic.onSurface,
                modifier = Modifier.size(25.dp)
            )
        }
    }
}
@Composable
private fun CircleMiniButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        shape = CircleShape,
        containerColor = Color.Transparent,
        contentColor = if (enabled) semantic.onSurface else semantic.onSurfaceVariant,
        role = Role.Button,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) semantic.onSurface else semantic.onSurfaceVariant,
                fontSize = 22.sp
            )
        }
    }
}
@Composable
private fun CircleMiniIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        enabled = enabled,
        shape = CircleShape,
        containerColor = Color.Transparent,
        contentColor = if (enabled) semantic.onSurface else semantic.onSurfaceVariant,
        role = Role.Button,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) semantic.onSurface else semantic.onSurfaceVariant,
                modifier = Modifier.size(23.dp)
            )
        }
    }
}
