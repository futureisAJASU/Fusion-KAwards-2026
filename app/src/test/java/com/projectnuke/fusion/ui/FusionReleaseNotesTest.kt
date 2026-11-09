package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionReleaseNotesTest {

    @Test
    fun newest_checkpoint_is_present_and_historical_entries_remain() {
        val history = buildFusionReleaseNotesHistory()
        val newest = history.first()
        val twoTwo = history.first { it.version == "2.2.0" }

        assertEquals(listOf("2.4.0", "2.3.0", "2.2.0", "2.1.0", "2.0.6"), history.take(5).map { it.version })
        assertEquals("2.4.0", newest.version)
        assertEquals("2026-10-06", newest.date)
        assertEquals("2026-09-30", twoTwo.date)
        assertEquals("2026-09-14", history.first { it.version == "2.0.5" }.date)
        assertNotNull(history.firstOrNull { it.version == "2.0.4" })
        assertNotNull(history.firstOrNull { it.version == "2.0.3" })
        assertNotNull(history.firstOrNull { it.version == "2.0.2" })
        assertNotNull(history.firstOrNull { it.version == "1.0.0-beta-stable" })
        assertTrue(history.any { it.version == "0.4.23-alpha" })
     }

    @Test
    fun release_groups_are_newest_first() {
        val groups = buildGroupedReleaseNotes(buildFusionReleaseNotesHistory())
        val twoDotZero = groups[4].notes.map { it.version }

        assertEquals(listOf("2.4.x", "2.3.x", "2.2.x", "2.1.x", "2.0.x"), groups.take(5).map { it.key })
        assertEquals("2.4.0", groups[0].notes.first().version)
        assertEquals("2.3.0", groups[1].notes.first().version)
        assertEquals("2.2.0", groups[2].notes.first().version)
        assertEquals("2.1.0", groups[3].notes.first().version)
        assertEquals("2.0.6", groups[4].notes.first().version)
        assertEquals(listOf("2.0.6", "2.0.5", "2.0.4"), twoDotZero.take(3))
        assertTrue(groups[4].notes.any { it.version == "2.0.4" })
        assertTrue(groups[4].notes.any { it.version == "2.0.3" })
        assertTrue(groups[4].notes.any { it.version == "2.0.2" })
        assertEquals("1.0.x", groups[5].key)
    }

    @Test
    fun release_groups_use_numeric_major_minor_order_and_keep_other_last() {
        val notes = listOf(
            syntheticNote("2.0.1"),
            syntheticNote("기타 버전"),
            syntheticNote("1.9.0"),
            syntheticNote("3.0.0"),
            syntheticNote("2.10.0"),
            syntheticNote("2.2.0"),
            syntheticNote("2.1.0"),
        )

        assertEquals(
            listOf("3.0.x", "2.10.x", "2.2.x", "2.1.x", "2.0.x", "1.9.x", "기타"),
            buildGroupedReleaseNotes(notes).map { it.key },
        )
    }

    @Test
    fun release_group_notes_retain_newest_first_input_order() {
        val notes = listOf(
            syntheticNote("2.0.6"),
            syntheticNote("2.0.5"),
            syntheticNote("2.0.4"),
            syntheticNote("1.0.0"),
        )

        val twoDotZero = buildGroupedReleaseNotes(notes).first { it.key == "2.0.x" }
        assertEquals(listOf("2.0.6", "2.0.5", "2.0.4"), twoDotZero.notes.map { it.version })
    }

    @Test
    fun malformed_versions_are_grouped_into_other() {
        val malformed = listOf(
            syntheticNote("2"),
            syntheticNote("2.x.0"),
            syntheticNote("v2.1.0"),
            syntheticNote("2.1"),
        )

        val groups = buildGroupedReleaseNotes(malformed)

        assertEquals(listOf("기타"), groups.map { it.key })
        assertEquals(malformed.map { it.version }, groups.single().notes.map { it.version })
    }

    @Test
    fun checkpoint_copy_surface_is_bounded_and_version_is_safe() {
        val newest = buildFusionReleaseNotesHistory().first()
        val items = newest.sections.flatMap { it.items }
        val versionPattern = Regex("\\d+\\.\\d+\\.\\d+(?:-[a-z]+(?:-[a-z]+)*)?")

        assertTrue(versionPattern.matches(newest.version))
        assertFalse(newest.version.any { it.isISOControl() })
        assertTrue(newest.sections.size in 4..8)
        assertTrue(items.size in 12..40)
        assertTrue(newest.summary.length <= 200)
        assertTrue(items.all { it.length <= 500 })
        assertTrue((newest.summary.length + items.sumOf(String::length)) <= 10_000)
    }

    @Test
    fun release_entry_has_checkpoint_version() {
        val newest = buildFusionReleaseNotesHistory().first()

        assertEquals("2.4.0", newest.version)
        assertEquals("Minor", newest.status)
        assertEquals("2026-10-06", newest.date)
    }

    @Test
    fun checkpoint_does_not_overclaim_scan_ocr_support() {
        val newest = buildFusionReleaseNotesHistory().first { it.version == "2.1.0" }
        val text = listOf(newest.summary) + newest.sections.flatMap { it.items }
        assertFalse(
            text.any { it.contains("OCR 지원") || it.contains("스캔 PDF 인식") }
        )
    }

    @Test
    fun checkpoint_2_1_0_contains_supported_concepts_without_overclaiming() {
        val newest = buildFusionReleaseNotesHistory().first { it.version == "2.1.0" }
        val text = (listOf(newest.summary) + newest.sections.flatMap { it.items }).joinToString("\n")

        assertTrue(text.contains("학습 모드"))
        assertTrue(text.contains("Project Brief"))
        assertTrue(text.contains("프로젝트 종합"))
        assertTrue(text.contains("답변") && text.contains("공유"))
        assertTrue(text.contains("출처"))
        assertTrue(text.contains("비교"))
        assertTrue(text.contains("검토·수정") && text.contains("직접 Send"))
        assertTrue(text.contains("안정성"))

        listOf(
            "OCR 지원",
            "스캔 PDF 인식",
            "자동 학습 생성",
            "자동으로 학습",
            "자동 프로젝트 종합",
            "자동으로 종합",
            "공식 채점",
            "정확한 정답 보장",
            "정답 보장",
            "검증된 답변",
            "모든 프로젝트 파일",
            "모든 원문",
            "최적의 답변",
            "자동으로 선택",
            "물리 디바이스",
            "adb",
        ).forEach { forbidden ->
            assertFalse("Unexpected release-note overclaim: $forbidden", text.contains(forbidden))
        }
    }

    @Test
    fun checkpoint_2_1_0_has_expected_sections_and_bounded_items() {
        val newest = buildFusionReleaseNotesHistory().first { it.version == "2.1.0" }

        assertEquals(
            listOf("학습 모드", "프로젝트", "답변 활용", "안정성", "품질 및 회귀 방지"),
            newest.sections.map { it.title },
        )
        assertTrue(newest.sections.flatMap { it.items }.size in 12..40)
    }

    @Test
    fun checkpoint_2_2_0_contains_supported_concepts_without_overclaiming() {
        val note = buildFusionReleaseNotesHistory().first { it.version == "2.2.0" }
        val text = (listOf(note.summary) + note.sections.flatMap { it.items }).joinToString("\n")

        listOf("생성 정보", "답변 맥락", "근거", "추천 질문", "외부", "모델 변형", "번역", "복구", "자동 성능", "안정성")
            .forEach { concept -> assertTrue("Missing 2.2.0 concept: $concept", text.contains(concept)) }
        listOf(
            "OCR 지원", "스캔 PDF 인식", "모든 사실 검증", "모든 환각 검출", "환각 제거",
            "검증된 답변 보장", "정답 보장", "자동 최적 모델 선택", "NNAPI/NPU 하드웨어 가속 확정",
            "모든 기기에서 동일한 성능", "production verifier asset distribution 완료", "PAD 완료",
        ).forEach { forbidden ->
            assertFalse("Unexpected release-note overclaim: $forbidden", text.contains(forbidden))
        }
    }

    @Test
    fun checkpoint_2_2_0_has_expected_sections_and_bounded_items() {
        val newest = buildFusionReleaseNotesHistory().first { it.version == "2.2.0" }
        assertEquals(
            listOf(
                "답변 맥락·근거 확인", "추천 질문·답변 활용", "외부 AI·모델 선택", "번역·음성",
                "문서·검색·대화 관리", "로컬 실행·성능", "복구·데이터 보호", "안정성",
            ),
            newest.sections.map { it.title },
        )
        assertEquals(24, newest.sections.sumOf { it.items.size })
    }

    @Test
    fun checkpoint_2_3_0_contains_supported_concepts_without_overclaiming() {
        val note = buildFusionReleaseNotesHistory().first { it.version == "2.3.0" }
        val text = (listOf(note.summary) + note.sections.flatMap { it.items }).joinToString("\n")

        listOf(
            "Fusion Search", "키워드", "의미 기반", "2~4개 자료", "저장 지식", "할 일", "스냅샷",
            "이미지·스캔", "로컬 OCR", "클라우드", "Evidence", "Research Mode", "복습 카드",
            "알림", "Branch Map", "AUTO", "결정적으로", "메모리", "발열", "동의", "원본 갈래", "전송 전에 검토",
        ).forEach { concept -> assertTrue("Missing 2.3.0 concept: $concept", text.contains(concept)) }
        listOf(
            "모든 사실 검증", "환각 제거", "완벽한 OCR", "항상 최적 모델", "자동 최적 모델 선택",
            "자동으로 클라우드", "Anki", "과학적으로 최적화된 간격 반복", "모든 Android 기기",
            "물리 디바이스 인증", "verifier 재인증",
        ).forEach { forbidden ->
            assertFalse("Unexpected release-note overclaim: $forbidden", text.contains(forbidden))
        }
    }

    @Test
    fun checkpoint_2_3_0_has_expected_sections_and_bounded_items() {
        val note = buildFusionReleaseNotesHistory().first { it.version == "2.3.0" }

        assertEquals("2026-10-01", note.date)
        assertEquals(
            listOf(
                "검색·자료 선택", "프로젝트·지식", "문서·카메라", "Research·비교",
                "학습·복습", "모델·실행", "대화·작업 흐름", "UI·안정성",
            ),
            note.sections.map { it.title },
        )
        assertEquals(24, note.sections.sumOf { it.items.size })
        assertTrue(note.sections.flatMap { it.items }.all { it.length <= 500 })
    }

    @Test
    fun checkpoint_2_4_0_contains_supported_concepts_without_overclaiming() {
        val note = buildFusionReleaseNotesHistory().first { it.version == "2.4.0" }
        val text = (listOf(note.summary) + note.sections.flatMap { it.items }).joinToString("\n")

        assertEquals("2.4.0", note.version)
        assertEquals("Minor", note.status)
        assertEquals("2026-10-06", note.date)
        listOf(
            "사실 확인", "근거", "웹 검색", "Work Plan", "검토",
            "Project Consistency", "로컬", "자동", "외부 AI", "Smart Inbox",
            "정리 후보", "할 일", "복습 카드", "PDF", "RAG",
            "Research", "스냅샷", "복구",
        ).forEach { concept -> assertTrue("Missing 2.4.0 concept: $concept", text.contains(concept)) }
        listOf(
            "모든 사실 검증", "환각 제거", "정확성 보장", "자동으로 클라우드",
            "자동 외부 전송", "자동으로 프로젝트 수정", "자동으로 Inbox 정리",
            "항상 최적 모델", "완벽한 관련성 판단", "모든 문서 전체 읽기", "모든 오류 복구",
        ).forEach { forbidden ->
            assertFalse("Unexpected release-note overclaim: $forbidden", text.contains(forbidden))
        }
    }

    @Test
    fun checkpoint_2_4_0_has_expected_sections_and_bounded_items() {
        val note = buildFusionReleaseNotesHistory().first { it.version == "2.4.0" }

        assertEquals(
            listOf(
                "사실 확인·근거 검토", "Project Work Plan", "프로젝트·지식 검토", "실행 프로필",
                "Smart Inbox", "문서·검색·Research", "데이터·복구·작업 상태", "모델·외부 연결·안정성",
            ),
            note.sections.map { it.title },
        )
        assertEquals(8, note.sections.size)
        assertEquals(24, note.sections.sumOf { it.items.size })
        assertTrue(note.sections.flatMap { it.items }.all { it.length <= 500 })
    }

    @Test
    fun restored_2_0_5_checkpoint_has_expected_shape() {
        val history = buildFusionReleaseNotesHistory()
        val note = history.firstOrNull { it.version == "2.0.5" }

        assertNotNull(note)
        note!!
        assertEquals("Patch", note.status)
        assertEquals("2026-09-14", note.date)
        assertTrue(note.summary.length <= 200)
        assertEquals(
            listOf("프로젝트 지식", "PDF 문서 활용", "문서 인덱스와 성능", "안정성 및 개인정보"),
            note.sections.map { it.title },
        )
        val items = note.sections.flatMap { it.items }
        assertTrue(items.size in 12..18)
        assertTrue(items.all { it.length <= 500 })
    }

    @Test
    fun checkpoint_2_0_5_does_not_overclaim_scan_ocr_support() {
        val note = buildFusionReleaseNotesHistory().first { it.version == "2.0.5" }
        val text = listOf(note.summary) + note.sections.flatMap { it.items }

        assertFalse(text.any { it.contains("OCR 지원") })
        assertFalse(text.any { it.contains("스캔 PDF 인식") })
        assertFalse(text.any { it.contains("이미지 PDF 인식") })
        assertTrue(text.any { it.contains("OCR") && it.contains("스캔") })
    }

    @Test
    fun checkpoint_2_0_5_does_not_claim_later_hardening_work() {
        val note = buildFusionReleaseNotesHistory().first { it.version == "2.0.5" }
        val text = (listOf(note.summary) + note.sections.flatMap { it.items }).joinToString("\n")

        assertFalse(text.contains("KoEn"))
        assertFalse(text.contains("Room"))
        assertFalse(text.contains("512"))
    }

    private fun syntheticNote(version: String): FusionReleaseNote = FusionReleaseNote(
        version = version,
        status = "Test",
        summary = "synthetic",
        sections = emptyList(),
    )
}
