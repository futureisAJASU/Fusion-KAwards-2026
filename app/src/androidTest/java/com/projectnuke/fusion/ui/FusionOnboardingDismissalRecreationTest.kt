package com.projectnuke.fusion.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingCompletionStore
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class FusionOnboardingDismissalRecreationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dismissForNowRemainsDismissedAcrossConfigurationStateRestoreWithoutMarkingComplete() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("onboarding-dismissal-recreation-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = FusionOnboardingCompletionStore(prefs)
        val restoration = StateRestorationTester(compose)

        restoration.setContent {
            val showOnboarding = rememberFusionOnboardingVisibility(store)
            if (showOnboarding.value) {
                TextButton(onClick = { showOnboarding.value = false }) { Text("나중에") }
            } else {
                Text("메인 화면")
            }
        }

        compose.onNodeWithText("나중에").assertIsDisplayed().performClick()
        compose.onNodeWithText("메인 화면").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("메인 화면").assertIsDisplayed()
        compose.onNodeWithText("나중에").assertDoesNotExist()
        assertFalse("dismiss for now must remain non-durable", store.isCompleted())
    }
}
