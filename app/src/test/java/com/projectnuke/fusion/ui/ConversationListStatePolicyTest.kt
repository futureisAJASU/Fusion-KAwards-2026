package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConversationListStatePolicyTest {
    @Test
    fun `same conversation and epoch share a list owner`() {
        assertEquals(
            conversationListStateOwner(7L, 3L),
            conversationListStateOwner(7L, 3L),
        )
    }

    @Test
    fun `different conversation does not share a list owner`() {
        assertNotEquals(
            conversationListStateOwner(7L, 3L),
            conversationListStateOwner(8L, 3L),
        )
    }

    @Test
    fun `fresh new chat epoch does not share a list owner`() {
        assertNotEquals(
            conversationListStateOwner(7L, 3L),
            conversationListStateOwner(7L, 4L),
        )
    }
}
