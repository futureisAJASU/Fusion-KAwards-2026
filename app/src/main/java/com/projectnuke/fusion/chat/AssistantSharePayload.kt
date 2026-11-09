package com.projectnuke.fusion.chat

import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.util.SafeWebUrlPolicy

/**
 * Evidence-aware answer share payload.
 *
 * Pure, UI-independent policy: builds the exact plain-text payload shared via
 * Android ACTION_SEND. No Android classes, no retrieval, no generation, no
 * persistence. The only inputs are the currently visible answer text plus the
 * provenance values already displayed in Fusion.
 *
 * Privacy contract:
 * - web sources export only a display label and a safe http/https URL;
 *   queryUsed, provider config/identity, rank, score and debug metadata are
 *   dropped at the mapping boundary and can never reach the payload;
 * - local document sources export only the file name and an optional page
 *   number; localPath, attachmentId, chunkIndex, offsets, contentHashHex,
 *   excerpts and derived-index metadata are dropped at the mapping boundary;
 * - sources are provenance/reference context only: the payload never claims
 *   verification ("검증됨", "사실 확인 완료", "신뢰할 수 있음").
 */
internal data class AssistantShareWebSource(
    val title: String?,
    val sourceLabel: String?,
    val url: String?,
)

internal data class AssistantShareLocalSource(
    val fileName: String,
    val pageNumber: Int?,
)

internal sealed interface AssistantShareBuildResult {
    data class Ready(val text: String) : AssistantShareBuildResult
    data object EmptyAnswer : AssistantShareBuildResult
    data object TooLarge : AssistantShareBuildResult
}

internal data class AssistantShareAvailability(
    val canShareAnswer: Boolean,
    val canShareWithSources: Boolean,
)

/** Web source reduced to the only two fields ever exported. */
internal data class AssistantShareSanitizedWebSource(
    val label: String,
    val url: String,
)

/** Local document source reduced to its single exported display line. */
internal data class AssistantShareSanitizedLocalSource(
    val line: String,
)

/**
 * Maps a displayed [WebSearchSource] to share input. Only the public label
 * candidates and the URL cross the boundary; queryUsed, provider type,
 * provider display name, search category, rank, score, snippet, publishedAt
 * and quality hints are dropped here and can never leak into the payload.
 */
internal fun WebSearchSource.toAssistantShareInput(): AssistantShareWebSource =
    AssistantShareWebSource(title = title, sourceLabel = source, url = url)

/**
 * Maps a displayed [LocalRagSourceEntry] to share input. Only the file name
 * and page number cross the boundary; localPath (there is none on the entry,
 * and none is consulted), attachmentId, chunkIndex, offsets, contentHashHex,
 * excerpt, rank and scores are dropped here.
 */
internal fun LocalRagSourceEntry.toAssistantShareInput(): AssistantShareLocalSource =
    AssistantShareLocalSource(fileName = fileName, pageNumber = pageNumber)

internal object AssistantSharePayloadPolicy {
    /**
     * Conservative product bound keeping shared text safely below Android
     * binder-danger territory. Payloads above this fail clearly (TooLarge);
     * the visible answer is never silently truncated.
     */
    const val MAX_SHARE_TEXT_CHARS = 64_000

    /** Outbound URL bound, matching the in-app source-opening gate. */
    const val MAX_URL_CHARS = SafeWebUrlPolicy.MAX_URL_CHARS

    /**
     * Bounded single-line display bound for outbound provenance labels.
     * External search titles and local file names are untrusted display
     * data; labels are normalized to one safe line (see
     * [sanitizeOutboundLabel]) and truncated here, never silently dropped
     * except when nothing safe remains.
     */
    const val MAX_SOURCE_LABEL_CHARS = 240
    const val MAX_LOCAL_FILE_LABEL_CHARS = 240

    const val SOURCES_HEADER = "출처"
    const val GENERIC_WEB_LABEL = "웹 출처"

    private val WHITESPACE_RUN_REGEX = Regex("""\s+""")

    fun buildAnswerOnly(visibleAnswer: String): AssistantShareBuildResult {
        if (visibleAnswer.isBlank()) return AssistantShareBuildResult.EmptyAnswer
        if (visibleAnswer.length > MAX_SHARE_TEXT_CHARS) return AssistantShareBuildResult.TooLarge
        return AssistantShareBuildResult.Ready(visibleAnswer)
    }

