package com.projectnuke.fusion.chat

import org.junit.Assert.*
import org.junit.Test

class SelectionContextSnapshotTest {
    private fun source(kind: SelectionSourceKind, title: String, text: String, id: Long = 1L) =
        when (kind) {
            SelectionSourceKind.CONVERSATION -> SelectionSourceSnapshot(
                SelectionSourceIdentity(kind, conversationId = id), title, text,
            )
            SelectionSourceKind.PROJECT -> SelectionSourceSnapshot(
                SelectionSourceIdentity(kind, projectId = id), title, text,
            )
            SelectionSourceKind.PROJECT_DOCUMENT -> SelectionSourceSnapshot(
                SelectionSourceIdentity(kind, projectId = id, fileId = id + 10), title, text,
            )
            SelectionSourceKind.ATTACHED_DOCUMENT -> SelectionSourceSnapshot(
                SelectionSourceIdentity(kind, conversationId = id, managedPath = "managed/$id.pdf"), title, text,
            )
            SelectionSourceKind.MEMORY -> SelectionSourceSnapshot(
                SelectionSourceIdentity(kind, memoryId = "memory-$id"), title, text,
            )
            SelectionSourceKind.SAVED_KNOWLEDGE -> SelectionSourceSnapshot(
                SelectionSourceIdentity(kind, savedKnowledgeId = "saved-$id"), title, text,
            )
        }

    private fun valid(vararg sources: SelectionSourceSnapshot): SelectionContextSnapshot =
        (SelectionContextSnapshot.create(sources.toList(), createdAt = 1L) as SelectionSnapshotResult.Valid).snapshot

    @Test fun heterogeneousSelectionFreezesOnlyBoundedSelectedExcerpts() {
        val snapshot = valid(
            source(SelectionSourceKind.CONVERSATION, "대화", "conversation excerpt"),
            source(SelectionSourceKind.PROJECT_DOCUMENT, "프로젝트 문서", "document excerpt"),
            source(SelectionSourceKind.MEMORY, "메모리", "saved memory excerpt"),
        )

        val prompt = SelectionContextComposition.appendToQuestion("비교해 줘", snapshot)
        assertTrue(prompt.startsWith("비교해 줘"))
        assertTrue(prompt.contains("conversation excerpt"))
        assertTrue(prompt.contains("document excerpt"))
        assertTrue(prompt.contains("saved memory excerpt"))
        assertFalse(prompt.contains("all project files"))
        assertFalse(prompt.contains("unselected conversation"))
    }

    @Test fun duplicateIdentityAndMalformedSourceFailClosed() {
        val duplicate = source(SelectionSourceKind.CONVERSATION, "대화", "내용")
        assertEquals(SelectionSnapshotResult.Invalid, SelectionContextSnapshot.create(listOf(duplicate, duplicate), 1L))
        val malformed = SelectionSourceSnapshot(
            SelectionSourceIdentity(SelectionSourceKind.PROJECT_DOCUMENT, projectId = 2L), "문서", "내용",
        )
        assertEquals(SelectionSnapshotResult.Invalid, SelectionContextSnapshot.create(listOf(malformed), 1L))
    }

    @Test fun sourceCountAndCombinedExcerptSizeStayBounded() {
        val tooMany = (1..SelectionContextSnapshot.MAX_SOURCES + 1).map { id ->
            source(SelectionSourceKind.MEMORY, "메모 $id", "내용 $id", id.toLong())
        }
        assertEquals(SelectionSnapshotResult.Invalid, SelectionContextSnapshot.create(tooMany, 1L))
        val bounded = (1..SelectionContextSnapshot.MAX_SOURCES).map { id ->
            source(SelectionSourceKind.MEMORY, "메모 $id", "x".repeat(1_200), id.toLong())
        }
        val snapshot = (SelectionContextSnapshot.create(bounded, 1L) as SelectionSnapshotResult.Valid).snapshot
        assertTrue(snapshot.sources.sumOf { it.excerpt.length } <= SelectionContextSnapshot.MAX_TOTAL_EXCERPT_CHARS)
    }

