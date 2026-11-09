package com.projectnuke.fusion.handoff

import android.content.Intent
import java.util.UUID

/**
 * Pure parsing policy for the two v1 Android text entry points:
 *
 *  - ACTION_SEND       + text/plain + EXTRA_TEXT         (share sheet)
 *  - ACTION_PROCESS_TEXT + text/plain + EXTRA_PROCESS_TEXT (selected text)
 *
 * v1 is TEXT ONLY: ACTION_SEND_MULTIPLE, file/image/PDF URIs, clipboard,
 * accessibility and overlay services are explicitly out of scope. The HTML
 * alternate extra is never read: external text is user-provided document
 * content, never trusted rich content and never Fusion control metadata.
 *
 * The pure overload is the tested policy; the Intent overload is a thin
 * binder for MainActivity.
 */
object ExternalTextHandoffParser {

    const val EXTRA_TEXT = Intent.EXTRA_TEXT
    const val EXTRA_PROCESS_TEXT = Intent.EXTRA_PROCESS_TEXT

    fun parse(intent: Intent): ExternalTextHandoff? =
        parse(
            action = intent.action,
            mimeType = intent.type,
            shareTextExtra = intent.getCharSequenceExtra(EXTRA_TEXT),
            processTextExtra = intent.getCharSequenceExtra(EXTRA_PROCESS_TEXT),
            newEventId = { UUID.randomUUID().toString() },
        )

    fun parse(
        action: String?,
        mimeType: String?,
        shareTextExtra: CharSequence?,
        processTextExtra: CharSequence?,
        newEventId: () -> String,
    ): ExternalTextHandoff? {
        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (normalizedMime != "text/plain") return null
        return when (action) {
            Intent.ACTION_SEND -> {
                eventFrom(shareTextExtra, ExternalTextHandoffSource.SHARE_TEXT, newEventId)
            }
            Intent.ACTION_PROCESS_TEXT -> {
                eventFrom(processTextExtra, ExternalTextHandoffSource.PROCESS_TEXT, newEventId)
            }
            else -> null
        }
    }

    private fun eventFrom(
        extra: CharSequence?,
        source: ExternalTextHandoffSource,
        newEventId: () -> String,
    ): ExternalTextHandoff? {
        if (extra == null) return null
        val text = extra.toString()
        if (text.isBlank()) return null
        return ExternalTextHandoff(
            id = newEventId().takeIf { it.isNotBlank() } ?: return null,
            source = source,
            text = text,
        )
    }
}