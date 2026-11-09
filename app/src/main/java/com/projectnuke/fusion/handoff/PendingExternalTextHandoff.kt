package com.projectnuke.fusion.handoff

/**
 * Single-owner pending handoff slot used by MainActivity.
 *
 * One external Intent is consumed exactly once: [submit] stores the newest
 * event (a second intentional share supersedes an unconsumed one), and
 * [consume] clears the slot so recomposition, configuration changes and
 * surface switches can never re-import the same payload.
 */
class PendingExternalTextHandoff {

    private var current: ExternalTextHandoff? = null

    val pending: ExternalTextHandoff?
        get() = current

    fun submit(event: ExternalTextHandoff) {
        current = event
    }

    fun consume(): ExternalTextHandoff? {
        val consumed = current
        current = null
        return consumed
    }
}