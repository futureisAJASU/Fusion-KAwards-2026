package com.projectnuke.fusion.model

/**
 * Pure contract for the Composer quick surface expansion.
 *
 * Quick Prompt and Quick Settings must be mutually exclusive.
 * Disabled composer closes all surfaces.
 * External AI API mode blocks/closes Quick Settings.
 * Model target changes close Quick Settings.
 * Quick Prompt can remain independent of local/external mode unless composer is disabled.
 * This owns no durable state.
 */
enum class ComposerQuickPanelSurface {
    NONE,
    QUICK_PROMPTS,
    QUICK_SETTINGS,
}

enum class ComposerQuickBackAction {
    CLOSE_TRANSIENT,
    PROPAGATE,
}

object ComposerQuickPanelPolicy {
    fun backAction(surface: ComposerQuickPanelSurface): ComposerQuickBackAction =
        if (surface == ComposerQuickPanelSurface.NONE) {
            ComposerQuickBackAction.PROPAGATE
        } else {
            ComposerQuickBackAction.CLOSE_TRANSIENT
        }

    fun resolveSurface(
        current: ComposerQuickPanelSurface,
        openPrompt: Boolean,
        openSettings: Boolean,
        disabled: Boolean,
        mode: ComposerQuickGenerationMode,
        stableTarget: ComposerQuickSettingsTarget?,
        currentTarget: ComposerQuickSettingsTarget?,
    ): ComposerQuickPanelSurface {
        if (disabled) return ComposerQuickPanelSurface.NONE

        if (openSettings && openPrompt) {
            // Mutual exclusivity: if both requested simultaneously, prefer settings
            // but only if not blocked by environment.
            return if (mode == ComposerQuickGenerationMode.EXTERNAL_AI_API) {
                ComposerQuickPanelSurface.NONE
            } else {
                ComposerQuickPanelSurface.QUICK_SETTINGS
            }
        }

        if (openSettings) {
            if (mode == ComposerQuickGenerationMode.EXTERNAL_AI_API) return ComposerQuickPanelSurface.NONE
            if (current == ComposerQuickPanelSurface.QUICK_SETTINGS) return ComposerQuickPanelSurface.NONE
            return ComposerQuickPanelSurface.QUICK_SETTINGS
        }

        if (openPrompt) {
            if (current == ComposerQuickPanelSurface.QUICK_PROMPTS) return ComposerQuickPanelSurface.NONE
            return ComposerQuickPanelSurface.QUICK_PROMPTS
        }

        // Environment close behavior: no toggle requested
        if (current == ComposerQuickPanelSurface.QUICK_SETTINGS) {
            if (mode == ComposerQuickGenerationMode.EXTERNAL_AI_API) return ComposerQuickPanelSurface.NONE
            if (stableTarget != null && currentTarget != null && stableTarget != currentTarget) {
                return ComposerQuickPanelSurface.NONE
            }
        }

        return current
    }

    fun settingsTargetForSurface(
        surface: ComposerQuickPanelSurface,
        target: ComposerQuickSettingsTarget?,
    ): ComposerQuickSettingsTarget? = if (surface == ComposerQuickPanelSurface.QUICK_SETTINGS) target else null

    fun shouldShowQuickSettings(
        surface: ComposerQuickPanelSurface,
        mode: ComposerQuickGenerationMode,
    ): Boolean = surface == ComposerQuickPanelSurface.QUICK_SETTINGS && mode == ComposerQuickGenerationMode.LOCAL_MODEL

    fun isQuickPromptIndependentOfMode(
        current: ComposerQuickPanelSurface,
        mode: ComposerQuickGenerationMode,
        disabled: Boolean,
    ): Boolean = !disabled && current == ComposerQuickPanelSurface.QUICK_PROMPTS

    fun isQuickSettingsBlocked(mode: ComposerQuickGenerationMode): Boolean =
        mode == ComposerQuickGenerationMode.EXTERNAL_AI_API
}
