package com.projectnuke.fusion.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

private val HelpPanelBg: Color
    @Composable get() = LocalFusionSemanticColors.current.panel
private val HelpCardBg: Color
    @Composable get() = LocalFusionSemanticColors.current.menu
private val HelpTextPrimary: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurface
private val HelpTextSecondary: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val HelpAccentBlue: Color
    @Composable get() = LocalFusionSemanticColors.current.primary

private data class HelpSection(
    val title: String,
    val items: List<String>
)

private val FusionHelpSections = listOf(
    HelpSection(
        title = "처음 시작하기",
        items = listOf(
            "모델 선택에서 추천 모델이나 가져온 모델을 고릅니다.",
            "사용하기를 누르면 채팅에 적용됩니다.",
            "응답 설정에서 답변 스타일과 길이를 쉽게 조절할 수 있습니다.",
            "세부 파라미터가 필요하면 고급 설정을 사용하세요.",
            "모델을 선택한 뒤 새 채팅에서 메시지를 보내면 로컬 모델로 답변을 생성합니다."
        )
    ),
    HelpSection(
        title = "모델 라이브러리",
        items = listOf(
            "추천에서는 바로 사용할 수 있는 모델과 기기에 잘 맞는 모델을 먼저 보여줍니다.",
            "내 모델에서는 가져오거나 연결한 모델을 확인할 수 있습니다.",
            "더 찾아보기에서 모델 검색, 필터, 정렬 기능을 사용할 수 있습니다.",
            "즐겨찾기는 자주 쓰는 모델을 빠르게 찾을 때 사용합니다.",
            "숨김 기능은 당장 사용하지 않는 모델 후보를 목록에서 정리할 때 사용합니다.",
            "모델 정보 화면에서는 크기, 권장 메모리, 실행 주의사항과 기술 정보를 확인할 수 있습니다.",
            "현재 메모리 여유가 적을 때는 실행 전에 다른 앱을 닫으면 더 안정적일 수 있습니다."
        )
    ),
    HelpSection(
        title = "채팅",
        items = listOf(
            "일반 채팅에서는 현재 선택된 모델과 생성 설정이 사용됩니다.",
            "답변 다시 생성은 기존 답변을 새로 생성할 때 사용합니다.",
            "더 짧게, 더 자세히, 표로 정리, 전문가 톤 같은 재생성 옵션을 사용할 수 있습니다.",
            "생성 중에는 다른 생성 작업을 동시에 시작하지 않는 것이 좋습니다."
        )
    ),
    HelpSection(
        title = "메모리",
        items = listOf(
            "대화 요약은 긴 대화의 핵심 맥락을 유지하는 데 사용됩니다.",
            "메모리 후보 추출은 대화에서 나중에 참고할 만한 정보를 뽑아 확인하는 기능입니다.",
            "메모리 후보는 자동 저장되지 않으며, 사용자가 저장한 항목만 메모리로 관리됩니다.",
            "메모리 관리 화면에서 저장된 메모리의 사용 여부와 적용 범위를 바꿀 수 있습니다.",
            "메모리 컨텍스트 미리보기에서 답변 생성에 포함될 내용을 확인할 수 있습니다."
        )
    ),
    HelpSection(
        title = "벤치마크",
        items = listOf(
            "벤치마크는 모델의 응답 시작 시간과 토큰 생성 속도를 측정합니다.",
            "MTP 켬/끔, 가속기, 최대 토큰 수 같은 설정 차이를 비교할 수 있습니다.",
            "총 tok/s보다 디코딩 기준 tok/s를 함께 보는 것이 좋습니다.",
            "벤치마크 전에는 다른 생성 작업이 진행 중인지 확인해 주세요."
        )
    ),
    HelpSection(
        title = "A/B 테스트",
        items = listOf(
            "A/B 테스트는 같은 프롬프트를 여러 모델 또는 설정으로 실행해 비교하는 기능입니다.",
            "테스트는 메모리 부담을 줄이기 위해 순차적으로 실행됩니다.",
            "결과 카드에서 답변, 설정, 속도 정보를 함께 확인할 수 있습니다.",
            "A/B 테스트 기록에서 이전 실험 결과를 다시 볼 수 있습니다."
        )
    ),
    HelpSection(
        title = "데이터 관리",
        items = listOf(
            "설정 백업 및 복원은 모델 선택, 생성 옵션, 일부 앱 설정을 보관할 때 사용합니다.",
            "채팅 Markdown 내보내기는 선택한 대화를 파일로 저장할 때 사용합니다.",
            "모델 파일, 채팅 기록, 저장된 메모리 내용, A/B 테스트 결과 본문은 기본적으로 설정 백업에 포함하지 않습니다."
        )
    ),
    HelpSection(
        title = "문제 해결",
        items = listOf(
            "문제가 발생하면 먼저 상태 대시보드와 기기 정보를 확인해 주세요.",
            "모델 실행 오류가 반복되면 개발자 로그와 오류 보고서를 복사해 확인할 수 있습니다.",
            "ADB 설치나 logcat 확인이 필요하면 Android SDK platform-tools와 개발자 로그를 확인해 주세요."
        )
    )
)

@Composable
fun FusionHelpScreen(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사용 가이드") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Fusion의 주요 기능을 빠르게 확인합니다.", color = HelpTextSecondary, fontSize = 12.sp)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(FusionHelpSections) { section ->
                        HelpSectionCard(section)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(buildFusionHelpGuideText()))
                    Toast.makeText(context, "사용 가이드를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("가이드 복사", color = HelpAccentBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = HelpTextSecondary)
            }
        },
        containerColor = HelpPanelBg,
        titleContentColor = HelpTextPrimary,
        textContentColor = HelpTextPrimary
    )
}

@Composable
private fun HelpSectionCard(section: HelpSection) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HelpCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(section.title, color = HelpTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            section.items.forEach { item ->
                Text("- $item", color = HelpTextSecondary, fontSize = 12.sp)
            }
        }
    }
}

private fun buildFusionHelpGuideText(): String {
    return buildString {
        appendLine("Fusion 사용 가이드")
        appendLine("Fusion의 주요 기능을 빠르게 확인합니다.")
        FusionHelpSections.forEach { section ->
            appendLine()
            appendLine("[${section.title}]")
            section.items.forEach { item ->
                appendLine("- $item")
            }
        }
    }.trimEnd()
}
