package com.projectnuke.fusion.validation

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.chat.PromptTokenCost
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS
import com.projectnuke.fusion.projects.ProjectSnapshotBounds
import com.projectnuke.fusion.retrieval.DocumentSectionPageInput
import com.projectnuke.fusion.retrieval.DocumentSectionPolicy
import com.projectnuke.fusion.ui.VoiceModeMessage
import com.projectnuke.fusion.ui.selectVoiceReplyToSpeak
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2-4 contract validation probes.
 * H14/H15/H17/H20/H21/H23 are CONTRACT-DEPENDENT (mechanism documented,
 * product policy undefined – no production change).
 * H16 is STILL NOT PROVEN (no host tokenizer).
 * H18 is PROVEN->FIXED (see production VoiceMode binding + tests below).
 * H19 is DISPROVEN (heading labels preserve source text).
 */
class Phase234ValidationTest {

    // ---------- H14: no total deadline, only inactivity limits ----------

    @Test
    fun h14_clientHasOnlyInactivityLimits_noTotalDeadline() {
        val store = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = true
            override suspend fun getSecret(id: String) = "k"
            override suspend fun deleteSecret(id: String) = true
        }
        val config = AiProviderConfig(
            id = "t", type = AiProviderType.OPENAI, displayName = "T",
            baseUrl = "https://example.com/", modelId = "m", apiKeySecretId = "k",
        )
        val client = OpenAiCompatibleClient(store, connectionFactory = {
            object : java.net.HttpURLConnection(URL("https://example.com/")) {
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun connect() {}
            }
        })
        val conn = client.createConnection("https://example.com/chat", config, "k")
        assertEquals(30_000, conn.connectTimeout)
        assertEquals(120_000, conn.readTimeout)
        // No total/callTimeout field exists on HttpURLConnection; total
        // wall-clock deadline while bytes keep arriving is undefined.
        // CONTRACT-DEPENDENT: need product decision on hard total deadline.
    }

    // ---------- H15: raw cap before tag filter (mechanism) ----------

    @Test
    fun h15_rawCapBeforeTagFilter_canHideOldTaggedMatch() {
        // Mimics FusionKnowledgeSearchRepository: bounded raw candidates first,
        // tag/collection membership after.
        data class Item(val id: Int, val tags: Set<String>)
        val rawCap = 5
        val allNewestFirst = listOf(
            Item(1, emptySet()), Item(2, emptySet()), Item(3, emptySet()),
            Item(4, emptySet()), Item(5, emptySet()),
            Item(6, setOf("wanted")),
        )
        val rawCandidates = allNewestFirst.take(rawCap)
        val filtered = rawCandidates.filter { "wanted" in it.tags }
        assertTrue(filtered.isEmpty())
        assertTrue(allNewestFirst.any { "wanted" in it.tags })
        // CONTRACT-DEPENDENT: bounded recall vs exhaustive filtering undefined.
    }

    // ---------- H16: conservative heuristic, no host tokenizer ----------

    @Test
    fun h16_estimatorIsConservative_noHostTokenizerForChatModels() {
        assertEquals(2, PromptTokenCost.CHARS_PER_TOKEN)
        assertEquals(264, PromptTokenCost.estimateMessage(400))
        // No pinned chat-model tokenizer is available on host; local fitting uses a 2x character rate.
    }
    // ---------- H17: auto routing keeps A settings except accelerator ----------

    @Test
    fun h17_autoRoutingDivergenceMechanism_settingsPreservedExceptAccelerator() {
        data class Settings(val accelerator: String, val threads: Int, val temp: Double)
        val settingsA = Settings("cpu", 2, 0.2)
        // Mimics AutoModelRoutingOwner: only accelerator rewritten on route A->B.
        val routed = settingsA.copy(accelerator = "gpu")
        assertEquals(2, routed.threads)
        assertEquals(0.2, routed.temp, 0.0)
        assertEquals("gpu", routed.accelerator)
        // CONTRACT-DEPENDENT: whether B's stored profile must override is undefined.
    }

    // ---------- H18: stale voice awaiting (PROVEN->FIXED) ----------

    private fun voiceMessages(vararg pairs: Pair<Long, String>) =
        pairs.map { (id, role) -> VoiceModeMessage(id, role) }

    @Test
    fun h18_countOnlySelector_speaksUnrelatedAnswer_provesBugMechanism() {
        // Prior (1) + voice U_A (10). A fails (no reply). Text B arrives:
        // user 11 + assistant 12. Count-only check speaks 12 as A's reply.
        val messages = voiceMessages(1L to "assistant", 10L to "user", 11L to "user", 12L to "assistant")
        val stale = selectVoiceReplyToSpeak(
            voiceModeActive = true, awaitingReply = true, isGenerating = false,
            messages = messages, submitSize = 1,
            submitConversationId = 9L, currentConversationId = 9L, lastSpokenId = null,
        )
        assertNotNull("old count-only logic speaks unrelated B (bug mechanism)", stale)
        assertEquals(12L, stale!!.id)
    }

    @Test
    fun h18_boundSelector_doesNotSpeakUnrelatedAnswer() {
        val messages = voiceMessages(1L to "assistant", 10L to "user", 11L to "user", 12L to "assistant")
        // Bound to durable voice source U=10: intervening user 11 blocks stale speak.
        assertNull(
            selectVoiceReplyToSpeak(
                voiceModeActive = true, awaitingReply = true, isGenerating = false,
                messages = messages, submitSize = 1,
                submitConversationId = 9L, currentConversationId = 9L, lastSpokenId = null,
                awaitingUserMessageId = 10L,
            )
        )
        // Happy path still speaks: prior + [U=10, A=12] with no intervening user.
        val ok = selectVoiceReplyToSpeak(
            voiceModeActive = true, awaitingReply = true, isGenerating = false,
            messages = voiceMessages(1L to "assistant", 10L to "user", 12L to "assistant"),
            submitSize = 1, submitConversationId = 9L, currentConversationId = 9L,
            lastSpokenId = null, awaitingUserMessageId = 10L,
        )
        assertNotNull(ok)
        assertEquals(12L, ok!!.id)
    }

    // ---------- H19: short prose preserved (DISPROVEN) ----------

    @Test
    fun h19_shortKoreanProse_isPreserved_notDropped() {
        val prose = "이 기능은 다음 버전에서 변경될 수 있음"
        val outline = DocumentSectionPolicy.detect(
            listOf(
                DocumentSectionPageInput(
                    pageNumber = 1, sourceChunkId = "c1",
                    sourceContentHashHex = "h1", text = "$prose\n다음 단락 본문입니다.",
                )
            )
        )
        val combined = outline.sections.joinToString("\n") { it.title + "\n" + it.text }
        assertTrue("source text must remain accessible (title or body): $combined", combined.contains(prose))
    }

    // ---------- H20: implemented — snapshot derives the 20k note bound ----------

    @Test
    fun h20_snapshotBoundMatchesNoteDomain() {
        assertEquals(MAX_SAVED_NOTE_BODY_CHARS, ProjectSnapshotBounds.MAX_KNOWLEDGE_TEXT_CHARS)
    }

    // ---------- H21: import inflation breaks re-export ----------

    @Test
    fun h21_importInflationMechanism_extraRowsCountTowardExportCap() {
        val knowledge = 256
        val conversations = 1 // -> +1 PORTABLE_SNAPSHOT_SUMMARY row on import
        val fileRefs = 1 // payloadIndex==null -> +1 FILE_REFERENCE row
        val importedRows = knowledge + conversations + fileRefs
        assertTrue(importedRows > ProjectSnapshotBounds.MAX_KNOWLEDGE_ITEMS)
        // CONTRACT-DEPENDENT: round-trip vs one-way normalization?
    }

    // ---------- H23: LIMIT before Research filter ----------

    @Test
    fun h23_rawLimitBeforeResearchFilter_canHideEligibleDistinct() {
        data class Row(val conv: Long, val research: Boolean)
        val sqlRawLimit3 = listOf(Row(1, false), Row(1, false), Row(1, true))
        val olderEligible = Row(2, true)
        val result = (sqlRawLimit3)
            .filter { it.research }
            .distinctBy { it.conv }
            .take(3)
        assertEquals(listOf(Row(1, true)), result)
        assertFalse(result.any { it.conv == 2L })
        assertTrue(olderEligible.research)
        // CONTRACT-DEPENDENT: latest N raw vs eligible/distinct?
    }
}
