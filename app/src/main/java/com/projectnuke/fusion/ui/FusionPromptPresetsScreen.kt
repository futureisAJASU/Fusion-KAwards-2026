package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PromptPresetPanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val PromptPresetCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val PromptPresetTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val PromptPresetTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val PromptPresetAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary

private data class PromptPresetCategory(
    val title: String,
    val items: List<String>
)

@Composable
fun FusionPromptPresetsDialog(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    val categories = remember { buildFusionPromptPresetCategories() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프롬프트 프리셋") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PromptPresetHeaderCard("프롬프트 프리셋", "자주 쓰는 요청 문구를 복사해 사용할 수 있습니다.")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        item {
                            PromptPresetCategoryCard(
                                category = category,
                                onCopy = { text ->
                                    clipboard.setText(AnnotatedString(text))
                                    Toast.makeText(context, "프롬프트를 복사했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(buildAllPromptPresetsText(categories)))
                Toast.makeText(context, "프롬프트 프리셋을 복사했습니다.", Toast.LENGTH_SHORT).show()
            }) { Text("전체 프리셋 복사", color = PromptPresetAccentBlue) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = PromptPresetTextSecondary) }
        },
        containerColor = PromptPresetPanelBg,
        titleContentColor = PromptPresetTextPrimary,
        textContentColor = PromptPresetTextPrimary
    )
}

@Composable
private fun PromptPresetHeaderCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PromptPresetCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = PromptPresetTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = PromptPresetTextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PromptPresetCategoryCard(
    category: PromptPresetCategory,
    onCopy: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PromptPresetCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(category.title, color = PromptPresetTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            category.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item,
                        color = PromptPresetTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { onCopy(item) }) {
                        Text("복사", color = PromptPresetAccentBlue, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun buildFusionPromptPresetCategories(): List<PromptPresetCategory> {
    return listOf(
        PromptPresetCategory(
            "요약",
            listOf(
                "아래 내용을 핵심만 간결하게 요약해 주세요.",
                "아래 내용을 bullet 형태로 정리해 주세요.",
                "중요한 결정 사항과 다음 할 일을 분리해서 정리해 주세요."
            )
        ),
        PromptPresetCategory(
            "설명",
            listOf(
                "이 내용을 더 쉽게 풀어서 설명해 주세요.",
                "전문가 관점에서 정확하게 설명해 주세요.",
                "초보자가 이해할 수 있도록 단계별로 설명해 주세요."
            )
        ),
        PromptPresetCategory(
            "비교",
            listOf(
                "두 선택지를 장단점 중심으로 비교해 주세요.",
                "표로 정리하고 마지막에 추천안을 제시해 주세요.",
                "성능, 비용, 안정성, 구현 난이도 기준으로 비교해 주세요."
            )
        ),
        PromptPresetCategory(
            "코드",
            listOf(
                "이 코드의 문제점을 찾아 수정 방향을 제안해 주세요.",
                "이 오류의 원인과 해결 방법을 단계별로 설명해 주세요.",
                "기존 기능을 깨뜨리지 않도록 안전한 수정 계획을 작성해 주세요."
            )
        ),
        PromptPresetCategory(
            "Fusion 개발",
            listOf(
                "이 기능을 안전하게 구현하기 위한 단계별 계획을 작성해 주세요.",
                "기존 기능을 깨뜨리지 않도록 테스트 체크리스트를 작성해 주세요.",
                "이 변경이 ChatScreen, 모델 런타임, Room 스키마에 영향을 주는지 먼저 감사해 주세요.",
                "작업을 단계별로 진행하고, 각 단계 후 ./gradlew assembleDebug를 실행해 주세요."
            )
        ),
        PromptPresetCategory(
            "실험",
            listOf(
                "이 결과를 기준으로 다음 실험 방향을 제안해 주세요.",
                "벤치마크 결과를 해석하고 병목 가능성을 정리해 주세요.",
                "A/B 테스트 결과에서 속도, 품질, 안정성 기준으로 비교해 주세요."
            )
        )
    )
}

private fun buildAllPromptPresetsText(categories: List<PromptPresetCategory>): String {
    return buildString {
        appendLine("Fusion 프롬프트 프리셋")
        appendLine()
        categories.forEach { category ->
            appendLine("[${category.title}]")
            category.items.forEach { item -> appendLine("- $item") }
            appendLine()
        }
    }.trimEnd()
}
