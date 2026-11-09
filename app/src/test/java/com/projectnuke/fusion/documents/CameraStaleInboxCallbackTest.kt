package com.projectnuke.fusion.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P14-03: unguarded Inbox UI state mutations (CONFIRMED P2, FIXED).
 *
 * P13 guarded the camera UI *clear* (`clearCameraCaptureUiIfOwned`), but the
 * Inbox camera callback kept four direct `cameraAdoptionInFlight = false`
 * assignments (SensitiveContent, CapacityReached, generic failure, exception
 * handler) plus unguarded success/failure notices that never checked the
 * current session:
 *
 * ```text
 * Capture A starts an Inbox save; A suspends before reporting its result.
 * Capture B becomes the active camera session and starts its own adoption
 * (B.cameraAdoptionInFlight = true).
 * Capture A resumes and reports a failure; A executes
 * cameraAdoptionInFlight = false — B's state is now incorrectly idle, so a
 * later dismiss abandons B's file while B is still adopting it, and the user
 * sees A's failure notice over B's live capture.
 * ```
 *
 * The fix routes every camera UI mutation through the single production seam
 * [cameraCallbackOwnsSession] (which `ChatScreen.ownsCameraCaptureSession`
 * delegates to). A stale callback still settles its own durable owner and
 * discards its own file via the abandonment owner — it only loses the right
 * to touch the newer session's UI state, in-flight flag and notices.
 *
 * `ChatScreen` is a composable and is not JVM-instantiable, so this test
 * drives a harness that replicates its Inbox branch bodies line-for-line
 * (session vars, in-flight flag, notice emission), gated by the REAL
 * production ownership function, through the exact stale ordering and the
 * full session-identity matrix.
 */
class CameraStaleInboxCallbackTest {

    @Test
    fun `abandonment captures ownership before clearing and suppresses stale notice`() {
        val current = CameraCaptureSession("/current.jpg", 7L, "current-token")
        val stale = CameraCaptureSession("/old.jpg", 7L, "old-token")
        var ui = current
        val notices = mutableListOf<String>()

        val owned = clearCameraSessionIfOwned(ui, current) { ui = CameraCaptureSession(null, null, null) }
        if (owned) notices += "current abandonment"

        assertTrue("the owned session may clear and notify", owned)
        assertEquals(CameraCaptureSession(null, null, null), ui)
        assertEquals(listOf("current abandonment"), notices)

        ui = current
        val staleOwned = clearCameraSessionIfOwned(ui, stale) { ui = CameraCaptureSession(null, null, null) }
        if (staleOwned) notices += "stale abandonment"

        assertFalse("a stale session cannot clear", staleOwned)
        assertEquals(current, ui)
        assertEquals(listOf("current abandonment"), notices)
    }

    @Test
    fun `incomplete camera identities cannot clear or notify`() {
        var clears = 0
        val complete = CameraCaptureSession("/capture.jpg", 7L, "token")
        val incomplete = listOf(
            CameraCaptureSession(null, null, null),
            CameraCaptureSession("/capture.jpg", null, "token"),
            CameraCaptureSession("/capture.jpg", 7L, " "),
        )

        incomplete.forEach { callback ->
            val owned = clearCameraSessionIfOwned(callback, callback) { clears++ }
            assertFalse("incomplete identity fails closed: $callback", owned)
        }
        assertEquals("no incomplete callback clears UI", 0, clears)
        assertFalse(cameraCallbackOwnsSession(complete, CameraCaptureSession("/capture.jpg", null, "token")))
    }

    /** Faithful replica of the ChatScreen Inbox camera-callback state. */
    private class InboxUiHarness {
        var path: String? = null
        var originId: Long? = null
        var token: String? = null
        var adoptionInFlight: Boolean = false
        val notices = mutableListOf<String>()

        fun current() = CameraCaptureSession(
            path = path,
            originConversationId = originId,
            importToken = token,
        )

        /** `launchCameraCapture` + `cameraAdoptionInFlight = true`. */
        fun begin(session: CameraCaptureSession) {
            path = session.path
            originId = session.originConversationId
            token = session.importToken
            adoptionInFlight = true
        }

        private fun owns(callback: CameraCaptureSession): Boolean =
            cameraCallbackOwnsSession(current(), callback)

        private fun clearIfOwned(callback: CameraCaptureSession) {
            if (owns(callback)) {
                path = null
                originId = null
                token = null
                adoptionInFlight = false
            }
        }

        // ---- Verbatim ChatScreen Inbox branch bodies (Toast -> notices) ----
        // Ownership is always captured BEFORE the owned clear, exactly as in
        // production: the clear itself would make every later check stale.

        fun onStored(callback: CameraCaptureSession) {
            val owned = owns(callback)
            clearIfOwned(callback)
            if (owned) notices += "stored"
        }

        fun onSensitiveContent(callback: CameraCaptureSession) {
            if (owns(callback)) {
                adoptionInFlight = false
                notices += "sensitive"
            }
        }

        fun onCapacityReached(callback: CameraCaptureSession) {
            if (owns(callback)) {
                adoptionInFlight = false
                notices += "capacity"
            }
        }

        fun onFailed(callback: CameraCaptureSession) {
            if (owns(callback)) {
                adoptionInFlight = false
                notices += "failed"
            }
        }

        fun onThrown(callback: CameraCaptureSession) {
            if (owns(callback)) {
                adoptionInFlight = false
                notices += "failed"
            }
        }

        fun onCancelled(callback: CameraCaptureSession) {
            // Fixed P14-01 shape: abandon (durable half, always runs) then
            // rethrow. The UI clear inside abandon is owned; the durable
            // settlement is not gated on UI ownership.
            durableSettled += callback
            clearIfOwned(callback)
        }

