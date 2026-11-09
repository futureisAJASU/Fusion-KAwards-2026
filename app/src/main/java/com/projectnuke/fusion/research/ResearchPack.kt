package com.projectnuke.fusion.research

import com.projectnuke.fusion.chat.AnswerContextReceipt
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant

enum class ResearchPackFormat { MARKDOWN, JSON }

data class ResearchPackSource(
    val type: Type,
    val title: String,
    val url: String? = null,
    val documentName: String? = null,
    val pageNumber: Int? = null,
) {
    enum class Type { WEB, DOCUMENT }
}

data class ResearchPackAudit(
    val state: State,
    val checkedAt: Long? = null,
    val coverage: Coverage = Coverage.NOT_AUDITED,
    val claimCount: Int = 0,
    val supportedCount: Int = 0,
    val conflictingCount: Int = 0,
    val insufficientCount: Int = 0,
    val notCheckedCount: Int = 0,
) {
    enum class State { MISSING, CURRENT, OUTDATED, CORRUPT, UNAVAILABLE }
    enum class Coverage { AUDITED, PARTIALLY_AUDITED, NOT_AUDITED }
}

data class ResearchPackSnapshot(
    val conversationTitle: String?,
    val query: String?,
    val visibleAnswer: String,
    val createdAt: Long,
    val projectName: String? = null,
    val sources: List<ResearchPackSource> = emptyList(),
    val audit: ResearchPackAudit = ResearchPackAudit(ResearchPackAudit.State.MISSING),
    val contextReceipt: AnswerContextReceipt? = null,
)

object ResearchPackExporter {
    const val MAX_ANSWER_CHARS = 200_000
    const val MAX_QUERY_CHARS = 12_000
    const val MAX_TITLE_CHARS = 180
    const val MAX_SOURCES = 40

    /** Validates and bounds all export fields. Local paths and source excerpts have no input field. */
    fun prepare(snapshot: ResearchPackSnapshot): ResearchPackSnapshot? {
        if (snapshot.visibleAnswer.isBlank() || snapshot.visibleAnswer.length > MAX_ANSWER_CHARS) return null
        val web = snapshot.sources.asSequence()
            .filter { it.type == ResearchPackSource.Type.WEB }
            .mapNotNull { source ->
                val title = scrubPrivatePaths(oneLine(source.title, 300)).ifBlank { "출처" }
                val safeUrl = safeWebUrl(source.url) ?: return@mapNotNull null
                ResearchPackSource(ResearchPackSource.Type.WEB, title, url = safeUrl)
            }
            .distinctBy { it.url }
        val documents = snapshot.sources.asSequence()
            .filter { it.type == ResearchPackSource.Type.DOCUMENT }
            .mapNotNull { source ->
                val name = fileName(source.documentName ?: source.title)
                if (name.isBlank()) return@mapNotNull null
                val rawTitle = oneLine(source.title, 300)
                val title = if (isAbsoluteLocalPath(rawTitle)) name else scrubPrivatePaths(rawTitle).ifBlank { name }
                ResearchPackSource(
                    ResearchPackSource.Type.DOCUMENT,
                    title,
                    documentName = name.take(180),
                    pageNumber = source.pageNumber?.takeIf { it in 1..1_000_000 },
                )
            }
            .distinctBy { it.documentName to it.pageNumber }
        val boundedSources = (web + documents).take(MAX_SOURCES).toList()
        val rawReceipt = snapshot.contextReceipt
        val safeReceipt = rawReceipt?.takeIf { it.interactionMode == "RESEARCH" }
        return snapshot.copy(
            conversationTitle = snapshot.conversationTitle?.let { scrubPrivatePaths(oneLine(it, MAX_TITLE_CHARS)) },
            query = snapshot.query?.take(MAX_QUERY_CHARS)?.let(::scrubPrivatePaths),
            visibleAnswer = scrubPrivatePaths(snapshot.visibleAnswer),
            projectName = snapshot.projectName?.let { scrubPrivatePaths(oneLine(it, MAX_TITLE_CHARS)) },
            sources = boundedSources,
            contextReceipt = safeReceipt,
            audit = snapshot.audit.bounded(),
        )
    }

    fun markdown(snapshot: ResearchPackSnapshot): String? {
        val pack = prepare(snapshot) ?: return null
        return buildString {
            appendLine("# Research Pack — ${pack.conversationTitle ?: "Fusion Research"}")
            appendLine()
            appendLine("- Captured: ${iso(pack.createdAt)}")
            pack.projectName?.let { appendLine("- Project: $it") }
            pack.query?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("## Query")
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("## Answer")
            appendLine()
            appendLine(pack.visibleAnswer)
            appendLine()
            appendLine("## Sources")
            appendLine()
            if (pack.sources.isEmpty()) appendLine("No persisted source records.")
            pack.sources.forEachIndexed { index, source ->
                val citation = "[${index + 1}]"
                when (source.type) {
                    ResearchPackSource.Type.WEB -> appendLine("$citation ${source.title} — ${source.url}")
                    ResearchPackSource.Type.DOCUMENT -> {
                        val page = source.pageNumber?.let { ", p. $it" }.orEmpty()
                        appendLine("$citation ${source.title} — ${source.documentName}$page (local document)")
                    }
                }
            }
            appendLine()
            appendLine("## Evidence audit")
            appendLine()
            appendAuditMarkdown(this, pack.audit)
            pack.contextReceipt?.let { receipt ->
                appendLine()
                appendLine("## Answer context")
                appendLine()
                appendLine("- Route: ${receipt.route.name}")
                receipt.priorConversationContextProvided?.let { appendLine("- Prior conversation provided: $it") }
                appendLine("- Project context used: ${receipt.projectContextUsed}")
                appendLine("- Project instructions applied: ${receipt.projectInstructionsApplied}")
                appendLine("- Input attachments/images: ${receipt.inputAttachmentCount}/${receipt.inputImageCount}")
                appendLine("- Local document/chunk sources: ${receipt.localRagDocumentCount}/${receipt.localRagChunkCount}")
                appendLine("- Web sources used: ${receipt.webSourceCount}")
            }
        }
    }

