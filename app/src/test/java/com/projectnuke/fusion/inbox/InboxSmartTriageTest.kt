package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.retrieval.DisabledSymmetricEmbeddingSource
import com.projectnuke.fusion.retrieval.EmbeddingFailureReason
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InboxSmartTriageTest {

    // ---------- fakes ----------

    private class FakeDataSource(
        var item: InboxItemEntity? = null,
        var projects: List<SmartProjectRow> = emptyList(),
        var knowledgeTitles: Map<Long, List<String>> = emptyMap(),
        var taskTitles: Map<Long, List<String>> = emptyMap(),
        var tags: List<SmartTagRow> = emptyList(),
        var linkedKeys: Set<String> = emptySet(),
    ) : InboxSmartTriageDataSource {
        val calls = mutableListOf<String>()
        var recentProjectsLimit = -1
        val scriptedItems = ArrayDeque<InboxItemEntity?>()
        var existingTagsLimit = -1

        override suspend fun getInboxItem(id: String): InboxItemEntity? {
            calls += "getInboxItem"
            return if (scriptedItems.isNotEmpty()) scriptedItems.removeFirst() else item
        }

        override suspend fun recentProjects(limit: Int): List<SmartProjectRow> {
            calls += "recentProjects"
            recentProjectsLimit = limit
            return projects.take(limit)
        }

        override suspend fun recentKnowledgeTitles(projectId: Long, limit: Int): List<String> {
            calls += "recentKnowledgeTitles"
            return knowledgeTitles[projectId].orEmpty().take(limit)
        }

        override suspend fun recentTaskTitles(projectId: Long, limit: Int): List<String> {
            calls += "recentTaskTitles"
            return taskTitles[projectId].orEmpty().take(limit)
        }

        override suspend fun existingTags(limit: Int): List<SmartTagRow> {
            calls += "existingTags"
            existingTagsLimit = limit
            return tags.take(limit)
        }

        override suspend fun inboxTagKeys(itemId: String): Set<String> {
            calls += "inboxTagKeys"
            return linkedKeys
        }
    }

    private class FakeEmbeddings(
        val handler: (List<String>) -> EmbeddingResult,
    ) : SymmetricEmbeddingSource {
        var calls = 0
        var maxBatch = 0
        override suspend fun embedSources(texts: List<String>): EmbeddingResult {
            calls++
            maxBatch = maxOf(maxBatch, texts.size)
            return handler(texts)
        }
    }

    private fun keywordEmbeddings(): FakeEmbeddings = FakeEmbeddings { texts ->
        EmbeddingResult.Success(texts.map { keywordVector(it) }, 4)
    }

    private fun keywordVector(text: String): FloatArray {
        val t = text.lowercase()
        return when {
            t.contains("fusion") -> floatArrayOf(1f, 0f, 0f, 0f)
            t.contains("온디바이스") -> floatArrayOf(0.9f, 0.4359f, 0f, 0f)
            t.contains("요리") -> floatArrayOf(0f, 1f, 0f, 0f)
            else -> floatArrayOf(0f, 0f, 1f, 0f)
        }
    }

    private fun item(
        id: String = "inbox-1",
        type: String = "NOTE",
        title: String = "제목",
        content: String = "내용",
        managedFileId: String? = null,
        updatedAt: Long = 100L,
    ) = InboxItemEntity(
        id = id,
        captureType = type,
        title = title,
        content = content,
        originalUri = if (type == "URL" || type == "WEB_CLIP") "https://example.com/a" else null,
        managedFileId = managedFileId,
        managedMimeType = managedFileId?.let { "application/pdf" },
        sourceHandoffEventId = null,
        createdAt = 100L,
        updatedAt = updatedAt,
        processingState = "READY",
    )

    private fun substantialFusionNote(): InboxItemEntity {
        val body = ("Fusion 온디바이스AI 프로젝트 정리. " +
            "로컬 검색과 임베딩을 이용해 자료를 정리하는 방법에 대한 긴 참고 기록입니다. ").repeat(12)
        return item(title = "Fusion 정리 기록", content = body)
    }

    // ---------- deterministic classifier (required 1-6) ----------

    @Test
    fun `file-backed DOCUMENT is never converted to text-only knowledge`() {
        val doc = item(
            type = "DOCUMENT",
            title = "회의록.pdf",
            content = "OCR로 읽힌 긴 텍스트. ".repeat(40),
            managedFileId = "managed-1",
        )
        val classification = InboxSmartTriagePolicy.classify(doc)
        assertEquals(InboxSmartActionKind.PROJECT, classification.primaryAction)
    }

    @Test
    fun `clear action-like text may recommend PROJECT_TASK`() {
        val task = item(title = "측정", content = "Verifier latency 측정하고 확인 결과를 제출해야 함. 내일까지.")
        assertEquals(InboxSmartActionKind.PROJECT_TASK, InboxSmartTriagePolicy.classify(task).primaryAction)
    }

    @Test
    fun `imperative sentence without task signals is not a task`() {
        val note = item(title = "메모", content = "창문을 닫아라.")
        assertEquals(InboxSmartActionKind.KEEP_IN_INBOX, InboxSmartTriagePolicy.classify(note).primaryAction)
    }

    @Test
    fun `weak ambiguous text falls back to KEEP_IN_INBOX`() {
        val weak = item(title = "메모", content = "잠시 보관")
        assertEquals(InboxSmartActionKind.KEEP_IN_INBOX, InboxSmartTriagePolicy.classify(weak).primaryAction)
    }

    @Test
    fun `short concept explanation may recommend REVIEW_CARD`() {
        val concept = item(title = "양자 얽힘", content = "양자 얽힘이란 두 입자가 거리에 상관없이 연결된 상태를 의미한다. 핵심 개념 정리.")
        assertEquals(InboxSmartActionKind.REVIEW_CARD, InboxSmartTriagePolicy.classify(concept).primaryAction)
    }

    @Test
    fun `long document never defaults to review card`() {
        val long = item(title = "긴 문서", content = "개념 설명을 포함한 아주 긴 문서. ".repeat(80))
        val primary = InboxSmartTriagePolicy.classify(long).primaryAction
        assertFalse(primary == InboxSmartActionKind.REVIEW_CARD)
    }

    @Test
    fun `substantial WEB_CLIP recommends SAVED_KNOWLEDGE`() {
        val clip = item(type = "WEB_CLIP", title = "기사", content = "웹에서 가져온 긴 참고 자료. ".repeat(30))
        assertEquals(InboxSmartActionKind.SAVED_KNOWLEDGE, InboxSmartTriagePolicy.classify(clip).primaryAction)
    }

    @Test
    fun `thin URL falls back to KEEP_IN_INBOX`() {
        val thin = item(type = "URL", title = "링크", content = "짧음")
        assertEquals(InboxSmartActionKind.KEEP_IN_INBOX, InboxSmartTriagePolicy.classify(thin).primaryAction)
    }

    // ---------- bounds (required 7-12) ----------

    @Test
    fun `item input text is bounded`() {
        val huge = item(content = "x".repeat(50_000))
        assertTrue(InboxSmartTriagePolicy.boundItemText(huge).length <= InboxSmartTriageLimits.MAX_ITEM_TEXT_CHARS)
    }

    @Test
    fun `project profile text is bounded`() {
        val profile = InboxSmartTriagePolicy.buildProjectProfile(
            "P".repeat(500),
            List(20) { "지식 제목 ".repeat(50) },
            List(20) { "할 일 ".repeat(50) },
            "안내 ".repeat(500),
        )
        assertTrue(profile.length <= InboxSmartTriageLimits.MAX_PROJECT_PROFILE_CHARS)
        assertTrue(InboxSmartTriageLimits.MAX_PROJECT_PROFILE_CHARS in 512..1000)
    }

    @Test
    fun `limits match the specified contract`() {
        assertEquals(3, InboxSmartTriageLimits.MAX_PROJECT_CANDIDATES)
        assertEquals(4, InboxSmartTriageLimits.MAX_TAG_SUGGESTIONS)
        assertEquals(2000, InboxSmartTriageLimits.MAX_ITEM_TEXT_CHARS)
        assertEquals(64, InboxSmartTriageLimits.MAX_PROJECTS_CONSIDERED)
        assertEquals(128, InboxSmartTriageLimits.MAX_EXISTING_TAGS_CONSIDERED)
    }

    @Test
    fun `output projects and tags are capped and deduplicated`() = runTest {
        val source = FakeDataSource(
            item = substantialFusionNote(),
            projects = (1L..10L).map { SmartProjectRow(it, "Fusion 프로젝트 $it", "Fusion 안내") },
            knowledgeTitles = (1L..10L).associateWith { listOf("Fusion 자료") },
            tags = listOf(
                SmartTagRow("fusion", "Fusion"),
                SmartTagRow("fusion-dup", "fusion"),
                SmartTagRow("ondevice", "온디바이스AI"),
                SmartTagRow("extra1", "Fusion 추가1"),
                SmartTagRow("extra2", "Fusion 추가2"),
                SmartTagRow("extra3", "Fusion 추가3"),
                SmartTagRow("extra4", "Fusion 추가4"),
            ),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(result is InboxSmartTriageResult.Ready)
        val proposal = (result as InboxSmartTriageResult.Ready).proposal
        assertTrue(proposal.projectCandidates.size <= InboxSmartTriageLimits.MAX_PROJECT_CANDIDATES)
        assertTrue(proposal.tagSuggestions.size <= InboxSmartTriageLimits.MAX_TAG_SUGGESTIONS)
        assertEquals(proposal.projectCandidates.map { it.projectId }.distinct().size, proposal.projectCandidates.size)
        assertEquals(proposal.tagSuggestions.map { it.value }.distinct().size, proposal.tagSuggestions.size)
    }

    @Test
    fun `projects considered are bounded`() = runTest {
        val source = FakeDataSource(
            item = substantialFusionNote(),
            projects = (1L..100L).map { SmartProjectRow(it, "프로젝트 $it", "") },
        )
        InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(source.recentProjectsLimit in 1..InboxSmartTriageLimits.MAX_PROJECTS_CONSIDERED)
        assertTrue(source.existingTagsLimit in 1..InboxSmartTriageLimits.MAX_EXISTING_TAGS_CONSIDERED)
    }

    // ---------- semantic ranking (required 13-18) ----------

    @Test
    fun `best relevant candidate is ranked first`() = runTest {
        val source = FakeDataSource(
            item = substantialFusionNote(),
            projects = listOf(
                SmartProjectRow(2, "요리 연구", "요리 안내"),
                SmartProjectRow(1, "Fusion", "Fusion 온디바이스AI 안내"),
            ),
            knowledgeTitles = mapOf(1L to listOf("Fusion 자료"), 2L to listOf("요리 자료")),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(result is InboxSmartTriageResult.Ready)
        val candidates = (result as InboxSmartTriageResult.Ready).proposal.projectCandidates
        assertFalse(candidates.isEmpty())
        assertEquals(1L, candidates.first().projectId)
    }

    @Test
    fun `raw semantic scores are never persisted or exposed`() {
        val fields = InboxSmartTriageProposal::class.java.declaredFields
        assertTrue(fields.none { it.type == Float::class.java || it.type == Double::class.java })
        assertTrue(fields.none { it.name.contains("score", ignoreCase = true) })
    }

    @Test
    fun `semantic failure falls back to deterministic suggestions`() = runTest {
        val source = FakeDataSource(
            item = substantialFusionNote(),
            projects = listOf(SmartProjectRow(1, "Fusion", "")),
        )
        val failing = FakeEmbeddings { EmbeddingResult.Failure(EmbeddingFailureReason.INFERENCE_FAILURE, "x") }
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", failing)
        assertTrue(result is InboxSmartTriageResult.Ready)
        val proposal = (result as InboxSmartTriageResult.Ready).proposal
        assertFalse(proposal.semanticAvailable)
        assertEquals(InboxSmartActionKind.SAVED_KNOWLEDGE, proposal.primaryAction)
        assertTrue(proposal.projectCandidates.isEmpty())
    }

    @Test
    fun `semantic empty result does not block deterministic action`() = runTest {
        val source = FakeDataSource(item = substantialFusionNote())
        val zeroes = FakeEmbeddings { texts -> EmbeddingResult.Success(texts.map { FloatArray(4) }, 4) }
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", zeroes)
        assertTrue(result is InboxSmartTriageResult.Ready)
        assertEquals(
            InboxSmartActionKind.SAVED_KNOWLEDGE,
            (result as InboxSmartTriageResult.Ready).proposal.primaryAction,
        )
    }

    @Test
    fun `offline disabled embeddings still yield deterministic proposal`() = runTest {
        val source = FakeDataSource(item = substantialFusionNote())
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", DisabledSymmetricEmbeddingSource)
        assertTrue(result is InboxSmartTriageResult.Ready)
        assertFalse((result as InboxSmartTriageResult.Ready).proposal.semanticAvailable)
    }

    @Test
    fun `cancellation propagates`() = runTest {
        val source = FakeDataSource(
            item = substantialFusionNote(),
            projects = listOf(SmartProjectRow(1, "Fusion", "")),
            tags = listOf(SmartTagRow("fusion", "Fusion")),
        )
        val cancelling = FakeEmbeddings { throw CancellationException("stop") }
        try {
            InboxSmartTriageCoordinator(source).propose("inbox-1", cancelling)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // Required: cancellation is never swallowed into a fallback.
        }
    }

    @Test
    fun `deleted project can never appear as a candidate`() = runTest {
        val source = FakeDataSource(
            item = item(type = "DOCUMENT", title = "문서", content = "본문", managedFileId = "m-1"),
            projects = emptyList(),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(result is InboxSmartTriageResult.Ready)
        val proposal = (result as InboxSmartTriageResult.Ready).proposal
        assertTrue(proposal.projectCandidates.isEmpty())
        // No reasonable Project candidate exists -> safe fallback.
        assertEquals(InboxSmartActionKind.KEEP_IN_INBOX, proposal.primaryAction)
    }

    @Test
    fun `strong semantic relevance may outrank URL default`() = runTest {
        val clip = item(type = "WEB_CLIP", title = "Fusion 기사", content = ("Fusion 온디바이스AI 관련 긴 기사 본문. ").repeat(20))
        val source = FakeDataSource(
            item = clip,
            projects = listOf(SmartProjectRow(7, "Fusion", "Fusion 안내")),
            knowledgeTitles = mapOf(7L to listOf("Fusion 자료")),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(result is InboxSmartTriageResult.Ready)
        val proposal = (result as InboxSmartTriageResult.Ready).proposal
        assertEquals(InboxSmartActionKind.PROJECT, proposal.primaryAction)
        assertEquals(7L, proposal.projectCandidates.first().projectId)
    }

    // ---------- tags (required 19-22) ----------

    @Test
    fun `only existing tags are suggested`() = runTest {
        val tagged = item(title = "기록", content = ("Fusion 온디바이스AI와 존재하지않는신조어태그에 대한 긴 기록. ").repeat(15))
        val source = FakeDataSource(
            item = tagged,
            tags = listOf(SmartTagRow("fusion", "Fusion"), SmartTagRow("ondevice", "온디바이스AI")),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(result is InboxSmartTriageResult.Ready)
        val values = (result as InboxSmartTriageResult.Ready).proposal.tagSuggestions.map { it.value }
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it == "Fusion" || it == "온디바이스AI" })
    }

    @Test
    fun `already-linked tags are excluded`() = runTest {
        val source = FakeDataSource(
            item = substantialFusionNote(),
            tags = listOf(SmartTagRow("fusion", "Fusion"), SmartTagRow("ondevice", "온디바이스AI")),
            linkedKeys = setOf("fusion"),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(result is InboxSmartTriageResult.Ready)
        val values = (result as InboxSmartTriageResult.Ready).proposal.tagSuggestions.map { it.value }
        assertFalse(values.contains("Fusion"))
    }

    // ---------- ownership / fencing (required 23-27) ----------

    @Test
    fun `duplicate analysis for same revision is suppressed`() = runTest {
        val source = FakeDataSource(item = substantialFusionNote())
        val owner = InboxSmartTriageFlightOwner()
        val key = InboxSmartTriagePolicy.keyFor(source.item!!)
        assertTrue(owner.acquire(key))
        val result = InboxSmartTriageCoordinator(source, owner).propose("inbox-1", keywordEmbeddings())
        assertEquals(InboxSmartTriageResult.DuplicateSuppressed, result)
        owner.release(key)
    }

    @Test
    fun `different revisions have independent ownership`() {
        val owner = InboxSmartTriageFlightOwner()
        val first = item(updatedAt = 100L)
        val second = first.copy(updatedAt = 101L, content = "바뀐 내용")
        val keyA = InboxSmartTriagePolicy.keyFor(first)
        val keyB = InboxSmartTriagePolicy.keyFor(second)
        assertTrue(keyA != keyB)
        assertTrue(owner.acquire(keyA))
        assertTrue(owner.acquire(keyB))
        owner.release(keyA)
        owner.release(keyB)
    }

    @Test
    fun `edited item makes the proposal stale`() = runTest {
        val original = substantialFusionNote()
        val source = FakeDataSource(item = original)
        val ready = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(ready is InboxSmartTriageResult.Ready)
        val proposal = (ready as InboxSmartTriageResult.Ready).proposal
        val edited = original.copy(content = original.content + " 추가", updatedAt = 101L)
        assertTrue(InboxSmartTriagePolicy.isStale(proposal, edited))
        assertNull(InboxSmartTriageCoordinator.freshItemFor(source, proposal, edited))
        assertNull(InboxSmartTriageCoordinator.freshItemFor(source, proposal, original.copy(updatedAt = 999L)))
    }

    @Test
    fun `deletion during ranking prevents publication`() = runTest {
        val source = FakeDataSource(item = substantialFusionNote())
        source.scriptedItems.add(source.item)
        source.scriptedItems.add(null)
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertEquals(InboxSmartTriageResult.Stale, result)
    }

    @Test
    fun `missing item yields stale, never a proposal`() = runTest {
        val source = FakeDataSource(item = null)
        assertEquals(
            InboxSmartTriageResult.Stale,
            InboxSmartTriageCoordinator(source).propose("missing", keywordEmbeddings()),
        )
    }

    @Test
    fun `disposal cancels obsolete ranking work`() = runTest {
        val source = FakeDataSource(item = substantialFusionNote())
        val hanging = FakeEmbeddings {
            throw CancellationException("disposed")
        }
        val deferred = async { InboxSmartTriageCoordinator(source).propose("inbox-1", hanging) }
        deferred.cancel()
        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `proposals never cross items`() = runTest {
        val sourceA = FakeDataSource(item = item(id = "a", title = "A", content = ("Fusion 관련 긴 기록 A. ").repeat(20)))
        val sourceB = FakeDataSource(item = item(id = "b", title = "B", content = "짧은 보관"))
        val coordinatorA = InboxSmartTriageCoordinator(sourceA)
        val coordinatorB = InboxSmartTriageCoordinator(sourceB)
        val resultA = coordinatorA.propose("a", keywordEmbeddings())
        val resultB = coordinatorB.propose("b", keywordEmbeddings())
        assertEquals("a", (resultA as InboxSmartTriageResult.Ready).proposal.itemId)
        assertEquals("b", (resultB as InboxSmartTriageResult.Ready).proposal.itemId)
        assertEquals(
            InboxSmartActionKind.KEEP_IN_INBOX,
            (resultB as InboxSmartTriageResult.Ready).proposal.primaryAction,
        )
    }

    // ---------- mutation boundary (required 28-33) ----------

    @Test
    fun `proposal generation performs zero mutations`() = runTest {
        val before = substantialFusionNote()
        val source = FakeDataSource(
            item = before,
            projects = listOf(SmartProjectRow(1, "Fusion", "")),
            knowledgeTitles = mapOf(1L to listOf("Fusion 자료")),
            tags = listOf(SmartTagRow("fusion", "Fusion")),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        assertTrue(result is InboxSmartTriageResult.Ready)
        // Read-only seam: every recorded call is a read; no write API exists.
        val reads = setOf(
            "getInboxItem", "recentProjects", "recentKnowledgeTitles",
            "recentTaskTitles", "existingTags", "inboxTagKeys",
        )
        assertTrue(source.calls.all { it in reads })
        // Source item untouched.
        assertEquals(before, source.item)
        // The seam itself exposes no mutation operations by construction.
        val mutating = InboxSmartTriageDataSource::class.java.methods.filter { method ->
            val name = method.name.lowercase()
            listOf("insert", "delete", "update", "add", "set", "move", "create", "remove", "clear", "write")
                .any { name.contains(it) }
        }
        assertTrue(mutating.isEmpty())
    }

    // ---------- confirmed-action routing (required 34-39) ----------

    @Test
    fun `project candidates are always existing projects`() = runTest {
        val source = FakeDataSource(
            item = substantialFusionNote(),
            projects = listOf(SmartProjectRow(1, "Fusion", "")),
            knowledgeTitles = mapOf(1L to listOf("Fusion 자료")),
        )
        val result = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        val candidates = (result as InboxSmartTriageResult.Ready).proposal.projectCandidates
        assertTrue(candidates.all { it.projectId == 1L })
    }

    @Test
    fun `stale proposal can never authorize a mutation`() = runTest {
        val original = substantialFusionNote()
        val source = FakeDataSource(item = original)
        val ready = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        val proposal = (ready as InboxSmartTriageResult.Ready).proposal
        // Item edited after the proposal: every confirmed path must refuse.
        val edited = original.copy(title = "바뀐 제목", updatedAt = 777L)
        source.item = edited
        assertNull(InboxSmartTriageCoordinator.freshItemFor(source, proposal, original))
        assertNull(InboxSmartTriageCoordinator.freshItemFor(source, proposal, edited))
    }

    @Test
    fun `fresh proposal authorizes its exact snapshot only`() = runTest {
        val original = substantialFusionNote()
        val source = FakeDataSource(item = original)
        val ready = InboxSmartTriageCoordinator(source).propose("inbox-1", keywordEmbeddings())
        val proposal = (ready as InboxSmartTriageResult.Ready).proposal
        assertNotNull(InboxSmartTriageCoordinator.freshItemFor(source, proposal, original))
    }

    // ---------- privacy / egress (required 40-44) ----------

    private fun triageSources(): List<File> {
        val roots = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.take(4).toList()
        val names = listOf("InboxSmartTriage.kt", "InboxSmartTriageCoordinator.kt", "InboxSmartTriageDialog.kt", "InboxSettlementGateways.kt")
        return names.map { name ->
            roots.map { File(it, "src/main/java/com/projectnuke/fusion/inbox/$name") }.firstOrNull { it.isFile }
                ?: roots.map { File(it, "app/src/main/java/com/projectnuke/fusion/inbox/$name") }.firstOrNull { it.isFile }
                ?: throw AssertionError("triage source not found: $name (roots=$roots)")
        }
    }

    @Test
    fun `smart triage performs zero external AI or web calls`() {
        val forbidden = listOf(
            "FusionWebSearch", "ExternalAi", "EXTERNAL_AI", "DataEgress",
            "Http", "OkHttp", "Retrofit", "WebView", "Llm", "LLM",
            "ExecutionProfile", "executionProfile",
        )
        triageSources().forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("${file.name} must not reference $token", text.contains(token))
            }
        }
    }

    @Test
    fun `smart triage owns no Room migration`() {
        triageSources().forEach { file ->
            assertFalse("${file.name} must not migrate Room", file.readText().contains("Migration("))
        }
    }

    @Test
    fun `coordinator exposes no profile or egress seam`() {
        val names = InboxSmartTriageCoordinator::class.java.methods.map { it.name.lowercase() } +
            InboxSmartTriageDataSource::class.java.methods.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("profile") || it.contains("egress") || it.contains("cloud") })
    }
}
