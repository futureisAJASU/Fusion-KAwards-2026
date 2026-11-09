package com.projectnuke.fusion.ui

/**
 * Per-visible-conversation/group single-flight gate for version navigation.
 * It only tracks group identities, never durable state or message content.
 */
internal class ResponseVersionNavigationFlight {
    private val lock = Any()
    private val activeGroups = mutableSetOf<Long>()

    fun tryAcquire(groupId: Long): Boolean = synchronized(lock) {
        activeGroups.add(groupId)
    }

    fun release(groupId: Long) {
        synchronized(lock) {
            activeGroups.remove(groupId)
        }
    }
}
