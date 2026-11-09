package com.projectnuke.fusion.chat

import com.projectnuke.fusion.retrieval.LocalRagSelection
import org.json.JSONArray
import org.json.JSONObject

/**
 * Next Action Flow v1C — durable answer context receipt.
 *
 * A small versioned provenance receipt stored as HIDDEN metadata alongside a
 * persisted assistant answer. It records which INFORMATION PATHS were ACTUALLY
 * used when the answer was produced — proven from the frozen/actual request
 * execution, NEVER reconstructed from current app state, NEVER a reasoning
 * chain, confidence score, prompt log, or diagnostics dump.
 *
 * The receipt is immutable per persisted assistant answer/version: it is
 * written once at persistence and later project/category/conversation changes
 * never rewrite it. Old answers without a receipt simply have no inspector.
 *
 * Deliberately NOT stored: system/project instruction text, conversation
 * history, attachment contents, RAG excerpts, absolute paths, API
 * keys/secrets, authorization headers, search payloads, thinking traces, or
 * user prompt copies.
 *
 * Envelope pattern matches the existing hidden-metadata envelopes
 * ([FUSION_SEARCH_SOURCES_JSON], [FUSION_TRUST_ROUTE_JSON],
 * [FUSION_LOCAL_RAG_SOURCES_JSON], <fusion_metrics>); stripped by the single
 * canonical strip chain.
 */
enum class AnswerContextRoute { LOCAL_MODEL, EXTERNAL_AI_API, DETERMINISTIC }

