package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences

const val PrefSavedMemoryContextEnabled = "saved_memory_context_enabled"

private const val MaxSavedMemoryItems = 10
private const val MaxSavedMemoryContextChars = 1500

data class FusionSavedMemoryContext(
    val text: String?,
    val itemCount: Int,
    val characterCount: Int,
    val trimmed: Boolean,
    val totalSavedCount: Int = 0,
    val enabledCount: Int = 0,
    val excludedByScopeCount: Int = 0
)

fun isSavedMemoryContextEnabled(prefs: SharedPreferences): Boolean {
    return prefs.getBoolean(PrefSavedMemoryContextEnabled, false)
}

internal fun isSavedMemoryCandidateApplicable(
    candidate: ConversationMemoryCandidate,
    currentConversationId: Long?,
    currentModelId: String?,
    globalPreviewOnly: Boolean,
): Boolean = when (candidate.scope) {
    MemoryScope.GLOBAL -> true
    MemoryScope.CONVERSATION_ONLY -> !globalPreviewOnly &&
        currentConversationId != null && candidate.conversationId == currentConversationId
    MemoryScope.MODEL_ONLY -> !globalPreviewOnly &&
        !currentModelId.isNullOrBlank() && candidate.modelId == currentModelId
    MemoryScope.DISABLED -> false
}

suspend fun buildSavedMemoryContext(
    context: Context,
    prefs: SharedPreferences,
    currentConversationId: Long?,
    currentModelId: String? = prefs.getString("selected_model", null),
    currentModelPath: String? = prefs.getString("selected_model_path", null),
    globalPreviewOnly: Boolean = false,
    externalTarget: Boolean = false,
): FusionSavedMemoryContext {
    val allCandidates = loadAllConversationMemoryCandidates(context)
    val enabledCandidates = allCandidates.filter { it.enabled && it.scope != MemoryScope.DISABLED }
    if (!isSavedMemoryContextEnabled(prefs)) {
        return FusionSavedMemoryContext(
            text = null,
            itemCount = 0,
            characterCount = 0,
            trimmed = false,
            totalSavedCount = allCandidates.size,
            enabledCount = enabledCandidates.size
        )
    }

    val stableCurrentModelId = if (externalTarget) null else currentModelId
    val matchingCandidates = enabledCandidates.filter { candidate ->
        isSavedMemoryCandidateApplicable(
            candidate = candidate,
            currentConversationId = currentConversationId,
            currentModelId = stableCurrentModelId,
            globalPreviewOnly = globalPreviewOnly,
        )
    }
    val candidates = matchingCandidates
        .asSequence()
        .mapNotNull { candidate ->
            sanitizeFusionMemoryText(candidate.text)
                .takeIf { it.isNotBlank() }
                ?.let { cleanText -> candidate to cleanText }
        }
        .distinctBy { (_, cleanText) -> cleanText }
        .sortedWith(
            compareByDescending<Pair<ConversationMemoryCandidate, String>> { (candidate, _) ->
                currentConversationId != null && candidate.conversationId == currentConversationId
            }
                .thenByDescending { (candidate, _) -> candidate.scope == MemoryScope.GLOBAL }
                .thenByDescending { (candidate, _) -> candidate.scope == MemoryScope.MODEL_ONLY }
                .thenByDescending { (candidate, _) -> candidate.updatedAt ?: candidate.createdAt }
                .thenBy { (_, cleanText) -> cleanText.length }
        )
        .toList()

    if (candidates.isEmpty()) {
        return FusionSavedMemoryContext(
            text = null,
            itemCount = 0,
            characterCount = 0,
            trimmed = false,
            totalSavedCount = allCandidates.size,
            enabledCount = enabledCandidates.size,
            excludedByScopeCount = enabledCandidates.size - matchingCandidates.size
        )
    }

    val header = "[저장된 메모리]"
    val packed = packSavedMemoryTexts(
        texts = candidates.map { it.second },
        maxChars = MaxSavedMemoryContextChars,
        headerLength = header.length,
        maxItems = MaxSavedMemoryItems,
    )
    val lines = packed.texts.map { "- $it" }
    val trimmed = packed.trimmed
    if (lines.isEmpty()) {
        return FusionSavedMemoryContext(
            text = null,
            itemCount = 0,
            characterCount = 0,
            trimmed = true,
            totalSavedCount = allCandidates.size,
            enabledCount = enabledCandidates.size,
            excludedByScopeCount = enabledCandidates.size - matchingCandidates.size
        )
    }

    val text = buildString {
        appendLine(header)
        append(lines.joinToString("\n"))
    }
    return FusionSavedMemoryContext(
        text = text,
        itemCount = lines.size,
        characterCount = text.length,
        trimmed = trimmed,
        totalSavedCount = allCandidates.size,
        enabledCount = enabledCandidates.size,
        excludedByScopeCount = enabledCandidates.size - matchingCandidates.size
    )
}

/** Compatibility entry point for the existing UI memory surfaces. */
fun sanitizeFusionMemoryText(raw: String): String =
    com.projectnuke.fusion.chat.sanitizeFusionMemoryText(raw)
