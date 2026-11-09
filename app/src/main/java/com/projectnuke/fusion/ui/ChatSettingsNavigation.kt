package com.projectnuke.fusion.ui

/**
 * The settings actions exposed by chat controls. Keeping this mapping pure
 * makes it impossible for the API setup CTA to accidentally reuse the local
 * model advanced-settings action.
 */
internal enum class ChatSettingsAction {
    OPEN_API_PROVIDER_SETTINGS,
    OPEN_ADVANCED_LOCAL_SETTINGS,
}

internal fun dispatchChatSettingsAction(
    action: ChatSettingsAction,
    onOpenApiProviderSettings: () -> Unit,
    onOpenAdvancedLocalSettings: () -> Unit,
) {
    when (action) {
        ChatSettingsAction.OPEN_API_PROVIDER_SETTINGS -> onOpenApiProviderSettings()
        ChatSettingsAction.OPEN_ADVANCED_LOCAL_SETTINGS -> onOpenAdvancedLocalSettings()
    }
}
