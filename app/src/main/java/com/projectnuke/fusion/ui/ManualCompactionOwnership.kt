package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.isFatalError

/** Identifies the conversation/request allowed to publish manual UI state. */
internal data class ManualCompactionUiOwner(
    val conversationId: Long,
    val requestId: String,
)

internal fun ownsManualCompactionUi(
    owner: ManualCompactionUiOwner?,
    visibleConversationId: Long,
    requestId: String,
): Boolean = owner?.conversationId == visibleConversationId && owner.requestId == requestId

internal fun rethrowManualCompactionFatal(error: Throwable) {
    if (error.isFatalError()) throw error
}
