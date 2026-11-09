package com.projectnuke.fusion.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release checklist is a MANUAL verification surface. These tests pin the
 * critical current-product journeys it must cover and that it never claims
 * automated/device validation it does not perform.
 */
class FusionReleaseChecklistContentTest {

    private val groups = buildFusionReleaseChecklistGroups()

    private fun itemsOf(title: String): List<String> =
        groups.first { it.title == title }.items

    @Test
    fun `checklist covers current critical journey groups`() {
        val titles = groups.map { it.title }
        listOf(
            "빌드/설치",
            "첫 실행/설정",
            "채팅",
            "개인정보/외부 전송",
            "문서/RAG",
            "학습",
            "추천 질문",
            "번역",
            "아카이브/보안",
            "음성/읽어주기",
            "프로젝트",
            "사실 확인",
            "Work Plan",
            "Inbox",
            "모델",
            "메모리",
            "실험",
            "데이터",
            "진단",
        ).forEach { required ->
            assertTrue("missing checklist group: $required", required in titles)
        }
    }

    @Test
    fun `onboarding journeys are covered`() {
        val items = itemsOf("첫 실행/설정")
        assertTrue(items.any { it.contains("로컬 모델 경로") })
        assertTrue(items.any { it.contains("외부 제공자 경로") })
        assertTrue(items.any { it.contains("둘러보기") })
        assertTrue(items.any { it.contains("온보딩 다시 열기") })
    }

    @Test
    fun `chat journeys are covered`() {
        val items = itemsOf("채팅")
        assertTrue(items.any { it.contains("로컬 모델 답변 생성") })
        assertTrue(items.any { it.contains("외부 API 답변 생성") })
        assertTrue(items.any { it.contains("새 채팅") })
        assertTrue(items.any { it.contains("문안 보존") })
        assertTrue(items.any { it.contains("중지/취소") })
        assertTrue(items.any { it.contains("다시 생성") })
        assertTrue(items.any { it.contains("버전") })
        assertTrue(items.any { it.contains("생성 정보") && it.contains("맥락") })
        assertTrue(items.any { it.contains("브랜치") })
        assertTrue(items.any { it.contains("복사/공유") })
    }

    @Test
    fun `privacy and external egress journeys are covered`() {
        val items = itemsOf("개인정보/외부 전송")
        assertTrue(items.any { it.contains("외부 요청") })
        assertTrue(items.any { it.contains("외부 전송 승인") })
        assertTrue(items.any { it.contains("외부 호출 없음") })
        assertTrue(items.any { it.contains("시크릿/설정") })
    }

    @Test
    fun `documents study followup translation archive voice and project journeys are covered`() {
        assertTrue(itemsOf("문서/RAG").any { it.contains("PDF") })
        assertTrue(itemsOf("문서/RAG").any { it.contains("이미지 전용 PDF") })
        assertTrue(itemsOf("문서/RAG").any { it.contains("출처/근거") })
        assertTrue(itemsOf("학습").any { it.contains("추가 전용") })
        assertTrue(itemsOf("학습").any { it.contains("의도치 않은 지속성") })
        assertTrue(itemsOf("추천 질문").any { it.contains("자동 전송 없음") })
        assertTrue(itemsOf("번역").any { it.contains("단독 번역") })
        assertTrue(itemsOf("번역").any { it.contains("작성창 삽입") })
        assertTrue(itemsOf("번역").any { it.contains("백엔드 부재") })
        assertTrue(itemsOf("아카이브/보안").any { it.contains("잠금 인증") })
        assertTrue(itemsOf("아카이브/보안").any { it.contains("바로가기 거부") })
        assertTrue(itemsOf("음성/읽어주기").any { it.contains("받아쓰기") })
        assertTrue(itemsOf("음성/읽어주기").any { it.contains("읽어주기 시작/중지") })
        assertTrue(itemsOf("프로젝트").any { it.contains("브리프/종합") })
        assertTrue(itemsOf("프로젝트").any { it.contains("소유권") })
    }

