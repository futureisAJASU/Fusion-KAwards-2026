package com.projectnuke.fusion.chat

import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentSource
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.util.visibleMessageContent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Next Action Flow v1C — Answer Context Receipt: codec, execution truth,
 * privacy, canonical stripping, version ownership, and presentation.
 */
class AnswerContextReceiptTest {

    private fun receipt(
        route: AnswerContextRoute = AnswerContextRoute.LOCAL_MODEL,
        interactionMode: String? = "NORMAL",
        prior: Boolean? = true,
        projectUsed: Boolean = false,
        instructions: Boolean = false,
        attachments: Int = 1,
        images: Int = 0,
        ragDocs: Int = 0,
        ragChunks: Int = 0,
        web: Int = 2,
    ) = AnswerContextReceipt.build(
        route = route,
        interactionMode = interactionMode,
        priorConversationContextProvided = prior,
        projectContextUsed = projectUsed,
        projectInstructionsApplied = instructions,
        inputAttachmentCount = attachments,
        inputImageCount = images,
        localRagDocumentCount = ragDocs,
        localRagChunkCount = ragChunks,
        webSourceCount = web,
    )

    private fun project(instructions: String = "") = ProjectContextSnapshot(
        projectId = 7L,
        instructions = instructions,
        files = emptyList(),
    )

    private fun ragSelection(docId: String, attachmentId: String): LocalRagSelection =
        LocalRagSelection(
            chunk = LocalDocumentChunk(
                docId = docId,
                displayName = "참고 $docId.txt",
                chunkIndex = 0,
                startOffset = 0,
                endOffset = 10,
                contentHashHex = "hash-$docId",
                text = "내용 $docId",
                pageNumber = null,
            ),
            source = LocalDocumentSource(
                docId = docId,
                displayName = "참고 $docId.txt",
                attachmentId = attachmentId,
                chunkIndex = 0,
                startOffset = 0,
                endOffset = 10,
                contentHashHex = "hash-$docId",
                excerpt = "발췌 $docId",
                pageNumber = null,
            ),
            lexicalScore = 8,
            semanticScore = null,
            rank = 1,
        )

    // ── CODEC ──────────────────────────────────────────────────────────────

    @Test
    fun `valid encode decode round trip`() {
        val original = receipt(web = 3, ragDocs = 2, ragChunks = 4)
        val encoded = AnswerContextReceipt.encode(original)!!
        val decoded = AnswerContextReceipt.parse(encoded)!!
        assertEquals(original, decoded)
    }

    @Test
    fun `receipt exposes only whether active project decision context was included`() {
        val original = AnswerContextReceipt.build(
            route = AnswerContextRoute.LOCAL_MODEL,
            projectContextUsed = true,
            projectDecisionContextIncluded = true,
        )
        val encoded = AnswerContextReceipt.encode(original)!!
        val decoded = AnswerContextReceipt.parse(encoded)!!
        assertTrue(decoded.projectDecisionContextIncluded)
        assertTrue(AnswerContextPresentation.inspectorRows(decoded).any { it.section == "프로젝트 결정" })
        assertFalse(encoded.contains("현재 결정"))
    }

    @Test
    fun `unknown future schema fails closed`() {
        val encoded = AnswerContextReceipt.encode(receipt())!!
        val future = encoded.replace("\"schemaVersion\":${AnswerContextReceipt.SCHEMA_VERSION}", "\"schemaVersion\":99")
        assertNull(AnswerContextReceipt.parse(future))
    }

    @Test
    fun `schema v1 receipt remains readable without refresh scope metadata`() {
        val current = AnswerContextReceipt.encode(receipt())!!
        val json = JSONObject(current.substringAfter(AnswerContextReceipt.START_MARKER).substringBefore(AnswerContextReceipt.END_MARKER).trim())
        json.put("schemaVersion", 1)
        listOf("projectContextId", "sourceScopeKind", "sourceScopeProjectId", "sourceScopeFileIds", "selectionProjectIds")
            .forEach(json::remove)
        val old = "${AnswerContextReceipt.START_MARKER}\n$json\n${AnswerContextReceipt.END_MARKER}"

        val decoded = AnswerContextReceipt.parse(old)
        assertEquals(1, decoded?.schemaVersion)
        assertTrue(decoded?.sourceScopeFileIds.orEmpty().isEmpty())
        assertTrue(decoded?.selectionProjectIds.orEmpty().isEmpty())
    }

