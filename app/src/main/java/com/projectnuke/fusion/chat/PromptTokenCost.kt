package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage

/** Conservative token-cost formula shared by fitting and diagnostics. */
internal object PromptTokenCost {
    /** Prior four-character estimation undercounted Korean/model-template prompts. */
    const val CHARS_PER_TOKEN = 2
    const val MESSAGE_OVERHEAD_TOKENS = 64
    /** Character overhead retained by external/legacy char-budget fitting. */
    const val MESSAGE_OVERHEAD_CHARS = 32

    fun estimateMessage(contentLength: Int): Int =
        ((contentLength.coerceAtLeast(0) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN +
            MESSAGE_OVERHEAD_TOKENS).coerceAtLeast(0)

    /** Count non-ASCII code points more conservatively than the old byte-blind heuristic. */
    fun estimateMessage(content: String): Int {
        return estimateContent(content) + MESSAGE_OVERHEAD_TOKENS
    }

    fun estimateContent(content: String): Int {
        var cost = 0
        var asciiRun = 0
        fun flushAscii() {
            if (asciiRun > 0) {
                cost += (asciiRun + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
                asciiRun = 0
            }
        }
        var index = 0
        while (index < content.length) {
            val codePoint = content.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (codePoint in 0x20..0x7e) {
                // Separators and URL/JSON punctuation often tokenize separately.
                if (Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)) {
                    asciiRun += width
                } else {
                    flushAscii()
                    cost += 1
                }
            } else {
                flushAscii()
                // Korean/CJK and symbols may consume multiple model tokens per character.
                cost += if (codePoint > 0xffff) 4 else 2
            }
            index += width
        }
        flushAscii()
        return cost
    }

    /** Conservative token estimate when only UTF-16 character length is known. */
    fun estimateUnknownContentLength(characters: Int): Int =
        characters.coerceIn(0, Int.MAX_VALUE / 2) * 2

    fun estimateMessages(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessage(it.content) }
}
