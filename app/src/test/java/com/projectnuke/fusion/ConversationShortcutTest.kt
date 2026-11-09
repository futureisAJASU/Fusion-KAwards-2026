package com.projectnuke.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pinned conversation shortcut: stable identity, ID-only extras, one-shot
// navigation, safe stale handling — all without Android launcher services.
class ConversationShortcutTest {

    @Test
    fun `shortcut requires a persisted conversation`() {
        assertTrue(ConversationShortcut.isPinnable(42L))
        assertFalse(ConversationShortcut.isPinnable(0L))
        assertFalse(ConversationShortcut.isPinnable(-7L))
        assertNull(ConversationShortcut.buildRequest(0L, "title"))
        assertNull(ConversationShortcut.buildRequest(-1L, "title"))
    }

    @Test
    fun `shortcut id is stable per conversation`() {
        assertEquals("fusion-conversation-42", ConversationShortcut.shortcutId(42L))
        assertEquals(
            ConversationShortcut.shortcutId(42L),
            ConversationShortcut.shortcutId(42L),
        )
        assertFalse(ConversationShortcut.shortcutId(42L) == ConversationShortcut.shortcutId(43L))
    }

    @Test
    fun `label is bounded with fallback`() {
        assertEquals("공부 기록", ConversationShortcut.boundedLabel("  공부 기록  "))
        assertEquals("새 대화", ConversationShortcut.boundedLabel("   "))
        val long = ConversationShortcut.boundedLabel("제".repeat(200))
        assertTrue(long.codePointCount(0, long.length) <= 48)
    }

    @Test
    fun `request carries id and bounded label only`() {
        val request = ConversationShortcut.buildRequest(42L, "공부 기록")!!
        assertEquals(42L, request.conversationId)
        assertEquals("공부 기록", request.label)
    }

    @Test
    fun `intent parsing accepts only positive ids`() {
        assertEquals(42L, ConversationShortcut.parseConversationId(42L))
        assertNull(ConversationShortcut.parseConversationId(null))
        assertNull(ConversationShortcut.parseConversationId(0L))
        assertNull(ConversationShortcut.parseConversationId(-3L))
    }

    @Test
    fun `pending slot navigates exactly once with newest winning`() {
        val slot = PendingConversationShortcut()
        val first = ConversationShortcutNavigation(42L, "evt-1")
        val second = ConversationShortcutNavigation(43L, "evt-2")
        slot.submit(first)
        slot.submit(second)
        assertEquals(second, slot.pending)
        assertEquals(second, slot.consume())
        assertNull(slot.pending)
        assertNull(slot.consume())
    }

    @Test
    fun `identical conversation redelivered stays two distinct events`() {
        val slot = PendingConversationShortcut()
        slot.submit(ConversationShortcutNavigation(42L, "evt-1"))
        slot.submit(ConversationShortcutNavigation(42L, "evt-2"))
        val consumed = slot.consume()
        assertEquals(42L, consumed?.conversationId)
        assertEquals("evt-2", consumed?.eventId)
        assertNull(slot.consume())
    }

    @Test
    fun `cold recreated representation serializes safely`() {
        // Only the conversation ID plus event identity cross recreation —
        // never conversation text, prompts or attachments.
        val original = ConversationShortcutNavigation(42L, "evt-9")
        val restored = ConversationShortcutNavigation(
            conversationId = original.conversationId,
            eventId = "evt-9",
        )
        assertEquals(original, restored)
        assertTrue(restored.conversationId > 0L)
        assertTrue(restored.eventId.isNotBlank())
    }
}
