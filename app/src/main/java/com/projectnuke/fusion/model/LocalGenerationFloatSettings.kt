package com.projectnuke.fusion.model

import java.util.Locale

/** Locale-invariant display and bounded, comma-tolerant parsing for sampler values. */
object LocalGenerationFloatSettings {
    const val DEFAULT_TOP_P = 0.95f
    const val DEFAULT_TEMPERATURE = 1.0f

    private val decimalPattern = Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")

    fun parseTopP(text: String): Float? = parse(text, min = 0f, max = 1f)

    fun parseTemperature(text: String): Float? = parse(text, min = 0f, max = 2f)

    fun isValidTopP(value: Float): Boolean = value.isFinite() && value in 0f..1f

    fun isValidTemperature(value: Float): Boolean = value.isFinite() && value in 0f..2f

    fun topPOrDefault(value: Float): Float = value.takeIf(::isValidTopP) ?: DEFAULT_TOP_P

    fun temperatureOrDefault(value: Float): Float =
        value.takeIf(::isValidTemperature) ?: DEFAULT_TEMPERATURE

    fun formatTopP(value: Float): String = String.format(Locale.ROOT, "%.2f", topPOrDefault(value))

    fun formatTemperature(value: Float): String =
        String.format(Locale.ROOT, "%.2f", temperatureOrDefault(value))

    private fun parse(text: String, min: Float, max: Float): Float? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.endsWith('.') || trimmed.endsWith(',') ||
            (trimmed.contains('.') && trimmed.contains(','))
        ) return null
        val normalized = trimmed.replace(',', '.')
        if (!decimalPattern.matches(normalized)) return null
        val value = normalized.toFloatOrNull() ?: return null
        return value.takeIf { it.isFinite() && it in min..max }
    }
}