data class AnswerContextReceipt(
    val schemaVersion: Int,
    val route: AnswerContextRoute,
    val interactionMode: String?,
    /** True means prior turns were provided to request preparation, not that
     * the final fitter necessarily retained every turn. */
    val priorConversationContextProvided: Boolean?,
    val projectContextUsed: Boolean,
    val projectInstructionsApplied: Boolean,
    val inputAttachmentCount: Int,
    val inputImageCount: Int,
    val localRagDocumentCount: Int,
    val localRagChunkCount: Int,
    val webSourceCount: Int,
    /** Request-time source boundaries needed only for explicit Research refresh. */
    val projectContextId: Long? = null,
    val sourceScopeKind: String? = null,
    val sourceScopeProjectId: Long? = null,
    val sourceScopeFileIds: List<Long> = emptyList(),
    val selectionProjectIds: List<Long> = emptyList(),
    val projectDecisionContextIncluded: Boolean = false,
) {
    companion object {
        const val SCHEMA_VERSION = 3
        const val START_MARKER = "[FUSION_ANSWER_CONTEXT_JSON]"
        const val END_MARKER = "[/FUSION_ANSWER_CONTEXT_JSON]"
        const val MAX_RAW_JSON_CHARS = 4_000
        const val MAX_COUNT = 10_000

        /**
         * Bounded scalar builder with explicit per-field control (privacy:
         * instruct/app/url/excerpt text never enters the receipt).
         */
        fun build(
            route: AnswerContextRoute,
            interactionMode: String? = null,
            priorConversationContextProvided: Boolean? = null,
            projectContextUsed: Boolean = false,
            projectInstructionsApplied: Boolean = false,
            inputAttachmentCount: Int = 0,
            inputImageCount: Int = 0,
            localRagDocumentCount: Int = 0,
            localRagChunkCount: Int = 0,
            webSourceCount: Int = 0,
            projectContextId: Long? = null,
            sourceScopeKind: String? = null,
            sourceScopeProjectId: Long? = null,
            sourceScopeFileIds: List<Long> = emptyList(),
            selectionProjectIds: List<Long> = emptyList(),
            projectDecisionContextIncluded: Boolean = false,
        ): AnswerContextReceipt = AnswerContextReceipt(
            schemaVersion = SCHEMA_VERSION,
            route = route,
            interactionMode = interactionMode,
            priorConversationContextProvided = priorConversationContextProvided,
            projectContextUsed = projectContextUsed,
            projectInstructionsApplied = projectInstructionsApplied,
            inputAttachmentCount = clampCount(inputAttachmentCount),
            inputImageCount = clampCount(inputImageCount),
            localRagDocumentCount = clampCount(localRagDocumentCount),
            localRagChunkCount = clampCount(localRagChunkCount),
            webSourceCount = clampCount(webSourceCount),
            projectContextId = projectContextId?.takeIf { it > 0L },
            sourceScopeKind = sourceScopeKind,
            sourceScopeProjectId = sourceScopeProjectId?.takeIf { it > 0L },
            sourceScopeFileIds = sourceScopeFileIds.filter { it > 0L }.distinct().take(8),
            selectionProjectIds = selectionProjectIds.filter { it > 0L }.distinct().take(8),
            projectDecisionContextIncluded = projectDecisionContextIncluded && projectContextUsed,
        )

        /**
         * Execution-truth builder: derives document/chunk counts from ACTUAL
         * selected Local RAG selections, and project facts from the immutable
         * request snapshot. A nonblank instruction is considered applied only
         * for a LOCAL_MODEL request, whose production prompt assembly inserts
         * [ProjectInstructionPromptPolicy.modelMessage] before fitting; the
         * external and deterministic paths never claim project instructions.
         */
        fun buildFromExecution(
            route: AnswerContextRoute,
            interactionMode: ChatInteractionMode? = null,
            priorConversationContextProvided: Boolean? = null,
            projectContext: ProjectContextSnapshot? = null,
            ragSelections: List<LocalRagSelection>? = null,
            inputAttachmentCount: Int = 0,
            inputImageCount: Int = 0,
            webSourceCount: Int = 0,
            sourceScope: ProjectSourceScope? = null,
            selectionContext: SelectionContextSnapshot? = null,
            projectDecisionContextIncluded: Boolean = false,
        ): AnswerContextReceipt {
            val selections = ragSelections.orEmpty()
            val distinctDocs = selections
                .map { selection -> selection.source.attachmentId.ifBlank { selection.source.displayName } }
                .filter { it.isNotBlank() }
                .toSet()
            return build(
                route = route,
                interactionMode = interactionMode?.name,
                priorConversationContextProvided = priorConversationContextProvided,
                projectContextUsed = projectContext != null,
                projectInstructionsApplied = route == AnswerContextRoute.LOCAL_MODEL &&
                    projectContext?.instructions?.isNotBlank() == true,
                inputAttachmentCount = inputAttachmentCount,
                inputImageCount = inputImageCount,
                localRagDocumentCount = distinctDocs.size,
                localRagChunkCount = selections.size,
                webSourceCount = webSourceCount,
                projectContextId = projectContext?.projectId,
                sourceScopeKind = sourceScope?.kind?.name,
                sourceScopeProjectId = sourceScope?.projectId,
                sourceScopeFileIds = sourceScope?.fileIds.orEmpty(),
                selectionProjectIds = selectionContext?.sources.orEmpty()
                    .mapNotNull { it.identity.projectId }.distinct().take(8),
                projectDecisionContextIncluded = projectDecisionContextIncluded && projectContext != null,
            )
        }

        private fun clampCount(value: Int): Int = value.coerceIn(0, MAX_COUNT)

        fun append(content: String, receipt: AnswerContextReceipt): String {
            val encoded = encode(receipt) ?: return content.trimEnd()
            return content.trimEnd() + "\n\n$encoded"
        }

        fun encode(receipt: AnswerContextReceipt): String? = runCatching {
            val json = JSONObject()
                .put("schemaVersion", receipt.schemaVersion)
                .put("route", receipt.route.name)
                .put("interactionMode", receipt.interactionMode ?: JSONObject.NULL)
                .put("priorConversationContextProvided", receipt.priorConversationContextProvided ?: JSONObject.NULL)
                .put("projectContextUsed", receipt.projectContextUsed)
                .put("projectInstructionsApplied", receipt.projectInstructionsApplied)
                .put("inputAttachmentCount", receipt.inputAttachmentCount)
                .put("inputImageCount", receipt.inputImageCount)
                .put("localRagDocumentCount", receipt.localRagDocumentCount)
                .put("localRagChunkCount", receipt.localRagChunkCount)
                .put("webSourceCount", receipt.webSourceCount)
                .put("projectContextId", receipt.projectContextId ?: JSONObject.NULL)
                .put("sourceScopeKind", receipt.sourceScopeKind ?: JSONObject.NULL)
                .put("sourceScopeProjectId", receipt.sourceScopeProjectId ?: JSONObject.NULL)
                .put("sourceScopeFileIds", JSONArray(receipt.sourceScopeFileIds))
                .put("selectionProjectIds", JSONArray(receipt.selectionProjectIds))
                .put("projectDecisionContextIncluded", receipt.projectDecisionContextIncluded)
            "$START_MARKER\n${json.toString()}\n$END_MARKER"
        }.getOrNull()

        /** Strict schema-v1 decode. Missing fields, wrong JSON types, future
         * versions, unknown enums, and impossible combinations fail closed. */
        fun parse(content: String): AnswerContextReceipt? {
            val block = extractBlock(content) ?: return null
            if (block.length > MAX_RAW_JSON_CHARS) return null
            return runCatching {
                val json = JSONObject(block)
                val version = requiredInt(json, "schemaVersion") ?: return null
                if (version !in 1..SCHEMA_VERSION) return null
                val routeName = json.get("route") as? String ?: return null
                val route = runCatching { AnswerContextRoute.valueOf(routeName) }
                    .getOrNull() ?: return null
                val names = listOf(
                    "inputAttachmentCount",
                    "inputImageCount",
                    "localRagDocumentCount",
                    "localRagChunkCount",
                    "webSourceCount",
                )
                val counts = HashMap<String, Int>(names.size)
                for (name in names) {
                    val value = requiredInt(json, name) ?: return null
                    if (value < 0 || value > MAX_COUNT) return null
                    counts[name] = value
                }
                if (!json.has("interactionMode")) return null
                val interactionMode: String? = if (json.isNull("interactionMode")) {
                    null
                } else {
                    val value = json.get("interactionMode") as? String ?: return null
                    runCatching { ChatInteractionMode.valueOf(value) }.getOrNull()?.name
                        ?: return null
                }
                if (!json.has("priorConversationContextProvided")) return null
                val priorConversationContextProvided: Boolean? = if (json.isNull("priorConversationContextProvided")) {
                    null
                } else {
                    json.get("priorConversationContextProvided") as? Boolean ?: return null
                }
                val projectContextUsed = requiredBoolean(json, "projectContextUsed") ?: return null
                val projectInstructionsApplied =
                    requiredBoolean(json, "projectInstructionsApplied") ?: return null
                if (projectInstructionsApplied && !projectContextUsed) return null
                val projectContextId: Long?
                val sourceScopeKind: String?
                val sourceScopeProjectId: Long?
                val sourceScopeFileIds: List<Long>
                val selectionProjectIds: List<Long>
                val projectDecisionContextIncluded: Boolean
                if (version == 1) {
                    projectContextId = null
                    sourceScopeKind = null
                    sourceScopeProjectId = null
                    sourceScopeFileIds = emptyList()
                    selectionProjectIds = emptyList()
                    projectDecisionContextIncluded = false
                } else {
                    projectContextId = optionalPositiveLong(json, "projectContextId") ?: if (json.isNull("projectContextId")) null else return null
                    sourceScopeKind = optionalString(json, "sourceScopeKind") ?: if (json.isNull("sourceScopeKind")) null else return null
                    sourceScopeProjectId = optionalPositiveLong(json, "sourceScopeProjectId") ?: if (json.isNull("sourceScopeProjectId")) null else return null
                    sourceScopeFileIds = boundedPositiveIds(json, "sourceScopeFileIds") ?: return null
                    selectionProjectIds = boundedPositiveIds(json, "selectionProjectIds") ?: return null
                    projectDecisionContextIncluded = if (version >= 3) {
                        requiredBoolean(json, "projectDecisionContextIncluded") ?: return null
                    } else false
                    if (projectContextId != null && !projectContextUsed) return null
                    if (projectDecisionContextIncluded && !projectContextUsed) return null
                    val scope = sourceScopeKind?.let { runCatching { ProjectSourceScopeKind.valueOf(it) }.getOrNull() }
                    if (sourceScopeKind != null && scope == null) return null
                    when (scope) {
                        ProjectSourceScopeKind.PROJECT -> if (sourceScopeProjectId == null || sourceScopeFileIds.isNotEmpty()) return null
                        ProjectSourceScopeKind.SELECTED_FILES -> if (sourceScopeProjectId == null || sourceScopeFileIds.isEmpty()) return null
                        ProjectSourceScopeKind.AVAILABLE,
                        ProjectSourceScopeKind.CURRENT_ATTACHMENTS -> if (sourceScopeProjectId != null || sourceScopeFileIds.isNotEmpty()) return null
                        null -> if (sourceScopeProjectId != null || sourceScopeFileIds.isNotEmpty()) return null
                    }
                }
                AnswerContextReceipt(
                    schemaVersion = version,
                    route = route,
                    interactionMode = interactionMode,
                    priorConversationContextProvided = priorConversationContextProvided,
                    projectContextUsed = projectContextUsed,
                    projectInstructionsApplied = projectInstructionsApplied,
                    inputAttachmentCount = counts["inputAttachmentCount"] ?: 0,
                    inputImageCount = counts["inputImageCount"] ?: 0,
                    localRagDocumentCount = counts["localRagDocumentCount"] ?: 0,
                    localRagChunkCount = counts["localRagChunkCount"] ?: 0,
                    webSourceCount = counts["webSourceCount"] ?: 0,
                    projectContextId = projectContextId,
                    sourceScopeKind = sourceScopeKind,
                    sourceScopeProjectId = sourceScopeProjectId,
                    sourceScopeFileIds = sourceScopeFileIds,
                    selectionProjectIds = selectionProjectIds,
                    projectDecisionContextIncluded = projectDecisionContextIncluded,
                )
            }.getOrNull()
        }

        private fun requiredInt(json: JSONObject, name: String): Int? {
            if (!json.has(name) || json.isNull(name)) return null
            return when (val value = json.get(name)) {
                is Int -> value
                is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            }
        }

        private fun requiredBoolean(json: JSONObject, name: String): Boolean? {
            if (!json.has(name) || json.isNull(name)) return null
            return json.get(name) as? Boolean
        }

        private fun optionalString(json: JSONObject, name: String): String? {
            if (!json.has(name)) return null
            return if (json.isNull(name)) null else json.get(name) as? String
        }

        private fun optionalPositiveLong(json: JSONObject, name: String): Long? {
            if (!json.has(name) || json.isNull(name)) return null
            val value = when (val raw = json.get(name)) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> return null
            }
            return value.takeIf { it > 0L }
        }

        private fun boundedPositiveIds(json: JSONObject, name: String): List<Long>? {
            if (!json.has(name)) return null
            val array = json.get(name) as? JSONArray ?: return null
            if (array.length() > 8) return null
            val ids = ArrayList<Long>(array.length())
            for (index in 0 until array.length()) {
                val value = when (val raw = array.get(index)) {
                    is Int -> raw.toLong()
                    is Long -> raw
                    else -> return null
                }
                if (value <= 0L) return null
                ids += value
            }
            if (ids.distinct().size != ids.size) return null
            return ids
        }

        private fun extractBlock(content: String): String? {
            val start = content.indexOf(START_MARKER)
            if (start < 0) return null
            val end = content.indexOf(END_MARKER, start)
            if (end < 0) return null
            return content.substring(start + START_MARKER.length, end).trim()
        }

        fun strip(content: String): String =
            Regex("""(?s)\Q$START_MARKER\E.*?\Q$END_MARKER\E""").replace(content, "").trimEnd()
    }
}

