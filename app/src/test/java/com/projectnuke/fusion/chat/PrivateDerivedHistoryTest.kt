package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 7: private-derived assistant turns never leave the device until a
// dedicated private-history consent UX exists.
class PrivateDerivedHistoryTest {

    private fun ragContent(token: String): String =
        "answer with $token\n\n[FUSION_LOCAL_RAG_SOURCES_JSON]\n[]\n[/FUSION_LOCAL_RAG_SOURCES_JSON]"

    @Test
    fun `detection keys on local rag provenance`() {
        assertTrue(isPrivateDerivedMessage(ragContent("x")))
        assertFalse(isPrivateDerivedMessage("plain answer"))
        assertFalse(isPrivateDerivedMessage("answer [FUSION_SEARCH_SOURCES_JSON]x[/FUSION_SEARCH_SOURCES_JSON]"))
        assertTrue(LocalRagSourceMetadata.hasProvenance(ragContent("x")))
    }

    @Test
    fun `branch taint requires an assistant turn`() {
        val userOnly = listOf(MessageEntity(1L, 7L, "user", ragContent("x"), 1L))
        assertFalse(isPrivateTaintedBranch(userOnly))
        val mixed = listOf(
            MessageEntity(1L, 7L, "user", "q", 1L),
            MessageEntity(2L, 7L, "assistant", ragContent("x"), 2L),
        )
        assertTrue(isPrivateTaintedBranch(mixed))
    }

    @Test
    fun `exclusion removes only private derived assistant turns`() {
        val entities = listOf(
            MessageEntity(1L, 7L, "user", "q", 1L),
            MessageEntity(2L, 7L, "assistant", "public answer", 2L),
            MessageEntity(3L, 7L, "assistant", ragContent("x"), 3L),
            MessageEntity(4L, 7L, "user", "follow up", 4L),
        )
        assertEquals(listOf(1L, 2L, 4L), excludePrivateDerivedTurns(entities).map { it.id })
    }

    @Test
    fun `unrelated ordinary history passes through untouched`() {
        val entities = listOf(
            MessageEntity(1L, 7L, "user", "q", 1L),
            MessageEntity(2L, 7L, "assistant", "a", 2L),
        )
        assertFalse(isPrivateTaintedBranch(entities))
        assertEquals(entities, excludePrivateDerivedTurns(entities))
    }
}
