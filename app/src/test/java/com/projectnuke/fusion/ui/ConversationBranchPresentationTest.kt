package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationBranchPresentationTest {
    @Test
    fun `normal conversation has no branch marker`() {
        assertNull(conversationBranchMarker(ConversationEntity(title = "normal", createdAt = 1L, updatedAt = 2L)))
    }

    @Test
    fun `branch conversation has subtle marker without changing title`() {
        val conversation = ConversationEntity(
            title = "source title",
            createdAt = 1L,
            updatedAt = 2L,
            branchedFromConversationId = 7L,
            branchedFromMessageId = 8L,
        )

        assertEquals("분기", conversationBranchMarker(conversation))
        assertEquals("source title", conversation.title)
    }

    @Test
    fun `message provenance alone does not mark a normal conversation`() {
        val conversation = ConversationEntity(
            title = "사용자 가지 기록",
            createdAt = 1L,
            updatedAt = 2L,
            branchedFromMessageId = 8L,
        )

        assertNull(conversationBranchMarker(conversation))
        assertEquals("사용자 가지 기록", conversation.title)
    }
}
