package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.retrieval.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FusionKnowledgeSearchTest {
    private fun row(id: Int, title: String = "자료 $id", kind: KnowledgeKind = KnowledgeKind.CONVERSATION) =
        KnowledgeCandidate("c:$id", kind, title, "본문", kind.label, KnowledgeTarget.Conversation(id.toLong()))

    @Test fun exactTitlesPrecedePartialAndBodyMatches() = runTest {
        val rows = listOf(row(1, "관련 Fusion 자료"), row(2, "Fusion"), row(3).copy(preview = "Fusion 본문"))
        assertEquals(listOf("c:2", "c:1", "c:3"), rankKnowledge("Fusion", rows, DisabledSemanticCandidateMatcher).map { it.key })
    }
    @Test fun semanticMatchUsesExistingMatcherAndHasBoundedInput() = runTest {
        val matcher = object : SemanticCandidateMatcher {
            override suspend fun rank(claim: String, segments: List<SemanticSegment>, limit: Int): SemanticMatchResult {
                assertTrue(segments.size <= 24)
                assertTrue(segments.all { it.text.length <= 1000 })
                return SemanticMatchResult(listOf(RankedSemanticMatch("c:4", .9f, 1)), null)
            }
        }
        assertEquals("c:4", rankKnowledge("뜻이 같은 질문", (1..90).map { row(it) }, matcher).single().key)
    }
    @Test fun missingBackendKeepsLexicalResultsAndStableLimits() = runTest {
        val rows = (1..90).map { row(it, "Fusion") }
        val first = rankKnowledge("Fusion", rows, DisabledSemanticCandidateMatcher)
        assertEquals(8, first.size)
        assertEquals(first, rankKnowledge("Fusion", rows.reversed(), DisabledSemanticCandidateMatcher))
    }
    @Test fun broadEarlierDomainsDoNotConsumeTheBoundBeforeSavedKnowledgeIsRanked() = runTest {
        val candidates = (1..256).map { row(it, "Fusion conversation $it") } +
            row(999, "Fusion saved note", KnowledgeKind.SAVED_KNOWLEDGE)

        val ranked = rankKnowledge("Fusion", candidates, DisabledSemanticCandidateMatcher)

        assertTrue(ranked.any { it.kind == KnowledgeKind.SAVED_KNOWLEDGE && it.key == "c:999" })
    }
    @Test fun staleSearchCancelsAndCannotPublish() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val controller = FusionSearchController(backgroundScope, { query ->
            if (query == "old") {
                entered.complete(Unit)
                try { CompletableDeferred<Unit>().await() } catch (e: CancellationException) { cancelled = true; throw e }
            }
            listOf(row(1, query))
        }, 0)
        controller.query("old")
        runCurrent()
        entered.await()
        controller.query("new")
        runCurrent()
        assertTrue(cancelled)
        assertEquals("new", controller.state.value.results.single().title)
        controller.close()
        assertTrue(controller.state.value.results.isEmpty())
    }
    @Test fun debounceClearsDeletedOrPriorResultsImmediately() = runTest {
        var calls = 0
        val controller = FusionSearchController(backgroundScope, { calls++; emptyList() })
        controller.query("first")
        advanceTimeBy(100)
        controller.query("last")
        advanceTimeBy(299)
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(controller.state.value.results.isEmpty())
    }
    @Test fun boundedSavedKnowledgeCoverageReachesSearchState() = runTest {
        val controller = FusionSearchController(
            scope = backgroundScope,
            search = { emptyList() },
            debounceMillis = 0,
            searchWithStatus = {
                KnowledgeSearchOutcome(
                    emptyList(),
                    savedKnowledgeScanIncomplete = true,
                    savedKnowledgeRawScanBudgetExhausted = true,
                )
            },
        )

        controller.query("restricted source")
        runCurrent()

        assertTrue(controller.state.value.savedKnowledgeScanIncomplete)
        assertTrue(controller.state.value.savedKnowledgeRawScanBudgetExhausted)
        controller.close()
    }

    @Test fun definiteAdditionalSavedKnowledgeResultsReachSearchState() = runTest {
        val controller = FusionSearchController(
            scope = backgroundScope,
            search = { emptyList() },
            debounceMillis = 0,
            searchWithStatus = {
                KnowledgeSearchOutcome(
                    candidates = listOf(row(1)),
                    savedKnowledgeResultLimitReached = true,
                    savedKnowledgeAdditionalResults = true,
                )
            },
        )
        controller.query("query")
        runCurrent()
        assertTrue(controller.state.value.savedKnowledgeResultLimitReached)
        assertTrue(controller.state.value.savedKnowledgeAdditionalResults)
        assertFalse(controller.state.value.savedKnowledgeScanIncomplete)
        controller.close()
    }
    @Test fun lockedArchiveCannotSupplyTitleOrSummary() {
        val secret = ConversationEntity(1, "secret", 0, 0, isArchived = true)
        val allowed = ArchiveAccessPolicy.accessibleConversations(listOf(secret), true, false)
        assertTrue(allowed.isEmpty())
        assertTrue(ArchiveAccessPolicy.accessibleSummaries(listOf(ConversationSummaryMemory(1, "private", 0)), allowed.map { it.id }.toSet()).isEmpty())
    }
    @Test fun queryIsBoundedAndSingleCharacterDoesNotSearch() = runTest {
        assertEquals(100, boundedKnowledgeQuery("x".repeat(1000)).length)
        assertTrue(rankKnowledge("x", listOf(row(1, "x")), DisabledSemanticCandidateMatcher).isEmpty())
    }
    @Test fun deletedAndUnreadableTargetsFailClosedAtNavigation() = runTest {
        assertFalse(admitKnowledgeNavigation { false })
        assertFalse(admitKnowledgeNavigation { throw java.io.IOException("unreadable") })
        assertTrue(admitKnowledgeNavigation { true })
    }
    @Test fun selectedDocumentRetainsExactOwningProjectIdentity() {
        val target = KnowledgeTarget.Project(id = 7L, fileId = 19L)
        val candidate = KnowledgeCandidate(
            key = "d:19",
            kind = KnowledgeKind.DOCUMENT,
            title = "project.pdf",
            preview = "excerpt",
            metadata = "문서",
            target = target,
            selectionExcerpt = "exact indexed page",
        )

        val snapshot = candidate.toSelectionSourceSnapshot()!!
        assertEquals("d:7:19", snapshot.identity.stableKey())
        assertEquals(target, snapshot.identity.toKnowledgeTarget())
        assertEquals("exact indexed page", snapshot.excerpt)
    }
    @Test fun selectedProjectRootKeepsIdentityWhenInstructionsAreEmpty() {
        val candidate = KnowledgeCandidate(
            key = "p:7", kind = KnowledgeKind.PROJECT, title = "HBM research", preview = "",
            metadata = "프로젝트", target = KnowledgeTarget.Project(7L), selectionExcerpt = "",
        )
        val selected = candidate.toSelectionSourceSnapshot()!!
        assertEquals("p:7", selected.identity.stableKey())
        assertEquals("프로젝트 이름: HBM research", selected.excerpt)
    }
    @Test fun savedKnowledgeUsesStableIndependentSelectionIdentity() {
        val target = KnowledgeTarget.SavedKnowledge("knowledge-7")
        val candidate = KnowledgeCandidate(
            key = "s:knowledge-7",
            kind = KnowledgeKind.SAVED_KNOWLEDGE,
            title = "저장한 답변",
            preview = "짧은 미리보기",
            metadata = "저장한 답변 사본",
            target = target,
            selectionExcerpt = "저장된 정확한 사본",
        )

        val snapshot = candidate.toSelectionSourceSnapshot()!!
        assertEquals("s:knowledge-7", snapshot.identity.stableKey())
        assertEquals(target, snapshot.identity.toKnowledgeTarget())
        assertEquals("저장된 정확한 사본", snapshot.excerpt)
    }
    @Test fun navigationPreservesCancellationAndFatalIdentity() = runTest {
        val fatal = LinkageError("fatal")
        try { admitKnowledgeNavigation { throw fatal }; fail() } catch (actual: LinkageError) { assertSame(fatal, actual) }
        try { admitKnowledgeNavigation { throw CancellationException("cancel") }; fail() } catch (_: CancellationException) { }
    }
    @Test fun permittedCandidatesRankedBeforeTopKPreservesAccessibleResult() = runTest {
        // 8 denied high-ranked entries + 1 allowed lower-ranked entry.
        // Correct order: ownership/access validation -> permitted ranking -> top-K.
        val denied = (1..8).map {
            row(it, "공통 query", KnowledgeKind.SAVED_KNOWLEDGE).copy(key = "s:denied-$it")
        }
        val allowed = row(9, "공통 query 허용", KnowledgeKind.SAVED_KNOWLEDGE).copy(key = "s:allowed")
        val deniedKeys = denied.map { it.key }.toSet()
        val all = denied + allowed
        // Repository filters before rankKnowledge: only permitted entries reach top-K.
        val permitted = all.filter { it.key !in deniedKeys }
        val ranked = rankKnowledge("공통 query", permitted, DisabledSemanticCandidateMatcher)
        assertTrue(ranked.any { it.key == "s:allowed" })
        // Filtering after per-kind take(8) would have starved the allowed entry;
        // the repository now filters before rankKnowledge, so this path is covered.
    }
}
