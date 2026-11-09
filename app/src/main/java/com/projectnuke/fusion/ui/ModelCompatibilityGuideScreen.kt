package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

private val GuideBg: Color @Composable get() = LocalFusionSemanticColors.current.background
private val GuideCard: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val GuideText: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val GuideSubText: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val GuideAccent: Color @Composable get() = LocalFusionSemanticColors.current.primary

data class ModelGuideSection(
    val title: String,
    val items: List<String>
)

private fun modelGuideSections(): List<ModelGuideSection> = listOf(
    ModelGuideSection(
        title = "권장 기기 기준",
        items = listOf(
            "8GB급 기기에서는 0.5B~2B급 소형 모델을 우선 권장합니다.",
            "3B~4B급 모델은 설정과 가용 메모리에 따라 주의가 필요합니다.",
            "5GB 이상 모델 파일은 8GB급 기기에서 기본 추천에서 제외하는 것이 안전합니다."
        )
    ),
    ModelGuideSection(
        title = "8GB급 기기",
        items = listOf(
            "소형 모델과 낮은 최대 토큰 수 설정을 우선 권장합니다.",
            "긴 출력, 높은 최대 토큰 수, 무거운 모델 조합은 메모리 부족 가능성을 높일 수 있습니다.",
            "실행 전 다른 앱을 정리하고, 가능하면 벤치마크로 실제 속도를 먼저 확인해 주세요."
        )
    ),
    ModelGuideSection(
        title = "12GB급 기기",
        items = listOf(
            "소형 모델은 비교적 안정적으로 사용할 수 있습니다.",
            "일부 중형 모델은 설정에 따라 실험할 수 있지만, 긴 출력에서는 주의가 필요합니다.",
            "A/B 테스트와 벤치마크를 통해 실제 사용 가능한 범위를 확인하는 것이 좋습니다."
        )
    ),
    ModelGuideSection(
        title = "16GB 이상 기기",
        items = listOf(
            "더 큰 모델 실험이 가능하지만 모델별 메모리 사용량과 출력 길이를 함께 확인해 주세요.",
            "GPU delegate, 가속기, MTP 설정 차이에 따라 실제 성능과 메모리 사용량이 달라질 수 있습니다.",
            "큰 모델도 항상 빠른 것은 아니므로 벤치마크와 실제 채팅 사용감을 함께 비교해 주세요."
        )
    ),
    ModelGuideSection(
        title = "모델 크기와 실제 메모리 차이",
        items = listOf(
            "모델 파일 크기보다 실제 실행 메모리가 더 커질 수 있습니다.",
            "KV 캐시, 런타임 버퍼, GPU delegate, 토큰 수 설정이 메모리 사용량에 영향을 줍니다.",
            "출력 길이가 길수록 실행 중 메모리 부담이 커질 수 있습니다."
        )
    ),
    ModelGuideSection(
        title = "로컬 실행과 원격 실행 구분",
        items = listOf(
            "로컬 실행 가능 모델은 현재 기기 메모리와 형식 조건을 함께 확인해야 합니다.",
            "원격 전용 후보나 변환이 필요한 후보는 바로 실행되지 않을 수 있습니다.",
            "Fusion에서는 후보 정보와 실제 로컬 실행 가능 상태를 구분해 확인하는 것이 좋습니다."
        )
    ),
    ModelGuideSection(
        title = "Gemma",
        items = listOf(
            "Fusion의 기본 로컬 채팅 후보로 보기 좋은 모델군입니다.",
            "현재 앱의 기본 모델 실행 흐름과 가장 잘 맞는 편입니다.",
            "8GB급 기기에서는 작은 모델과 보수적인 설정을 권장합니다."
        )
    ),
    ModelGuideSection(
        title = "Qwen",
        items = listOf(
            "소형 모델부터 코딩, 추론 실험용 후보까지 폭이 넓습니다.",
            "8GB급 기기에서는 0.6B~1.7B급 모델을 우선 권장합니다.",
            "4B 이상 모델은 메모리 상태와 토큰 수 설정을 함께 확인해 주세요."
        )
    ),
    ModelGuideSection(
        title = "Llama",
        items = listOf(
            "생태계가 넓고 참고 자료가 많은 대표 모델군입니다.",
            "1B~3B급 모델은 비교적 실험 후보로 보기 좋습니다.",
            "더 큰 Llama 계열은 모바일 로컬 실행보다 PC나 서버 환경에 더 적합할 수 있습니다."
        )
    ),
    ModelGuideSection(
        title = "Phi",
        items = listOf(
            "작은 모델에서 추론과 코딩 성능을 실험하기 좋은 후보입니다.",
            "8GB급 기기에서는 최대 토큰 수를 낮게 설정하는 것이 좋습니다.",
            "추론형 출력은 길어질 수 있어 메모리와 속도에 주의가 필요합니다."
        )
    ),
    ModelGuideSection(
        title = "DeepSeek",
        items = listOf(
            "추론 특화 모델 실험 후보입니다.",
            "Distill 계열 모델은 로컬 실험 후보로 볼 수 있습니다.",
            "더 큰 DeepSeek 계열은 모바일 로컬 실행보다 원격 실행에 더 적합할 수 있습니다."
        )
    ),
    ModelGuideSection(
        title = "Mistral",
        items = listOf(
            "경량 또는 중형 범위에서 실험 후보로 볼 수 있습니다.",
            "기기별 호환성과 형식 조건을 함께 확인해 주세요.",
            "Ministral 계열은 모바일 실험 후보로 검토할 수 있습니다."
        )
    ),
    ModelGuideSection(
        title = "Kimi",
        items = listOf(
            "대체로 원격 또는 별도 실행 환경에 더 가까운 후보입니다.",
            "모바일 로컬 실행보다는 서버나 원격 실행 후보로 보는 것이 좋습니다.",
            "Fusion에서는 로컬 실행 후보보다 모델 카탈로그나 원격 후보로 분류하는 것이 적합할 수 있습니다."
        )
    ),
    ModelGuideSection(
        title = "모델 선택 전 확인할 것",
        items = listOf(
            "모델 파일이 로컬에 있는지 확인해 주세요.",
            "지원 형식인지 확인해 주세요.",
            "권장 최대 토큰 수를 확인해 주세요.",
            "벤치마크로 실제 속도를 확인해 주세요."
        )
    )
)

