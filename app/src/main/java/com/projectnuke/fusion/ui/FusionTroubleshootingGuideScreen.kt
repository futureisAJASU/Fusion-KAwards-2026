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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TroublePanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val TroubleCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val TroubleTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val TroubleTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val TroubleAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary

private data class TroubleCategory(
    val title: String,
    val items: List<String>
)

@Composable
fun FusionTroubleshootingGuideDialog(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    val categories = remember { buildTroubleshootingCategories() }

    FusionReadingDialog(
        scrollBody = false,
        onDismissRequest = onDismiss,
        title = { Text("문제 해결 가이드") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TroubleHeaderCard(
                    "문제 해결 가이드",
                    "Fusion 사용 중 자주 발생할 수 있는 문제와 확인 방법을 정리합니다."
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        item {
                            TroubleCategoryCard(
                                category = category,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(buildTroubleCategoryText(category)))
                                    Toast.makeText(context, "문제 해결 항목을 복사했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(buildAllTroubleshootingText(categories)))
                    Toast.makeText(context, "문제 해결 가이드를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("전체 가이드 복사", color = TroubleAccentBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = TroubleTextSecondary)
            }
        },
        containerColor = TroublePanelBg,
        titleContentColor = TroubleTextPrimary,
        textContentColor = TroubleTextPrimary
    )
}

@Composable
private fun TroubleHeaderCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = TroubleCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = TroubleTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = TroubleTextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TroubleCategoryCard(
    category: TroubleCategory,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = TroubleCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(category.title, color = TroubleTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            category.items.forEach { item ->
                Text("- $item", color = TroubleTextSecondary, fontSize = 12.sp)
            }
            TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Text("이 항목 복사", color = TroubleAccentBlue, fontSize = 12.sp)
            }
        }
    }
}

private fun buildTroubleshootingCategories(): List<TroubleCategory> {
    return listOf(
        TroubleCategory(
            "모델이 실행되지 않을 때",
            listOf(
                "모델 파일 경로가 올바른지 확인해 주세요.",
                "모델 파일이 삭제되었거나 이동된 경우 다시 연결해야 합니다.",
                "지원되지 않는 형식의 모델은 변환 후 사용할 수 있습니다.",
                "같은 모델을 다시 선택해 초기 연결 상태를 확인해 보세요.",
                "앱을 다시 시작한 뒤에도 같은 문제가 반복되는지 확인해 주세요.",
                "모델 로드 오류가 계속되면 개발자 로그를 함께 확인해 주세요."
            )
        ),
        TroubleCategory(
            "메모리 부족 또는 앱 종료",
            listOf(
                "큰 모델 실행 전 다른 앱을 정리하는 것이 좋습니다.",
                "최대 토큰 수를 낮추면 KV 캐시와 런타임 메모리 부담을 줄일 수 있습니다.",
                "8GB급 기기에서는 5GB 이상 모델의 로컬 실행을 권장하지 않습니다.",
                "저메모리 기기에서는 소형 모델과 낮은 생성 설정을 우선 권장합니다.",
                "GPU delegate, 런타임 버퍼, 출력 길이 증가로 실제 메모리 사용량이 더 커질 수 있습니다.",
                "벤치마크도 낮은 토큰 수와 보수적인 설정으로 먼저 시도해 보세요."
            )
        ),
        TroubleCategory(
            "응답이 너무 느릴 때",
            listOf(
                "더 작은 모델로 바꿔 응답 시작 속도를 먼저 비교해 보세요.",
                "최대 토큰 수를 낮추면 긴 생성으로 인한 지연을 줄일 수 있습니다.",
                "가능한 경우 CPU와 GPU 계열 가속 경로를 비교해 보세요.",
                "MTP는 일부 기기와 모델에서 오히려 느릴 수 있으므로 켬/끔 모두 확인하는 것이 좋습니다.",
                "총 tok/s뿐 아니라 디코딩 기준 tok/s도 함께 확인해 주세요.",
                "기기 온도가 높아지면 스로틀링으로 속도가 떨어질 수 있습니다."
            )
        ),
        TroubleCategory(
            "벤치마크 오류",
            listOf(
                "벤치마크 전에 일반 채팅 생성이 진행 중이지 않은지 확인해 주세요.",
                "모델 설정을 변경한 뒤에는 같은 조건으로 다시 실행해 보세요.",
                "모델이 정상적으로 다시 로드되었는지 확인해 주세요.",
                "비교 실험에서는 고정된 프롬프트를 사용해야 결과 해석이 쉬워집니다.",
                "벤치마크 기록 화면에서 이전 결과와 실패 패턴을 함께 확인해 주세요.",
                "오류가 반복되면 개발자 로그와 logcat을 함께 확인해 주세요."
            )
        ),
        TroubleCategory(
            "MTP 또는 가속기 설정",
            listOf(
                "MTP가 항상 더 빠른 것은 아니며 기기와 모델에 따라 결과가 달라질 수 있습니다.",
                "MTP 켬/끔은 기기별, 모델별로 직접 테스트해 보는 것이 가장 정확합니다.",
                "현재 앱에서 NPU 실행은 보장하지 않으며 후보 정보로만 표시합니다.",
                "Snapdragon, Exynos, Tensor, MediaTek 기기마다 사용 가능한 가속 경로가 다를 수 있습니다.",
                "앱에 표시되는 가속기 정보나 실제 백엔드 상태가 있다면 함께 확인해 주세요."
            )
        ),
        TroubleCategory(
            "채팅 화면 문제",
            listOf(
                "키보드가 열릴 때 입력창이나 빠른 입력 영역의 배치가 어색한지 먼저 확인해 주세요.",
                "생성 중에는 다른 생성 작업이 잠금 상태로 차단될 수 있습니다.",
                "답변 다시 생성이 실패해도 기존 답변은 유지되도록 설계되어 있습니다.",
                "같은 문제가 반복되면 개발자 로그를 복사해 함께 확인해 주세요."
            )
        ),
        TroubleCategory(
            "메모리 기능 문제",
            listOf(
                "저장된 메모리는 메모리 사용 설정이 켜져 있을 때 답변 생성에 참고됩니다.",
                "메모리별 사용 중 또는 사용 안 함 상태를 확인해 주세요.",
                "적용 범위가 전체 대화, 현재 대화, 특정 모델 중 무엇으로 설정되어 있는지 확인해 주세요.",
                "메모리 컨텍스트 미리보기에서 실제로 포함될 메모리를 확인해 주세요.",
                "삭제되었거나 사용 안 함으로 바뀐 메모리는 답변 생성에 포함되지 않습니다.",
                "메모리 내용 자체는 개발자 로그에 포함되지 않도록 설계되어 있습니다."
            )
        ),
        TroubleCategory(
            "A/B 테스트 문제",
            listOf(
                "A/B 테스트 대상은 메모리 부담을 줄이기 위해 순차적으로 실행됩니다.",
                "지원되지 않거나 원격 전용인 모델은 로컬 A/B 테스트 대상에서 제외됩니다.",
                "한 대상이 실패해도 다른 대상 결과는 유지되는지 확인해 주세요.",
                "이전 기록이 너무 많으면 A/B 테스트 기록 화면에서 삭제해 정리할 수 있습니다.",
                "A/B 테스트 결과 본문은 개발자 로그에 포함되지 않도록 설계되어 있습니다."
            )
        ),
        TroubleCategory(
            "ADB 설치 문제",
            listOf(
                "adb devices로 기기가 연결되어 있는지 확인해 주세요.",
                "adb 명령어를 찾을 수 없으면 Android SDK platform-tools 경로의 adb.exe를 직접 실행해 주세요.",
                "설치 명령은 app/build/outputs/apk/debug/app-debug.apk 경로 기준으로 다시 확인해 주세요.",
                "USB 디버깅이 켜져 있는지 확인해 주세요.",
                "케이블을 다시 연결하고 기기에서 USB 디버깅 인증을 허용했는지 확인해 주세요."
            )
        ),
        TroubleCategory(
            "로그 확인",
            listOf(
                "개발자 로그 화면에서 앱 상태와 오류 보고서를 복사할 수 있습니다.",
                "오류 보고서는 개발자 로그보다 간단한 공유용 상태 요약으로 사용할 수 있습니다.",
                "logcat에서는 AndroidRuntime, FATAL EXCEPTION, Fusion, LiteRt 같은 패턴을 확인해 주세요.",
                "기본적으로 private chat contents는 오류 보고서와 개발자 로그에 포함하지 않도록 설계되어 있습니다."
            )
        )
    )
}

private fun buildTroubleCategoryText(category: TroubleCategory): String {
    return buildString {
        appendLine("[${category.title}]")
        category.items.forEach { appendLine("- $it") }
    }.trimEnd()
}

private fun buildAllTroubleshootingText(categories: List<TroubleCategory>): String {
    return buildString {
        appendLine("Fusion 문제 해결 가이드")
        appendLine("Fusion 사용 중 자주 발생할 수 있는 문제와 확인 방법을 정리합니다.")
        appendLine()
        categories.forEach { category ->
            appendLine("[${category.title}]")
            category.items.forEach { appendLine("- $it") }
            appendLine()
        }
    }.trimEnd()
}