    @Test
    fun `execution profile journeys are covered`() {
        val items = itemsOf("채팅")
        assertTrue(items.any { it.contains("로컬 실행 프로필") })
        assertTrue(items.any { it.contains("로컬 자동 모델 선택") })
        assertTrue(items.any { it.contains("외부 AI 제안 취소") && it.contains("외부 호출 없음") })
        assertTrue(items.any { it.contains("외부 AI 프로필") && it.contains("제공자 실행") })
        assertTrue(items.any { it.contains("프로필 변경") && it.contains("진행 중 요청") })
    }

    @Test
    fun `fact verification journeys are covered`() {
        val items = itemsOf("사실 확인")
        assertTrue(items.any { it.contains("기존 출처") && it.contains("사실 확인") })
        assertTrue(items.any { it.contains("승인된 웹 검색") })
        assertTrue(items.any { it.contains("검색 취소") && it.contains("웹 요청 없음") })
        assertTrue(items.any { it.contains("근거 부족") && it.contains("충돌") })
        assertTrue(items.any { it.contains("검색 쿼리") && it.contains("자동 포함") })
    }

    @Test
    fun `work plan journeys are covered`() {
        val items = itemsOf("Work Plan")
        assertTrue(items.any { it.contains("한 단계만 시작") })
        assertTrue(items.any { it.contains("REVIEW") })
        assertTrue(items.any { it.contains("Task/Decision") })
        assertTrue(items.any { it.contains("handoff") })
        assertTrue(items.any { it.contains("닫았다 다시 열어도") })
    }

    @Test
    fun `inbox triage journeys are covered`() {
        val items = itemsOf("Inbox")
        assertTrue(items.any { it.contains("정리 제안 생성") })
        assertTrue(items.any { it.contains("무리하게 Project로 추천") })
        assertTrue(items.any { it.contains("오프라인") && it.contains("기본 제안") })
        assertTrue(items.any { it.contains("제목·메모 편집") })
        assertTrue(items.any { it.contains("앞면·뒷면 편집") })
        assertTrue(items.any { it.contains("오래된 제안 거부") })
        assertTrue(items.any { it.contains("이동되거나 삭제되지 않는지") })
    }

    @Test
    fun `existing useful checks remain present`() {
        assertTrue(itemsOf("빌드/설치").any { it.contains("Gradle") })
        assertTrue(itemsOf("모델").any { it.contains("모델 라이브러리") })
        assertTrue(itemsOf("모델").any { it.contains("외부 모델 목록") && it.contains("변형") })
        assertTrue(itemsOf("모델").any { it.contains("자동·수동 성능") })
        assertTrue(itemsOf("메모리").any { it.contains("메모리 관리") })
        assertTrue(itemsOf("실험").any { it.contains("벤치마크") })
        assertTrue(itemsOf("데이터").any { it.contains("Markdown") })
        assertTrue(itemsOf("데이터").any { it.contains("백업") })
        assertTrue(itemsOf("진단").any { it.contains("상태 점검") })
        assertTrue(itemsOf("진단").any { it.contains("상태 대시보드") })
    }

    @Test
    fun `checklist makes no automated or device validation claim`() {
        val allText = groups.joinToString("\n") { group ->
            group.title + "\n" + group.items.joinToString("\n")
        }
        // The checklist is manual verification only; nothing may claim an
        // automated test, device farm, or CI ran these items.
        assertTrue(allText.isNotBlank())
        listOf("자동 테스트", "자동화", "CI", "device farm").forEach { forbidden ->
            assertTrue(
                "checklist must not claim automated validation: $forbidden",
                forbidden !in allText,
            )
        }
        // Every item reads as a manual verification step.
        groups.flatMap { it.items }.forEach { item ->
            assertTrue("item must end with 확인: $item", item.endsWith("확인"))
        }
    }
}