/**
 * Answer Context Inspector presentation rows (pure). Renders ONLY facts the
 * receipt actually proves — no ranges, no percentages, no confidence, no
 * reasoning trace. Legacy/unknown stays omitted rather than falsified.
 */
object AnswerContextPresentation {
    data class Row(val section: String, val value: String)

    fun inspectorRows(receipt: AnswerContextReceipt): List<Row> {
        val rows = ArrayList<Row>(7)
        rows += Row("처리", when (receipt.route) {
            AnswerContextRoute.LOCAL_MODEL -> "기기 내 모델"
            AnswerContextRoute.EXTERNAL_AI_API -> "외부 AI"
            AnswerContextRoute.DETERMINISTIC -> "자동 결정 규칙"
        })
        receipt.interactionMode?.let { mode ->
            rows += Row("모드", if (mode == ChatInteractionMode.STUDY.name) "학습 모드" else "일반")
        }
        when (receipt.priorConversationContextProvided) {
            true -> rows += Row("대화", "이전 대화 맥락 제공")
            false -> rows += Row("대화", "이전 대화 맥락 미제공")
            null -> Unit
        }
        if (receipt.projectContextUsed) {
            rows += Row("프로젝트", "프로젝트 컨텍스트 사용")
        }
        if (receipt.projectDecisionContextIncluded) {
            rows += Row("프로젝트 결정", "활성 프로젝트 결정 참고 정보 제공")
        }
        if (receipt.projectInstructionsApplied) {
            rows += Row("프로젝트", "프로젝트 지침 적용")
        }
        if (receipt.inputAttachmentCount > 0 || receipt.inputImageCount > 0) {
            rows += Row(
                "입력",
                "첨부 ${receipt.inputAttachmentCount}개" +
                    if (receipt.inputImageCount > 0) " · 이미지 ${receipt.inputImageCount}개" else "",
            )
        }
        if (receipt.localRagDocumentCount > 0 || receipt.localRagChunkCount > 0) {
            rows += Row(
                "로컬 자료",
                "${receipt.localRagDocumentCount}개 문서" +
                    if (receipt.localRagChunkCount > 0) " · 관련 구간 ${receipt.localRagChunkCount}개" else "",
            )
        }
        if (receipt.webSourceCount > 0) {
            rows += Row("웹", "자료 ${receipt.webSourceCount}개 사용")
        }
        return rows
    }
}
