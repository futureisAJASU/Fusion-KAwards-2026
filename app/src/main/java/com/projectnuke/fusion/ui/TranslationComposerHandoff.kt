package com.projectnuke.fusion.ui

internal sealed interface TranslationComposerHandoffResult {
    data object Success : TranslationComposerHandoffResult
    data object NoUsableResult : TranslationComposerHandoffResult
    data object DraftRejected : TranslationComposerHandoffResult
}

internal sealed interface TranslationComposerHandoffPresentation {
    data object SuccessTargetStillVisible : TranslationComposerHandoffPresentation
    data object SuccessTargetChanged : TranslationComposerHandoffPresentation
    data object NoUsableResult : TranslationComposerHandoffPresentation
    data object DraftRejected : TranslationComposerHandoffPresentation
}

internal suspend fun applyTranslationToComposer(
    replacementText: String?,
    conversationId: Long,
    canWrite: () -> Boolean = { true },
    replaceDurably: suspend (Long, String) -> Boolean,
): TranslationComposerHandoffResult {
    val translatedText = replacementText ?: return TranslationComposerHandoffResult.NoUsableResult
    if (!canWrite()) return TranslationComposerHandoffResult.DraftRejected
    return if (replaceDurably(conversationId, translatedText)) {
        TranslationComposerHandoffResult.Success
    } else {
        TranslationComposerHandoffResult.DraftRejected
    }
}

internal fun resolveTranslationComposerHandoffPresentation(
    result: TranslationComposerHandoffResult,
    targetConversationId: Long,
    currentConversationId: Long,
): TranslationComposerHandoffPresentation = when (result) {
    TranslationComposerHandoffResult.Success ->
        if (targetConversationId == currentConversationId) {
            TranslationComposerHandoffPresentation.SuccessTargetStillVisible
        } else {
            TranslationComposerHandoffPresentation.SuccessTargetChanged
        }
    TranslationComposerHandoffResult.NoUsableResult ->
        TranslationComposerHandoffPresentation.NoUsableResult
    TranslationComposerHandoffResult.DraftRejected ->
        TranslationComposerHandoffPresentation.DraftRejected
}
