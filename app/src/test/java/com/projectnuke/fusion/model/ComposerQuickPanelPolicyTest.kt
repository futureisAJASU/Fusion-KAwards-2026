package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerQuickPanelPolicyTest {
    private val targetA = ComposerQuickSettingsTarget(
        stableModelId = "model-a",
        selectedPath = "/models/a.litertlm",
        displayIdentity = "Model A",
    )
    private val targetB = targetA.copy(stableModelId = "model-b", displayIdentity = "Model B")

    @Test
    fun quickPromptOpeningClosesQuickSettings() {
        assertEquals(
            ComposerQuickPanelSurface.QUICK_PROMPTS,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_SETTINGS,
                openPrompt = true,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun quickSettingsOpeningClosesQuickPrompt() {
        assertEquals(
            ComposerQuickPanelSurface.QUICK_SETTINGS,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_PROMPTS,
                openPrompt = false,
                openSettings = true,
                disabled = false,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun togglingSameSurfaceClosesIt() {
        assertEquals(
            ComposerQuickPanelSurface.NONE,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_PROMPTS,
                openPrompt = true,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
        assertEquals(
            ComposerQuickPanelSurface.NONE,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_SETTINGS,
                openPrompt = false,
                openSettings = true,
                disabled = false,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun disabledComposerClosesAll() {
        assertEquals(
            ComposerQuickPanelSurface.NONE,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_SETTINGS,
                openPrompt = false,
                openSettings = false,
                disabled = true,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
        assertEquals(
            ComposerQuickPanelSurface.NONE,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_PROMPTS,
                openPrompt = false,
                openSettings = false,
                disabled = true,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun externalModeBlocksClosesQuickSettings() {
        assertEquals(
            ComposerQuickPanelSurface.NONE,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_SETTINGS,
                openPrompt = false,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.EXTERNAL_AI_API,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
        assertEquals(
            ComposerQuickPanelSurface.NONE,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.NONE,
                openPrompt = false,
                openSettings = true,
                disabled = false,
                mode = ComposerQuickGenerationMode.EXTERNAL_AI_API,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun externalModeDoesNotCloseQuickPromptByItself() {
        assertEquals(
            ComposerQuickPanelSurface.QUICK_PROMPTS,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_PROMPTS,
                openPrompt = false,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.EXTERNAL_AI_API,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun stableTargetKeepsQuickSettingsOpen() {
        assertEquals(
            ComposerQuickPanelSurface.QUICK_SETTINGS,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_SETTINGS,
                openPrompt = false,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun changedTargetClosesQuickSettings() {
        assertEquals(
            ComposerQuickPanelSurface.NONE,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_SETTINGS,
                openPrompt = false,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetB,
            ),
        )
    }

    @Test
    fun settingsTargetForSurfaceReturnsTargetOnlyWhileQuickSettingsOpen() {
        assertEquals(
            targetA,
            ComposerQuickPanelPolicy.settingsTargetForSurface(
                surface = ComposerQuickPanelSurface.QUICK_SETTINGS,
                target = targetA,
            ),
        )
        assertNull(
            ComposerQuickPanelPolicy.settingsTargetForSurface(
                surface = ComposerQuickPanelSurface.QUICK_PROMPTS,
                target = targetA,
            ),
        )
        assertNull(
            ComposerQuickPanelPolicy.settingsTargetForSurface(
                surface = ComposerQuickPanelSurface.NONE,
                target = targetA,
            ),
        )
    }

    @Test
    fun quickPromptCanRemainIndependentOfLocalExternalMode() {
        assertEquals(
            ComposerQuickPanelSurface.QUICK_PROMPTS,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_PROMPTS,
                openPrompt = false,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.EXTERNAL_AI_API,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun quickPromptCanRemainOpenInLocalMode() {
        assertEquals(
            ComposerQuickPanelSurface.QUICK_PROMPTS,
            ComposerQuickPanelPolicy.resolveSurface(
                current = ComposerQuickPanelSurface.QUICK_PROMPTS,
                openPrompt = false,
                openSettings = false,
                disabled = false,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
                stableTarget = targetA,
                currentTarget = targetA,
            ),
        )
    }

    @Test
    fun backClosesEitherTransientSurfaceBeforePropagation() {
        assertEquals(
            ComposerQuickBackAction.CLOSE_TRANSIENT,
            ComposerQuickPanelPolicy.backAction(ComposerQuickPanelSurface.QUICK_SETTINGS),
        )
        assertEquals(
            ComposerQuickBackAction.CLOSE_TRANSIENT,
            ComposerQuickPanelPolicy.backAction(ComposerQuickPanelSurface.QUICK_PROMPTS),
        )
        assertEquals(
            ComposerQuickBackAction.PROPAGATE,
            ComposerQuickPanelPolicy.backAction(ComposerQuickPanelSurface.NONE),
        )
    }
}
