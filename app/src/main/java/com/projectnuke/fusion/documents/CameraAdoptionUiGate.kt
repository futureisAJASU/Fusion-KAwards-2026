package com.projectnuke.fusion.documents

/**
 * Single production seam deciding whether an asynchronous camera callback
 * may mutate the shared camera UI state (P13-03, P14-03).
 *
 * The Compose camera UI state (`cameraOutputPath`,
 * `cameraOriginConversationId`, `cameraImportToken`,
 * `cameraAdoptionInFlight`) is shared by every capture in one composition,
 * while adoption coroutines outlive the capture that started them. A stale
 * callback from an older capture must therefore prove it still owns the
 * current session before it touches anything: clearing the UI, resetting the
 * in-flight flag, or showing a success/failure notice.
 *
 * Ownership is the full [CameraCaptureSession] equality: the path alone is
 * ambiguous (re-used or restored paths), the conversation alone is ambiguous
 * (sequential captures in one conversation) — only path + origin conversation
 * + import token together identify one capture.
 *
 * Every camera UI mutation in `ChatScreen` (guarded clears, failure-flag
 * resets, success/failure notices) routes through this function, so the
 * ownership rule cannot drift between branches.
 */
internal fun cameraCallbackOwnsSession(
    current: CameraCaptureSession,
    callback: CameraCaptureSession,
): Boolean = current.path?.isNotBlank() == true &&
    current.originConversationId != null &&
    current.importToken?.isNotBlank() == true &&
    callback.path?.isNotBlank() == true &&
    callback.originConversationId != null &&
    callback.importToken?.isNotBlank() == true &&
    callback == current

/**
 * Captures notice ownership before clearing the owned camera session. The
 * returned value remains valid after [clearOwned] mutates the UI state.
 */
internal fun clearCameraSessionIfOwned(
    current: CameraCaptureSession,
    callback: CameraCaptureSession,
    clearOwned: () -> Unit,
): Boolean {
    val owned = cameraCallbackOwnsSession(current, callback)
    if (owned) clearOwned()
    return owned
}
