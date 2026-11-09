package com.projectnuke.fusion.ui

/** The two product-level destinations owned by [FusionApp]. */
internal enum class FusionMainSurface {
    CHAT,
    TRANSLATION,
}

internal fun FusionMainSurface.openTranslation(): FusionMainSurface =
    FusionMainSurface.TRANSLATION

internal fun FusionMainSurface.returnToChat(): FusionMainSurface =
    FusionMainSurface.CHAT

internal fun FusionMainSurface.openChat(): FusionMainSurface =
    FusionMainSurface.CHAT
