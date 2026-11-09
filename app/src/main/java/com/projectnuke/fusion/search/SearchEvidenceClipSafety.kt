package com.projectnuke.fusion.search

internal object SearchEvidenceClipSafety {
    private val assertionChangingPattern = Regex(
        "(?i)(?:\\b(?:not|never|no longer|corrected|correction|actually|in fact|instead|supersed\\w*|revis\\w*|" +
            "misstated|mistaken|inaccurate|incorrect|wrong|previously|former|formerly|expected|planned|scheduled|" +
            "cancelled|canceled|delayed|denied|postponed|scrapped|withdrawn|revoked)\\b|\\bn't\\b|" +
            "그러나|하지만|그런데|취소|연기|지연|정정|아니|계획|예정)",
    )
    private val contrastPattern = Regex(
        "(?i)(?:\\b(?:however|but|although|though|yet|current|now)\\b|그러나|하지만|그런데)",
    )
    private val numericTokenPattern = Regex("\\d")
    private const val CONTEXT_BEFORE_CHARS = 40
    private const val CONTEXT_AFTER_CHARS = 120

    fun dropsMeaningChangingContext(full: String, clipped: String): Boolean {
        if (clipped.length >= full.length) return false
        if (!full.startsWith(clipped)) return false
        val cut = clipped.length
        val tail = full.substring(cut)
        if (assertionChangingPattern.containsMatchIn(tail)) return true
        if (contrastPattern.containsMatchIn(tail) && numericTokenPattern.containsMatchIn(tail)) return true
        val contextStart = (cut - CONTEXT_BEFORE_CHARS).coerceAtLeast(0)
        val contextEnd = (cut + CONTEXT_AFTER_CHARS).coerceAtMost(full.length)
        val context = full.substring(contextStart, contextEnd)
        if (assertionChangingPattern.containsMatchIn(context)) return true
        return contrastPattern.containsMatchIn(context) && numericTokenPattern.containsMatchIn(context)
    }
}