        val durableSettled = mutableListOf<CameraCaptureSession>()
    }

    private val sessionA = CameraCaptureSession("/a.jpg", 1L, "token-a")
    private val sessionB = CameraCaptureSession("/b.jpg", 1L, "token-b")

    /** The required P14-03 reproduction ordering. */
    private fun harnessWithBActive(): InboxUiHarness {
        val ui = InboxUiHarness()
        ui.begin(sessionA)
        ui.begin(sessionB)
        assertTrue(ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
        return ui
    }

    @Test
    fun `control - unguarded reset idles a newer adoption`() {
        // Verbatim OLD production shape: unconditional flag reset and
        // unconditional notice. Proves the ordering above corrupts any code
        // that does not gate on session ownership.
        var adoptionInFlight = false
        var current: CameraCaptureSession? = null
        val notices = mutableListOf<String>()
        // B becomes active.
        current = sessionB
        adoptionInFlight = true
        // Stale A failure, old bodies:
        adoptionInFlight = false
        notices += "failed"
        assertFalse("control: unguarded reset idles B", adoptionInFlight)
        assertEquals(
            "control: the stale notice shows over B",
            listOf("failed"),
            notices,
        )
        assertEquals(sessionB, current)
    }

    @Test
    fun `stale generic failure must not idle a newer adoption`() {
        val ui = harnessWithBActive()
        ui.onFailed(sessionA)
        assertTrue("B's adoption must stay in flight", ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
        assertTrue("no stale notice may show over B", ui.notices.isEmpty())
    }

    @Test
    fun `stale sensitive-content result must not idle a newer adoption`() {
        val ui = harnessWithBActive()
        ui.onSensitiveContent(sessionA)
        assertTrue(ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
        assertTrue(ui.notices.isEmpty())
    }

    @Test
    fun `stale capacity result must not idle a newer adoption`() {
        val ui = harnessWithBActive()
        ui.onCapacityReached(sessionA)
        assertTrue(ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
        assertTrue(ui.notices.isEmpty())
    }

    @Test
    fun `stale throw must not idle a newer adoption`() {
        val ui = harnessWithBActive()
        ui.onThrown(sessionA)
        assertTrue(ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
        assertTrue(ui.notices.isEmpty())
    }

    @Test
    fun `stale cancellation settles its own durable state but keeps the newer UI`() {
        val ui = harnessWithBActive()
        ui.onCancelled(sessionA)
        assertEquals("stale durable settlement must still run", listOf(sessionA), ui.durableSettled)
        assertTrue("B's adoption must stay in flight", ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
    }

    @Test
    fun `stale success must neither clear nor narrate over a newer session`() {
        val ui = harnessWithBActive()
        ui.onStored(sessionA)
        assertTrue(ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
        assertTrue(ui.notices.isEmpty())
    }

    @Test
    fun `owning failure still clears the flag and notifies`() {
        val ui = InboxUiHarness()
        ui.begin(sessionA)
        ui.onFailed(sessionA)
        assertFalse(ui.adoptionInFlight)
        assertEquals(listOf("failed"), ui.notices)
    }

    @Test
    fun `owning success still clears and notifies`() {
        val ui = InboxUiHarness()
        ui.begin(sessionA)
        ui.onStored(sessionA)
        assertFalse(ui.adoptionInFlight)
        assertNull(ui.path)
        assertEquals(listOf("stored"), ui.notices)
    }

    @Test
    fun `same conversation different token is stale`() {
        val ui = harnessWithBActive()
        ui.onFailed(sessionB.copy(importToken = "token-other"))
        assertTrue(ui.adoptionInFlight)
        assertTrue(ui.notices.isEmpty())
    }

    @Test
    fun `same path different token is stale`() {
        val ui = InboxUiHarness()
        ui.begin(sessionB)
        ui.onFailed(sessionB.copy(importToken = "token-stale"))
        assertTrue(ui.adoptionInFlight)
        assertTrue(ui.notices.isEmpty())
    }

    @Test
    fun `different conversation callback is stale`() {
        val ui = harnessWithBActive()
        ui.onFailed(CameraCaptureSession("/b.jpg", 999L, "token-b"))
        assertTrue(ui.adoptionInFlight)
        assertEquals(sessionB, ui.current())
        assertTrue(ui.notices.isEmpty())
    }

    @Test
    fun `identical restored identity after recreation still owns its session`() {
        // Activity recreation restores the same triple via rememberSaveable;
        // the owner must keep settling instead of leaking (P13-03 contract,
        // preserved through the shared seam).
        val ui = InboxUiHarness()
        ui.begin(sessionA)
        val restored = sessionA.copy()
        ui.onFailed(restored)
        assertFalse(ui.adoptionInFlight)
        assertEquals(listOf("failed"), ui.notices)
    }

    @Test
    fun `multiple quick captures - only the latest owns the UI`() {
        val ui = InboxUiHarness()
        val s1 = CameraCaptureSession("/1.jpg", 1L, "t1")
        val s2 = CameraCaptureSession("/2.jpg", 1L, "t2")
        val s3 = CameraCaptureSession("/3.jpg", 1L, "t3")
        ui.begin(s1)
        ui.begin(s2)
        ui.begin(s3)
        ui.onFailed(s1)
        ui.onCapacityReached(s2)
        assertTrue(ui.adoptionInFlight)
        assertEquals(s3, ui.current())
        assertTrue(ui.notices.isEmpty())
        ui.onStored(s3)
        assertFalse(ui.adoptionInFlight)
        assertEquals(listOf("stored"), ui.notices)
    }
}
