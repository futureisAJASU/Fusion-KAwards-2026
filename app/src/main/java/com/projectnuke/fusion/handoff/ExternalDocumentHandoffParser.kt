package com.projectnuke.fusion.handoff

import android.content.Intent
import com.projectnuke.fusion.retrieval.LocalRagInputPolicy
import java.util.UUID

/**
 * Pure parsing policy for v2 Android document-stream entry points:
 *
 *  - ACTION_SEND + EXTRA_STREAM (single supported document stream)
 *  - ACTION_SEND_MULTIPLE + EXTRA_STREAM list (up to
 *    [ExternalDocumentHandoff.MAX_HANDOFF_DOCS], deterministic order)
 *
 * v2 accepts only document types truthfully supported by
 * [LocalRagInputPolicy]: the text family, application/json,
 * .txt/.md/.json/.csv, and PDF text-layer documents (verified at import
 * time through Document Intelligence). Images/OCR/DOCX/PPT and arbitrary
 * binaries are rejected and never labeled local-readable.
 *
 * Intent precedence is owned by [ExternalHandoffParser]; this object only
 * decides whether a stream payload is a document event. Text extras alone
 * never produce a document event here.
 *
 * The pure overloads are the tested policy; Intent overloads are thin
 * binders for MainActivity.
 */
object ExternalDocumentHandoffParser {

    /**
     * The only URI scheme admitted for document streams. Android share/governs
     * document URIs through `content://` plus a per-activity read grant, which
     * carries consent, revocation and per-URI permission semantics. `file://`
     * (or any other scheme) bypasses that model entirely: it is opened directly
     * by path with no grant, can be attacker-swapped between metadata lookup and
     * copy (TOCTOU at writable paths), and is rejected at this boundary. Since
     * Android N the platform blocks exposing `file://` in outgoing shares anyway;
     * this keeps the ingress side explicit and host-testable.
     */
    const val SCHEME_CONTENT = "content"

    // Pure string scheme extraction: android.net.Uri.parse is a framework
    // stub (returns null) in local JVM tests, so the admission policy must not
    // depend on it. The scheme is the leading segment before "://".
    private fun schemeOf(uriString: String): String? =
        uriString.substringBefore("://").takeIf { it.isNotBlank() && it != uriString }

    private fun isAdmittedStreamUri(uriString: String): Boolean =
        schemeOf(uriString) == SCHEME_CONTENT

