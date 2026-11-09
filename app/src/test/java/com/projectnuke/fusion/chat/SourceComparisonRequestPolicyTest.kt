package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceComparisonRequestPolicyTest {
    @Test
    fun `comparison freezes only two to four selected source groups`() {
        fun selection(size: Int) = SelectionContextSnapshot.create(
            (1..size).map { index ->
                SelectionSourceSnapshot(
                    SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = "saved-$index"),
                    "자료 $index", "발췌 $index",
                )
            },
            createdAt = 100,
        ).let { (it as SelectionSnapshotResult.Valid).snapshot }

        assertEquals(
            SourceComparisonRequestPreparation.InvalidSourceCount,
            SourceComparisonRequestPolicy.prepare(selection(1)),
        )
        assertEquals(
            SourceComparisonRequestPreparation.InvalidSourceCount,
            SourceComparisonRequestPolicy.prepare(selection(5)),
        )
        val frozen = selection(3)
        val request = SourceComparisonRequestPolicy.prepare(frozen) as SourceComparisonRequestPreparation.Ready
        assertTrue(request.request.selection === frozen)
        assertEquals(3, request.request.selection.sources.size)
    }

    @Test
    fun `comparison requires source identity and guards contradiction and winner claims`() {
        assertTrue(SourceComparisonRequestPolicy.COMPOSER_TEXT.contains("[자료 번호: 제목]"))
        assertTrue(SourceComparisonRequestPolicy.COMPOSER_TEXT.contains("같은 사실 질문"))
        assertTrue(SourceComparisonRequestPolicy.COMPOSER_TEXT.contains("승자로 정하지 마세요"))
        assertTrue(SourceComparisonRequestPolicy.COMPOSER_TEXT.contains("외부 검색을 추가하지 마세요"))
    }
}
