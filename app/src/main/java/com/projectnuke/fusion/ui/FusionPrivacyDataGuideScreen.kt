package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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

private val PrivacyGuidePanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val PrivacyGuideCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val PrivacyGuideTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val PrivacyGuideTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val PrivacyGuideAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary

private data class PrivacyGuideSection(
    val title: String,
    val items: List<String>
)

private val FusionPrivacyGuideSections = listOf(
    PrivacyGuideSection(
        title = "기본 원칙",
        items = listOf(
            "Fusion은 온디바이스 실행과 로컬 데이터 관리를 우선으로 설계하고 있습니다.",
            "채팅, 저장된 메모리, 대화 요약, 실험 기록은 기본적으로 기기 안에 저장됩니다.",
            "사용자가 직접 내보내기, 복사, GitHub 이슈 제보, 웹 검색 같은 외부 기능을 사용할 때는 일부 정보가 외부 앱이나 서비스로 전달될 수 있습니다."
        )
    ),
    PrivacyGuideSection(
        title = "채팅 기록",
        items = listOf(
            "채팅 기록은 앱의 로컬 저장소에 저장됩니다.",
            "Fusion은 Android 시스템 자동 백업과 기기 이전 대상에서 앱 데이터를 제외합니다.",
            "채팅 Markdown 내보내기를 사용할 경우, 사용자가 선택한 대화 내용이 파일 또는 클립보드로 내보내질 수 있습니다.",
            "채팅 기록은 설정 백업에 기본적으로 포함되지 않습니다.",
            "개발자 로그와 오류 보고서에는 채팅 전문을 포함하지 않도록 설계되어 있습니다."
        )
    ),
    PrivacyGuideSection(
        title = "메모리",
        items = listOf(
            "저장된 메모리와 대화 요약은 답변 생성에 참고될 수 있습니다.",
            "메모리 사용 설정이 꺼져 있거나 사용 안 함으로 설정된 메모리는 답변 생성에 포함되지 않습니다.",
            "저장된 메모리와 대화 요약 내용은 설정 백업에 포함되지 않습니다.",
            "개발자 로그에는 메모리 내용이 아니라 개수와 상태만 표시하도록 설계되어 있습니다."
        )
    ),
    PrivacyGuideSection(
        title = "모델 파일",
        items = listOf(
            "외부 모델 파일은 사용자가 직접 선택하거나 연결한 로컬 파일을 사용합니다.",
            "모델 파일 자체는 설정 백업에 포함되지 않습니다.",
            "모델 파일 경로가 변경되거나 파일이 삭제되면 다시 연결해야 할 수 있습니다."
        )
    ),
    PrivacyGuideSection(
        title = "벤치마크와 A/B 테스트",
        items = listOf(
            "벤치마크 기록에는 모델 이름, 설정, 속도, 성공/실패 상태 같은 성능 정보가 저장될 수 있습니다.",
            "A/B 테스트 기록에는 사용자가 입력한 테스트 프롬프트와 생성 결과가 저장될 수 있습니다.",
            "A/B 테스트 기록은 설정 백업에 기본적으로 포함되지 않습니다.",
            "개발자 로그에는 A/B 테스트 본문이 아니라 기록 개수와 상태 요약만 표시하도록 설계되어 있습니다."
        )
    ),
    PrivacyGuideSection(
        title = "설정 백업",
        items = listOf(
            "설정 백업에는 모델 선택, 생성 옵션, 일부 앱 설정이 포함될 수 있습니다.",
            "채팅 기록, 모델 파일, 저장된 메모리 내용, 대화 요약 내용, A/B 테스트 결과 본문은 설정 백업에 포함하지 않는 방향으로 설계되어 있습니다.",
            "백업 JSON을 공유하기 전에는 포함된 설정값을 확인하는 것이 좋습니다."
        )
    ),
    PrivacyGuideSection(
        title = "개발자 로그와 오류 보고서",
        items = listOf(
            "개발자 로그는 문제 해결을 위해 앱 상태, 기기 정보, 모델 설정, 오류 요약을 표시합니다.",
            "개발자 로그와 오류 보고서는 채팅 전문, 전체 프롬프트, 첨부파일 내용, 저장된 메모리 내용을 포함하지 않도록 설계되어 있습니다.",
            "오류 보고서를 외부에 공유하기 전에는 내용을 직접 확인해 주세요."
        )
    ),
    PrivacyGuideSection(
        title = "외부로 이동하는 기능",
        items = listOf(
            "GitHub 이슈 제보를 누르면 외부 브라우저 또는 GitHub 앱으로 이동할 수 있습니다.",
            "웹 검색 기능을 사용할 경우 검색 요청이나 검색어가 외부 검색 서비스로 전달될 수 있습니다.",
            "클립보드 복사 기능을 사용하면 복사된 내용은 다른 앱에 붙여넣을 수 있습니다.",
            "파일 내보내기를 사용할 경우 사용자가 선택한 위치에 파일이 저장됩니다."
        )
    ),
    PrivacyGuideSection(
        title = "권장 사항",
        items = listOf(
            "민감한 정보는 채팅, 메모리, 실험 노트, A/B 테스트 프롬프트에 입력하지 않는 것이 좋습니다.",
            "GitHub 이슈를 작성할 때는 개인정보, API 키, 비밀번호, 개인 파일 경로를 포함하지 않도록 주의해 주세요.",
            "앱을 공개 저장소에서 빌드해 사용하는 경우, 실험 기능의 동작 범위를 직접 확인하는 것이 좋습니다."
        )
    )
)

@Composable
fun FusionPrivacyDataGuideDialog(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    FusionReadingDialog(
        scrollBody = false,
        onDismissRequest = onDismiss,
        title = { Text("개인정보 및 데이터 안내") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Fusion의 로컬 데이터 저장과 내보내기 범위를 정리합니다.", color = PrivacyGuideTextSecondary, fontSize = 12.sp)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(FusionPrivacyGuideSections) { section ->
                        PrivacyGuideSectionCard(section)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(buildFusionPrivacyGuideText()))
                    Toast.makeText(context, "개인정보 및 데이터 안내를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("안내 복사", color = PrivacyGuideAccentBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = PrivacyGuideTextSecondary)
            }
        },
        containerColor = PrivacyGuidePanelBg,
        titleContentColor = PrivacyGuideTextPrimary,
        textContentColor = PrivacyGuideTextPrimary
    )
}

@Composable
private fun PrivacyGuideSectionCard(section: PrivacyGuideSection) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PrivacyGuideCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(section.title, color = PrivacyGuideTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            section.items.forEach { item ->
                Text("- $item", color = PrivacyGuideTextSecondary, fontSize = 12.sp)
            }
        }
    }
}

private fun buildFusionPrivacyGuideText(): String {
    return buildString {
        appendLine("Fusion 개인정보 및 데이터 안내")
        appendLine("Fusion의 로컬 데이터 저장과 내보내기 범위를 정리합니다.")
        FusionPrivacyGuideSections.forEach { section ->
            appendLine()
            appendLine("[${section.title}]")
            section.items.forEach { item ->
                appendLine("- $item")
            }
        }
    }.trimEnd()
}
