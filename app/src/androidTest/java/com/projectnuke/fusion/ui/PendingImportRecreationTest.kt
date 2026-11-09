package com.projectnuke.fusion.ui

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PendingImportRecreationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun confirmationRestoresExactUriTokenAndGrantOwnership() {
        val restoration = StateRestorationTester(compose)
        lateinit var state: androidx.compose.runtime.MutableState<PendingModelImport?>
        restoration.setContent {
            state = rememberSaveable(stateSaver = PendingModelImportSaver) { mutableStateOf(null) }
        }
        val pending = PendingModelImport("stable-operation", "model", "model.litertlm",
            Uri.parse("content://models/owned"), 123, true, ModelFamily.CUSTOM, acquiredReadGrant = false)
        compose.runOnIdle { state.value = pending }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(pending, state.value) }
        compose.runOnIdle { state.value = null }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertNull(state.value) }
    }
}
