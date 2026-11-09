package com.projectnuke.fusion.documents

/**
 * Identity of one camera capture session.
 *
 * A camera operation may clear or dispose ONLY the session it owns. The
 * Compose-level camera UI state (`cameraOutputPath`, `cameraOriginConversationId`,
 * `cameraImportToken`, `cameraProjectTargetId`, `cameraAdoptionInFlight`) is
 * shared by every capture in the composition, so a stale callback from an
 * older capture can otherwise erase a newer capture's state:
 *
 *  1. Capture A starts an asynchronous adoption (project / inbox / chat).
 *  2. The user starts Capture B while A's adoption coroutine is still in
 *     flight (the launcher does not gate on `cameraAdoptionInFlight`).
 *  3. A's adoption finishes with a failure/cancellation and runs
 *     `abandonCameraCapture(pathA, conversationA, tokenA)`.
 *  4. The unconditional `clearCameraCaptureUi()` wipes `cameraOutputPath`,
 *     `cameraImportToken` and `cameraAdoptionInFlight`, which now belong to B.
 *  5. B's own camera result arrives, reads `cameraOutputPath == null`, and
 *     abandons B's capture: "촬영을 취소했거나 카메라에서 이미지를 받지 못했습니다."
 *
 * The file path alone is not an identity (a restored or re-used path is
 * ambiguous); the conversation ID alone is not an identity (one conversation
 * can host many sequential captures). The import token is the only per-capture
 * identity, so all three fields must match.
 */
internal data class CameraCaptureSession(
    val path: String?,
    val originConversationId: Long?,
    val importToken: String?,
) {
    /**
     * True when [other] still identifies THIS session.
     *
     * A stale session never owns the current one, and (critically) never
     * reports ownership of a session it does not own, so it can neither clear
     * the UI state nor reset the in-flight adoption flag.
     */
    fun owns(other: CameraCaptureSession): Boolean =
        path == other.path &&
            originConversationId == other.originConversationId &&
            importToken == other.importToken
}
