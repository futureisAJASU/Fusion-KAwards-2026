package com.projectnuke.fusion.model

import com.projectnuke.fusion.test.PlatformSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformancePreferenceStoreTest {
    @Test
    fun missingAndMalformedValuesUseAutoWithoutMutatingStoredEvidence() {
        val prefs = PlatformSharedPreferences()
        val store = RuntimePerformancePreferenceStore(prefs)
        assertEquals(RuntimePerformancePreference(), store.read())
        prefs.values[RuntimePerformancePreferenceStore.KEY] = "future-or-damaged"
        assertEquals(RuntimePerformancePreference(), store.read())
        assertEquals("future-or-damaged", prefs.values[RuntimePerformancePreferenceStore.KEY])
    }

    @Test
    fun writesAreNormalizedAndMalformedPreviousValueIsQuarantined() {
        val prefs = PlatformSharedPreferences().apply {
            values[RuntimePerformancePreferenceStore.KEY] = "bad"
        }
        val store = RuntimePerformancePreferenceStore(prefs)
        assertTrue(store.write(RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 140)))
        assertEquals(RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 100), store.read())
        assertEquals("bad", prefs.values["${RuntimePerformancePreferenceStore.KEY}.corrupt.bak"])
    }

    @Test
    fun failedCommitRollsBackPreferenceAndQuarantineKeys() {
        val prefs = PlatformSharedPreferences().apply {
            values[RuntimePerformancePreferenceStore.KEY] = "bad"
            durableValues[RuntimePerformancePreferenceStore.KEY] = "bad"
            enqueueCommitResults(false, true)
        }
        assertFalse(RuntimePerformancePreferenceStore(prefs).write(RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 100)))
        assertEquals("bad", prefs.values[RuntimePerformancePreferenceStore.KEY])
        assertEquals("bad", prefs.durableValues[RuntimePerformancePreferenceStore.KEY])
        assertNull(prefs.values["${RuntimePerformancePreferenceStore.KEY}.corrupt.bak"])
    }
}
