package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import kotlinx.coroutines.launch

/** Presentation only: the caller still owns attachment admission and durable draft writes. */
@Composable
internal fun ComposerAddMenu(
    enabled: Boolean,
    quickPromptsAllowed: Boolean,
    quickPromptPresets: List<String>,
    dictationActive: Boolean,
    voiceModeActive: Boolean,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onDictation: () -> Unit,
    onVoiceMode: () -> Unit,
    onQuickPrompt: (String) -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val motion = LocalFluidMotionMode.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("자료·입력 도구", "빠른 입력").forEachIndexed { page, label ->
                FluidPressableSurface(
                    onClick = { scope.launch {
                        if (motion == FluidMotionMode.FULL) pager.animateScrollToPage(page)
                        else pager.scrollToPage(page)
                    } },
                    modifier = Modifier.weight(1f).heightIn(min = FusionSpacing.control),
                    shape = MaterialTheme.shapes.small,
                    containerColor = if (pager.targetPage == page) semantic.bubble else Color.Transparent,
                    contentColor = if (pager.targetPage == page) semantic.primary else semantic.onSurfaceVariant,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    role = Role.Tab,
                    isSelected = pager.targetPage == page,
                ) { Text(label, style = MaterialTheme.typography.labelLarge) }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = semantic.divider)
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("composer-add-pages")
                .semantics { stateDescription = if (pager.currentPage == 0) "자료·입력 도구 페이지" else "빠른 입력 페이지" },
        ) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                if (page == 0) {
                    DropdownMenuItem(text = { Text("카메라로 자료 캡처") },
                        leadingIcon = { Icon(Icons.Rounded.CameraAlt, null) }, enabled = enabled, onClick = onCamera)
                    DropdownMenuItem(text = { Text("파일 첨부") },
                        leadingIcon = { Icon(Icons.Rounded.AttachFile, null) }, enabled = enabled, onClick = onFile)
                    DropdownMenuItem(text = { Text(if (dictationActive) "음성 입력 중지" else "음성 입력") },
                        leadingIcon = { Icon(Icons.Rounded.Mic, null) }, enabled = enabled, onClick = onDictation)
                    DropdownMenuItem(text = { Text(if (voiceModeActive) "보이스 모드 종료" else "보이스 모드") },
                        leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null) }, enabled = enabled, onClick = onVoiceMode)
                } else {
                    if (!quickPromptsAllowed) {
                        Text("현재 대화 모드에서는 빠른 입력을 사용할 수 없습니다.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = semantic.onSurfaceVariant)
                    } else if (quickPromptPresets.isEmpty()) {
                        Text("사용할 수 있는 빠른 입력이 없습니다.", modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = semantic.onSurfaceVariant)
                    }
                    quickPromptPresets.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null) }, enabled = enabled && quickPromptsAllowed,
                            onClick = { onQuickPrompt(preset) })
                    }
                }
            }
        }
    }
}
