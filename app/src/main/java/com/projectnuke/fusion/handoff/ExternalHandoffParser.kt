package com.projectnuke.fusion.handoff

import android.content.Intent
import java.util.UUID

/**
 * Unified Android -> Fusion external-handoff precedence (v2).
 *
 * Preserves v1 text semantics exactly:
 *
 *  - ACTION_PROCESS_TEXT -> current text handoff path only
 *  - ACTION_SEND with a supported stream -> document handoff
 *  - ACTION_SEND without stream but with EXTRA_TEXT -> current text path
 *  - ACTION_SEND_MULTIPLE -> document-stream handoff only
 *
 * HTML extras are never interpreted as trusted rich content (the text parser
 * already treats them as literal user content).
 *
 * The pure overload is the tested policy; the Intent overload is a thin
 * binder for MainActivity that also extracts stream URI strings without
 * retaining open streams.
 */
object ExternalHandoffParser {

    fun parse(intent: Intent): ExternalHandoff? =
        parse(
            action = intent.action,
            mimeType = intent.type,
            shareTextExtra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
            processTextExtra = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            singleStreamUri = extractSingleStream(intent),
            multipleStreamUris = extractMultipleStreams(intent),
            newEventId = { UUID.randomUUID().toString() },
        )

    fun parse(
        action: String?,
        mimeType: String?,
        shareTextExtra: CharSequence?,
        processTextExtra: CharSequence?,
        singleStreamUri: String?,
        multipleStreamUris: List<String>?,
        displayNameHint: String? = null,
        displayNameByUri: Map<String, String?> = emptyMap(),
        mimeByUri: Map<String, String?> = emptyMap(),
        newEventId: () -> String,
    ): ExternalHandoff? {
        // ACTION_PROCESS_TEXT is text-only and unchanged: never reinterpreted
        // as a document even if a stream extra happens to be present.
        if (action == Intent.ACTION_PROCESS_TEXT) {
            return ExternalTextHandoffParser.parse(
                action = action,
                mimeType = mimeType,
                shareTextExtra = shareTextExtra,
                processTextExtra = processTextExtra,
                newEventId = newEventId,
            )
        }
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            // Document-stream handoff only; text extras alone never qualify.
            return ExternalDocumentHandoffParser.parseMultiple(
                uriStrings = multipleStreamUris,
                declaredMimeType = mimeType,
                displayNameByUri = displayNameByUri,
                mimeByUri = mimeByUri,
                newEventId = newEventId,
            )
        }
        if (action == Intent.ACTION_SEND) {
            // A present stream takes the document path; otherwise the current
            // text path applies unchanged.
            if (singleStreamUri?.isNotBlank() == true) {
                val doc = ExternalDocumentHandoffParser.parseSingle(
                    singleUriString = singleStreamUri,
                    declaredMimeType = mimeType,
                    displayNameHint = displayNameHint,
                    newEventId = newEventId,
                )
                // A positively-unsupported stream is a bounded rejection, not
                // a silent fallback to empty text handling: return null so the
                // caller shows no fake success.
                return doc
            }
            return ExternalTextHandoffParser.parse(
                action = action,
                mimeType = mimeType,
                shareTextExtra = shareTextExtra,
                processTextExtra = processTextExtra,
                newEventId = newEventId,
            )
        }
        return null
    }

    private fun extractSingleStream(intent: Intent): String? = runCatching {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)?.toString()
    }.getOrNull()

    private fun extractMultipleStreams(intent: Intent): List<String>? = runCatching {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)?.map { it.toString() }
    }.getOrNull()
}
