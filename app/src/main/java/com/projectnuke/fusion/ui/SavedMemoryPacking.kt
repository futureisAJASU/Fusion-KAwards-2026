package com.projectnuke.fusion.ui

internal data class PackedSavedMemory(
    val texts: List<String>,
    val trimmed: Boolean,
)

internal fun packSavedMemoryTexts(
    texts: List<String>,
    maxChars: Int = 1500,
    headerLength: Int = 10,
    maxItems: Int = 10,
): PackedSavedMemory {
    val kept = mutableListOf<String>()
    var used = headerLength
    var trimmed = false
    texts.forEach { text ->
        if (kept.size >= maxItems) return@forEach
        val lineLength = 2 + text.length
        if (used + 1 + lineLength > maxChars) {
            trimmed = true
        } else {
            kept += text
            used += 1 + lineLength
        }
    }
    return PackedSavedMemory(kept, trimmed)
}
