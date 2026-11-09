package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessDao

/** Inserts only the structural witness; caller supplies the surrounding Room transaction. */
internal suspend fun insertResponseVersionCommitWitness(
    dao: ResponseVersionCommitWitnessDao,
    conversationId: Long,
    plan: ResponseVersionCommitPlan,
    messageId: Long,
) {
    dao.insert(
        ResponseVersionCommitWitnessEntity(
            messageId = messageId,
            conversationId = conversationId,
            targetAssistantMessageId = plan.targetAssistantMessageId,
            groupId = plan.groupId,
            createdAt = System.currentTimeMillis(),
        )
    )
}
