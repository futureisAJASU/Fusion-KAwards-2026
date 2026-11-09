package com.projectnuke.fusion.util

import com.projectnuke.fusion.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryMessageIdentitySanitizerTest {
    private fun item(id: Long, role: String, content: String) =
        HistoryMessageWithId(id, ChatMessage(role, content))

    @Test
    fun `no removal preserves every exact durable identity`() {
        val input = listOf(
            item(101, "user", "u1"),
            item(102, "assistant", "a1"),
            item(103, "user", "u2"),
            item(104, "assistant", "a2"),
        )

        val result = sanitizeAssistantHistoryTurns(input)

        assertEquals(listOf(101L, 102L, 103L, 104L), result.map { it.durableMessageId })
    }

    @Test
    fun `one withheld middle pair removes both without shifting later ids`() {
        val input = listOf(
            item(101, "user", "u1"),
            item(102, "assistant", "a1"),
            item(103, "user", "u2"),
            item(104, "assistant", "<think>legacy private</think>hidden answer"),
            item(105, "user", "u3"),
            item(106, "assistant", "a3"),
        )

        val result = sanitizeAssistantHistoryTurns(input)

        assertEquals(listOf(101L, 102L, 105L, 106L), result.map { it.durableMessageId })
        assertEquals(listOf("u1", "a1", "u3", "a3"), result.map { it.message.content })
    }

    @Test
    fun `multiple withheld pairs and first or last pair remain atomic`() {
        val input = listOf(
            item(201, "user", "first user"),
            item(202, "assistant", "<thinking>old</thinking>first withheld"),
            item(203, "user", "kept user"),
            item(204, "assistant", "kept answer"),
            item(205, "user", "middle user"),
            item(206, "assistant", "<think>old</think>middle withheld"),
            item(207, "user", "last user"),
            item(208, "assistant", "<thinking>old</thinking>last withheld"),
        )

        val result = sanitizeAssistantHistoryTurns(input)

        assertEquals(listOf(203L, 204L), result.map { it.durableMessageId })
    }
}