    fun buildAnswerWithSources(
        visibleAnswer: String,
        webSources: List<AssistantShareWebSource>,
        localSources: List<AssistantShareLocalSource>,
    ): AssistantShareBuildResult {
        if (visibleAnswer.isBlank()) return AssistantShareBuildResult.EmptyAnswer
        val safeWeb = sanitizeWebSources(webSources)
        val safeLocal = sanitizeLocalSources(localSources)
        if (safeWeb.isEmpty() && safeLocal.isEmpty()) {
            // Nothing safe to cite: fail safe to the answer-only payload.
            return buildAnswerOnly(visibleAnswer)
        }
        val lines = ArrayList<String>(safeWeb.size + safeLocal.size + 3)
        lines.add(visibleAnswer)
        lines.add("")
        lines.add(SOURCES_HEADER)
        for (source in safeWeb) {
            lines.add("* ${source.label} — ${source.url}")
        }
        for (source in safeLocal) {
            lines.add("* ${source.line}")
        }
        val text = lines.joinToString("\n")
        // Never chop the answer to make sources fit: fail clearly instead.
        if (text.length > MAX_SHARE_TEXT_CHARS) return AssistantShareBuildResult.TooLarge
        return AssistantShareBuildResult.Ready(text)
    }

    fun hasShareableSources(
        webSources: List<AssistantShareWebSource>,
        localSources: List<AssistantShareLocalSource>,
    ): Boolean =
        sanitizeWebSources(webSources).isNotEmpty() ||
            sanitizeLocalSources(localSources).isNotEmpty()

    fun resolveAvailability(
        visibleAnswer: String,
        webSources: List<AssistantShareWebSource>,
        localSources: List<AssistantShareLocalSource>,
    ): AssistantShareAvailability {
        val canShareAnswer = visibleAnswer.isNotBlank()
        return AssistantShareAvailability(
            canShareAnswer = canShareAnswer,
            canShareWithSources = canShareAnswer &&
                hasShareableSources(webSources, localSources),
        )
    }

    /**
     * Bounded single-line normalization for outbound provenance labels.
     *
     * External search titles and local file names are untrusted display
     * data: a title containing an embedded newline (or bidi/format
     * controls) must never inject extra visual lines into the exported
     * text. CR/LF/TAB and all other whitespace become normal spacing,
     * whitespace runs collapse, ISO control characters and Unicode
     * FORMAT (Cf: bidi overrides/isolates, zero-width controls, BOM, ...)
     * are removed, and the result is trimmed and bounded. The output never
     * contains a newline. URL semantics are untouched (see [safeWebUrl]).
     */
    fun sanitizeOutboundLabel(raw: String?, maxChars: Int): String {
        if (raw.isNullOrEmpty()) return ""
        val spaced = StringBuilder(raw.length)
        for (char in raw) {
            when {
                char == '\n' || char == '\r' || char == '\t' ||
                    Character.isWhitespace(char) -> spaced.append(' ')
                char.isISOControl() -> Unit
                Character.getType(char) == Character.FORMAT.toInt() -> Unit
                else -> spaced.append(char)
            }
        }
        return spaced.toString()
            .replace(WHITESPACE_RUN_REGEX, " ")
            .trim()
            .take(maxChars)
    }

    fun sanitizeWebSources(
        inputs: List<AssistantShareWebSource>,
    ): List<AssistantShareSanitizedWebSource> {
        val seenUrls = LinkedHashSet<String>()
        val out = ArrayList<AssistantShareSanitizedWebSource>(inputs.size)
        for (input in inputs) {
            val url = safeWebUrl(input.url) ?: continue
            if (!seenUrls.add(url)) continue
            val label = sanitizeOutboundLabel(input.title, MAX_SOURCE_LABEL_CHARS)
                .takeIf { it.isNotEmpty() }
                ?: sanitizeOutboundLabel(input.sourceLabel, MAX_SOURCE_LABEL_CHARS)
                    .takeIf { it.isNotEmpty() }
                ?: GENERIC_WEB_LABEL
            out.add(AssistantShareSanitizedWebSource(label = label, url = url))
        }
        return out
    }

    fun sanitizeLocalSources(
        inputs: List<AssistantShareLocalSource>,
    ): List<AssistantShareSanitizedLocalSource> {
        val seen = HashSet<Pair<String, Int?>>()
        val out = ArrayList<AssistantShareSanitizedLocalSource>(inputs.size)
        for (input in inputs) {
            // Never export directory identity: keep the bare file name only,
            // then reduce it to one safe display line. A name that
            // sanitizes to empty carries no safe provenance: omit it.
            val name = sanitizeOutboundLabel(
                input.fileName.substringAfterLast('/').substringAfterLast('\\'),
                MAX_LOCAL_FILE_LABEL_CHARS,
            )
            if (name.isEmpty()) continue
            val page = input.pageNumber?.takeIf { it > 0 }
            if (!seen.add(name to page)) continue
            val line = if (page != null) "$name · ${page}페이지" else name
            out.add(AssistantShareSanitizedLocalSource(line = line))
        }
        return out
    }

    /**
     * Accepts only the same safe http/https web URLs Fusion already permits
     * for source opening: valid URI, http/https scheme, non-blank host, no
     * userInfo. Rejects malformed and non-web URLs.
     */
    fun safeWebUrl(raw: String?): String? {
        return SafeWebUrlPolicy.normalize(raw)
    }
}
