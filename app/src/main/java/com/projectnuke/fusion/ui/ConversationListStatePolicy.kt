package com.projectnuke.fusion.ui

/** Identity of the recoverable LazyColumn position owner. */
internal data class ConversationListStateOwner(
    val conversationId: Long,
    val newChatEpoch: Long,
)

internal fun conversationListStateOwner(
    conversationId: Long,
    newChatEpoch: Long,
): ConversationListStateOwner = ConversationListStateOwner(conversationId, newChatEpoch)
