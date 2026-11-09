package com.projectnuke.fusion.ui

/**
 * Drawer information-architecture model, kept pure and host-testable.
 *
 * The primary drawer utility section exposes three entries — 실험실(Lab),
 * 아카이브(Archive), 설정(Settings). Lab is a navigation composition that
 * groups the advanced experimental tools (Benchmark + Prompt Lab) behind one
 * entry instead of consuming separate primary drawer slots. It does not merge
 * their internal architectures; it is only information architecture.
 */
internal object DrawerNavigationModel {
    const val LAB_TITLE = "실험실"
    const val LAB_SUBTITLE = "모델과 프롬프트를 실험합니다."

    const val UTILITIES_SECTION = "유틸리티"
    const val ARCHIVE_TITLE = "아카이브"
    const val ARCHIVE_SUBTITLE = "보관한 채팅을 확인하고 복원합니다."
    const val SETTINGS_TITLE = "설정"
    const val SETTINGS_SUBTITLE = "모델, 생성, 데이터 관련 설정을 관리합니다."

    /** Experimental destinations hosted inside the Lab surface, in order. */
    val labDestinations: List<LabDestination> = LabDestination.entries
}

/** Experimental tools reachable only through the 실험실(Lab) surface. */
internal enum class LabDestination(
    val title: String,
    val subtitle: String,
    val leading: String,
) {
    BENCHMARK("벤치마크", "선택한 모델의 성능을 측정합니다.", "B"),
    PROMPT_LAB("Prompt Lab", "시스템 프롬프트와 응답 스타일을 조정합니다.", "L"),
}

/**
 * Global key namespace for heterogeneous dynamic item families that share a
 * single LazyColumn (e.g. the unified HOME drawer list, where Project rows and
 * Conversation rows are both dynamic Long-keyed entities). ProjectEntity ids
 * and ConversationEntity ids come from independent database tables and can
 * overlap; explicit LazyColumn keys must be unique across the WHOLE list, so
 * each family gets a stable immutable namespaced identity. Never list indices.
 */
internal object DrawerItemKey {
    fun project(projectId: Long): String = "project:$projectId"

    fun conversation(conversationId: Long): String = "conversation:$conversationId"
}