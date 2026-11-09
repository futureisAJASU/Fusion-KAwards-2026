package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ConversationEntity

internal fun conversationBranchMarker(conversation: ConversationEntity): String? =
    conversation.branchedFromConversationId?.let { "분기" }