private fun buildGuideText(): String {
    return buildString {
        appendLine("# Fusion 모델 호환성 가이드")
        appendLine()
        modelGuideSections().forEach { section ->
            appendLine("## ${section.title}")
            section.items.forEach { item ->
                appendLine("- $item")
            }
            appendLine()
        }
    }
}

@Composable
fun ModelCompatibilityGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    FusionReadingDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = GuideBg,
        titleContentColor = GuideText,
        textContentColor = GuideText,
        title = {
            Column {
                Text(
                    text = "모델 호환성 가이드",
                    color = GuideText,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "모델군별 특징과 실행 기준을 확인합니다.",
                    color = GuideSubText
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()

            ) {
                modelGuideSections().forEach { section ->
                    GuideSectionCard(section)
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        copyTextToClipboard(
                            context = context,
                            label = "Fusion 모델 호환성 가이드",
                            text = buildGuideText()
                        )
                        Toast.makeText(context, "모델 호환성 가이드를 복사했습니다.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = GuideAccent)
                ) {
                    Text("가이드 복사")
                }

                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = GuideAccent)
                ) {
                    Text("닫기")
                }
            }
        }
    )
}

@Composable
private fun GuideSectionCard(section: ModelGuideSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = GuideCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = section.title,
                color = GuideAccent,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            section.items.forEach { item ->
                Text(
                    text = "- $item",
                    color = GuideText,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
    }
}

private fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
