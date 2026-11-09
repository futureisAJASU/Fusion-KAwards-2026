package com.projectnuke.fusion.handoff

/**
 * Single-owner pending handoff slot for the generalized [ExternalHandoff]
 * event family (text + v2 document streams).
 *
 * One external Intent is consumed exactly once: [submit] stores the newest
 * event (a second intentional share supersedes an unconsumed one), and
 * [consume] clears the slot so recomposition, configuration changes and
 * surface switches can never re-import the same payload.
 *
 * URI persistence uses safe URI string identity plus event metadata only;
 * open streams and ContentResolver objects are never stored here.
 */
class PendingExternalHandoff {

    private var current: ExternalHandoff? = null

    val pending: ExternalHandoff?
        get() = current

    fun submit(event: ExternalHandoff) {
        current = event
    }

    fun consume(): ExternalHandoff? {
        val consumed = current
        current = null
        return consumed
    }
}
