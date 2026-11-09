package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ChatInteractionModePolicy
import com.projectnuke.fusion.chat.ChatInteractionModeScope
import com.projectnuke.fusion.chat.ChatInteractionModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEmptyStatePolicyTest {
    @Test
    fun `conversation interaction scope changes for a conversation switch and fresh chat`() {
        assertNotEquals(
            ChatInteractionModeScope(conversationId = 1L, newChatEpoch = 0L),
            ChatInteractionModeScope(conversationId = 2L, newChatEpoch = 0L),
        )
        assertNotEquals(
            ChatInteractionModeScope(conversationId = 0L, newChatEpoch = 1L),
            ChatInteractionModeScope(conversationId = 0L, newChatEpoch = 2L),
        )
    }

    @Test
    fun `normal empty state keeps existing copy exactly`() {
        assertEquals("Fusion", ChatEmptyStatePolicy.normal.title)
        assertEquals("새 채팅을 시작해 보세요.", ChatEmptyStatePolicy.normal.supportingText)
        assertEquals(
            "모델과 모드는 위쪽 칩에서 바꿀 수 있습니다.",
            ChatEmptyStatePolicy.normal.secondaryText,
        )
        assertEquals(
            ChatEmptyStatePolicy.normal,
            ChatEmptyStatePolicy.copyFor(ChatInteractionMode.NORMAL, visibleMessageCount = 0, isGenerating = false),
        )
    }

    @Test
    fun `study empty state uses dedicated short copy`() {
        val copy = ChatEmptyStatePolicy.copyFor(
            ChatInteractionMode.STUDY,
            visibleMessageCount = 0,
            isGenerating = false,
        )

        assertEquals("무엇을 공부할까요?", copy.title)
        assertEquals(
            "개념을 물어보거나, 문제를 풀거나, 이해가 어려운 부분부터 시작해 보세요.",
            copy.supportingText,
        )
        assertEquals(
            "학습 모드는 질문과 힌트를 활용해 단계적으로 도와줍니다.",
            copy.secondaryText,
        )
        assertFalse(copy.supportingText.contains("문서"))
        assertFalse(copy.secondaryText.orEmpty().contains("문서"))
        listOf(copy.title, copy.supportingText, copy.secondaryText.orEmpty()).forEach { text ->
            assertFalse(text.contains("보장"))
            assertFalse(text.contains("검증된 정답"))
            assertFalse(text.contains("정확한 채점"))
        }
    }

    @Test
    fun `study empty state requires empty visible conversation and settled generation`() {
        assertTrue(
            ChatEmptyStatePolicy.shouldShowStudyState(
                ChatInteractionMode.STUDY,
                visibleMessageCount = 0,
                isGenerating = false,
            ),
        )
        assertFalse(
            ChatEmptyStatePolicy.shouldShowStudyState(
                ChatInteractionMode.STUDY,
                visibleMessageCount = 1,
                isGenerating = false,
            ),
        )
        assertFalse(
            ChatEmptyStatePolicy.shouldShowStudyState(
                ChatInteractionMode.STUDY,
                visibleMessageCount = 0,
                isGenerating = true,
            ),
        )
        assertFalse(
            ChatEmptyStatePolicy.shouldShowStudyState(
                ChatInteractionMode.NORMAL,
                visibleMessageCount = 0,
                isGenerating = false,
            ),
        )
    }

    @Test
    fun `empty study entry is a mode switch with no draft callback seam`() {
        val scope = ChatInteractionModeScope(conversationId = 0L, newChatEpoch = 4L)
        val entered = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            scope,
            ChatInteractionMode.STUDY,
            controlsEnabled = true,
        )

        assertEquals(ChatInteractionMode.STUDY, entered.mode)
        assertTrue(ChatEmptyStatePolicy.shouldShowStudyState(entered.mode, 0, false))
        // Mode entry exposes no composer mutation callback and therefore does
        // not seed a topic, send, retrieve, or generate anything.
        val callbackMethods = ChatInteractionModePolicy.javaClass.methods
            .flatMap { it.parameterTypes.asList() }
            .count { it.name.contains("Function") }
        assertEquals(0, callbackMethods)
    }

    @Test
    fun `F2 and global selector entry share semantic STUDY mode`() {
        val scope = ChatInteractionModeScope(conversationId = 7L, newChatEpoch = 2L)
        val global = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            scope,
            ChatInteractionMode.STUDY,
            controlsEnabled = true,
        )
        val contextual = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            scope,
            ChatInteractionMode.STUDY,
            controlsEnabled = true,
        )

        assertEquals(global, contextual)
        assertEquals(ChatInteractionMode.STUDY, contextual.mode)
    }

    @Test
    fun `exit restores normal placeholder and quick prompts`() {
        val scope = ChatInteractionModeScope(7L, 2L)
        val exited = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(
                mode = ChatInteractionMode.STUDY,
                conversationId = scope.conversationId,
                newChatEpoch = scope.newChatEpoch,
            ),
            scope,
            ChatInteractionMode.NORMAL,
            controlsEnabled = true,
        )

        assertEquals("입력하여 채팅", ChatInteractionModePolicy.composerPlaceholder(exited.mode))
        assertTrue(ChatInteractionModePolicy.allowsQuickPrompt(exited.mode))
        assertEquals(ChatEmptyStatePolicy.normal, ChatEmptyStatePolicy.copyFor(exited.mode, 0, false))
    }

    @Test
    fun `scope switch resets the empty state to normal`() {
        val studyState = ChatInteractionModeState(
            mode = ChatInteractionMode.STUDY,
            conversationId = 7L,
            newChatEpoch = 1L,
        )
        val currentMode = ChatInteractionModePolicy.currentMode(
            studyState,
            ChatInteractionModeScope(conversationId = 8L, newChatEpoch = 1L),
        )

        assertEquals(ChatInteractionMode.NORMAL, currentMode)
        assertEquals(ChatEmptyStatePolicy.normal, ChatEmptyStatePolicy.copyFor(currentMode, 0, false))
    }
}
