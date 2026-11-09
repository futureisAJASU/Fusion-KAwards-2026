package com.projectnuke.fusion.projects

/**
 * Finding B: MESSAGE settlement provenance.
 *
 * The generic "first assistant message with id > userMessageId" assumption
 * is removed. An assistant settles a Work Plan attempt only with direct
 * response ownership proof:
 *
 * - the exact committed user submission is identified by its durable
 *   submission witness token ([submissionToken]) resolving to
 *   ([conversationId], [userMessageId]);
 * - the exact generation request ([requestId]) was created for that
 *   submission (first commit wins, first generation wins; in-memory
 *   transient binding, never treated as durable completion evidence);
 * - the persisted assistant's durable origin is exactly that user message,
 *   proven by the response-version group when present, otherwise by the
 *   nearest preceding user message on the active timeline.
 *
 * A later normal message B and its answer, a regenerated answer from a
 * different request, or any unrelated insertion therefore never settles
 * the earlier attempt. Pure so the rule is unit-testable without Room.
 */
object ProjectWorkPlanMessageOwnership {
    data class TimelineRef(val id: Long, val role: String)

    /**
     * First assistant owned by [userMessageId] in timeline order, excluding
     * already-tried assistants. Ownership requires durable origin proof:
     * the version group owner when recorded, else the nearest preceding
     * user message. Chronological "first after user id" alone never owns.
     */
    fun firstOwnedAssistantId(
        timeline: List<TimelineRef>,
        groupOwnerByAssistantId: Map<Long, Long>,
        userMessageId: Long,
        excludeAssistantIds: Set<Long> = emptySet(),
    ): Long? {
        if (userMessageId <= 0L) return null
        for (item in timeline) {
            if (item.role != "assistant" || item.id <= userMessageId) continue
            if (item.id in excludeAssistantIds) continue
            if (isOwnedAssistant(timeline, groupOwnerByAssistantId, item.id, userMessageId)) {
                return item.id
            }
        }
        return null
    }

    /**
     * Whether [assistantMessageId] is directly owned by [userMessageId].
     * A recorded version group owner is authoritative; otherwise the
     * nearest preceding user message on the active timeline must be
     * exactly the committed Work Plan user message.
     */
    fun isOwnedAssistant(
        timeline: List<TimelineRef>,
        groupOwnerByAssistantId: Map<Long, Long>,
        assistantMessageId: Long,
        userMessageId: Long,
    ): Boolean {
        if (userMessageId <= 0L || assistantMessageId <= userMessageId) return false
        val assistant = timeline.firstOrNull { it.id == assistantMessageId && it.role == "assistant" }
            ?: return false
        groupOwnerByAssistantId[assistant.id]?.let { groupOwner ->
            return groupOwner == userMessageId
        }
        return nearestPrecedingUserId(timeline, assistantMessageId) == userMessageId
    }

    private fun nearestPrecedingUserId(timeline: List<TimelineRef>, targetId: Long): Long? {
        val targetIndex = timeline.indexOfFirst { it.id == targetId }
        if (targetIndex <= 0) return null
        for (index in targetIndex - 1 downTo 0) {
            if (timeline[index].role == "user") return timeline[index].id
        }
        return null
    }
}
