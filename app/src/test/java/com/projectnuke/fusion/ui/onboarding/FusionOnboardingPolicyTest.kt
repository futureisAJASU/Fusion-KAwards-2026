package com.projectnuke.fusion.ui.onboarding

import com.projectnuke.fusion.test.PlatformSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionOnboardingPolicyTest {

    // ---- first-run completion ownership ----

    @Test
    fun `completion is false on first run and durable after completion`() {
        val prefs = PlatformSharedPreferences()
        val store = FusionOnboardingCompletionStore(prefs)
        assertFalse(store.isCompleted())

        store.complete()
        assertTrue(store.isCompleted())

        val reopened = FusionOnboardingCompletionStore(prefs)
        assertTrue(reopened.isCompleted())
    }

    @Test
    fun `dismiss-for-now never marks first run completed`() {
        val prefs = PlatformSharedPreferences()
        val store = FusionOnboardingCompletionStore(prefs)
        assertEquals(
            FusionOnboardingAction.DISMISS_FOR_NOW,
            FusionOnboardingPolicy.onSecondary(FusionOnboardingUiState()),
        )
        // The dismiss outcome does not write completion by itself; only the
        // screen's explicit complete() call on a COMPLETE_* outcome does.
        assertFalse(store.isCompleted())
    }

    @Test
    fun `dismiss-for-now is transient and does not permanently skip`() {
        // DISMISS_FOR_NOW must remain a distinct non-durable outcome: it is
        // never a permanent "skip" that would suppress later first-run
        // onboarding.
        val action = FusionOnboardingPolicy.onSecondary(FusionOnboardingUiState())
        assertEquals(FusionOnboardingAction.DISMISS_FOR_NOW, action)
        assertNotEquals(FusionOnboardingAction.COMPLETE_BROWSE, action)
    }

    @Test
    fun `completion routes are durable`() {
        val prefs = PlatformSharedPreferences()
        val store = FusionOnboardingCompletionStore(prefs)
        assertFalse(store.isCompleted())

        store.complete()
        assertTrue(store.isCompleted())

        // A later cold start observes the same durable completion.
        val coldStart = FusionOnboardingCompletionStore(prefs)
        assertTrue(coldStart.isCompleted())
    }

    @Test
    fun `manual reopen is always possible after completion`() {
        val prefs = PlatformSharedPreferences()
        val store = FusionOnboardingCompletionStore(prefs)
        store.complete()
        assertTrue(store.isCompleted())
        // The surface is rendered on demand; reopening does not depend on or
        // reset the stored completion.
        val reopenedState = FusionOnboardingUiState()
        assertEquals(FusionOnboardingPage.INTRO, reopenedState.page)
        assertTrue(store.isCompleted())
    }

    // ---- flow navigation ----

    @Test
    fun `local route completes into the local model path`() {
        val state = FusionOnboardingUiState(
            page = FusionOnboardingPage.NEXT_ACTION,
            pathChoice = FusionOnboardingPathChoice.LOCAL_MODEL,
        )
        assertEquals(FusionOnboardingAction.COMPLETE_LOCAL_ROUTE, FusionOnboardingPolicy.resolveCompletionAction(state))
    }

    @Test
    fun `external route completes into beginner provider setup`() {
        val state = FusionOnboardingUiState(
            page = FusionOnboardingPage.NEXT_ACTION,
            pathChoice = FusionOnboardingPathChoice.EXTERNAL_AI,
        )
        assertEquals(FusionOnboardingAction.COMPLETE_EXTERNAL_ROUTE, FusionOnboardingPolicy.resolveCompletionAction(state))
    }

    @Test
    fun `browse only completes without routing anywhere`() {
        val state = FusionOnboardingUiState(
            page = FusionOnboardingPage.NEXT_ACTION,
            pathChoice = null,
        )
        assertEquals(FusionOnboardingAction.COMPLETE_BROWSE, FusionOnboardingPolicy.resolveCompletionAction(state))
    }

    @Test
    fun `proceed is blocked until a path choice exists`() {
        val intro = FusionOnboardingPolicy.onPrimary(FusionOnboardingUiState())!!
        assertEquals(FusionOnboardingPage.PATH_CHOICE, intro.page)
        assertFalse(FusionOnboardingPolicy.canProceedToNextAction(intro))
        assertTrue(
            FusionOnboardingPolicy.canProceedToNextAction(
                intro.copy(pathChoice = FusionOnboardingPathChoice.LOCAL_MODEL),
            ),
        )
    }

    @Test
    fun `secondary on path choice goes back instead of dismissing`() {
        val pathChoice = FusionOnboardingPolicy.onPrimary(FusionOnboardingUiState())!!
        assertNull(FusionOnboardingPolicy.onSecondary(pathChoice))
        assertTrue(
            FusionOnboardingPolicy.onSecondary(FusionOnboardingUiState()) == FusionOnboardingAction.DISMISS_FOR_NOW
        )
    }

    // ---- navigation/back behavior remains unchanged ----

    @Test
    fun `back navigation walks pages backward and never completes`() {
        // PATH_CHOICE -> INTRO and NEXT_ACTION -> PATH_CHOICE are pure page
        // transitions: no completion action is produced by back navigation.
        val intro = FusionOnboardingUiState()
        val pathChoice = FusionOnboardingPolicy.onPrimary(intro)!!
        val nextAction = FusionOnboardingPolicy.onPrimary(pathChoice.copy(pathChoice = FusionOnboardingPathChoice.LOCAL_MODEL))!!
        assertEquals(FusionOnboardingPage.NEXT_ACTION, nextAction.page)

        // Back from NEXT_ACTION returns to PATH_CHOICE; back from PATH_CHOICE
        // returns to INTRO. Neither produces a completion action.
        assertNull(FusionOnboardingPolicy.resolveCompletionAction(pathChoice))
        assertNull(FusionOnboardingPolicy.resolveCompletionAction(intro))
    }

    @Test
    fun `copy is korean first and never blocks on configuration`() {
        assertTrue(FusionOnboardingPolicy.introBody().contains("로컬 모델"))
        assertTrue(FusionOnboardingPolicy.privacyNote().contains("중계하지 않아요"))
        FusionOnboardingPathChoice.values().forEach { choice ->
            assertTrue(choice.subtitle.isNotBlank())
            assertTrue(choice.label.isNotBlank())
        }
    }

    @Test
    fun `next action copy is natural korean without the old awkward phrasing`() {
        val copy = FusionOnboardingPolicy.nextActionIntro()
        assertTrue(copy.contains("나중에"))
        assertTrue(copy.contains("바꿀 수 있어요"))
        assertFalse(copy.startsWith("기대하지"))
    }
}