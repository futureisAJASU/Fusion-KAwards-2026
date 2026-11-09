package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.ComposerQuickBackAction
import com.projectnuke.fusion.model.ComposerQuickPanelPolicy
import com.projectnuke.fusion.model.ComposerQuickPanelSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class FusionMainSurfaceNavigationTest {

    @Test
    fun `sidebar translation opens standalone main surface and back returns to chat`() {
        assertEquals(
            FusionMainSurface.TRANSLATION,
            FusionMainSurface.CHAT.openTranslation(),
        )
        assertEquals(
            FusionMainSurface.CHAT,
            FusionMainSurface.TRANSLATION.returnToChat(),
        )
    }

    @Test
    fun `chat and new chat actions always return to chat`() {
        assertEquals(FusionMainSurface.CHAT, FusionMainSurface.TRANSLATION.openChat())
        assertEquals(FusionMainSurface.CHAT, FusionMainSurface.CHAT.openChat())
    }

    @Test
    fun `system back closes quick settings before activity propagation`() {
        assertEquals(
            ComposerQuickBackAction.CLOSE_TRANSIENT,
            ComposerQuickPanelPolicy.backAction(ComposerQuickPanelSurface.QUICK_SETTINGS),
        )
    }

    @Test
    fun `system back closes quick prompt before activity propagation`() {
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
