package com.projectnuke.fusion.chat

internal object ExternalPromptCapacityPolicy {
    const val CONTEXT_LIMIT_CHARS: Int = 48_000
    private const val CHARS_PER_TOKEN = 4

    fun effectiveOutputReserveChars(maxOutputTokens: Int): Int =
        (maxOutputTokens.coerceAtLeast(0) * CHARS_PER_TOKEN)
            .coerceAtMost(CONTEXT_LIMIT_CHARS / 2)

    fun availablePromptChars(
        maxOutputTokens: Int,
        currentRequestChars: Int = 0,
        attachmentCount: Int = 0,
        webSearchPlanned: Boolean = false,
    ): Int = (CONTEXT_LIMIT_CHARS -
        effectiveOutputReserveChars(maxOutputTokens) -
        currentRequestChars.coerceAtLeast(0) -
        attachmentCount.coerceAtLeast(0) * 2_000 -
        if (webSearchPlanned) 6_000 else 0
        ).coerceAtLeast(0)
}
