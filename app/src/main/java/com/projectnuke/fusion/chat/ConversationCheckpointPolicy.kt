package com.projectnuke.fusion.chat

internal const val CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS = 80
internal const val CONVERSATION_CHECKPOINT_MAX_NOTE_CODE_POINTS = 280
internal const val CONVERSATION_CHECKPOINT_MAX_PER_CONVERSATION = 64

internal data class BoundedCheckpointText(val value: String, val valid: Boolean)

internal fun boundedCheckpointText(raw: String, maxCodePoints: Int): BoundedCheckpointText {
    val clean = raw.trim()
    val count = clean.codePointCount(0, clean.length)
    return if (count in 1..maxCodePoints) BoundedCheckpointText(clean, true)
    else BoundedCheckpointText(clean, false)
}

internal fun defaultCheckpointTitle(answer: String): String {
    val firstLine = answer.lineSequence().firstOrNull()?.trim().orEmpty()
    val cleaned = firstLine.ifBlank { answer.trim() }.take(320)
    val count = cleaned.codePointCount(0, cleaned.length)
    val end = cleaned.offsetByCodePoints(0, minOf(count, CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS))
    return cleaned.substring(0, end).trim().ifBlank { "Conversation checkpoint" }
}