    fun json(snapshot: ResearchPackSnapshot): String? {
        val pack = prepare(snapshot) ?: return null
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("type", "FUSION_RESEARCH_PACK")
            .put("capturedAt", iso(pack.createdAt))
            .put("title", pack.conversationTitle ?: JSONObject.NULL)
            .put("query", pack.query ?: JSONObject.NULL)
            .put("project", pack.projectName ?: JSONObject.NULL)
            .put("visibleAnswer", pack.visibleAnswer)
        val sources = JSONArray()
        pack.sources.forEachIndexed { index, source ->
            sources.put(
                JSONObject()
                    .put("citation", "[${index + 1}]")
                    .put("type", source.type.name)
                    .put("title", source.title)
                    .put("url", source.url ?: JSONObject.NULL)
                    .put("document", source.documentName ?: JSONObject.NULL)
                    .put("page", source.pageNumber ?: JSONObject.NULL),
            )
        }
        root.put("sources", sources)
        val audit = pack.audit
        root.put(
            "evidenceAudit",
            JSONObject()
                .put("state", audit.state.name)
                .put("checkedAt", audit.checkedAt?.let(::iso) ?: JSONObject.NULL)
                .put("coverage", audit.coverage.name)
                .put("claimCount", audit.claimCount)
                .put("supported", audit.supportedCount)
                .put("conflicting", audit.conflictingCount)
                .put("insufficient", audit.insufficientCount)
                .put("notChecked", audit.notCheckedCount),
        )
        pack.contextReceipt?.let { receipt ->
            root.put(
                "answerContext",
                JSONObject()
                    .put("route", receipt.route.name)
                    .put("priorConversationContextProvided", receipt.priorConversationContextProvided ?: JSONObject.NULL)
                    .put("projectContextUsed", receipt.projectContextUsed)
                    .put("projectInstructionsApplied", receipt.projectInstructionsApplied)
                    .put("inputAttachmentCount", receipt.inputAttachmentCount)
                    .put("inputImageCount", receipt.inputImageCount)
                    .put("localRagDocumentCount", receipt.localRagDocumentCount)
                    .put("localRagChunkCount", receipt.localRagChunkCount)
                    .put("webSourceCount", receipt.webSourceCount),
            )
        }
        return root.toString(2).takeIf { it.length <= 500_000 }
    }

    private fun appendAuditMarkdown(output: StringBuilder, audit: ResearchPackAudit) {
        output.appendLine("- Report: ${audit.state.name}")
        output.appendLine("- Coverage: ${audit.coverage.name}")
        audit.checkedAt?.let { output.appendLine("- Checked: ${iso(it)}") }
        output.appendLine("- Claims: ${audit.claimCount} (supported ${audit.supportedCount}, conflicting ${audit.conflictingCount}, insufficient ${audit.insufficientCount}, not checked ${audit.notCheckedCount})")
    }

    private fun ResearchPackAudit.bounded() = copy(
        claimCount = claimCount.coerceIn(0, 200),
        supportedCount = supportedCount.coerceIn(0, 200),
        conflictingCount = conflictingCount.coerceIn(0, 200),
        insufficientCount = insufficientCount.coerceIn(0, 200),
        notCheckedCount = notCheckedCount.coerceIn(0, 200),
    )

    private fun safeWebUrl(raw: String?): String? = runCatching {
        val uri = URI(raw?.takeIf { it.length <= 2_048 } ?: return null).normalize()
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.userInfo != null) return null
        val safeQuery = uri.rawQuery?.split('&')?.joinToString("&") { pair ->
            val key = pair.substringBefore('=').let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            if (SensitiveQueryKey.containsMatchIn(key)) "${pair.substringBefore('=')}=REDACTED" else pair
        }
        val authority = (if (host.contains(':')) "[$host]" else host) +
            (uri.port.takeIf { it >= 0 }?.let { ":$it" } ?: "")
        URI("$scheme://$authority${uri.rawPath.orEmpty()}${safeQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
    }.getOrNull()

    private fun fileName(value: String): String = oneLine(value.replace('\\', '/').substringAfterLast('/'), 180)

    private fun isAbsoluteLocalPath(value: String): Boolean = value.startsWith('/') ||
        value.startsWith("\\\\") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)

    private fun scrubPrivatePaths(value: String): String = PrivatePath.replace(value, "[local path omitted]")

    private fun oneLine(value: String, max: Int): String = value
        .replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(max)

    private fun iso(epochMillis: Long): String = runCatching { Instant.ofEpochMilli(epochMillis).toString() }
        .getOrDefault(Instant.EPOCH.toString())

    private val SensitiveQueryKey = Regex("(?i)(?:key|token|secret|auth|signature|password|passwd|credential|session|sig)")
    private val PrivatePath = Regex("""(?i)(?:[A-Z]:[\\/][^\s<>"']+|\\\\[^\s<>"']+|/(?:data|storage|private|home|Users)/[^\s<>"']+)""")
}
