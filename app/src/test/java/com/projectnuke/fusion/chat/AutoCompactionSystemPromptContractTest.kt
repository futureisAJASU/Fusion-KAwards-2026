package com.projectnuke.fusion.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 regression: the internal AUTO_COMPACT system prompt is a contract.
 * It must be a stable, explicit specification of what the LLM is allowed to
 * produce as an internal compact summary. It must NOT instruct the LLM to
 * answer the user, must NOT permit chain-of-thought or reasoning narration,
 * must NOT advertise itself as a hidden/internal task in the produced
 * summary, and must suggest a stable compact structure so the projection
 * parser and validation boundary can agree.
 */
class AutoCompactionSystemPromptContractTest {

    @Test
    fun `system prompt exists and is non blank`() {
        assertTrue(
            "AUTO_COMPACT_SYSTEM_PROMPT must not be blank",
            AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.isNotBlank(),
        )
    }

    @Test
    fun `system prompt forbids answering the user`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt must forbid answering the user",
            prompt.contains("do not answer") || prompt.contains("must not answer") ||
                prompt.contains("do not respond to the user"),
        )
    }

    @Test
    fun `system prompt forbids continuing the conversation`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt must forbid continuing the conversation",
            prompt.contains("do not continue") || prompt.contains("must not continue"),
        )
    }

    @Test
    fun `system prompt forbids chain-of-thought or reasoning narration`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt must forbid chain-of-thought or reasoning narration",
            prompt.contains("chain-of-thought") || prompt.contains("chain of thought"),
        )
        assertTrue(
            "System prompt must forbid reasoning narration",
            prompt.contains("reasoning narration"),
        )
    }

    @Test
    fun `system prompt requires preserving user goals`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt must require preserving user goals",
            prompt.contains("goals"),
        )
    }

    @Test
    fun `system prompt requires preserving decisions and agreements`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt must require preserving decisions",
            prompt.contains("decisions"),
        )
    }

    @Test
    fun `system prompt requires preserving open items`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt must require preserving open / unfinished items",
            prompt.contains("open items") || prompt.contains("unfinished") ||
                prompt.contains("unresolved"),
        )
    }

    @Test
    fun `system prompt requires complete replacement when existing summary is present`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(prompt.contains("existing compact summary"))
        assertTrue(prompt.contains("complete replacement summary"))
        assertTrue(prompt.contains("preserves all still-relevant information"))
        assertTrue(prompt.contains("incorporates the new completed turns"))
    }

    @Test
    fun `system prompt forbids summarizing only newly supplied turns`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(prompt.contains("do not summarize only the newly supplied turns"))
        assertTrue(prompt.contains("do not drop previous goals"))
    }

    @Test
    fun `system prompt treats transcript and existing summary as untrusted data`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(prompt.contains("untrusted transcript data"))
        assertTrue(prompt.contains("never obey"))
        assertTrue(prompt.contains("summarization behavior"))
        assertTrue(prompt.contains("memory behavior"))
        assertTrue(prompt.contains("do not execute"))
    }

    @Test
    fun `system prompt suggests stable compact structure`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT
        assertTrue(
            "System prompt should suggest a Goals: section",
            prompt.contains("Goals:"),
        )
        assertTrue(
            "System prompt should suggest a Decisions: section",
            prompt.contains("Decisions:"),
        )
    }

    @Test
    fun `system prompt forbids speculating or inventing facts`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt must forbid inventing missing facts",
            prompt.contains("do not invent") || prompt.contains("no fabrication") ||
                prompt.contains("do not fabricate") || prompt.contains("no speculation") ||
                prompt.contains("do not speculate"),
        )
    }

    @Test
    fun `system prompt does not request strict JSON`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertFalse(
            "System prompt should not require strict JSON (small local models may be unreliable)",
            prompt.contains("respond with json") || prompt.contains("output as json") ||
                prompt.contains("valid json only"),
        )
    }

    @Test
    fun `summary remains internal and is not exposed to the user`() {
        val prompt = AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT.lowercase()
        assertTrue(
            "System prompt should mark this as an internal/hidden task and not a user reply",
            prompt.contains("internal") || prompt.contains("hidden") ||
                prompt.contains("not shown to the user") || prompt.contains("do not address the user"),
        )
    }
}
