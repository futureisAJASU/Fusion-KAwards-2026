package com.projectnuke.fusion.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSelectorPresentationPolicyTest {
    private val scope = ChatInteractionModeScope(conversationId = 7L, newChatEpoch = 2L)

    @Test
    fun `same scope mode and enabled state does not force dismissal`() {
        assertFalse(
            ModeSelectorPresentationPolicy.shouldDismiss(
                previousScope = scope,
                currentScope = scope,
                previousEnabled = true,
                currentEnabled = true,
                previousMode = ChatInteractionMode.NORMAL,
                currentMode = ChatInteractionMode.NORMAL,
            )
        )
    }

    @Test
    fun `conversation change dismisses`() {
        assertTrue(shouldDismiss(currentScope = scope.copy(conversationId = 8L)))
    }

    @Test
    fun `new chat epoch change dismisses`() {
        assertTrue(shouldDismiss(currentScope = scope.copy(newChatEpoch = 3L)))
    }

    @Test
    fun `mode change dismisses`() {
        assertTrue(
            ModeSelectorPresentationPolicy.shouldDismiss(
                previousScope = scope,
                currentScope = scope,
                previousEnabled = true,
                currentEnabled = true,
                previousMode = ChatInteractionMode.NORMAL,
                currentMode = ChatInteractionMode.STUDY,
            )
        )
    }

    @Test
    fun `becoming disabled dismisses`() {
        assertTrue(
            ModeSelectorPresentationPolicy.shouldDismiss(
                previousScope = scope,
                currentScope = scope,
                previousEnabled = true,
                currentEnabled = false,
                previousMode = ChatInteractionMode.NORMAL,
                currentMode = ChatInteractionMode.NORMAL,
            )
        )
    }

    private fun shouldDismiss(currentScope: ChatInteractionModeScope): Boolean =
        ModeSelectorPresentationPolicy.shouldDismiss(
            previousScope = scope,
            currentScope = currentScope,
            previousEnabled = true,
            currentEnabled = true,
            previousMode = ChatInteractionMode.NORMAL,
            currentMode = ChatInteractionMode.NORMAL,
        )
}
