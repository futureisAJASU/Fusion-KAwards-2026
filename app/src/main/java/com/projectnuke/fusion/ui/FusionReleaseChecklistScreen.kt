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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ReleaseChecklistPanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val ReleaseChecklistCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val ReleaseChecklistTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val ReleaseChecklistTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val ReleaseChecklistAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary

internal data class ReleaseChecklistGroup(
    val title: String,
    val items: List<String>
)

@Composable
fun FusionReleaseChecklistDialog(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    val groups = remember { buildFusionReleaseChecklistGroups() }
    val checkedStates = remember(groups) {
        mutableStateMapOf<String, Boolean>().apply {
            groups.flatMap { group -> group.items.map { item -> "${group.title}::$item" } }
                .forEach { put(it, false) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("릴리즈 체크리스트") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReleaseChecklistHeaderCard("릴리즈 체크리스트", "배포 전 실기기에서 수동으로 확인할 항목을 체크합니다.")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEach { group ->
                        item {
                            ReleaseChecklistGroupCard(
                                group = group,
                                checkedStates = checkedStates
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    checkedStates.keys.forEach { key -> checkedStates[key] = true }
                }) { Text("전체 선택", color = ReleaseChecklistAccentBlue) }
                TextButton(onClick = {
                    checkedStates.keys.forEach { key -> checkedStates[key] = false }
                }) { Text("초기화", color = ReleaseChecklistAccentBlue) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(buildReleaseChecklistText(groups, checkedStates)))
                    Toast.makeText(context, "체크리스트를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }) { Text("체크리스트 복사", color = ReleaseChecklistAccentBlue) }
                TextButton(onClick = onDismiss) { Text("닫기", color = ReleaseChecklistTextSecondary) }
            }
        },
        containerColor = ReleaseChecklistPanelBg,
        titleContentColor = ReleaseChecklistTextPrimary,
        textContentColor = ReleaseChecklistTextPrimary
    )
}

@Composable
private fun ReleaseChecklistHeaderCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ReleaseChecklistCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = ReleaseChecklistTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = ReleaseChecklistTextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReleaseChecklistGroupCard(
    group: ReleaseChecklistGroup,
    checkedStates: MutableMap<String, Boolean>
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ReleaseChecklistCardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(group.title, color = ReleaseChecklistTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            group.items.forEach { item ->
                val key = "${group.title}::$item"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = checkedStates[key] == true,
                        onCheckedChange = { checkedStates[key] = it }
                    )
                    Text(
                        text = item,
                        color = ReleaseChecklistTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

internal fun buildFusionReleaseChecklistGroups(): List<ReleaseChecklistGroup> {
    return listOf(
        ReleaseChecklistGroup(
            "빌드/설치",
            listOf(
                "Gradle 빌드 확인",
                "APK 설치 확인",
                "앱 실행 확인",
                "앱 버전 표시 확인"
            )
        ),
        ReleaseChecklistGroup(
            "첫 실행/설정",
            listOf(
                "온보딩 로컬 모델 경로 완료 확인",
                "온보딩 외부 제공자 경로 완료 확인",
                "설정 없이 둘러보기 확인",
                "설정에서 온보딩 다시 열기 확인"
            )
        ),
        ReleaseChecklistGroup(
            "채팅",
            listOf(
                "로컬 모델 답변 생성 확인",
                "외부 API 답변 생성 확인",
                "새 채팅 생성 확인",
                "작성 중인 문안 보존 확인",
                "생성 중지/취소 확인",
                "답변 다시 생성 확인",
                "답변 버전 전환 확인",
                "답변별 생성 정보와 맥락 참여 확인",
                "브랜치 분기 확인",
                "답변 복사/공유 확인",
                "로컬 실행 프로필 확인",
                "자동 실행 프로필에서 로컬 자동 모델 선택 확인",
                "자동 프로필의 외부 AI 제안 취소 시 외부 호출 없음 확인",
                "외부 AI 프로필 제공자 실행 확인",
                "생성 중 프로필 변경이 진행 중 요청을 바꾸지 않는지 확인"
            )
        ),
        ReleaseChecklistGroup(
            "사실 확인",
            listOf(
                "기존 출처만 사용한 사실 확인 확인",
                "승인된 웹 검색을 포함한 사실 확인 확인",
                "검색 취소 시 웹 요청 없음 확인",
                "근거 부족과 충돌 상태 구분 확인",
                "로컬/비공개 자료가 검색 쿼리에 자동 포함되지 않는지 확인"
            )
        ),
        ReleaseChecklistGroup(
            "Work Plan",
            listOf(
                "다음 단계 실행이 한 단계만 시작하는지 확인",
                "REVIEW에서 사용자 검토 없이 다음 단계가 실행되지 않는지 확인",
                "Task/Decision 확인 취소 시 계획 상태 확인",
                "Research/비교/추출 handoff 후 기존 기능에서 이어지는지 확인",
                "계획 화면을 닫았다 다시 열어도 실행 상태가 일관적인지 확인"
            )
        ),
        ReleaseChecklistGroup(
            "Inbox",
            listOf(
                "정리 제안 생성 확인",
                "관련성이 약한 자료가 무리하게 Project로 추천되지 않는지 확인",
                "Smart Inbox가 오프라인에서도 기본 제안을 만드는지 확인",
                "할 일 제안에서 제목·메모 편집 후 저장 확인",
                "복습 카드 제안에서 앞면·뒷면 편집 후 저장 확인",
                "제안 생성 후 원본 Inbox 항목 변경 시 오래된 제안 거부 확인",
                "정리 제안만 열었을 때 Inbox 자료가 이동되거나 삭제되지 않는지 확인"
            )
        ),
        ReleaseChecklistGroup(
            "개인정보/외부 전송",
            listOf(
                "일반 외부 요청 동작 확인",
                "비공개/로컬 문서의 외부 전송 승인 흐름 확인",
                "취소 시 외부 호출 없음 확인",
                "제공자 시크릿/설정 화면 확인"
            )
        ),
        ReleaseChecklistGroup(
            "문서/RAG",
            listOf(
                "텍스트 첨부 확인",
                "읽기 가능한 PDF 첨부 확인",
                "이미지 전용 PDF 거동 확인",
                "프로젝트 지식 확인",
                "로컬 출처/근거 UI 확인"
            )
        ),
        ReleaseChecklistGroup(
            "학습",
            listOf(
                "학습 모드 진입 확인",
                "학습 프롬프트 동작이 추가 전용인지 확인",
                "학습 모드 종료 확인",
                "대화/새 채팅 경계에서 의도치 않은 지속성 없음 확인"
            )
        ),
        ReleaseChecklistGroup(
            "추천 질문",
            listOf(
                "조건 충족 시 관련 질문 생성 확인",
                "탭하면 작성창에 삽입되는지 확인",
                "자동 전송 없음 확인",
                "비활성화 설정에서 대체 동작 확인"
            )
        ),
        ReleaseChecklistGroup(
            "번역",
            listOf(
                "단독 번역 화면 확인",
                "채팅 답변 → 번역 확인",
                "번역 → 작성창 삽입 확인",
                "로컬 백엔드 부재 시 처리 확인",
                "언어 교환/복사 확인"
            )
        ),
        ReleaseChecklistGroup(
            "아카이브/보안",
            listOf(
                "아카이브 열기 확인",
                "아카이브 복원 확인",
                "아카이브 잠금 인증 확인",
                "잠긴 아카이브 바로가기 거부 확인"
            )
        ),
        ReleaseChecklistGroup(
            "음성/읽어주기",
            listOf(
                "받아쓰기 확인",
                "음성 최종 전송 동작 확인",
                "어시스턴트 읽어주기 시작/중지 확인"
            )
        ),
        ReleaseChecklistGroup(
            "프로젝트",
            listOf(
                "프로젝트 생성 확인",
                "프로젝트 채팅 확인",
                "프로젝트 지식 확인",
                "프로젝트 브리프/종합 확인",
                "삭제 후 대화 소유권 유지 확인"
            )
        ),
        ReleaseChecklistGroup(
            "모델",
            listOf(
                "모델 라이브러리 열기 확인",
                "모델 상세 화면 확인",
                "모델 호환성 가이드 확인",
                "모델 선택 흐름 확인",
                "외부 모델 목록 탐색과 변형 선택 확인",
                "자동·수동 성능 설정 적용 확인"
            )
        ),
        ReleaseChecklistGroup(
            "메모리",
            listOf(
                "메모리 관리 화면 확인",
                "메모리 컨텍스트 미리보기 확인",
                "메모리 사용 켬/끔 확인",
                "대화 요약 확인"
            )
        ),
        ReleaseChecklistGroup(
            "실험",
            listOf(
                "벤치마크 화면 확인",
                "A/B 테스트 화면 확인",
                "A/B 테스트 기록 확인",
                "Prompt Lab 확인"
            )
        ),
        ReleaseChecklistGroup(
            "데이터",
            listOf(
                "설정 백업 및 복원 화면 확인",
                "채팅 Markdown 내보내기 확인",
                "아카이브 화면 확인",
                "첨부 파일 저장 공간 화면 확인"
            )
        ),
        ReleaseChecklistGroup(
            "진단",
            listOf(
                "상태 점검 확인",
                "상태 대시보드 확인",
                "기기 정보 화면 확인",
                "개발자 로그 복사 확인",
                "업데이트 기록 확인"
            )
        )
    )
}

private fun buildReleaseChecklistText(
    groups: List<ReleaseChecklistGroup>,
    checkedStates: Map<String, Boolean>
): String {
    return buildString {
        appendLine("Fusion 릴리즈 체크리스트")
        appendLine()
        groups.forEach { group ->
            appendLine("[${group.title}]")
            group.items.forEach { item ->
                val checked = checkedStates["${group.title}::$item"] == true
                appendLine("- [${if (checked) "x" else " "}] $item")
            }
            appendLine()
        }
    }.trimEnd()
}
