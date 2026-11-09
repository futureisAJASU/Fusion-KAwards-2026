package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.DraftHydrationHealth

/** Pure UI policy: recovery remains an explicit, confirmed user action. */
internal data class ComposerDraftRecoveryPresentation(
    val message: String?,
    val recoveryAvailable: Boolean,
)

internal object ComposerDraftRecoveryPresentationPolicy {
    const val UnreadableMessage = "입력창 저장 상태를 읽을 수 없습니다."
    const val IoUnavailableMessage = "입력창 저장소를 사용할 수 없습니다. 저장공간 상태를 확인한 뒤 다시 시도해 주세요."

    fun resolve(health: DraftHydrationHealth): ComposerDraftRecoveryPresentation = when (health) {
        DraftHydrationHealth.UNREADABLE_CORRUPT,
        DraftHydrationHealth.UNREADABLE_OVERSIZED ->
            ComposerDraftRecoveryPresentation(UnreadableMessage, recoveryAvailable = true)
        DraftHydrationHealth.UNREADABLE_IO ->
            ComposerDraftRecoveryPresentation(IoUnavailableMessage, recoveryAvailable = false)
        DraftHydrationHealth.MISSING,
        DraftHydrationHealth.VALID -> ComposerDraftRecoveryPresentation(null, recoveryAvailable = false)
    }

    fun shouldStartRecovery(confirmed: Boolean, health: DraftHydrationHealth): Boolean =
        confirmed && resolve(health).recoveryAvailable
}
