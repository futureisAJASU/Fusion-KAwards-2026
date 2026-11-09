package com.projectnuke.fusion.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * Single-owner fence for platform utterance callbacks.
 *
 * Every [begin] mints a fresh unique token and makes it the sole active
 * owner of the read-aloud session. A terminal platform callback (done or
 * error) may settle the session only through [accept] of the currently
 * active token; the accept consumes ownership exactly once. Tokens that
 * are older than the current one, unknown, or cleared by [invalidate]
 * (utterance stop/shutdown or a failed platform speak) can never settle a
 * newer generation, so stale callbacks stay fenced.
 */
internal class UtteranceOwnershipFence {

    private val counter = AtomicLong(0L)
    private var active: String? = null

    /** Mints a new unique token and makes it the current active utterance. */
    @Synchronized
    fun begin(ownerToken: String? = null): String {
        val token = ownerToken ?: "fusion-read-aloud-${counter.incrementAndGet()}"
        active = token
        return token
    }

    /**
     * Accepts the terminal callback for [utteranceId] if and only if that
     * utterance still owns the session. Consumes ownership on success so a
     * duplicate terminal can never settle twice; everything else — older,
     * foreign, or invalidated identities — is fenced.
     */
    @Synchronized
    fun accept(utteranceId: String?): Boolean {
        if (utteranceId == null) return false
        if (utteranceId != active) return false
        active = null
        return true
    }

    /** Drops current ownership; any later callback for it is fenced. */
    @Synchronized
    fun invalidate() {
        active = null
    }
}