    fun parseIntent(intent: Intent, newEventId: () -> String = { UUID.randomUUID().toString() }): ExternalDocumentHandoff? {
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val singleUri = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
        }.getOrNull()?.toString()
        val multipleUris: List<String>? = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
        }.getOrNull()?.map { it.toString() }
            ?: runCatching {
                @Suppress("UNCHECKED_CAST")
                (intent.getSerializableExtra(Intent.EXTRA_STREAM) as? ArrayList<*>)
                    ?.mapNotNull { it?.toString() }
            }.getOrNull()
        // Per-URI MIME/display-name truth requires ContentResolver at import
        // time; the parser only uses the Intent-declared type as a hint and
        // never trusts a single MIME for every stream.
        val mimeHint = intent.type
        return when (action) {
            Intent.ACTION_SEND -> parseSingle(
                singleUriString = singleUri,
                declaredMimeType = mimeHint,
                displayNameHint = null,
                newEventId = newEventId,
            )
            Intent.ACTION_SEND_MULTIPLE -> parseMultiple(
                uriStrings = multipleUris,
                declaredMimeType = mimeHint,
                displayNameByUri = emptyMap(),
                mimeByUri = emptyMap(),
                newEventId = newEventId,
            )
            else -> null
        }
    }

    /**
     * Single-stream pure policy. Returns null when there is no stream or the
     * hinted type/name is clearly unsupported. Unknown (null) hints are
     * accepted here and verified truthfully at import time via ContentResolver
     * metadata; only positively-unsupported combinations are rejected early.
     */
    fun parseSingle(
        singleUriString: String?,
        declaredMimeType: String?,
        displayNameHint: String?,
        newEventId: () -> String,
    ): ExternalDocumentHandoff? {
        val uri = singleUriString?.takeIf { it.isNotBlank() } ?: return null
        if (!isAdmittedStreamUri(uri)) return null
        val mimeHint = declaredMimeType?.substringBefore(';')?.trim()?.lowercase()
        // A ContentProvider is not required to encode the real document filename
        // in its URI (`content://provider/document/12345`). Treating the final
        // URI segment as a filename therefore rejected genuinely supported
        // documents: the generic `application/octet-stream` MIME plus the opaque
        // segment `12345` looked positively unsupported, so the event died before
        // ContentResolver metadata (the real DISPLAY_NAME) could be read. Only a
        // segment that actually looks like a filename may act as a name signal.
        val uriNameHint = uriNameLooksLikeFileName(
            uri.substringAfterLast('/').substringAfterLast(':'),
        )
        val nameHint = displayNameHint ?: uriNameHint
        // Early rejection only when the available evidence POSITIVELY
        // establishes an unsupported type. Otherwise defer to import-time
        // ContentResolver truth (which never labels unsupported docs readable).
        if (mimeHint != null && nameHint.isNotBlank()) {
            if (!LocalRagInputPolicy.isSupported(mimeHint, nameHint)) return null
        } else if (mimeHint != null && nameHint.isBlank()) {
            // A declared MIME with no usable name signal: reject only clearly
            // non-document families (images, video, audio, office, binary).
            if (isClearlyUnsupportedMime(mimeHint)) return null
        }
        val id = newEventId().takeIf { it.isNotBlank() } ?: return null
        return ExternalDocumentHandoff(
            id = id,
            uriStrings = listOf(uri),
            declaredMimeType = declaredMimeType,
            source = ExternalDocumentHandoffSource.SHARE_STREAM,
        )
    }

    /**
     * Multi-stream pure policy. Never rejects the whole event merely because
     * one entry looks unsupported: partial-import semantics (deterministic
     * order, skip excess/failures truthfully) are owned by the import
     * coordinator. Returns null only when there are no usable URI strings.
     */
    fun parseMultiple(
        uriStrings: List<String>?,
        declaredMimeType: String?,
        displayNameByUri: Map<String, String?> = emptyMap(),
        mimeByUri: Map<String, String?> = emptyMap(),
        newEventId: () -> String,
    ): ExternalDocumentHandoff? {
        val distinct = uriStrings.orEmpty()
            .filter { it.isNotBlank() && isAdmittedStreamUri(it) }
            .distinct()
        if (distinct.isEmpty()) return null
        // Whole-event early rejection only when EVERY entry is positively
        // unsupported with explicit per-URI signals; otherwise import-time
        // filtering reports per-file truth. Opaque URI segments are not treated
        // as filenames, so an opaque id plus a generic MIME is deferred rather
        // than counted as evidence of an unsupported document.
        val allPositivelyUnsupported = distinct.isNotEmpty() && distinct.all { uri ->
            val mime = mimeByUri[uri]?.substringBefore(';')?.trim()?.lowercase()
            val name = displayNameByUri[uri]
                ?: uriNameLooksLikeFileName(uri.substringAfterLast('/').substringAfterLast(':'))
            mime != null && name.isNotBlank() && !LocalRagInputPolicy.isSupported(mime, name)
        }
        if (allPositivelyUnsupported) return null
        val id = newEventId().takeIf { it.isNotBlank() } ?: return null
        return ExternalDocumentHandoff(
            id = id,
            uriStrings = distinct,
            declaredMimeType = declaredMimeType,
            source = ExternalDocumentHandoffSource.SHARE_MULTIPLE,
        )
    }

    /**
     * True when a content-URI path segment plausibly IS a filename.
     *
     * Android document IDs are frequently opaque (`.../document/12345`,
     * `.../document/abc%3A1%3A42`, `msf:42`). Only a segment carrying a
     * recognized document extension is treated as a name signal; anything else
     * is deferred to real ContentResolver metadata at import time.
     */
    private fun uriNameLooksLikeFileName(segment: String): String {
        val candidate = segment.trim()
        if (candidate.isBlank()) return ""
        if (!candidate.contains('.')) return ""
        val lower = candidate.lowercase()
        return if (
            lower.endsWith(".txt") || lower.endsWith(".md") ||
            lower.endsWith(".json") || lower.endsWith(".csv") || lower.endsWith(".pdf")
        ) {
            candidate
        } else {
            ""
        }
    }

    private fun isClearlyUnsupportedMime(mime: String): Boolean {
        if (mime.startsWith("text/")) return false
        if (mime == "application/json") return false
        if (mime == "application/pdf") return false
        // `application/octet-stream` and other unknown application/* values carry
        // NO positive indication of the document type -- a generic binary label
        // is exactly what a ContentProvider reports for a PDF or a text file when
        // the sender did not declare a precise type. Rejecting them here refused
        // genuinely supported documents before ContentResolver metadata (the real
        // DISPLAY_NAME and MIME) could be read, so they are deferred instead.
        // Only families that can never be a readable document are rejected now.
        return mime.startsWith("image/") ||
            mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime.contains("officedocument") ||
            mime.contains("msword") ||
            mime.contains("ms-powerpoint")
    }
}
