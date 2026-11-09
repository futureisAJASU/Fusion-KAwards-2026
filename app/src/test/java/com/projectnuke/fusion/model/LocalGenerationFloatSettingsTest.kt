package com.projectnuke.fusion.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGenerationFloatSettingsTest {
    @Test
    fun `accepts invariant and comma decimal input within established ranges`() {
        assertEquals(0.75f, LocalGenerationFloatSettings.parseTopP("0.75")!!, 0f)
        assertEquals(0.75f, LocalGenerationFloatSettings.parseTopP("0,75")!!, 0f)
        assertEquals(1.25f, LocalGenerationFloatSettings.parseTemperature("1,25")!!, 0f)
        assertEquals(0.5f, LocalGenerationFloatSettings.parseTemperature("  .5  ")!!, 0f)
    }

    @Test
    fun `rejects nonfinite empty intermediate ambiguous and out of range input`() {
        listOf("NaN", "Infinity", "-Infinity", "", " ", "0,", "1.", "1.234,5")
            .forEach { assertNull("$it", LocalGenerationFloatSettings.parseTemperature(it)) }
        assertNull(LocalGenerationFloatSettings.parseTopP("1.01"))
        assertNull(LocalGenerationFloatSettings.parseTopP("-0.01"))
        assertNull(LocalGenerationFloatSettings.parseTemperature("2.01"))
        assertNull(LocalGenerationFloatSettings.parseTemperature("-1"))
    }

    @Test
    fun `formatting is invariant across comma decimal locales and parses back`() {
        val original = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.KOREA).forEach { locale ->
                Locale.setDefault(locale)
                val formattedTopP = LocalGenerationFloatSettings.formatTopP(0.75f)
                val formattedTemperature = LocalGenerationFloatSettings.formatTemperature(1.25f)
                assertEquals("0.75", formattedTopP)
                assertEquals("1.25", formattedTemperature)
                assertEquals(0.75f, LocalGenerationFloatSettings.parseTopP(formattedTopP)!!, 0f)
                assertEquals(1.25f, LocalGenerationFloatSettings.parseTemperature(formattedTemperature)!!, 0f)
                assertEquals(1.25f, LocalGenerationFloatSettings.parseTemperature("1,25")!!, 0f)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `invalid persisted values recover to finite in range defaults`() {
        assertTrue(LocalGenerationFloatSettings.isValidTopP(LocalGenerationFloatSettings.topPOrDefault(Float.NaN)))
        assertTrue(LocalGenerationFloatSettings.isValidTemperature(LocalGenerationFloatSettings.temperatureOrDefault(Float.POSITIVE_INFINITY)))
        assertEquals(0.95f, LocalGenerationFloatSettings.topPOrDefault(Float.NaN), 0f)
        assertEquals(1.0f, LocalGenerationFloatSettings.temperatureOrDefault(Float.NEGATIVE_INFINITY), 0f)
    }
}