    @Test
    fun `execution receipt freezes project source scope identities`() {
        val selection = (SelectionContextSnapshot.create(
            sources = listOf(
                SelectionSourceSnapshot(SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = 8), "first", "source one"),
                SelectionSourceSnapshot(SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = 9), "second", "source two"),
            ),
            createdAt = 10,
        ) as SelectionSnapshotResult.Valid).snapshot
        val original = AnswerContextReceipt.buildFromExecution(
            route = AnswerContextRoute.LOCAL_MODEL,
            interactionMode = ChatInteractionMode.RESEARCH,
            projectContext = project(),
            sourceScope = ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, 7, listOf(21, 22)),
            selectionContext = selection,
        )
        val decoded = AnswerContextReceipt.parse(AnswerContextReceipt.encode(original)!!)

        assertEquals(7L, decoded?.projectContextId)
        assertEquals(ProjectSourceScopeKind.SELECTED_FILES.name, decoded?.sourceScopeKind)
        assertEquals(7L, decoded?.sourceScopeProjectId)
        assertEquals(listOf(21L, 22L), decoded?.sourceScopeFileIds)
        assertEquals(listOf(8L, 9L), decoded?.selectionProjectIds)
    }

    @Test
    fun `malformed receipt fails closed`() {
        val content = "답변\n\n[FUSION_ANSWER_CONTEXT_JSON]\n{not json!!\n[/FUSION_ANSWER_CONTEXT_JSON]"
        assertNull(AnswerContextReceipt.parse(content))
        assertNull(AnswerContextReceipt.parse("no marker at all"))
        assertNull(AnswerContextReceipt.parse("앞[FUSION_ANSWER_CONTEXT_JSON]뒤없음"))
    }

    @Test
    fun `bounded scalar validation rejects out of range`() {
        val encoded = AnswerContextReceipt.encode(receipt())!!
        val huge = encoded.replace("\"webSourceCount\":2", "\"webSourceCount\":999999999")
        assertNull(AnswerContextReceipt.parse(huge))
        val negative = encoded.replace("\"webSourceCount\":2", "\"webSourceCount\":-3")
        assertNull(AnswerContextReceipt.parse(negative))
    }

    @Test
    fun `current schema rejects missing required fields and wrong json types`() {
        val encoded = AnswerContextReceipt.encode(receipt())!!
        assertNull(AnswerContextReceipt.parse(encoded.replace("\"webSourceCount\":2", "")))
        assertNull(AnswerContextReceipt.parse(encoded.replace("\"webSourceCount\":2", "\"webSourceCount\":\"2\"")))
        assertNull(AnswerContextReceipt.parse(encoded.replace("\"projectContextUsed\":false", "\"projectContextUsed\":\"false\"")))
    }

    @Test
    fun `invalid interaction mode and impossible project state fail closed`() {
        val encoded = AnswerContextReceipt.encode(receipt())!!
        assertNull(AnswerContextReceipt.parse(encoded.replace("\"interactionMode\":\"NORMAL\"", "\"interactionMode\":\"UNKNOWN\"")))
        assertNull(AnswerContextReceipt.parse(encoded.replace("\"projectInstructionsApplied\":false", "\"projectInstructionsApplied\":true")))
    }

    @Test
    fun `json null interaction mode remains kotlin null`() {
        val encoded = AnswerContextReceipt.encode(receipt(interactionMode = null))!!
        assertNull(AnswerContextReceipt.parse(encoded)!!.interactionMode)
    }

    @Test
    fun `build clamps negative counts and strips identifies exactly`() {
        val built = AnswerContextReceipt.build(
            route = AnswerContextRoute.LOCAL_MODEL,
            inputAttachmentCount = -5,
            webSourceCount = 100_000,
        )
        assertEquals(0, built.inputAttachmentCount)
        assertEquals(AnswerContextReceipt.MAX_COUNT, built.webSourceCount)
    }

    @Test
    fun `strip preserves the visible answer exactly`() {
        val encoded = AnswerContextReceipt.encode(receipt())!!
        val content = "핵심 답변입니다.\n\n$encoded\n\n추가 줄"
        val stripped = AnswerContextReceipt.strip(content)
        assertFalse(stripped.contains("FUSION_ANSWER_CONTEXT_JSON"))
        assertFalse(stripped.contains("schemaVersion"))
        assertTrue(stripped.contains("핵심 답변입니다."))
        assertTrue(stripped.contains("추가 줄"))
    }

    // ── EXECUTION TRUTH ────────────────────────────────────────────────────

    @Test
    fun `local response routes local model`() {
        val r = AnswerContextReceipt.buildFromExecution(
            route = AnswerContextRoute.LOCAL_MODEL, projectContext = project(),
        )
        assertEquals(AnswerContextRoute.LOCAL_MODEL, r.route)
    }

    @Test
    fun `external response routes external ai`() {
        val r = AnswerContextReceipt.buildFromExecution(route = AnswerContextRoute.EXTERNAL_AI_API)
        assertEquals(AnswerContextRoute.EXTERNAL_AI_API, r.route)
    }

    @Test
    fun `project absent does not claim project use`() {
        val r = AnswerContextReceipt.buildFromExecution(route = AnswerContextRoute.LOCAL_MODEL, projectContext = null)
        assertFalse(r.projectContextUsed)
        assertFalse(r.projectInstructionsApplied)
    }

    @Test
    fun `nonblank project instructions are applied flag true`() {
        val withInstructions = AnswerContextReceipt.buildFromExecution(
            route = AnswerContextRoute.LOCAL_MODEL, projectContext = project("반드시 준수"),
        )
        assertTrue(withInstructions.projectContextUsed)
        assertTrue(withInstructions.projectInstructionsApplied)
    }

    @Test
    fun `zero adopted search sources are not claimed`() {
        val r = AnswerContextReceipt.buildFromExecution(route = AnswerContextRoute.LOCAL_MODEL, webSourceCount = 0)
        assertEquals(0, r.webSourceCount)
        assertFalse(AnswerContextPresentation.inspectorRows(r).any { it.section == "웹" })
    }

    @Test
    fun `selected local rag chunks produce correct counts`() {
        val selections = listOf(
            ragSelection("a", "att-a"),
            ragSelection("a", "att-a"),
            ragSelection("b", "att-b"),
        )
        val r = AnswerContextReceipt.buildFromExecution(
            route = AnswerContextRoute.LOCAL_MODEL, ragSelections = selections,
        )
        assertEquals(2, r.localRagDocumentCount)
        assertEquals(3, r.localRagChunkCount)
    }

    @Test
    fun `candidate-but-unused local docs are not claimed as used`() {
        val r = AnswerContextReceipt.buildFromExecution(
            route = AnswerContextRoute.LOCAL_MODEL, ragSelections = null,
        )
        assertEquals(0, r.localRagDocumentCount)
        assertEquals(0, r.localRagChunkCount)
    }

    // ── PRIVACY ────────────────────────────────────────────────────────────

    @Test
    fun `receipt never contains instruction text paths or excerpts or secrets`() {
        val secretInstruction = "비밀: reply=open_door; key=abc123"
        val r = AnswerContextReceipt.buildFromExecution(
            route = AnswerContextRoute.LOCAL_MODEL,
            projectContext = project(secretInstruction),
            ragSelections = listOf(ragSelection("x", "/storage/emulated/0/Fusion/attachments/x.txt")),
        )
        val json = AnswerContextReceipt.encode(r)!!
        assertFalse(json.contains("open_door"))
        assertFalse(json.contains("abc123"))
        assertFalse(json.contains("/storage/emulated"))
        assertFalse(json.contains("설명"))
        assertFalse(json.contains("발췌"))
        // Only booleans + bound counts are present.
        val obj = JSONObject(json.substringAfter('\n').substringBeforeLast('\n'))
        assertFalse(obj.has("instructions"))
        assertFalse(obj.has("localPath"))
        assertFalse(obj.has("excerpt"))
        assertFalse(obj.has("apiKey"))
    }

    @Test
    fun `canonical visible answer consumers strip the receipt`() {
        val content = "보이는 답변" + "\n\n" + AnswerContextReceipt.encode(receipt(web = 5))!!
        val visible = visibleMessageContent(content)
        assertEquals("보이는 답변", visible)
        assertFalse(visible.contains("FUSION_ANSWER_CONTEXT_JSON"))
    }

    // ── VERSION OWNERSHIP ──────────────────────────────────────────────────

    @Test
    fun `two versions with identical visible text keep distinct receipts`() {
        val versionA = "기계 표기를 방지한 답변" + "\n\n" + AnswerContextReceipt.encode(receipt(route = AnswerContextRoute.LOCAL_MODEL, web = 2))!!
        val versionB = "기계 표기를 방지한 답변" + "\n\n" + AnswerContextReceipt.encode(receipt(route = AnswerContextRoute.EXTERNAL_AI_API, web = 0))!!
        val receiptA = AnswerContextReceipt.parse(versionA)!!
        val receiptB = AnswerContextReceipt.parse(versionB)!!
        assertNotEquals(receiptA, receiptB)
        assertEquals(AnswerContextRoute.LOCAL_MODEL, receiptA.route)
        assertEquals(AnswerContextRoute.EXTERNAL_AI_API, receiptB.route)
    }

    @Test
    fun `legacy answer without a receipt yields none and does not fabricate context`() {
        assertNull(AnswerContextReceipt.parse("과거에 저장된 평범한 답변"))
        assertNull(AnswerContextReceipt.parse(""))
    }

    // ── PRESENTATION ───────────────────────────────────────────────────────

    @Test
    fun `inspector rows are truthful and carry no percentages or sources`() {
        val rows = AnswerContextPresentation.inspectorRows(receipt(web = 3, ragDocs = 2, ragChunks = 4))
        val joined = rows.joinToString("\n") { "${it.section}: ${it.value}" }
        assertFalse(joined.contains("%"))
        assertFalse(joined.contains("신뢰도"))
        assertFalse(joined.contains("http"))
        assertFalse(joined.contains("confidence"))
        assertTrue(joined.contains("기기 내 모델"))
        assertTrue(joined.contains("자료 3개 사용"))
        assertTrue(joined.contains("2개 문서"))
        assertTrue(joined.contains("관련 구간 4개"))
        assertTrue(joined.contains("첨부 1개"))
    }

    @Test
    fun `inspector renders external route without privacy claims`() {
        val rows = AnswerContextPresentation.inspectorRows(
            AnswerContextReceipt.build(route = AnswerContextRoute.EXTERNAL_AI_API, webSourceCount = 0),
        )
        assertEquals("외부 AI", rows.first { it.section == "처리" }.value)
        val joined = rows.joinToString("\n") { it.value }
        assertFalse(joined.contains("비공개"))
        assertFalse(joined.contains("private"))
    }
}