    @Test fun crossProjectResearchAcceptsOnlyTwoToFourProjectRoots() {
        val sources = (1L..3L).map { source(SelectionSourceKind.PROJECT, "Project $it", "excerpt $it", it) }
        assertTrue(SelectionContextSnapshot.create(
            sources, 1L, purpose = SelectionContextPurpose.CROSS_PROJECT_RESEARCH,
        ) is SelectionSnapshotResult.Valid)
        assertEquals(
            SelectionSnapshotResult.Invalid,
            SelectionContextSnapshot.create(listOf(sources.first()), 1L,
                purpose = SelectionContextPurpose.CROSS_PROJECT_RESEARCH),
        )
        assertEquals(
            SelectionSnapshotResult.Invalid,
            SelectionContextSnapshot.create(
                listOf(sources[0], sources[1], source(SelectionSourceKind.CONVERSATION, "Chat", "excerpt", 9L)),
                1L, purpose = SelectionContextPurpose.CROSS_PROJECT_RESEARCH,
            ),
        )
    }

    @Test fun noteReferenceSelectionAcceptsBoundedDurableSourcesOnly() {
        val project = source(SelectionSourceKind.PROJECT_DOCUMENT, "paper.pdf", "document excerpt", 11L)
        assertTrue(SelectionContextSnapshot.create(
            listOf(project), 1L, purpose = SelectionContextPurpose.NOTE_REFERENCES,
        ) is SelectionSnapshotResult.Valid)
        assertEquals(
            SelectionSnapshotResult.Invalid,
            SelectionContextSnapshot.create(
                listOf(source(SelectionSourceKind.MEMORY, "memory", "text")), 1L,
                purpose = SelectionContextPurpose.NOTE_REFERENCES,
            ),
        )
    }

    @Test fun excerptsAndCollectionAreFrozenWithinBounds() {
        val input = mutableListOf(source(SelectionSourceKind.CONVERSATION, "제목".repeat(100), "발췌".repeat(2_000)))
        val snapshot = valid(*input.toTypedArray())
        input.clear()
        assertEquals(1, snapshot.sources.size)
        assertEquals(SelectionContextSnapshot.MAX_TITLE_CHARS, snapshot.sources.single().title.length)
        assertEquals(SelectionContextSnapshot.MAX_EXCERPT_CHARS, snapshot.sources.single().excerpt.length)
        try {
            (snapshot.sources as MutableList).clear()
            fail("snapshot source list must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun selectionOwnerRequiresFreshEpochPromotesAndResetsOnSwitch() {
        val snapshot = valid(source(SelectionSourceKind.CONVERSATION, "대화", "선택한 내용"))
        val owner = SelectionContextOwner()
        assertFalse(owner.install(snapshot, conversationId = 0L, currentEpoch = 3L, expectedEpoch = 2L))
        assertTrue(owner.install(snapshot, conversationId = 0L, currentEpoch = 3L, expectedEpoch = 3L))
        assertSame(snapshot, owner.snapshotFor(conversationId = 0L, currentEpoch = 3L))
        assertTrue(owner.promote(snapshot.id, conversationId = 27L, currentEpoch = 3L))
        assertSame(snapshot, owner.snapshotFor(conversationId = 27L, currentEpoch = 3L))
        assertNull(owner.snapshotFor(conversationId = 28L, currentEpoch = 3L))
        assertNull(owner.binding.value)
    }

    @Test fun staleClearCannotEraseNewerSelection() {
        val first = valid(source(SelectionSourceKind.CONVERSATION, "첫 자료", "첫 발췌"))
        val second = valid(source(SelectionSourceKind.MEMORY, "둘째 자료", "둘째 발췌", id = 2L))
        val owner = SelectionContextOwner()
        assertTrue(owner.install(first, 0L, 5L, 5L))
        assertTrue(owner.install(second, 0L, 5L, 5L))
        assertFalse(owner.consume(first.id, 5L))
        assertSame(second, owner.snapshotFor(0L, 5L))
        owner.clear()
        assertNull(owner.binding.value)
    }
}
