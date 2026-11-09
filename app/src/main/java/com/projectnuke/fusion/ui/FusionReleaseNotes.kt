package com.projectnuke.fusion.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class FusionReleaseNoteSection(
    val title: String,
    val items: List<String>
)

data class FusionReleaseNote(
    val version: String,
    val status: String,
    val summary: String,
    val sections: List<FusionReleaseNoteSection>,
    val date: String? = null
)

internal data class FusionReleaseNoteGroup(
    val key: String,
    val notes: List<FusionReleaseNote>
)

private data class ReleaseNoteGroupSortKey(
    val other: Boolean,
    val major: Int,
    val minor: Int,
) : Comparable<ReleaseNoteGroupSortKey> {
    override fun compareTo(otherKey: ReleaseNoteGroupSortKey): Int {
        val otherBucket = other.compareTo(otherKey.other)
        if (otherBucket != 0) return otherBucket
        val majorOrder = otherKey.major.compareTo(major)
        if (majorOrder != 0) return majorOrder
        return otherKey.minor.compareTo(minor)
    }
}

@Composable
fun ReleaseNotesDialog(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val semanticColors = LocalFusionSemanticColors.current
    val appVersionName = remember(context) { resolveVersionName(context) }
    val releaseNotes = remember { buildFusionReleaseNotesHistory() }
    val groupedReleaseNotes = remember(releaseNotes) { buildGroupedReleaseNotes(releaseNotes) }
    val defaultExpandedGroup = remember(groupedReleaseNotes) { groupedReleaseNotes.firstOrNull()?.key }
    var expandedGroups by remember(defaultExpandedGroup) {
        mutableStateOf(defaultExpandedGroup?.let(::setOf) ?: emptySet())
    }
    var selectedNote by remember { mutableStateOf<FusionReleaseNote?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("업데이트 기록") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ReleaseCard {
                        Text("Fusion의 최근 변경사항입니다.", color = colors.onSurface, fontSize = 13.sp)
                        Text("현재 앱 버전: $appVersionName", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                items(groupedReleaseNotes, key = { it.key }) { group ->
                    val isExpanded = group.key in expandedGroups
                    ReleaseNotesGroupCard(
                        group = group,
                        expanded = isExpanded,
                        onToggle = {
                            expandedGroups = if (isExpanded) {
                                expandedGroups - group.key
                            } else {
                                expandedGroups + group.key
                            }
                        }
                    )
                }
                groupedReleaseNotes.forEach { group ->
                    if (group.key in expandedGroups) {
                        items(group.notes, key = { it.version }) { note ->
                            ReleaseNoteSummaryCard(note = note, onClick = { selectedNote = note })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(buildReleaseNotesHistoryText(appVersionName, releaseNotes)))
                Toast.makeText(context, "업데이트 기록을 복사했습니다.", Toast.LENGTH_SHORT).show()
            }) { Text("업데이트 기록 복사", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = colors.onSurfaceVariant) }
        },
        containerColor = semanticColors.panel,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface
    )

    selectedNote?.let { note ->
        AlertDialog(
            onDismissRequest = { selectedNote = null },
            title = { Text(note.version) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ReleaseCard {
                            Text(note.status, color = colors.onSurface, fontSize = 12.sp)
                            note.date?.let { Text("날짜: $it", color = colors.onSurfaceVariant, fontSize = 11.sp) }
                            Text(note.summary, color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    items(note.sections, key = { it.title }) { section ->
                        ReleaseCard {
                            Text(section.title, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            section.items.forEach { item ->
                                Text("- $item", color = colors.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(buildSingleReleaseNotesText(note)))
                    Toast.makeText(context, "업데이트 기록을 복사했습니다.", Toast.LENGTH_SHORT).show()
                }) { Text("업데이트 기록 복사", color = colors.primary) }
            },
            dismissButton = {
                TextButton(onClick = { selectedNote = null }) { Text("닫기", color = colors.onSurfaceVariant) }
            },
            containerColor = semanticColors.panel,
            titleContentColor = colors.onSurface,
            textContentColor = colors.onSurface
        )
    }
}

@Composable
private fun ReleaseCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun ReleaseNotesGroupCard(
    group: FusionReleaseNoteGroup,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val latestVersion = group.notes.firstOrNull()?.version ?: "-"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(group.key, color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "접기" else "펼치기", color = colors.primary, fontSize = 11.sp)
            }
            Text("${group.notes.size}개 업데이트", color = colors.onSurface, fontSize = 12.sp)
            Text("최신: $latestVersion", color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReleaseNoteSummaryCard(
    note: FusionReleaseNote,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(note.version, color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(note.status, color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(note.summary, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("섹션 ${note.sections.size}개", color = colors.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

internal fun buildGroupedReleaseNotes(notes: List<FusionReleaseNote>): List<FusionReleaseNoteGroup> {
    val grouped = linkedMapOf<String, MutableList<FusionReleaseNote>>()
    notes.forEach { note ->
        val key = releaseNoteGroupKey(note.version)
        grouped.getOrPut(key) { mutableListOf() }.add(note)
    }
    return grouped.entries
        .sortedBy { releaseNoteGroupSortKey(it.key) }
        .map { (key, groupNotes) -> FusionReleaseNoteGroup(key = key, notes = groupNotes) }
}

private fun releaseNoteGroupKey(version: String): String {
    val baseVersion = version.substringBefore("-")
    val parts = baseVersion.split(".")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    // Only a complete numeric major.minor.patch base version has a release
    // family. Short, extra-component or otherwise malformed versions stay in
    // the safe 기타 bucket instead of being assigned a misleading family.
    if (parts.size != 3) return "기타"
    val major = parts[0].toIntOrNull() ?: return "기타"
    val minor = parts[1].toIntOrNull() ?: return "기타"
    return "$major.$minor.x"
}

private fun releaseNoteGroupSortKey(groupKey: String): ReleaseNoteGroupSortKey {
    val match = Regex("^(\\d+)\\.(\\d+)\\.x$").matchEntire(groupKey)
    if (match == null) return ReleaseNoteGroupSortKey(other = true, major = 0, minor = 0)
    val major = match.groupValues[1].toIntOrNull()
        ?: return ReleaseNoteGroupSortKey(other = true, major = 0, minor = 0)
    val minor = match.groupValues[2].toIntOrNull()
        ?: return ReleaseNoteGroupSortKey(other = true, major = 0, minor = 0)
    return ReleaseNoteGroupSortKey(other = false, major = major, minor = minor)
}

internal fun buildFusionReleaseNotesHistory(): List<FusionReleaseNote> {
    return listOf(
        FusionReleaseNote(
            version = "2.4.0",
            status = "Minor",
            summary = "답변 사실 확인, 단계별 Work Plan 실행, 실행 프로필, Smart Inbox 정리 제안을 추가하고 프로젝트·문서·복구·권한 경계를 전반적으로 강화했습니다.",
            date = "2026-10-06",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "사실 확인·근거 검토",
                    items = listOf(
                        "답변의 확인 가능한 주장을 기존 출처와 대조하고, 필요한 경우 사용자가 승인한 웹 검색으로 추가 확인할 수 있습니다.",
                        "근거가 답변을 뒷받침하는지, 충돌하는지, 충분하지 않은지를 구분하며 근거 부족만으로 내용을 거짓이라고 단정하지 않습니다.",
                        "로컬 문서나 비공개 맥락은 검색 또는 외부 AI 전송 권한으로 자동 전환되지 않으며 기존 개인정보 보호 규칙을 계속 따릅니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "Project Work Plan",
                    items = listOf(
                        "Work Plan에서 현재 단계를 한 번에 하나씩 실행하고 결과를 검토한 뒤 다음 단계로 진행할 수 있습니다.",
                        "Research, 프로젝트 맥락 질문, 자료 비교, 구조화 추출, 지식 검토와 초안 작성은 기존 기능 화면과 연결되며 자동 전송이나 자동 자료 선택을 하지 않습니다.",
                        "할 일·결정·직접 수행 단계는 사용자 확인을 유지하고, 중복 실행·오래된 결과·화면 전환으로 실행 상태가 꼬이지 않도록 소유권을 강화했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "프로젝트·지식 검토",
                    items = listOf(
                        "Project Consistency 검토에서 로컬 의미 검색으로 관련 후보를 보조하되 실제 프로젝트 내용은 자동으로 수정하지 않습니다.",
                        "잠긴 아카이브와 연결된 저장 지식·보고서·스냅샷·복습 카드의 접근 범위를 다시 확인하도록 권한 경계를 강화했습니다.",
                        "지식을 이동하거나 편집할 때 태그와 모음 관계를 보존하고, 삭제되거나 접근할 수 없는 자료가 검색·검토 결과를 왜곡하지 않도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "실행 프로필",
                    items = listOf(
                        "채팅 실행 방식을 로컬, 자동, 외부 AI의 세 가지 프로필로 정리해 모델 실행 경로를 더 쉽게 선택할 수 있습니다.",
                        "자동 프로필은 기기 상태와 요청에 따라 로컬 모델과 검색 경로를 조정하며 외부 AI가 필요할 때는 먼저 사용자 확인을 요청합니다.",
                        "요청을 보낸 뒤 프로필·모델·외부 제공자 설정을 바꾸더라도 이미 시작된 요청과 다시 생성 작업은 캡처된 실행 조건을 유지합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "Smart Inbox",
                    items = listOf(
                        "Inbox 자료에 대해 저장 지식, Project, 할 일, 복습 카드와 기존 태그 중 적절한 정리 후보를 기기 안에서 제안합니다.",
                        "Project와 태그 후보는 로컬 의미 검색과 보수적인 관련성 규칙으로 선별하며 관련 근거가 약하면 Inbox에 그대로 두도록 제안합니다.",
                        "할 일과 복습 카드는 내용을 검토·수정한 뒤 직접 저장하며, 그 사이 원본 Inbox 항목이 바뀌면 오래된 제안을 적용하지 않습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "문서·검색·Research",
                    items = listOf(
                        "PDF와 로컬 RAG에서 실제로 포함된 범위를 더 정확하게 추적하고 일부 내용만 읽은 경우 전체 문서를 읽은 것처럼 표시하지 않도록 개선했습니다.",
                        "구조화 추출의 표·본문 범위와 긴 입력 처리, KoEn-E5 입력 경계를 보강해 잘린 내용이나 잘못된 범위가 정상 결과처럼 보이지 않도록 했습니다.",
                        "Research 갱신과 검색 후보 선택 순서를 보강하고, 수동 검색에서 더 나은 결과가 대체 후보에 있을 때 이를 유지하도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "데이터·복구·작업 상태",
                    items = listOf(
                        "작성 중 문안, Project 편집 상태, 체크포인트와 결정 화면의 소유권을 보강해 화면 전환이나 복구 뒤 오래된 상태가 새 작업을 덮지 않도록 했습니다.",
                        "프로세스 중단 뒤 남은 복구 작업은 현재 저장 상태를 다시 확인한 뒤 적용하고, 동시에 발생한 새 변경을 오래된 복구 결과로 되돌리지 않도록 했습니다.",
                        "Project 스냅샷의 이름·메모·요약·관계 round-trip과 할 일·복습 알림의 저장 및 복구 동작을 강화했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "모델·외부 연결·안정성",
                    items = listOf(
                        "외부 AI 요청에 전체 실행 시간 제한과 고정된 제공자 선택을 적용하고, 요청 도중 설정 변경이 이미 시작된 호출을 바꾸지 않도록 했습니다.",
                        "모델 가져오기·다운로드·삭제에서 URI 권한, 손상된 메타데이터, 복원 실패와 치명적 오류를 더 정확하게 구분하고 잘못된 성공 처리를 줄였습니다.",
                        "음성 답변 대기, 메모리 편집, 검색 상태, 복사 메뉴와 여러 비동기 화면을 보강해 취소·실패·경쟁 상태에서 기존 데이터가 더 안정적으로 유지되도록 했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.3.0",
            status = "Minor",
            summary = "Fusion 검색과 프로젝트 자료를 선택해 질문·비교·복습으로 이어가고, 저장 지식·작업·로컬 OCR·대화 갈래 종합과 프로젝트 스냅샷을 확장했습니다.",
            date = "2026-10-01",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "검색·자료 선택",
                    items = listOf(
                        "Fusion Search에서 키워드와 의미 기반으로 대화·프로젝트 자료·저장 지식을 함께 찾습니다.",
                        "여러 자료를 선택해 고정된 범위로 질문하거나 2~4개 자료 비교를 준비할 수 있습니다.",
                        "선택 자료가 삭제되었거나 보관함 접근 권한이 없으면 범위를 넓히지 않고 중단합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "프로젝트·지식",
                    items = listOf(
                        "프로젝트 지식 탐색기에서 파일 범위와 저장 지식을 확인하고 관리합니다.",
                        "보이는 답변을 지식 노트로 저장하고, 답변에서 할 일을 검토한 뒤 저장할 수 있습니다.",
                        "프로젝트 지식·할 일·요약을 스냅샷으로 내보내고, 원본 자료 포함 여부를 골라 가져옵니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "문서·카메라",
                    items = listOf(
                        "텍스트 레이어가 있는 PDF와 이미지·스캔 자료의 글자를 기기 안에서 읽어 로컬 검색에 활용합니다.",
                        "종이나 보드를 촬영해 로컬 OCR 미리보기를 확인한 뒤 채팅 또는 프로젝트 지식에 추가합니다.",
                        "OCR 결과는 원본 이미지와 촬영 상태에 따라 달라질 수 있으며 카메라 이미지는 클라우드로 보내지 않습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "Research·비교",
                    items = listOf(
                        "범위가 제한되고 취소할 수 있는 Research Mode를 제공합니다.",
                        "선택한 출처를 비교하고 중요한 주장에 출처 정보를 유지합니다.",
                        "근거 확인은 기존 Evidence 정보를 따르며 표현 차이만으로 모순을 단정하지 않습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "학습·복습",
                    items = listOf(
                        "Study 진도를 저장하고 답변·지식·자료에서 복습 카드를 미리 본 뒤 보관합니다.",
                        "다시 보기·어려움·이해함 상태로 간단한 복습 대상을 관리합니다.",
                        "할 일과 복습 카드에 알림 시간을 지정하고 상태를 확인하거나 취소할 수 있습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "모델·실행",
                    items = listOf(
                        "AUTO는 모델 기능과 기기 메모리·전원·발열 조건을 반영해 가능한 로컬 경로를 결정적으로 선택합니다.",
                        "외부 AI로 자료를 보내는 경로는 기존 동의와 자료 전송 정책을 따릅니다.",
                        "요청 범위와 취소·완료 상태를 명확히 해 오래된 작업이 새 화면을 덮지 않도록 보강했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "대화·작업 흐름",
                    items = listOf(
                        "Branch Map에서 대화 갈래를 살펴보고 선택한 요약으로 새 종합 요청을 준비합니다.",
                        "종합 요청은 원본 갈래를 바꾸지 않으며 전송 전에 검토하고 수정할 수 있습니다.",
                        "답변을 노트·할 일·복습 카드로 이어가고 기존 번역과 프로젝트 흐름에서 활용합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "UI·안정성",
                    items = listOf(
                        "검색·채팅·프로젝트 화면의 계층과 보조 작업 메뉴를 정리해 필요한 동작을 맥락에 맞게 찾을 수 있습니다.",
                        "삭제·권한 변경·취소 뒤에도 자료 소유권과 저장 결과가 분명하게 유지되도록 보강했습니다.",
                        "대용량 자료·카메라 이미지·프로젝트 내보내기에 크기와 범위 제한을 적용했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.2.0",
            status = "Minor",
            summary = "답변 맥락과 근거를 더 투명하게 확인하고, 추천 질문·외부 모델·번역·문서 활용·성능 제어를 확장했습니다. 취소·복구·파일 수명주기와 로컬 실행 안정성도 강화했습니다.",
            date = "2026-09-30",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "답변 맥락·근거 확인",
                    items = listOf(
                        "답변별 생성 정보에서 로컬 모델·외부 AI 경로와 이전 대화·프로젝트 지시의 참여 여부를 확인할 수 있습니다.",
                        "첨부·이미지 수, 로컬 문서 검색 조각, 웹 자료 등 답변에 포함된 맥락 정보를 보여줍니다.",
                        "답변 주장과 검색 자료의 관계를 더 쉽게 살펴보고, 검증 자산을 사용할 수 없을 때 의미 검증 결과를 만들어내지 않도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "추천 질문·답변 활용",
                    items = listOf(
                        "완료된 로컬 답변에 맞춘 추천 질문을 제공하며 자동 생성을 설정에서 끌 수 있습니다.",
                        "대화·답변·버전이 바뀐 뒤 도착한 오래된 추천 결과는 표시하지 않습니다.",
                        "답변 활용 동작은 현재 보이는 답변에 연결되고, 추천 내용을 확인하거나 눌러도 전송은 사용자가 직접 합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "외부 AI·모델 선택",
                    items = listOf(
                        "제공자의 모델 목록과 기능 정보를 탐색하고, 제공자 기본 변형이나 직접 만든 모델 변형을 선택할 수 있습니다.",
                        "모델 변형 선택을 저장하고 메타데이터 범위를 제한해 안전하게 관리합니다.",
                        "인증 정보 변경을 안전하게 반영하고 실제 요청의 제공자·모델 정보를 고정하며, 실행 가능한 제공자가 없을 때 상태를 더 분명히 안내합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "번역·음성",
                    items = listOf(
                        "번역 결과를 채팅 작성창으로 보낼 수 있으며 현재 실행 가능한 로컬 모델 경로를 함께 사용합니다.",
                        "번역 중 취소와 실패 처리를 보강했습니다.",
                        "음성 받아쓰기의 최종 문장 소유권과 인식 시작 실패 정리를 강화하고, 오래된 읽어주기 응답이 새 재생 상태를 덮지 않도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "문서·검색·대화 관리",
                    items = listOf(
                        "문서 색인과 읽기 작업의 범위를 제한하고, 첨부 텍스트를 엄격하게 읽으며 의미 순위 처리에 실패하면 검색을 이어갈 수 있도록 보완했습니다.",
                        "웹 출처와 이동 경로를 더 안전하게 다루고, 잠긴 보관함의 접근 경계와 대화 목록·검색 복원을 보강했습니다.",
                        "대화 가지의 출처 정보를 저장하고 첨부 미리보기 방향을 바로잡았습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "로컬 실행·성능",
                    items = listOf(
                        "자동 성능 조절은 기기·전원·발열 신호에 보수적으로 반응하며, 사용자가 최소에서 최대까지 직접 설정할 수도 있습니다.",
                        "더 높은 수동 설정은 전력 사용과 발열을 늘릴 수 있습니다.",
                        "로컬 CPU 실행·대체 경로와 검증기 CPU 실행에 같은 CPU 예산을 적용하고, 설정이 달라지면 런타임 재사용을 제한하며 백그라운드 자원 해제를 보강했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "복구·데이터 보호",
                    items = listOf(
                        "작성 중 초안을 직접 복구할 수 있고, 설정 백업·복원과 이전 형식 프로필 복구를 강화했습니다.",
                        "잘못된 설정 값, 숫자 범위 초과, 손상된 UTF-8 백업을 거부하고 복원 실패 시 이전 상태로 안전하게 정리합니다.",
                        "파일 교체는 취소와 치명적 오류를 보존하며, 초안 교체·삭제 중에도 다른 곳에서 참조하는 첨부 파일을 지킵니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성",
                    items = listOf(
                        "취소와 네이티브 자원 해제 순서를 보강하고, 전경 런타임 시작과 대화 삭제 정리의 실패를 안전하게 처리합니다.",
                        "응답 버전 경계와 런타임 실패 원인을 보존하고, 오래된 설정을 현재 런타임에 잘못 재사용하지 않도록 했습니다.",
                        "검증기 파일 정보와 모델 저장 공간 확인을 강화했으며 동시 작업과 회귀 테스트 범위를 넓혔습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.1.0",
            status = "Minor",
            summary = "학습 모드와 프로젝트 브리프·종합, 답변 출처 공유·다음 행동·버전 비교를 추가하고, 새 채팅과 로컬 저장소의 동시 작업 안정성을 강화했습니다.",
            date = "2026-09-16",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "학습 모드",
                    items = listOf(
                        "일반 채팅과 분리된 명시적 학습 모드를 사용할 수 있습니다.",
                        "composer에서 일반 모드와 학습 모드를 직접 전환할 수 있습니다.",
                        "학습 모드에서 질문·힌트·채점 요청·답·해설·다음 문제 도구를 사용할 수 있습니다.",
                        "학습 모드 응답은 단계적인 학습과 자기 풀이를 우선하며, 답이나 해설을 명시적으로 요청하기 전에는 바로 공개하지 않도록 유도합니다.",
                        "문서 없이도 시작할 수 있고, 문서나 프로젝트 자료가 있을 때는 기존 로컬 문맥과 RAG 경계를 따릅니다. 학습 도구 요청과 전송은 사용자가 직접 제어합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "프로젝트",
                    items = listOf(
                        "Project Brief에서 현재 프로젝트의 대화 수·공유 자료 수와 최근 저장된 대화 요약을 한눈에 볼 수 있습니다.",
                        "Project Brief는 제한된 최근 범위만 읽으며, 프로젝트의 원문 대화 기록 전체를 훑지 않습니다.",
                        "최근 대화 요약을 바탕으로 새 Project Chat에 프로젝트 종합 요청을 준비할 수 있습니다.",
                        "프로젝트 종합에서 전체 종합·결정사항·다음 할 일·리스크 점검 모드를 선택할 수 있습니다.",
                        "생성 전에 요청 내용이 composer에 표시되며, 검토·수정한 뒤 사용자가 직접 Send합니다.",
                        "기존 Project instructions와 Project Knowledge는 일반 전송 파이프라인에서 계속 활용됩니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "답변 활용",
                    items = listOf(
                        "답변을 다른 앱으로 공유할 수 있으며, 웹 출처가 있는 답변은 정리된 출처와 함께 공유할 수 있습니다.",
                        "로컬 문서 출처는 외부 공유 payload에 파일명과 페이지 중심의 최소 정보만 포함합니다.",
                        "답변 맥락에 따라 다음 질문과 활용 행동을 추천받을 수 있습니다.",
                        "여러 답변 버전이 있으면 현재 답변과 다른 버전을 임시 비교 화면에서 나란히 확인할 수 있습니다.",
                        "답변 비교는 현재 활성 답변 버전을 변경하지 않습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성",
                    items = listOf(
                        "학습 도구와 다음 행동의 요청 작성은 저장이 확인된 초안 소유권을 사용하며, 같은 작업의 중복 실행을 막습니다.",
                        "새 채팅과 프로젝트 종합은 읽을 수 있는 초안 상태를 확인한 뒤 composer 소유권을 교체합니다.",
                        "새 채팅 전환에 실패했을 때 새 프로젝트 연결이 먼저 게시되지 않도록 보강했습니다.",
                        "실행 중인 로컬 모델 다운로드의 staging 파일을 화면 복구 정리가 건드리지 않도록 보호하고, 중단된 저장소 작업은 비동기로 복구합니다.",
                        "답변 활용은 현재 화면에 실제로 보이는 답변을 기준으로 하며, 공유 출처 표시는 한 줄 형식으로 정리합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "품질 및 회귀 방지",
                    items = listOf(
                        "학습 모드 선택 상태를 대화와 새 채팅 전환 단위로 분리해 오래된 선택이 이어지지 않도록 했습니다.",
                        "코루틴과 Main dispatcher 테스트의 소유권을 분리해 테스트 간 간섭을 줄였습니다.",
                        "확장된 회귀 테스트에서 확인된 초안·작업 소유권 경합을 고쳤습니다.",
                        "기본 채팅·학습·문서·프로젝트·공유·비교·도구·Voice·새 채팅 등 핵심 Fusion 흐름의 competition/demo 계약을 점검합니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.0.6",
            status = "Patch",
            summary = "문서·대화·모델 저장소의 복구와 동시 작업 안정성을 강화하고, 앱 종료·취소·삭제·가져오기 중에도 사용자 데이터와 로컬 파일의 소유권이 안전하게 유지되도록 보강했습니다.",
            date = "2026-09-14",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "문서와 공유",
                    items = listOf(
                        "공유로 들어온 텍스트·문서를 실패해도 기존 작성 내용을 잃지 않도록 개선했습니다.",
                        "공유 처리 중 앱이 종료되거나 재실행되어도 중복 가져오기를 줄이는 복구 보강을 추가했습니다.",
                        "PDF text-layer 문서를 로컬에서 읽어 관련 부분만 대화 맥락으로 활용합니다.",
                        "이미지·스캔 PDF는 OCR하지 않는 현재 제한을 유지합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "대화 안정성",
                    items = listOf(
                        "메시지 전송 직후 앱 종료 시 초안·전송 상태를 복구하도록 강화했습니다.",
                        "답변 재생성·버전 전환·클라우드 대체 답변이 동시에 처리될 때 덮어쓰기 문제를 보강했습니다.",
                        "대화 삭제와 가지치기·응답 저장이 겹칠 때 잘못된 상태가 남지 않도록 개선했습니다.",
                        "빠른 답변 버전 이동의 중복 처리와 잘못된 이동을 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "로컬 모델과 저장소",
                    items = listOf(
                        "모델 가져오기·다운로드·삭제의 실패·취소 복구를 강화했습니다.",
                        "앱 종료 중 중단된 모델 교체·삭제 백업을 카탈로그 상태와 비교해 안전하게 복구합니다.",
                        "모델 파일과 모델 목록 정보가 서로 어긋나는 문제를 보강했습니다.",
                        "손상되거나 비정상적으로 큰 모델 목록 파일을 메모리 과부하 없이 안전하게 처리합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "성능과 메모리",
                    items = listOf(
                        "긴 사용 기록의 첨부 정리에서 모든 메시지를 한 번에 메모리에 올리지 않도록 개선했습니다.",
                        "로컬 문서 상태 확인 경로의 메모리·메인스레드 부담을 완화했습니다.",
                        "의미 검색 모델의 native resource lifetime을 안정화했습니다.",
                        "동일 모델 다운로드의 동시 작업을 직렬화하고 사용 후 lock state를 정리합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "개인정보 및 신뢰성",
                    items = listOf(
                        "외부 AI 요청 권한이 다른 요청과 섞이지 않도록 request 소유권을 강화했습니다.",
                        "취소·삭제·앱 종료 후 늦게 도착한 작업이 다른 대화 상태를 덮어쓰지 않도록 보강했습니다.",
                        "로컬 문서·첨부 데이터는 기존 local-first 경계를 유지합니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.0.5",
            status = "Patch",
            summary = "프로젝트에 공유한 로컬 문서와 PDF 활용을 확장하고, 페이지 단위 문서 검색·출처 표시와 문서 인덱스 복구·상태 확인을 안정화했습니다.",
            date = "2026-09-14",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "프로젝트 지식",
                    items = listOf(
                        "프로젝트에 로컬 파일을 공유해 여러 대화에서 함께 활용할 수 있습니다.",
                        "공유한 프로젝트 파일은 현재 첨부와 함께 로컬 검색의 출처로 참여합니다.",
                        "프로젝트 파일 추가·삭제와 대화 소속 변경이 앱 재실행 후에도 유지되도록 정리했습니다.",
                        "프로젝트 파일은 실제 관리 파일을 참조해 불필요한 중복 복사를 만들지 않도록 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "PDF 문서 활용",
                    items = listOf(
                        "텍스트 레이어가 있는 PDF에서 기기 안에서 텍스트를 추출합니다.",
                        "페이지를 인식해 관련 페이지·문단만 기존 로컬 검색을 통해 대화 맥락으로 전달합니다.",
                        "PDF 원본 전체를 모델에 그대로 전달하지 않고 필요한 부분만 활용합니다.",
                        "활용한 내용의 페이지 출처를 로컬 문서 출처에 표시합니다.",
                        "스캔·이미지 전용 PDF는 OCR 처리하지 않으며, 그림·도표 내용을 시각적으로 이해하지 않습니다.",
                        "비밀번호로 보호되거나 읽을 수 없는 PDF는 상태 안내와 함께 실패합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "문서 인덱스와 성능",
                    items = listOf(
                        "추출한 PDF 텍스트를 로컬 파생 색인으로 보관해 다시 읽지 않고도 활용합니다.",
                        "원본 파일의 크기나 수정 시간, 버전이 바뀌면 오래된 색인을 재사용하지 않습니다.",
                        "손상되거나 오래된 색인은 안전하게 버리고 다시 만듭니다.",
                        "문서 상태 확인은 가벼운 상태 정보만 읽어 전체 색인을 열지 않으며, 기존 유효한 색인은 필요할 때 가벼운 상태 정보를 함께 갖도록 정리됩니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성 및 개인정보",
                    items = listOf(
                        "관리 중인 원본 파일이 기준이며, 파생된 PDF 색인은 언제든 다시 만들 수 있는 임시 데이터로 취급합니다.",
                        "첨부 삭제와 고아 정리 시 파생된 문서 색인 잔여물도 함께 정리합니다.",
                        "문서 처리는 기기 안에서 이뤄지며 클라우드 PDF 분석, OCR 서비스, 새로운 로컬 언어 모델 실행 환경을 도입하지 않았습니다.",
                        "형식이 잘못되거나 오래된 문서 정보는 빈 상태로 덮어쓰지 않고 안전하게 실패 처리합니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.0.4",
            status = "Patch",
            summary = "Android 공유·문서 대화와 음성 입력, 프로젝트·파일 관리, 대화 가지치기와 번역·검색 연계를 확장하고 로컬 우선 대화 흐름의 안정성을 보강했습니다.",
            date = "2026-09-13",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "Android와 문서",
                    items = listOf(
                        "공유된 텍스트를 대화로 가져오는 흐름을 실제 내용 전달 기준으로 정리했습니다.",
                        "공유 텍스트는 크기에 따라 인라인 삽입과 관리형 텍스트 문서로 적응적으로 처리합니다.",
                        "지원 형식의 로컬 문서 공유를 받아들이고 대화 맥락에서 활용할 수 있도록 정리했습니다.",
                        "로컬 문서 맥락이 이후 턴까지 이어지도록 연속성을 유지합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "음성",
                    items = listOf(
                        "오프라인 읽어주기와 수명주기 정리를 보강했습니다.",
                        "온디바이스 음성 받아쓰기로 일반 작성창에 텍스트를 입력할 수 있습니다.",
                        "턴 기반 Voice Mode로 인식된 텍스트를 일반 전송 경로로 제출합니다.",
                        "음성은 텍스트로만 전달되며 원시 오디오는 모델·제공자·검색 경로에 들어가지 않습니다.",
                        "인식 세션과 대화 소유권을 보강해 최종 인식 결과가 정확히 한 번 적용되도록 수정했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "대화 기능",
                    items = listOf(
                        "어시스턴트 답변을 새 채팅으로 가지치기할 수 있습니다.",
                        "문맥에 맞는 학습 프롬프트를 제안합니다.",
                        "어시스턴트 답변을 Translation으로 넘겨 확인할 수 있습니다.",
                        "여러 턴에 걸친 웹 검색 후속 질문의 맥락 처리를 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "프로젝트와 파일",
                    items = listOf(
                        "Projects v1으로 대화를 프로젝트 단위로 정리할 수 있습니다.",
                        "대화 할당·제거·프로젝트 탐색을 지원하고 프로젝트 생성과 할당을 원자적으로 처리합니다.",
                        "대화 파일 목록에서 기존 파일을 다시 첨부할 수 있습니다.",
                        "대화를 홈 화면에 고정할 수 있습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성 및 개인정보",
                    items = listOf(
                        "공유·가져오기 실패와 취소 시 소유권 정리와 등록 해제를 보강했습니다.",
                        "요청 스냅샷 격리와 첨부·비공개 기록 검색 보호를 유지합니다.",
                        "대화 전환 중 음성 입력이 다른 채팅을 건드리지 않도록 소유권을 보강했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.0.3",
            status = "Patch",
            summary = "독립 번역, 로컬 문서 활용과 초보자 설정 흐름을 확장하고 모델 선택·생성·검색·런타임 안정성과 테마 동작을 보강했습니다.",
            date = "2026-09-12",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "새 기능",
                    items = listOf(
                        "사이드바에서 독립 화면으로 여는 로컬 Translation 기능을 추가했습니다.",
                        "대화에 첨부한 지원 형식의 로컬 문서를 관련 맥락으로 활용할 수 있도록 제품 흐름을 정리했습니다.",
                        "최초 실행에서 로컬 모델과 외부 AI 경로를 이해하기 쉬운 온보딩 및 초보자 설정 흐름을 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "모델과 생성",
                    items = listOf(
                        "모델 라이브러리를 student-first 구조로 정리하고 기기 적합성, 메모리 부담, 사용 위험과 권장 설정을 더 명확하게 표시하도록 개선했습니다.",
                        "Composer Quick Settings를 추가해 주요 응답·생성 설정에 빠르게 접근할 수 있도록 했습니다.",
                        "현재 선택 모델, 생성 상태, 런타임 단계와 실제 가속기 선택을 더 명확하게 표시하도록 개선했습니다.",
                        "저메모리 환경의 로컬 생성과 답변 재생성 경로를 안정화했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "번역",
                    items = listOf(
                        "Translation 요청과 backend capability 계약을 backend-independent 구조로 정리했습니다.",
                        "향후 dedicated Translation artifact를 안전하게 채택할 수 있도록 manifest, evidence, capability 및 adoption gate 검증을 강화했습니다.",
                        "source language가 지정되지 않은 상태와 실제 감지된 언어를 구분하도록 의미 체계를 정리했습니다.",
                        "전용 Translation 모델은 이번 버전에 포함하지 않았으며, 현재 Translation은 기존 공유 로컬 runtime/backend를 사용합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성 및 버그 수정",
                    items = listOf(
                        "새 채팅이 이전 상태를 남기지 않는 실제 fresh reset 동작이 되도록 수정했습니다.",
                        "일부 Android 환경에서 주식·검색 planner가 crash할 수 있던 문제를 수정했습니다.",
                        "외부 AI provider의 저장 상태, 준비 상태, disabled ownership 및 비동기 race를 보강했습니다.",
                        "모델 라이브러리 선택 상태와 실제 managed runtime model truth가 어긋날 수 있던 경로를 수정했습니다.",
                        "benchmark·Translation·generation 사이의 runtime contention 계약과 관련 회귀 검증을 강화했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "화면 및 테마",
                    items = listOf(
                        "Fluid Surface UX 기반과 composer-adjacent quick surfaces를 정리했습니다.",
                        "Tune/Quick Settings가 화면 전체를 밀어내던 구조를 수정하고 transient surface Back 동작을 안정화했습니다.",
                        "Translation을 Tune 내부 UI가 아닌 독립 main-content destination으로 정리했습니다.",
                        "최초 테마를 SYSTEM/AUTO로 변경해 시스템 Light·Dark에 맞는 Fusion LIGHT·DARK로 시작하도록 했습니다.",
                        "BLACK은 명시적으로 선택하는 OLED-black 테마로 유지하고, 밝은 하늘색 filled action의 글자와 아이콘 대비를 개선했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.0.2",
            status = "Patch",
            summary = "Black·Dark·Light 테마와 더 간결한 설정 구조로 화면의 일관성과 사용성을 개선했습니다.",
            date = "2026-09-09",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "테마와 화면 표현",
                    items = listOf(
                        "Black, Dark, Light 중 원하는 앱 테마를 선택하고 다음 실행에도 그대로 유지할 수 있습니다.",
                        "보라색 중심의 기본 색상 대신 파란 강조색과 중성 표면을 사용해 화면과 control의 시각 언어를 통일했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "설정",
                    items = listOf(
                        "설정을 일반 및 응답, 화면 모양, 모델 및 AI, 개인정보 및 데이터, 고급 및 정보 카테고리로 나누어 필요한 항목을 더 쉽게 찾을 수 있습니다.",
                        "기본 모델 항목을 눌러 기존 모델 라이브러리의 선택 흐름으로 바로 이동할 수 있습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "외부 AI API 설정",
                    items = listOf(
                        "저장, 연결 테스트, 닫기와 삭제 동작의 색상 역할을 정리하고 제공자 상태 표시를 현재 테마에 맞췄습니다.",
                        "제공자와 인증 방식 선택 control을 세로로 다시 배치해 좁은 세로 화면에서도 긴 항목이 글자 단위로 줄바꿈되지 않도록 개선했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.0.1",
            status = "Patch",
            summary = "출처 번역, 첨부 문서 활용, 외부 전송 안내와 검색·설정 화면의 일관성을 개선했습니다.",
            date = "2026-09-09",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "출처와 문서 활용",
                    items = listOf(
                        "답변 근거에 연결된 원문을 한국어로 확인하는 흐름을 추가하고, 같은 메시지를 다시 열어도 현재 번역 상태를 안전하게 이어갑니다.",
                        "대화에 첨부한 로컬 문서에서 관련 내용을 제한적으로 찾아 답변 준비에 활용할 수 있도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "외부 전송과 신뢰 안내",
                    items = listOf(
                        "외부 AI로 내용을 보내기 전 요청별 동의와 전송 범위를 더 명확히 확인하도록 정리했습니다.",
                        "복잡한 요청의 안내·재시도 흐름을 다듬어 선택한 제공자와 현재 요청 상태가 일관되게 유지되도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "검색과 답변 표시",
                    items = listOf(
                        "시간에 민감한 정보의 검색 안내와 주식 관련 질의 처리를 보완하고, 답변의 Markdown·근거 표시 안정성을 개선했습니다.",
                        "검색과 근거 확인의 상태를 더 분명히 구분해, 확인되지 않은 내용을 확인 완료처럼 보이지 않도록 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "외부 AI 설정",
                    items = listOf(
                        "외부 AI 제공자 설정 화면의 선택 control과 저장·시험 동작을 안정화해 설정 상태가 더 예측 가능하게 표시되도록 개선했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "2.0.0-beta",
            status = "Beta",
            summary = "신뢰 가능한 답변, 긴 대화 처리, 모델 런타임과 복구 흐름을 크게 강화한 주요 체크포인트입니다.",
            date = "2026-09-07",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "핵심 변화",
                    items = listOf(
                        "1.0.0-beta-stable R5 이후 누적된 Evidence, 한·영 의미 검색, Trust Router, 긴 대화 처리, 모델 런타임과 데이터 복구 개선을 하나의 2.0.0-beta 체크포인트로 정리했습니다.",
                        "이번 버전은 새로운 안정(stable) 릴리스가 아니라 주요 기능과 실험 기반을 함께 점검하는 베타 체크포인트입니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "신뢰 가능한 답변 / Evidence",
                    items = listOf(
                        "답변의 확인 가능한 주장을 분리해 검색 근거가 뒷받침하는지, 충돌하는지, 근거가 부족한지, 확인하지 않았는지를 주장 단위로 보여줄 수 있습니다.",
                        "기존 답변과 근거를 메시지별로 저장하고 답변 해시·규칙 버전·시간을 함께 확인해 오래되거나 바뀐 답변의 근거는 현재 상태로 표시하지 않습니다.",
                        "출처의 제목과 요약을 중심으로 보수적으로 판단하며 날짜, 수량·단위, 대상, 관계, 부정·가능성 표현을 구분합니다. Evidence는 사실을 자동으로 증명하는 기능이 아닙니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "검색과 Trust Router",
                    items = listOf(
                        "현재 정보나 바뀔 수 있는 구체적인 정보는 웹 확인 경로로 안내하고, 일반적인 답변은 조용한 기본 경로로 유지합니다.",
                        "정확히 알 수 없는 미래 결과를 단정해 달라는 요청에는 결정적인 안내를 보여주며, 이 경우 답변 모델이나 외부 제공자를 호출하지 않습니다.",
                        "복잡한 작업이 더 강한 모델에 적합할 수 있다는 안내를 제공하지만 자동으로 클라우드로 전환하거나 외부로 내용을 보내지 않습니다.",
                        "웹 확인 경로를 선택했다는 표시와 실제 검색 결과·Evidence는 구분합니다. 검색이 성공했거나 답변이 검증되었다고 자동으로 표시하지 않습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "한·영 의미 검색",
                    items = listOf(
                        "고정된 오프라인 KoEn-E5 Tiny 기반으로 한국어·영어 출처 후보를 의미적으로 찾아 기존 규칙 기반 후보를 보완합니다.",
                        "의미 유사도는 후보 순위에만 사용하며 사실 판단이나 진실성 점수로 표시하지 않습니다. 모델·토크나이저를 사용할 수 없으면 기존 규칙 기반 경로로 안전하게 돌아갑니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "긴 대화와 컨텍스트",
                    items = listOf(
                        "긴 대화에서 모델별 용량과 출력 공간을 함께 계산하고, 전체 대화 턴·요약 경계를 보존하면서 필요한 맥락을 더 예측 가능하게 선택합니다.",
                        "자동·수동 요약은 길이와 요청 범위를 제한하고, 잘못된 요약·오래된 분기·모델 계열 불일치를 확인해 안전하게 반영합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "모델 및 런타임",
                    items = listOf(
                        "모델 프로필, 출력 길이와 KV 캐시 용량, 실행 설정을 하나의 요청 시점에 고정해 생성 중 설정이 바뀌어도 현재 요청의 기준이 흔들리지 않도록 했습니다.",
                        "모델 가져오기·카탈로그·실행 엔진의 호환성과 대체 경로 상태를 더 명확히 구분하고, 실제 선택된 실행 상태와 실패 원인을 진단에 남깁니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "Kanana 준비",
                    items = listOf(
                        "Kanana 계열 실험을 위해 고정된 프롬프트 형식, 구조화된 LiteRT 메시지 역할, 패키지 검증과 재현 가능한 내보내기 사전 점검 기반을 마련했습니다.",
                        "Kanana 변환·기기 실행·쌍 비교 해석은 실험 게이트로 남아 있으며, 이번 버전에서 배포 완료나 물리 기기 수료를 선언하지 않습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "검색 / 외부 AI",
                    items = listOf(
                        "검색 결과의 출처·검색 의도·표시용 정보를 더 엄격히 제한하고, 검색 제공자와 외부 AI 설정의 형식·연결 상태·실패 처리를 보강했습니다.",
                        "외부 제공자 설정을 저장하거나 교체할 때 비밀 정보와 설정 메타데이터의 저장 경계를 나누고, 실패 시 복구·정리 상태를 잃지 않도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "데이터 안정성 및 복구",
                    items = listOf(
                        "설정, 초안, 모델·제공자 상태, 대화 메타데이터를 부분 저장·실패한 저장 확정·경쟁 상태·손상된 데이터에 대비해 더 엄격하게 저장하고 복구합니다.",
                        "읽을 수 없는 중요한 저장 상태는 빈 상태로 덮어쓰지 않고 보존·격리하며, 정리하지 못한 파일이나 비밀 정보는 후속 정리 작업으로 추적합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "대화 / 초안 / 기록",
                    items = listOf(
                        "초안과 전송 소유권을 분리해 앱 종료·취소·새 대화 생성이 겹쳐도 잘못된 메시지나 첨부 정리가 다른 요청을 덮어쓰지 않도록 했습니다.",
                        "재시도, 스타일 재생성, A/B 기록, 대화 검색과 표시 가능한 기록을 요청·메시지 단위로 정리해 저장된 답변과 현재 분기를 더 일관되게 다룹니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "기타 품질 개선",
                    items = listOf(
                        "개발자 진단, 성능 측정, 데이터 형식 전환, 런타임 호환성 테스트와 재현 가능한 테스트 자료를 확장해 문제 원인을 더 구체적으로 확인할 수 있습니다.",
                        "앱 내부 표시와 릴리스 문구는 실행 가능 여부와 실제 실행 여부를 구분하도록 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "알려진 제한 / 아직 진행 중",
                    items = listOf(
                        "이번 체크포인트에서는 최종 물리 Android C1 Evidence 검증과 S1 KoEn-E5 Android 재실행을 수행하지 않았습니다.",
                        "Kanana 실험의 변환·기기 실행·쌍 비교 해석 및 관련 물리 기기 게이트도 완료로 표시하지 않습니다.",
                        "Evidence와 의미 검색은 답변의 진실성이나 정확도를 보장하지 않으며, 일부 출처·주장은 근거 부족 또는 확인하지 않음으로 남을 수 있습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "1.0.0-beta-stable",
            status = "Beta Stable",
            summary = "일상 사용을 목표로 메시지 전송, 생성 세션, 첨부 파일과 대화 삭제 흐름을 전면 안정화했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "메시지 전송과 대화 안정성",
                    items = listOf(
                        "빠른 연속 전송과 오래된 coroutine 종료가 새 전송 상태를 덮어쓰는 문제를 차단했습니다.",
                        "새 대화와 사용자 메시지 저장을 Room transaction 기반 cancellation-safe commit으로 묶어 빈 대화와 중복 메시지가 생길 수 있는 경계를 줄였습니다.",
                        "메시지 저장 후에만 새 대화를 공개하고, 저장된 초안과 첨부 항목을 정확히 한 번 정리하도록 개선했습니다.",
                        "대화를 전환해도 다른 대화의 Stop 버튼과 생성 상태가 잘못 나타나거나 사라지지 않도록 수정했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "생성·중단·재시도",
                    items = listOf(
                        "대화별 생성 세션 소유권과 request identity를 분리해 stale 응답과 잘못된 상태 정리를 방지했습니다.",
                        "생성 요청이 실제로 취소 가능한 상태가 된 뒤에만 Stop 버튼으로 전환하도록 정리했습니다.",
                        "로컬 모델과 외부 AI API의 설치 실패, 취소, 빈 응답과 저장 실패 안내를 더 일관되게 처리했습니다.",
                        "첨부 파일이 누락된 재시도와 외부 API 첨부 재시도를 request 생성 전에 차단합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "첨부 파일 안전성과 성능",
                    items = listOf(
                        "첨부 metadata를 엄격한 v3 envelope로 저장하고 로컬 경로와 Base64 정보가 모델 또는 외부 API 기록에 노출되지 않도록 정리했습니다.",
                        "관리 저장소 밖 경로, traversal, directory와 symlink를 신뢰하지 않으며 누락 파일은 별도 unavailable 상태로 표시합니다.",
                        "첨부 가져오기와 삭제를 직렬화하고 최대 5개 제한, 일부 실패와 제한 제외 개수를 정확히 안내합니다.",
                        "이미지 thumbnail의 파일 검사와 downsample decode를 IO dispatcher로 옮기고 RGB_565를 사용해 입력창 멈춤과 메모리 사용량을 줄였습니다.",
                        "앱 복귀와 파일 열기 실패 시 첨부 상태를 재검증해 사라진 파일을 즉시 unavailable 카드로 전환합니다.",
                        "대화 검색용 본문 색인을 백그라운드에서 한 번 생성해 검색어를 입력할 때마다 파일 시스템을 다시 검사하지 않도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "대화 삭제와 UI 일관성",
                    items = listOf(
                        "모든 대화 삭제 진입점에서 진행 중인 생성을 먼저 취소하고 완료를 기다린 뒤 삭제하도록 통일했습니다.",
                        "삭제 중 dialog dismiss, 취소와 삭제 버튼을 실제로 잠그고 진행 상태와 오류 문구를 일관되게 표시합니다.",
                        "생성·전송·첨부 가져오기 중 composer 변경, 빠른 프롬프트, 모드 변경과 첨부 제거를 일관되게 잠급니다.",
                        "사용할 수 없는 첨부와 실패 상태가 활성 control처럼 보이지 않도록 시각 상태를 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "답변 저장과 모델 파일",
                    items = listOf(
                        "답변 저장 후 대화 timestamp 갱신만 실패했을 때 저장된 답변을 삭제하던 문제를 수정했습니다.",
                        "Retry와 스타일 재생성의 답변 버전 정보를 확정 저장하고 취소 직후에도 다시 불러오도록 보강했습니다.",
                        "모델 복사에 임시 파일과 원자적 이동을 적용하고 metadata 저장 실패 시 복사 파일을 정리합니다.",
                        "현재 실행 엔진에 맞춰 .litertlm만 직접 실행 가능으로 표시하며 .task 파일은 호환성 확인 대상으로 구분합니다.",
                        "현재 선택한 모델을 삭제하면 잘못된 모델 경로가 계속 사용되지 않도록 선택 상태를 정리합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "로컬 데이터 보호",
                    items = listOf(
                        "Android 시스템 자동 백업과 기기 이전 대상에서 Fusion 앱 데이터를 제외했습니다.",
                        "외부 모델 연결을 해제하거나 Fusion 관리 저장소로 가져오기가 완료되면 불필요한 문서 URI 권한을 정리합니다.",
                        "설정 복원과 실제 추론 엔진 모두 관리 모델 저장소 밖 경로와 symlink를 거부합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "Beta Stable 안내",
                    items = listOf(
                        "핵심 채팅 기능을 일상적으로 시험할 수 있는 1.0.0 Beta Stable 기준으로 버전 체계를 올렸습니다.",
                        "로컬 모델의 실제 속도와 메모리 사용량은 기기, 모델 형식과 런타임 backend에 따라 달라질 수 있습니다.",
                        "음성 입력과 보이스 모드는 아직 안내 단계이며 이번 버전의 안정화 범위에 포함되지 않습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.23-alpha",
            status = "알파",
            summary = "모델 정보를 한 화면에서 확인할 수 있는 모델 여권을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "모델 여권",
                    items = listOf(
                        "모델 상세 메뉴에서 모델 여권을 열 수 있도록 추가했습니다.",
                        "모델 패밀리, 크기, 실행 형식, 상태, 권장 기기, 출처를 통합해 표시합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "기기 적합도와 실행 정보",
                    items = listOf(
                        "현재 기기 RAM을 기준으로 적합도, 권장 최대 출력 토큰, MTP와 추론 권장 상태를 확인할 수 있습니다.",
                        "감지된 AP와 SoC, NPU 변환 후보 안내, 가져온 모델의 파일 및 연결 상태를 함께 표시합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "벤치마크와 메모",
                    items = listOf(
                        "측정 횟수, 중앙값, 최고 속도, 최근 측정 정보와 MTP 추천을 요약해 표시합니다.",
                        "기존 모델 메모를 확인하거나 수정하고 모델 선택, 권장 설정, 벤치마크, 모델 페이지 등의 기존 작업을 바로 실행할 수 있습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.22-alpha",
            status = "알파",
            summary = "Fusion의 작업 상태를 은은한 주변광으로 구분하는 Fusion Pulse를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "Fusion Pulse",
                    items = listOf(
                        "응답 생성, 모델 준비, 웹 검색, 메모리 후보 추출 상태에 따라 서로 다른 주변광 효과를 표시합니다.",
                        "기존 응답 오로라는 유지하면서 각 작업 상태를 색상과 움직임으로 자연스럽게 구분했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "상태별 효과",
                    items = listOf(
                        "모델 준비 중에는 짙은 파란색 호흡광을 표시하고, 웹 검색 중에는 청록색 스캔광을 표시합니다.",
                        "메모리 후보 추출 중에는 응답 오로라보다 차분한 흰색과 파란색 세로 빛을 표시합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "전환과 성능",
                    items = listOf(
                        "작업 시작과 종료 시 주변광이 부드럽게 나타나고 사라지도록 전환을 유지했습니다.",
                        "상태가 없을 때는 애니메이션을 실행하지 않으며 전체 화면 효과나 무거운 흐림 처리를 사용하지 않습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.21-alpha",
            status = "알파",
            summary = "답변을 다시 생성할 때 이전 답변을 함께 보관하고 선택할 수 있도록 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "답변 버전",
                    items = listOf(
                        "답변 다시 생성과 스타일 변경으로 만든 답변을 기존 답변 위에 덮어쓰지 않고 별도 버전으로 보관합니다.",
                        "새로 생성된 답변은 자동으로 현재 답변으로 표시되며 이전 답변의 내용도 유지됩니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "버전 탐색",
                    items = listOf(
                        "답변이 두 개 이상인 경우 좌우 이동 버튼과 현재 버전 번호를 표시합니다.",
                        "선택한 답변 버전을 저장해 대화를 다시 열어도 마지막으로 본 답변을 표시합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "호환성과 내보내기",
                    items = listOf(
                        "기존 대화의 답변은 별도 변경 없이 단일 답변으로 표시됩니다.",
                        "채팅 Markdown 내보내기에는 현재 선택된 답변 버전을 포함하도록 정리했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.20-alpha",
            status = "알파",
            summary = "응답 생성 중 표시되는 주변광 효과를 부드러운 오로라 형태로 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "오로라 주변광",
                    items = listOf(
                        "기존 띠 형태의 주변광을 여러 겹의 파란색, 청록색 세로 빛줄기로 구성된 오로라 효과로 교체했습니다.",
                        "빛의 위아래가 자연스럽게 투명해지도록 정리해 단단한 경계가 보이지 않도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "위치와 전환",
                    items = listOf(
                        "오로라를 대화 입력창 바로 위에 배치해 응답 생성 상태를 자연스럽게 확인할 수 있도록 했습니다.",
                        "응답 생성 시작과 종료 시 주변광이 부드럽게 나타나고 사라지도록 전환 효과를 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "움직임",
                    items = listOf(
                        "여러 주기의 느린 흔들림과 밝기 변화를 조합해 반복 경계에서 움직임이 갑자기 되돌아가는 현상을 없앴습니다.",
                        "주변광은 응답 생성 중에만 표시되며 대화 입력 조작을 방해하지 않습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.19-alpha",
            status = "알파",
            summary = "모델 파일 가져오기와 외부 연결 과정을 더 명확하게 정리했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "모델 파일 확인",
                    items = listOf(
                        "파일 가져오기와 외부 모델 파일 연결 뒤에 모델 파일 확인 단계를 추가했습니다.",
                        "파일 이름, 파일 크기, 확장자, 추정 형식, 실행 상태, 권장 다음 동작을 한 번에 확인할 수 있습니다.",
                        "모델 패밀리를 직접 선택한 뒤 연결 또는 실행용 복사를 진행할 수 있습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "가져온 모델",
                    items = listOf(
                        "외부 파일 연결을 기본 동작으로 유지하고, 대용량 모델 파일을 자동으로 복사하지 않도록 정리했습니다.",
                        "지원 확인 필요, 변환 필요, 외부 파일 연결, Fusion 관리 저장소 상태를 더 분명하게 표시하도록 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "지원되지 않는 형식이나 변환이 필요한 형식은 바로 실행 모델로 선택되지 않도록 했습니다.",
                        "원본 외부 파일은 삭제하지 않으며, 연결 해제는 Fusion 참조만 제거합니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.18-alpha",
            status = "알파",
            summary = "Fusion 상태 점검 화면을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "상태 점검",
                    items = listOf(
                        "설정 메뉴에 Fusion 상태 점검 항목을 추가했습니다.",
                        "모델, 설정, 저장소, 메모리, 데이터베이스, 권한, 벤치마크, 내보내기, 개발자 로그 상태를 한 번에 확인할 수 있습니다.",
                        "다시 점검과 점검 보고서 복사 기능을 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "보고서",
                    items = listOf(
                        "점검 보고서에 앱 버전, 기기 정보, 메모리 상태, 선택 모델, 생성 설정, 데이터베이스 개수, 벤치마크 개수를 포함하도록 했습니다.",
                        "채팅 본문, 프롬프트, 첨부파일 내용, 비밀값은 보고서에 포함하지 않도록 유지했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 경량 상태 점검과 조회 기능만 추가합니다.",
                        "Room 스키마, 채팅 생성, 모델 런타임, 벤치마크 실행 동작은 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.17-alpha",
            status = "알파",
            summary = "사이드바 상단 헤더 배치를 더 정돈했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "헤더 배치",
                    items = listOf(
                        "오른쪽 액션을 GitHub와 새 채팅 버튼의 묶음으로 정리해 간격을 더 자연스럽게 조정했습니다.",
                        "대화 제목 영역이 우선 폭을 갖도록 정리해 서브타이틀이 한 줄로 더 안정적으로 보이게 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "GitHub 칩",
                    items = listOf(
                        "GitHub 칩의 고정 폭을 줄이고 좌우 여백을 줄여 상단 헤더에서 차지하는 공간을 축소했습니다.",
                        "GitHub 링크 동작은 그대로 유지했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 헤더 UI 정리만 포함합니다.",
                        "채팅, 모델 실행, 벤치마크, 데이터 구조, 설정 백업/복원은 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.16-alpha",
            status = "알파",
            summary = "GitHub 바로가기 표시를 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "GitHub 연결",
                    items = listOf(
                        "대화 목록 상단에 GitHub 저장소 바로가기 버튼을 추가했습니다.",
                        "GitHub 버튼을 누르면 외부 브라우저에서 Fusion 저장소를 열 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "UI",
                    items = listOf(
                        "GitHub 바로가기를 검색창과 어울리는 둥근 pill 형태로 정리했습니다.",
                        "상단 헤더에서 새 채팅 버튼과 함께 자연스럽게 보이도록 배치를 조정했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 외부 링크와 UI 배치만 변경합니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.15-alpha",
            status = "알파",
            summary = "모델 메모리 점검 기준을 동적으로 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "모델 점검",
                    items = listOf(
                        "모델 실행 전 점검에서 총 메모리와 현재 사용 가능한 메모리를 함께 반영하도록 개선했습니다.",
                        "모델 크기, 권장 메모리, 현재 최대 토큰 수를 기준으로 실행 위험도를 계산하도록 했습니다.",
                        "8GB 기준에 고정되지 않고 기기별 RAM 상태에 따라 권장, 주의 필요, 무거움, 권장하지 않음 단계를 표시합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "모델 추천",
                    items = listOf(
                        "모델 목록과 상세 화면의 경고 문구가 현재 기기 상태를 더 잘 반영하도록 정리했습니다.",
                        "총 메모리는 충분하지만 현재 가용 메모리가 낮은 경우를 구분해 안내합니다.",
                        "무거운 모델 선택 전 확인 절차가 동적 위험도 계산을 사용하도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 모델 실행 전 안내와 추천 기준을 개선합니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.14-alpha",
            status = "알파",
            summary = "GitHub 이슈 제보와 실험 노트 UI를 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "GitHub 연결",
                    items = listOf(
                        "GitHub Issues로 버그와 개선 요청을 남길 수 있는 진입점을 추가했습니다.",
                        "브라우저에서 이슈 페이지를 열 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "실험 노트",
                    items = listOf(
                        "실험 노트 화면의 하단 버튼 배치를 더 정돈된 형태로 개선했습니다.",
                        "복사, 저장, 초기화, 닫기 버튼을 2줄 구성으로 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 링크 연결과 UI 정리 중심으로 적용했습니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.12-alpha",
            status = "알파",
            summary = "앱 정보 화면 표시 방식을 정리했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "앱 정보",
                    items = listOf(
                        "앱 정보 영역의 개발 관련 항목 배치를 정리했습니다.",
                        "릴리즈 체크리스트를 일반 화면에서 숨기고, 개발자 모드에서만 표시하도록 했습니다.",
                        "앱 정보 카드를 7초 이상 길게 누르면 개발자 모드를 활성화할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "개발자 항목",
                    items = listOf(
                        "개발 명령어 화면을 제거했습니다.",
                        "릴리즈 체크리스트는 개발자 항목으로 이동했습니다.",
                        "개발자 모드는 비밀번호가 아닌 UI 표시 전환 기능으로 동작합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 설정 메뉴 표시와 안내 화면 정리 중심으로 적용했습니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.11-alpha",
            status = "알파",
            summary = "가이드 화면 내용을 더 실용적으로 확장했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "가이드 확장",
                    items = listOf(
                        "사용 가이드에 시작 순서, 모델 선택, 채팅, 메모리, 벤치마크, A/B 테스트, 데이터 관리 설명을 더 자세히 추가했습니다.",
                        "문제 해결 가이드에 모델 실행, 메모리 부족, 응답 지연, A/B 테스트, ADB 설치, 로그 확인 항목을 더 실용적으로 정리했습니다.",
                        "모델 호환성 가이드에 기기 RAM 기준, 실제 메모리 차이, 로컬 실행 구분, 모델 선택 전 확인 사항을 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "복사 개선",
                    items = listOf(
                        "가이드 복사 시 확장된 전체 내용을 함께 복사하도록 정리했습니다.",
                        "깨진 한글 없이 실사용 안내 문구를 읽기 쉽게 다듬었습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 가이드 화면의 정적 내용만 확장했습니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리, 데이터 스키마 동작은 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.10-alpha",
            status = "알파",
            summary = "문제 해결 가이드를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "문제 해결",
                    items = listOf(
                        "모델 실행, 메모리 부족, 벤치마크 오류, MTP 설정 문제를 확인할 수 있는 문제 해결 가이드를 추가했습니다.",
                        "채팅 화면, 메모리 기능, ADB 설치, 로그 확인 관련 안내를 정리했습니다.",
                        "각 문제 해결 항목을 클립보드로 복사할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "개발 보조",
                    items = listOf(
                        "개발자 로그와 logcat 확인 흐름을 더 쉽게 찾을 수 있도록 했습니다.",
                        "릴리즈 체크리스트와 함께 사용할 수 있는 진단용 안내 화면을 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 정적 안내 화면과 복사 기능만 추가합니다.",
                        "앱 내부에서 명령어를 실행하지 않으며, 채팅과 모델 실행 데이터는 변경하지 않습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.8-alpha",
            status = "알파",
            summary = "실험 노트 화면을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "실험 노트",
                    items = listOf(
                        "모델 테스트와 벤치마크 메모를 기록할 수 있는 실험 노트 화면을 추가했습니다.",
                        "실험 노트를 저장, 복사, 초기화할 수 있도록 했습니다.",
                        "실험 노트는 로컬 SharedPreferences에 저장됩니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "개발 보조",
                    items = listOf(
                        "A/B 테스트, 벤치마크, 모델 실행 결과를 사람이 직접 정리할 수 있도록 했습니다.",
                        "반복 실험 중 발견한 내용을 앱 안에서 간단히 기록할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "실험 노트는 채팅, 모델 실행, 메모리 컨텍스트에 자동으로 반영되지 않습니다.",
                        "실험 노트 내용은 개발자 로그와 설정 백업에 포함하지 않습니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.7-alpha",
            status = "알파",
            summary = "프롬프트 프리셋 화면을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "프롬프트 프리셋",
                    items = listOf(
                        "자주 쓰는 요청 문구를 확인하고 복사할 수 있는 프롬프트 프리셋 화면을 추가했습니다.",
                        "요약, 설명, 비교, 코드, Fusion 개발, 실험 카테고리를 추가했습니다.",
                        "각 프리셋을 클립보드로 복사할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "개발 보조",
                    items = listOf(
                        "Fusion 기능 구현과 테스트에 사용할 수 있는 안전한 개발용 프롬프트를 추가했습니다.",
                        "벤치마크와 A/B 테스트 결과 해석에 사용할 수 있는 실험용 프롬프트를 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 정적 프리셋과 복사 기능만 추가합니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.6-alpha",
            status = "알파",
            summary = "릴리즈 체크리스트를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "릴리즈 확인",
                    items = listOf(
                        "빌드와 실기기 테스트 항목을 확인할 수 있는 릴리즈 체크리스트 화면을 추가했습니다.",
                        "채팅, 모델, 메모리, 실험, 데이터, 진단 항목을 그룹별로 확인할 수 있도록 했습니다.",
                        "전체 선택과 초기화 기능을 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "개발 보조",
                    items = listOf(
                        "체크리스트를 클립보드로 복사할 수 있도록 했습니다.",
                        "배포 전 반복 테스트 흐름을 더 쉽게 확인할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "체크리스트는 로컬 화면 상태로만 동작하며 앱 데이터는 변경하지 않습니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.5-alpha",
            status = "알파",
            summary = "기기 정보 화면을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "기기 정보",
                    items = listOf(
                        "현재 기기의 모델명, Android 버전, AP/SoC 정보를 확인할 수 있는 기기 정보 화면을 추가했습니다.",
                        "총 메모리와 현재 사용 가능한 메모리를 확인할 수 있도록 했습니다.",
                        "기기 메모리 기준으로 Fusion 권장 모드를 표시하도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "모델 실행 참고",
                    items = listOf(
                        "8GB급 기기에서 소형 모델과 낮은 최대 토큰 수 설정을 권장하도록 안내를 추가했습니다.",
                        "모델 파일 크기와 실제 실행 메모리가 다를 수 있다는 안내를 추가했습니다.",
                        "KV 캐시와 런타임 버퍼로 인한 메모리 부담을 설명했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 읽기 전용 기기 정보 화면 중심으로 추가했습니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.4-alpha",
            status = "알파",
            summary = "상태 대시보드를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "상태 확인",
                    items = listOf(
                        "현재 모델, 메모리, 성능, 기기, 앱 정보를 한눈에 확인할 수 있는 상태 대시보드를 추가했습니다.",
                        "저장된 메모리와 대화 요약은 내용이 아니라 개수와 상태만 표시하도록 했습니다.",
                        "벤치마크와 A/B 테스트 기록 요약을 확인할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "사용성",
                    items = listOf(
                        "상태 정보를 클립보드로 복사할 수 있도록 했습니다.",
                        "개발자 로그보다 간단한 사용자용 상태 확인 화면을 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성",
                    items = listOf(
                        "이번 업데이트는 읽기 전용 상태 화면 중심으로 추가했습니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터 구조는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.3-alpha",
            status = "알파",
            summary = "모델 호환성 가이드를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "모델 가이드",
                    items = listOf(
                        "모델군별 특징과 실행 기준을 확인할 수 있는 모델 호환성 가이드를 추가했습니다.",
                        "Gemma, Qwen, Llama, Phi, DeepSeek, Mistral, Kimi 모델군의 용도와 주의사항을 정리했습니다.",
                        "8GB 기기에서 권장되는 모델 크기와 실행 기준을 확인할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "사용성",
                    items = listOf(
                        "설정 화면에서 모델 호환성 가이드를 열 수 있도록 했습니다.",
                        "가이드 내용을 클립보드로 복사할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 업데이트는 읽기 전용 가이드 화면만 추가합니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리 데이터는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.2-alpha",
            status = "알파",
            summary = "사용 가이드를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "사용 가이드",
                    items = listOf(
                        "Fusion의 주요 기능과 사용 방법을 빠르게 확인할 수 있는 가이드 화면을 추가했습니다.",
                        "모델 라이브러리, 메모리, 벤치마크, A/B 테스트, 데이터 관리, 문제 해결 흐름을 한곳에서 정리했습니다.",
                        "가이드 내용을 클립보드로 복사할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "UI",
                    items = listOf(
                        "설정 화면에서 사용 가이드로 바로 들어갈 수 있도록 했습니다.",
                        "다크 UI와 하늘색 강조 색상에 맞춰 가이드 화면을 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "이번 패치는 읽기 전용 안내 화면과 버전 정보만 수정합니다.",
                        "채팅, 모델 실행, 벤치마크, 메모리, A/B 테스트 동작은 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.1-alpha",
            status = "알파",
            summary = "A/B 테스트 기록과 결과 관리를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "A/B 테스트 기록",
                    items = listOf(
                        "실행한 A/B 테스트 결과를 최근 기록으로 확인할 수 있도록 했습니다.",
                        "프롬프트, 모델, 설정, 응답 결과, 속도 정보를 함께 볼 수 있도록 했습니다.",
                        "최근 A/B 테스트 기록을 열기, 복사, 삭제할 수 있도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "결과 비교",
                    items = listOf(
                        "A/B 테스트 결과 상세 화면에서 각 대상의 답변과 성능 정보를 비교할 수 있습니다.",
                        "가장 빠른 응답 시작, 가장 높은 디코딩 속도, 실패한 대상 수를 요약해 표시합니다.",
                        "필요한 경우 결과를 Markdown 형식으로 복사할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "A/B 테스트 기록은 채팅 기록이나 모델 파일과 별도로 관리됩니다.",
                        "개발자 로그에는 A/B 테스트 본문이 아니라 기록 개수와 상태만 표시합니다.",
                        "설정 백업에는 A/B 테스트 결과 본문을 포함하지 않습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.4.0-alpha",
            status = "알파",
            summary = "모델 A/B 테스트 기능을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "모델 비교",
                    items = listOf(
                        "같은 프롬프트로 여러 모델 또는 설정을 비교할 수 있는 A/B 테스트 화면을 추가했습니다.",
                        "각 테스트 대상의 모델, 생성 설정, 응답 결과를 함께 확인할 수 있도록 했습니다.",
                        "테스트는 메모리 부담을 줄이기 위해 순차적으로 실행됩니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "성능 비교",
                    items = listOf(
                        "First Token Latency, 총 생성 시간, 토큰 속도 등 주요 성능 정보를 결과 카드에 표시합니다.",
                        "가장 빠른 응답 시작과 가장 높은 디코딩 속도를 비교 요약으로 확인할 수 있습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "지원되지 않는 모델이나 원격 전용 모델은 로컬 A/B 테스트 대상에서 제외합니다.",
                        "일부 테스트가 실패해도 다른 결과는 유지되도록 처리했습니다.",
                        "개발자 로그에는 테스트 결과의 본문이 아니라 상태와 요약 정보만 기록합니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.14-alpha",
            status = "알파",
            summary = "메모리 검색과 적용 범위를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "메모리 관리",
                    items = listOf(
                        "저장된 메모리를 검색하고 필터링할 수 있도록 개선했습니다.",
                        "메모리를 최근 수정순, 최근 저장순, 길이 기준으로 정렬할 수 있도록 했습니다.",
                        "메모리별 사용 상태와 적용 범위를 더 명확하게 확인할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "메모리 적용 범위",
                    items = listOf(
                        "메모리를 전체 대화 또는 특정 대화 기준으로 사용할 수 있도록 적용 범위 설정을 추가했습니다.",
                        "사용 중지된 메모리는 답변 생성에 포함되지 않도록 정리했습니다.",
                        "메모리 컨텍스트 미리보기가 실제 답변 생성 규칙과 더 일치하도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "저장된 메모리와 대화 요약 내용은 설정 백업에 포함하지 않도록 유지했습니다.",
                        "개발자 로그에는 메모리 내용이 아니라 개수와 상태만 표시하도록 정리했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.13-alpha",
            status = "알파",
            summary = "저장된 메모리를 답변 생성에 반영할 수 있도록 했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "메모리",
                    items = listOf(
                        "저장된 메모리를 답변 생성에 참고하는 설정을 추가했습니다.",
                        "메모리별 사용 여부를 관리할 수 있도록 개선했습니다.",
                        "답변 생성에 포함될 메모리 컨텍스트를 미리 확인할 수 있도록 했습니다.",
                        "대화 요약과 저장된 메모리를 함께 활용할 수 있도록 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "채팅",
                    items = listOf(
                        "일반 답변 생성과 답변 다시 생성이 동일한 메모리 컨텍스트를 사용하도록 개선했습니다.",
                        "긴 대화에서도 저장된 핵심 정보를 더 안정적으로 참고할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안전성",
                    items = listOf(
                        "삭제되거나 사용 중지된 메모리는 답변 생성에 포함되지 않도록 했습니다.",
                        "메모리 컨텍스트 길이에 제한을 적용해 긴 메모리로 인한 문제를 줄였습니다.",
                        "개발자 로그에는 메모리 내용이 아니라 개수와 상태만 표시하도록 했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.12-alpha",
            status = "알파",
            summary = "메모리 관리 화면을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "메모리 관리",
                    items = listOf(
                        "저장된 메모리와 대화 요약을 한곳에서 확인하고 관리할 수 있는 화면을 추가했습니다.",
                        "저장된 메모리를 검색하고 수정, 복사, 삭제할 수 있도록 했습니다.",
                        "대화 요약도 열기, 복사, 삭제로 관리할 수 있도록 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "저장소 재사용",
                    items = listOf(
                        "메모리 후보 추출에서 저장한 메모리를 같은 저장소로 불러오도록 연결했습니다.",
                        "기존 대화 요약 저장 구조를 그대로 재사용해 별도 데이터베이스 변경 없이 관리할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "버전 관리",
                    items = listOf(
                        "현재 앱 버전을 0.3.12-alpha로 업데이트했습니다.",
                        "0.3.12-alpha 패치 기록을 업데이트 기록에 추가했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.11-alpha",
            status = "알파",
            summary = "메모리 후보 추출과 버전 정보를 정리했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "메모리 후보 추출",
                    items = listOf(
                        "현재 대화에서 나중에 참고할 만한 메모리 후보를 추출할 수 있도록 했습니다.",
                        "추출한 후보를 검토한 뒤 복사하거나 선택해서 저장할 수 있도록 했습니다.",
                        "메모리 후보는 자동 저장하지 않고 사용자가 확인한 항목만 저장합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "대화 맥락",
                    items = listOf(
                        "저장된 대화 요약과 최근 대화를 함께 참고해 메모리 후보를 정리하도록 개선했습니다.",
                        "민감한 정보는 자동 저장하지 않고 검토용 후보로만 표시합니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "버전 관리",
                    items = listOf(
                        "현재 앱 버전을 0.3.11-alpha로 업데이트했습니다.",
                        "0.3.11-alpha 패치 기록을 업데이트 기록에 추가했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.10-alpha",
            status = "알파",
            summary = "업데이트 기록, 채팅 설정의 인코딩 문제를 수정했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "업데이트 기록",
                    items = listOf(
                        "일부 버전의 패치노트에서 글자가 깨져 보이던 문제를 수정했습니다.",
                        "버전별 업데이트 기록이 정상적인 한국어로 표시되도록 정리했습니다.",
                        "업데이트 기록 복사 시 깨진 문자가 포함되지 않도록 수정했습니다.",
                        "채팅 설정에서 잘못된 설정 문구를 삭제했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "버전 관리",
                    items = listOf(
                        "현재 앱 버전을 0.3.10-alpha로 업데이트했습니다.",
                        "0.3.10-alpha 패치 기록을 업데이트 기록에 추가했습니다.",
                        "기존 0.3.x-alpha 패치노트의 순서와 요약 문구를 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성",
                    items = listOf(
                        "이번 패치는 업데이트 기록과 버전 정보만 수정합니다.",
                        "채팅, 모델 실행, 벤치마크, 설정 데이터는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.9-alpha",
            status = "알파",
            summary = "대화 요약 메모리와 패치노트를 정리했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "대화 요약 메모리",
                    items = listOf(
                        "대화 요약을 저장해 긴 대화에서도 핵심 맥락을 유지할 수 있도록 했습니다.",
                        "요약 생성, 직접 편집, 복사, 삭제 기능을 추가했습니다.",
                        "요약은 현재 대화의 생성과 응답 재생성에 함께 반영됩니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "업데이트 기록",
                    items = listOf(
                        "0.3.9-alpha 버전을 추가했습니다.",
                        "버전별 패치노트를 최신 상태로 유지했습니다."
                    )
                )
            )
        ),        FusionReleaseNote(
            version = "0.3.8-alpha",
            status = "알파",
            summary = "버전과 패치노트를 정리했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "업데이트 기록",
                    items = listOf(
                        "0.3.8-alpha 버전을 추가했습니다.",
                        "버전별 패치노트를 최신 상태로 유지했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성",
                    items = listOf(
                        "앱 버전 표시와 업데이트 기록만 정리했습니다.",
                        "채팅, 모델, 벤치마크, 설정 동작은 변경하지 않았습니다."
                    )
                )
            )
        ),        FusionReleaseNote(
            version = "0.3.7-alpha",
            status = "알파",
            summary = "응답 재생성과 패치노트 정리를 반영했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "답변 다시 생성",
                    items = listOf(
                        "답변을 자동으로 다시 생성할 수 있도록 개선했습니다.",
                        "더 짧게, 더 자세히, 표로 정리, 전문가 톤 재생성 동작을 추가했습니다.",
                        "재생성 실패 시 기존 답변은 그대로 유지하도록 안전하게 처리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "업데이트 기록",
                    items = listOf(
                        "0.3.7-alpha 버전을 추가했습니다.",
                        "버전별 패치노트를 더 정리된 형태로 유지했습니다."
                    )
                )
            )
        ),        FusionReleaseNote(
            version = "0.3.6-alpha",
            status = "알파",
            summary = "채팅 입력 영역의 배치를 다듬었습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "채팅 화면",
                    items = listOf(
                        "빠른 입력 버튼의 세로 위치를 입력창에 더 자연스럽게 맞췄습니다.",
                        "빈 채팅 화면에서 빠른 입력 영역이 과하게 위로 떠 보이는 문제를 개선했습니다.",
                        "키보드가 열리거나 닫힐 때 입력 영역의 배치가 어색해지지 않도록 조정했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "UI 개선",
                    items = listOf(
                        "하단 입력 영역의 여백과 정렬을 더 일관되게 다듬었습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.5-alpha",
            status = "알파",
            summary = "업데이트 기록과 버전 정보를 정리했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "업데이트 기록",
                    items = listOf(
                        "0.3.4-alpha 패치 기록을 추가했습니다.",
                        "버전별 업데이트 기록을 더 명확하게 정리했습니다.",
                        "일부 업데이트 기록에서 글자가 깨져 보이던 문제를 수정했습니다.",
                        "업데이트 기록 복사 시 깨진 문자가 포함되지 않도록 정리했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "버전 관리",
                    items = listOf(
                        "현재 앱 버전을 0.3.5-alpha로 업데이트했습니다.",
                        "앱에 표시되는 버전 정보가 Android 앱 버전과 일치하도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "안정성",
                    items = listOf(
                        "업데이트 기록 화면만 수정하며 채팅, 모델 실행, 벤치마크 데이터는 변경하지 않았습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.4-alpha",
            status = "알파",
            summary = "개발자 로그 화면과 채팅 입력 화면 배치를 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "개발자 로그",
                    items = listOf(
                        "개발자 로그 화면의 카드 정렬과 하단 버튼 배치를 개선했습니다.",
                        "개발자 로그 복사, 오류 보고서 복사, 로그 지우기, 닫기 버튼을 더 정돈된 형태로 배치했습니다.",
                        "개발자 로그 내용과 하단 동작 영역이 서로 겹치지 않도록 조정했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "채팅 화면",
                    items = listOf(
                        "키보드가 열릴 때 빈 채팅 화면과 입력 영역의 배치가 어긋나는 문제를 개선했습니다.",
                        "새 채팅 화면의 안내 문구가 입력창이나 하단 영역에 가려지지 않도록 조정했습니다.",
                        "입력창, 빠른 입력 영역, 시스템 내비게이션 영역의 하단 여백 처리를 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "UI 안정성",
                    items = listOf(
                        "화면 하단 안전 영역 처리 방식을 정리했습니다.",
                        "일부 화면에서 하단 버튼이 가려질 수 있는 문제를 줄였습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.3-alpha",
            status = "알파",
            summary = "업데이트 기록과 앱 버전 표시를 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "업데이트 기록",
                    items = listOf(
                        "업데이트 기록 화면을 추가했습니다.",
                        "버전별 변경사항을 목록에서 선택해 확인할 수 있도록 개선했습니다.",
                        "업데이트 기록을 클립보드로 복사할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "앱 버전",
                    items = listOf(
                        "현재 앱 버전을 업데이트 기록 화면에 표시하도록 개선했습니다.",
                        "앱 버전 정보가 확인되지 않을 때 안내 문구를 표시하도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "UI",
                    items = listOf(
                        "업데이트 기록 화면을 다크 UI와 하늘색 강조 색상에 맞춰 정리했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.2-alpha",
            status = "알파",
            summary = "개발자 로그와 진단 기능을 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "개발자 로그",
                    items = listOf(
                        "현재 앱 상태, 모델, 메모리, 설정 정보를 확인할 수 있는 개발자 로그 화면을 추가했습니다.",
                        "개발자 로그와 오류 보고서를 클립보드로 복사할 수 있도록 했습니다.",
                        "최근 오류가 없을 때 안내 문구를 표시하도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "진단 정보",
                    items = listOf(
                        "기기 모델, Android 버전, SoC/AP 정보, 메모리 상태를 진단 정보에 포함했습니다.",
                        "선택된 모델, 런타임 형식, 가속기, MTP 상태를 확인할 수 있도록 했습니다.",
                        "벤치마크 기록 요약을 개발자 로그에서 확인할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "개인정보 보호",
                    items = listOf(
                        "오류 보고서에 채팅 내용, 첨부파일 내용, 전체 프롬프트가 포함되지 않도록 정리했습니다.",
                        "모델 메모 내용은 기본 진단 정보에 포함하지 않도록 했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.1-alpha",
            status = "알파",
            summary = "모델 라이브러리 관리 기능을 개선했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "모델 관리",
                    items = listOf(
                        "모델 즐겨찾기와 숨김 관리를 추가했습니다.",
                        "모델 검색, 필터, 정렬 기능을 추가했습니다.",
                        "최근 사용 모델과 모델별 메모를 추가했습니다.",
                        "모델별 벤치마크 요약을 확인할 수 있도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "기기 추천",
                    items = listOf(
                        "기기 메모리와 모델 크기를 기준으로 추천 모델을 확인할 수 있도록 개선했습니다.",
                        "8GB 기기에서 사용할 수 있는 소형 모델을 더 자연스럽게 분류하도록 개선했습니다.",
                        "무거운 모델 선택 전 확인 절차를 추가했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "데이터 관리",
                    items = listOf(
                        "설정 백업 및 복원 기능을 추가했습니다.",
                        "채팅 Markdown 내보내기에서 내보낼 채팅을 선택할 수 있도록 개선했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.3.0-alpha",
            status = "알파",
            summary = "Model Zoo 기반 구조를 추가했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "모델 라이브러리 기반",
                    items = listOf(
                        "Model Zoo 기반 구조를 추가했습니다.",
                        "Gemma, Qwen, Llama, Phi, DeepSeek, Mistral, Kimi 등 다양한 모델 후보를 표시할 수 있도록 준비했습니다.",
                        "모델별 패밀리, 런타임 형식, 권장 메모리 정보를 표시하도록 했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "모델 상세 정보",
                    items = listOf(
                        "모델 상세 화면에서 파일 크기, 권장 메모리, 권장 토큰 수를 확인할 수 있도록 했습니다.",
                        "모델 페이지와 공식 링크를 열 수 있도록 했습니다.",
                        "기기 메모리 기준으로 모델 실행 경고를 표시하도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "NPU/GPU/CPU 안내",
                    items = listOf(
                        "기기 SoC/AP에 따라 Exynos, Snapdragon, MediaTek, Tensor 계열 안내 문구를 다르게 표시하도록 준비했습니다.",
                        "전용 NPU 실행은 보장하지 않고 후보 정보로만 표시하도록 정리했습니다."
                    )
                )
            )
        ),
        FusionReleaseNote(
            version = "0.2-beta",
            status = "베타",
            summary = "기본 로컬 모델 채팅 기능을 정리했습니다.",
            sections = listOf(
                FusionReleaseNoteSection(
                    title = "채팅",
                    items = listOf(
                        "기본 로컬 모델 채팅 기능을 정리했습니다.",
                        "고급 생성 설정을 추가했습니다.",
                        "추론, 웹 검색, MTP 가속 실험 옵션을 사용할 수 있도록 했습니다.",
                        "응답 속도와 토큰 정보를 확인할 수 있도록 개선했습니다."
                    )
                ),
                FusionReleaseNoteSection(
                    title = "대화 관리",
                    items = listOf(
                        "채팅 고정, 이름 변경, 삭제, 아카이브 기능을 개선했습니다.",
                        "사이드바 검색과 검색 결과 표시를 개선했습니다.",
                        "검색 결과와 기본 목록의 시각적 구분을 개선했습니다."
                    )
                )
            )
        )
    )
}

private fun buildSingleReleaseNotesText(note: FusionReleaseNote): String {
    return buildString {
        appendLine("업데이트 기록")
        appendLine("${note.version} (${note.status})")
        note.date?.let { appendLine("날짜: $it") }
        appendLine(note.summary)
        appendLine()
        note.sections.forEach { section ->
            appendLine("[${section.title}]")
            section.items.forEach { item -> appendLine("- $item") }
            appendLine()
        }
    }.trimEnd()
}

private fun buildReleaseNotesHistoryText(appVersionName: String, notes: List<FusionReleaseNote>): String {
    return buildString {
        appendLine("업데이트 기록")
        appendLine("현재 앱 버전: $appVersionName")
        appendLine()
        notes.forEach { note ->
            appendLine("${note.version} (${note.status})")
            note.date?.let { appendLine("날짜: $it") }
            appendLine(note.summary)
            note.sections.forEach { section ->
                appendLine("[${section.title}]")
                section.items.forEach { item -> appendLine("- $item") }
            }
            appendLine()
        }
    }.trimEnd()
}

private fun resolveVersionName(context: Context): String {
    val packageInfo = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()
    return packageInfo?.versionName ?: "현재 앱 버전 정보를 확인할 수 없습니다."
}
