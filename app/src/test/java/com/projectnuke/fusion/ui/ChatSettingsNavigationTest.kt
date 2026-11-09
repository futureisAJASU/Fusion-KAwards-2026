package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSettingsNavigationTest {

    @Test
    fun `api setup action opens provider settings and not advanced settings`() {
        val actions = mutableListOf<ChatSettingsAction>()

        dispatchChatSettingsAction(
            action = ChatSettingsAction.OPEN_API_PROVIDER_SETTINGS,
            onOpenApiProviderSettings = { actions += ChatSettingsAction.OPEN_API_PROVIDER_SETTINGS },
            onOpenAdvancedLocalSettings = { actions += ChatSettingsAction.OPEN_ADVANCED_LOCAL_SETTINGS },
        )

        assertEquals(listOf(ChatSettingsAction.OPEN_API_PROVIDER_SETTINGS), actions)
    }

    @Test
    fun `advanced settings action remains a separate destination`() {
        val actions = mutableListOf<ChatSettingsAction>()

        dispatchChatSettingsAction(
            action = ChatSettingsAction.OPEN_ADVANCED_LOCAL_SETTINGS,
            onOpenApiProviderSettings = { actions += ChatSettingsAction.OPEN_API_PROVIDER_SETTINGS },
            onOpenAdvancedLocalSettings = { actions += ChatSettingsAction.OPEN_ADVANCED_LOCAL_SETTINGS },
        )

        assertEquals(listOf(ChatSettingsAction.OPEN_ADVANCED_LOCAL_SETTINGS), actions)
    }
}
