package com.projectnuke.fusion.search

import java.text.Normalizer
import java.util.Locale

enum class FusionQuickAction(val title: String, val subtitle: String) {
    NEW_PROJECT("새 프로젝트", "Project 만들기"),
    OPEN_INBOX("Inbox 열기", "나중에 정리할 자료 보기"),
    MANAGE_MODELS("모델 관리", "모델 라이브러리"),
    OPEN_TRANSLATION("번역", "번역 화면 열기"),
    CAMERA_CAPTURE("카메라로 자료 추가", "촬영하고 로컬 OCR 하기"),
    UPDATE_HISTORY("업데이트 기록", "변경 사항 보기"),
    OPEN_SETTINGS("설정", "Fusion 설정 열기"),
}

/** Exact deterministic matches only; destructive project actions are not registered. */
object FusionQuickActionRegistry {
    private val aliases = linkedMapOf(
        FusionQuickAction.NEW_PROJECT to setOf("새 프로젝트", "프로젝트 만들기", "프로젝트 생성"),
        FusionQuickAction.OPEN_INBOX to setOf("inbox", "inbox 열기", "인박스", "인박스 열기"),
        FusionQuickAction.MANAGE_MODELS to setOf("모델 관리", "모델 라이브러리", "모델 관리 열기"),
        FusionQuickAction.OPEN_TRANSLATION to setOf("번역", "번역 열기"),
        FusionQuickAction.CAMERA_CAPTURE to setOf("카메라로 자료 추가", "카메라", "카메라 촬영"),
        FusionQuickAction.UPDATE_HISTORY to setOf("업데이트 기록", "업데이트 노트", "변경 사항"),
        FusionQuickAction.OPEN_SETTINGS to setOf("설정", "설정 열기"),
    )

    fun resolve(query: String): FusionQuickAction? {
        val normalized = normalize(query)
        if (normalized.isBlank()) return null
        return aliases.entries.firstOrNull { (_, phrases) -> phrases.any { normalize(it) == normalized } }?.key
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
}
