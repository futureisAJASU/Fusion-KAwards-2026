package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit chat interaction mode (Phase 1): NORMAL <-> STUDY presentation
 * switch scoped to exactly one (conversationId, newChatEpoch) pair.
 *
 * The switch is pure: no composer text, message, attachment, generation or
 * retrieval callback exists on this API by construction.
 */
class ChatInteractionModePolicyTest {

    private fun scope(conversationId: Long = 7L, epoch: Long = 0L) =
        ChatInteractionModeScope(conversationId, epoch)

    private fun study(conversationId: Long = 7L, epoch: Long = 0L) =
        ChatInteractionModeState(
            mode = ChatInteractionMode.STUDY,
            conversationId = conversationId,
            newChatEpoch = epoch,
        )

    @Test
    fun `A default mode is NORMAL`() {
        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModeState().mode)
        assertEquals(
            ChatInteractionMode.NORMAL,
            ChatInteractionModePolicy.currentMode(ChatInteractionModeState(), 7L, 0L),
        )
    }

    @Test
    fun `B entering STUDY binds the exact scope`() {
        val next = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            scope(42L, 3L),
            ChatInteractionMode.STUDY,
            true,
        )

        assertEquals(ChatInteractionMode.STUDY, next.mode)
        assertEquals(42L, next.conversationId)
        assertEquals(3L, next.newChatEpoch)
        assertEquals(
            ChatInteractionMode.STUDY,
            ChatInteractionModePolicy.currentMode(next, 42L, 3L),
        )
        assertTrue(ChatInteractionModePolicy.isStudyMode(next, scope(42L, 3L)))
    }

    @Test
    fun `C conversation switch resets to NORMAL`() {
        val bound = study(7L, 0L)

        assertEquals(
            ChatInteractionMode.NORMAL,
            ChatInteractionModePolicy.currentMode(bound, 9L, 0L),
        )
        assertFalse(ChatInteractionModePolicy.isStudyMode(bound, scope(9L, 0L)))
    }

    @Test
    fun `D same-id newChatEpoch change resets to NORMAL`() {
        val bound = ChatInteractionModeState(
            mode = ChatInteractionMode.STUDY,
            conversationId = 0L,
            newChatEpoch = 1L,
        )

        assertEquals(
            ChatInteractionMode.STUDY,
            ChatInteractionModePolicy.currentMode(bound, 0L, 1L),
        )
        assertEquals(
            ChatInteractionMode.NORMAL,
            ChatInteractionModePolicy.currentMode(bound, 0L, 2L),
        )
    }

    @Test
    fun `E process recreation defaults to NORMAL`() {
        // No persistence exists on this API: a fresh default state (as after
        // process death) is NORMAL for every scope.
        val recreated = ChatInteractionModeState()
        assertEquals(ChatInteractionMode.NORMAL, recreated.mode)
        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModePolicy.currentMode(recreated, 7L, 0L))
        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModePolicy.currentMode(recreated, 0L, 0L))
    }

    @Test
    fun `F mode switch exposes no draft or message callbacks by construction`() {
        val methods = (ChatInteractionModePolicy.javaClass.declaredMethods +
            ChatInteractionModePolicy.javaClass.methods)
            .filter { it.declaringClass != Any::class.java }
        for (method in methods) {
            val callbackParams = method.parameterTypes.filter { it.name.contains("Function") }
            assertTrue(
                "${method.name} exposes unexpected callbacks",
                callbackParams.isEmpty(),
            )
        }
        val names = methods.map { it.name }.joinToString(",")
        assertFalse(names.contains("draft", ignoreCase = true))
        assertFalse(names.contains("message", ignoreCase = true))
        assertFalse(names.contains("attach", ignoreCase = true))
        assertFalse(names.contains("send", ignoreCase = true))
        assertFalse(names.contains("submit", ignoreCase = true))
        assertFalse(names.contains("generate", ignoreCase = true))
        assertFalse(names.contains("retriev", ignoreCase = true))
        assertFalse(names.contains("back", ignoreCase = true))
    }

    @Test
    fun `G disabled mode switch is rejected`() {
        val before = ChatInteractionModeState()

        val rejectedEnter = ChatInteractionModePolicy.switch(
            before,
            scope(),
            ChatInteractionMode.STUDY,
            false,
        )
        assertEquals(before, rejectedEnter)
        assertEquals(
            ChatInteractionMode.NORMAL,
            ChatInteractionModePolicy.currentMode(rejectedEnter, 7L, 0L),
        )

        val active = study()
        val rejectedExit = ChatInteractionModePolicy.switch(
            active,
            scope(),
            ChatInteractionMode.NORMAL,
            false,
        )
        assertEquals(active, rejectedExit)
    }

    @Test
    fun `H same-mode switch is idempotent`() {
        val normal = ChatInteractionModeState()
        assertSame(
            normal,
            ChatInteractionModePolicy.switch(normal, scope(), ChatInteractionMode.NORMAL, true),
        )

        val active = study()
        assertSame(
            active,
            ChatInteractionModePolicy.switch(active, scope(), ChatInteractionMode.STUDY, true),
        )
    }

    @Test
    fun `stale scope cannot mutate a STUDY binding`() {
        val bound = study(7L, 0L)

        // Neither exit nor rebind from the wrong scope.
        assertEquals(
            bound,
            ChatInteractionModePolicy.switch(bound, scope(9L, 0L), ChatInteractionMode.NORMAL, true),
        )
        assertEquals(
            bound,
            ChatInteractionModePolicy.switch(bound, scope(9L, 0L), ChatInteractionMode.STUDY, true),
        )
        // The original binding still renders for its own scope.
        assertEquals(ChatInteractionMode.STUDY, ChatInteractionModePolicy.currentMode(bound, 7L, 0L))
    }

    @Test
    fun `N explicit switch to NORMAL exits STUDY and keeps scope identity`() {
        val active = study(7L, 2L)

        val exited = ChatInteractionModePolicy.switch(
            active,
            scope(7L, 2L),
            ChatInteractionMode.NORMAL,
            true,
        )

        assertEquals(ChatInteractionMode.NORMAL, exited.mode)
        assertEquals(7L, exited.conversationId)
        assertEquals(2L, exited.newChatEpoch)
        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModePolicy.currentMode(exited, 7L, 2L))
    }

    @Test
    fun `L entering STUDY requires no document context by construction`() {
        // The switch signature takes scope + target + gate only: there is no
        // document, provenance, project or attachment parameter, so STUDY is
        // enterable from an empty New Chat.
        val next = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            ChatInteractionModeScope(0L, 5L),
            ChatInteractionMode.STUDY,
            true,
        )
        assertEquals(ChatInteractionMode.STUDY, next.mode)
        assertEquals(0L, next.conversationId)
        assertEquals(5L, next.newChatEpoch)
    }

    @Test
    fun `quick prompt availability follows the mode`() {
        assertTrue(ChatInteractionModePolicy.allowsQuickPrompt(ChatInteractionMode.NORMAL))
        assertFalse(ChatInteractionModePolicy.allowsQuickPrompt(ChatInteractionMode.STUDY))
    }

    @Test
    fun `J composer selector enters STUDY with zero documents`() {
        // Global entry takes no document context: an empty New Chat scope
        // with localDocumentCount == 0 binds STUDY directly.
        val emptyNewChat = ChatInteractionModeScope(0L, 9L)
        val entered = ChatInteractionModePolicy.switch(
            ChatInteractionModeState(),
            emptyNewChat,
            ChatInteractionMode.STUDY,
            true,
        )

        assertEquals(ChatInteractionMode.STUDY, entered.mode)
        assertEquals(0L, entered.conversationId)
        assertTrue(ChatInteractionModePolicy.isStudyMode(entered, emptyNewChat))
    }

    @Test
    fun `K exiting STUDY preserves user work by construction`() {
        // The switch signature carries no draft, attachment, model,
        // settings, project, message or version parameter: mode changes
        // cannot touch user work. Only the mode field differs.
        val active = study(7L, 2L)
        val exited = ChatInteractionModePolicy.switch(
            active,
            scope(7L, 2L),
            ChatInteractionMode.NORMAL,
            true,
        )

        assertEquals(active.conversationId, exited.conversationId)
        assertEquals(active.newChatEpoch, exited.newChatEpoch)
        assertEquals(ChatInteractionMode.NORMAL, exited.mode)
    }

    @Test
    fun `L composer placeholder differs per mode without inserting text`() {
        assertEquals(
            "입력하여 채팅",
            ChatInteractionModePolicy.composerPlaceholder(ChatInteractionMode.NORMAL),
        )
        assertEquals(
            "배울 내용을 입력하세요",
            ChatInteractionModePolicy.composerPlaceholder(ChatInteractionMode.STUDY),
        )
    }

    @Test
    fun `M returning to NORMAL restores generic quick prompts`() {
        val active = study()
        assertFalse(ChatInteractionModePolicy.allowsQuickPrompt(ChatInteractionMode.STUDY))
        val exited = ChatInteractionModePolicy.switch(
            active,
            scope(),
            ChatInteractionMode.NORMAL,
            true,
        )
        assertTrue(
            ChatInteractionModePolicy.allowsQuickPrompt(
                ChatInteractionModePolicy.currentMode(exited, scope()),
            ),
        )
    }
}
