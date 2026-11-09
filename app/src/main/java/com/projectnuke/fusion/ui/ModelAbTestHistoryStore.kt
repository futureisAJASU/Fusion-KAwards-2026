package com.projectnuke.fusion.ui

import android.content.Context
import java.io.File
import com.projectnuke.fusion.util.BoundedUtf8FileRead
import com.projectnuke.fusion.util.DebtQuarantine
import com.projectnuke.fusion.util.DebtQuarantineOutcome
import com.projectnuke.fusion.util.readUtf8FileBounded
import com.projectnuke.fusion.util.writeTextAtomically
import com.projectnuke.fusion.util.FallbackCauseFormatter
import com.projectnuke.fusion.util.toKoreanMtpStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class AbResultRating {
    NONE,
    PREFERRED,
    DISLIKED
}

data class StoredAbTestResult(
    val targetLabel: String,
    val modelName: String,
    val modelId: String?,
    val accelerator: String,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val mtpEnabled: Boolean,
    val reasoningEnabled: Boolean,
    val memoryEnabled: Boolean,
    val answer: String?,
    val success: Boolean,
    val errorSummary: String?,
    val firstTokenLatencyMs: Long?,
    val totalGenerationTimeMs: Long,
    val estimatedTokens: Int,
    val totalTokensPerSecond: Double,
    val decodeTokensPerSecond: Double?,
    /** Phase 8: runtime-truthful applied fields captured from the immutable snapshot. */
    val selectedTextBackend: String? = null,
    val selectedVisionBackend: String? = null,
    val mtpRuntimeStatus: String? = null,
    val fallbackEventCodes: String? = null,
    val nativeTtftSeconds: Double? = null,
    val nativePrefillTokensPerSecond: Double? = null,
    val nativeDecodeTokensPerSecond: Double? = null,
    val modelFingerprintPath: String? = null,
    val modelFingerprintSize: Long? = null,
    val modelFingerprintModifiedAt: Long? = null,
    /** Phase B: preserve the full model fingerprint including validator
     * version and MTP capability so stored history distinguishes a model
     * whose drafter support changed across a model-file replacement. */
    val modelFingerprintValidationVersion: Int? = null,
    val modelFingerprintMtpSupported: Boolean? = null,
    /** Phase B: machine-readable generation failure kind when [success] is
     * false. Null for acquisition failures that never reached a running
     * engine, and for successful targets. */
    val generationFailureKind: String? = null,
    val rating: AbResultRating = AbResultRating.NONE
)

data class StoredAbTestSession(
    val id: String,
    val fullPrompt: String,
    val createdAt: Long,
    val results: List<StoredAbTestResult>
) {
    val promptPreview: String get() = fullPrompt.replace(Regex("""\s+"""), " ").trim().take(100)
    val targetCount: Int get() = results.size
    val failureCount: Int get() = results.count { !it.success }
}

internal sealed interface AbHistoryLoad {
    data object Missing : AbHistoryLoad
    data class Valid(val sessions: List<StoredAbTestSession>) : AbHistoryLoad
    data object Corrupt : AbHistoryLoad
    data object Oversized : AbHistoryLoad
}

object ModelAbTestHistoryStore {
    internal const val MaxSessions = 20
    internal const val MaxResultsPerSession = 8
    internal const val MaxPromptChars = 16_384
    internal const val MaxAnswerChars = 65_536
    internal const val MaxSessionIdChars = 200
    internal const val MaxTargetLabelChars = 200
    internal const val MaxModelNameChars = 300
    internal const val MaxModelIdChars = 300
    internal const val MaxAcceleratorChars = 80
    internal const val MaxErrorSummaryChars = 1_000
    internal const val MaxSelectedTextBackendChars = 80
    internal const val MaxSelectedVisionBackendChars = 80
    internal const val MaxMtpRuntimeStatusChars = 128
    internal const val MaxFallbackEventCodesChars = 4_096
    internal const val MaxModelFingerprintPathChars = 4_096
    internal const val MaxGenerationFailureKindChars = 128
    /** Conservative six-byte JSON escape expansion plus structural overhead. */
    internal const val MAX_HISTORY_FILE_BYTES = 96 * 1024 * 1024
    private const val FileName = "fusion_ab_test_history.json"
    private const val QuarantineSuffix = ".corrupt.bak"
    private val writeMutex = Mutex()
    internal var quarantineHook: ((File, Boolean) -> DebtQuarantineOutcome)? = null

    /**
     * Display read: corruption renders as an empty history so the UI stays
     * usable. Mutations use the typed load below and never overwrite
     * unreadable evidence without a secured quarantine.
     */
    fun load(context: Context): List<StoredAbTestSession> {
        return when (val load = loadLocked(historyFile(context))) {
            is AbHistoryLoad.Valid -> load.sessions
            is AbHistoryLoad.Missing,
            is AbHistoryLoad.Corrupt,
            is AbHistoryLoad.Oversized -> emptyList()
        }
    }

    internal fun loadResult(
        file: File,
        maxBytes: Int = MAX_HISTORY_FILE_BYTES,
    ): AbHistoryLoad = loadLocked(file, maxBytes)

    private fun loadLocked(file: File, maxBytes: Int = MAX_HISTORY_FILE_BYTES): AbHistoryLoad {
        if (!file.isFile) return AbHistoryLoad.Missing
        val raw = when (val read = readUtf8FileBounded(file, maxBytes)) {
            is BoundedUtf8FileRead.Success -> read.text
            is BoundedUtf8FileRead.Oversized -> return AbHistoryLoad.Oversized
            is BoundedUtf8FileRead.Failed -> return AbHistoryLoad.Corrupt
        }
        return try {
            run {
                val array = JSONArray(raw)
                // The writer caps sessions at MaxSessions, so a longer array is
                // out-of-contract rather than silently truncatable history.
                if (array.length() > MaxSessions) return@run AbHistoryLoad.Corrupt
                // Typed contract: a malformed top-level session or a malformed
                // required result inside a session makes the WHOLE file CORRUPT.
                // Silently dropping one entry would let the next save rewrite
                // history without that diagnostic evidence.
                val sessions = ArrayList<StoredAbTestSession>(array.length())
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index)
                        ?: return@run AbHistoryLoad.Corrupt
                    val session = obj.toStoredSession()
                        ?: return@run AbHistoryLoad.Corrupt
                    sessions.add(session)
                }
                AbHistoryLoad.Valid(sessions)
            }
        } catch (_: Exception) {
            AbHistoryLoad.Corrupt
        }
    }

    private fun quarantineLocked(file: File, allowCopyFallback: Boolean): Boolean = when (
        quarantineHook?.invoke(file, allowCopyFallback)
            ?: DebtQuarantine.quarantine(file, QuarantineSuffix, allowCopyFallback)
    ) {
        is DebtQuarantineOutcome.Secured,
        DebtQuarantineOutcome.SourceMissing -> true
        DebtQuarantineOutcome.Failed -> false
    }

    suspend fun loadAsync(context: Context): List<StoredAbTestSession> {
        return withContext(Dispatchers.IO) {
            load(context)
        }
    }

    suspend fun save(context: Context, session: StoredAbTestSession): Boolean {
        return storageTransaction {
            val file = historyFile(context)
            val base = when (val load = loadLocked(file)) {
                is AbHistoryLoad.Valid -> load.sessions
                is AbHistoryLoad.Missing -> emptyList()
                // Quarantine the unreadable artifact, then land the new
                // session instead of silently completing a corrupt file.
                is AbHistoryLoad.Corrupt -> {
                    if (!quarantineLocked(file, allowCopyFallback = true)) return@storageTransaction false
                    emptyList()
                }
                is AbHistoryLoad.Oversized -> {
                    if (!quarantineLocked(file, allowCopyFallback = false)) return@storageTransaction false
                    emptyList()
                }
            }
            val next = (listOf(session) + base.filterNot { it.id == session.id })
                .sortedByDescending { it.createdAt }
                .take(MaxSessions)
            write(context, next)
        }
    }

    suspend fun delete(context: Context, sessionId: String): Boolean {
        return storageTransaction {
            val file = historyFile(context)
            val base = when (val load = loadLocked(file)) {
                is AbHistoryLoad.Valid -> load.sessions
                is AbHistoryLoad.Missing -> return@storageTransaction true
                // Fail closed: without readable history the wrong session
                // could be removed and evidence destroyed.
                is AbHistoryLoad.Corrupt,
                is AbHistoryLoad.Oversized -> return@storageTransaction false
            }
            write(context, base.filterNot { it.id == sessionId })
        }
    }

    suspend fun clear(context: Context): Boolean {
        return writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val file = historyFile(context)
                if (file.isFile) {
                    when (loadLocked(file)) {
                        is AbHistoryLoad.Corrupt ->
                            if (!quarantineLocked(file, allowCopyFallback = true)) return@withContext false
                        is AbHistoryLoad.Oversized ->
                            if (!quarantineLocked(file, allowCopyFallback = false)) return@withContext false
                        else -> Unit
                    }
                }
                // Clear means removing all local history, including a secured
                // corruption backup left by an earlier recovery operation.
                val quarantine = File(file.parentFile, file.name + QuarantineSuffix)
                val primaryDeleted = !file.exists() || file.delete()
                primaryDeleted && (!quarantine.exists() || quarantine.delete())
            }
        }
    }

    suspend fun updateRating(context: Context, sessionId: String, targetLabel: String, rating: AbResultRating): Boolean {
        return storageTransaction {
            val file = historyFile(context)
            val base = when (val load = loadLocked(file)) {
                is AbHistoryLoad.Valid -> load.sessions
                is AbHistoryLoad.Missing -> return@storageTransaction false
                is AbHistoryLoad.Corrupt,
                is AbHistoryLoad.Oversized -> return@storageTransaction false
            }
            if (base.none { it.id == sessionId }) return@storageTransaction false
            val updated = base.map { session ->
                if (session.id != sessionId) {
                    session
                } else {
                    session.copy(
                        results = session.results.map { result ->
                            if (result.targetLabel == targetLabel) result.copy(rating = rating) else result
                        }
                    )
                }
            }
            write(context, updated)
        }
    }

    private suspend fun <T> storageTransaction(block: suspend () -> T): T = writeMutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }

    private suspend fun write(context: Context, sessions: List<StoredAbTestSession>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val array = JSONArray()
                sessions.take(MaxSessions).forEach { array.put(it.bounded().toJson()) }
                val encoded = array.toString(2)
                if (encoded.toByteArray(Charsets.UTF_8).size > MAX_HISTORY_FILE_BYTES) {
                    return@withContext false
                }
                writeTextAtomically(historyFile(context), encoded)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun historyFile(context: Context): File = File(context.filesDir, FileName)

    private fun StoredAbTestSession.bounded(): StoredAbTestSession = copy(
        id = id.take(MaxSessionIdChars),
        fullPrompt = fullPrompt.take(MaxPromptChars),
        results = results.take(MaxResultsPerSession).map {
            it.copy(
                targetLabel = it.targetLabel.take(MaxTargetLabelChars),
                modelName = it.modelName.take(MaxModelNameChars),
                modelId = it.modelId?.take(MaxModelIdChars),
                accelerator = it.accelerator.take(MaxAcceleratorChars),
                answer = it.answer?.take(MaxAnswerChars),
                errorSummary = it.errorSummary?.take(MaxErrorSummaryChars),
                selectedTextBackend = it.selectedTextBackend?.take(MaxSelectedTextBackendChars),
                selectedVisionBackend = it.selectedVisionBackend?.take(MaxSelectedVisionBackendChars),
                mtpRuntimeStatus = it.mtpRuntimeStatus?.take(MaxMtpRuntimeStatusChars),
                fallbackEventCodes = it.fallbackEventCodes?.take(MaxFallbackEventCodesChars),
                modelFingerprintPath = it.modelFingerprintPath?.take(MaxModelFingerprintPathChars),
                generationFailureKind = it.generationFailureKind?.take(MaxGenerationFailureKindChars),
            )
        },
    )

    /** Public test seam for the legal writer representation, not a fixture size. */
    internal fun maximumLegalEncodedBytes(): Int {
        val resultTextChars =
            MaxTargetLabelChars + MaxModelNameChars + MaxModelIdChars + MaxAcceleratorChars +
                MaxAnswerChars + MaxErrorSummaryChars + MaxSelectedTextBackendChars +
                MaxSelectedVisionBackendChars + MaxMtpRuntimeStatusChars +
                MaxFallbackEventCodesChars + MaxModelFingerprintPathChars + MaxGenerationFailureKindChars
        val perResult = resultTextChars * 6 + 4_096
        val perSession = (MaxSessionIdChars + MaxPromptChars) * 6 + 4_096 + MaxResultsPerSession * perResult
        return MaxSessions * perSession + 4_096
    }
}

private fun StoredAbTestSession.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("fullPrompt", fullPrompt)
        .put("createdAt", createdAt)
        .put("results", JSONArray().also { array -> results.forEach { array.put(it.toJson()) } })
}

private fun StoredAbTestResult.toJson(): JSONObject {
    val obj = JSONObject()
        .put("targetLabel", targetLabel)
        .put("modelName", modelName)
        .put("modelId", modelId)
        .put("accelerator", accelerator)
        .put("maxTokens", maxTokens)
        .put("temperature", temperature.toDouble())
        .put("topK", topK)
        .put("topP", topP.toDouble())
        .put("mtpEnabled", mtpEnabled)
        .put("reasoningEnabled", reasoningEnabled)
        .put("memoryEnabled", memoryEnabled)
        .put("answer", answer)
        .put("success", success)
        .put("errorSummary", errorSummary)
        .put("firstTokenLatencyMs", firstTokenLatencyMs)
        .put("totalGenerationTimeMs", totalGenerationTimeMs)
        .put("estimatedTokens", estimatedTokens)
        .put("totalTokensPerSecond", totalTokensPerSecond)
        .put("decodeTokensPerSecond", decodeTokensPerSecond)
        .put("rating", rating.name)
    selectedTextBackend?.let { obj.put("selectedTextBackend", it) }
    selectedVisionBackend?.let { obj.put("selectedVisionBackend", it) }
    mtpRuntimeStatus?.let { obj.put("mtpRuntimeStatus", it) }
    fallbackEventCodes?.let { obj.put("fallbackEventCodes", it) }
    nativeTtftSeconds?.let { obj.put("nativeTtftSeconds", it) }
    nativePrefillTokensPerSecond?.let { obj.put("nativePrefillTokensPerSecond", it) }
    nativeDecodeTokensPerSecond?.let { obj.put("nativeDecodeTokensPerSecond", it) }
    modelFingerprintPath?.let { obj.put("modelFingerprintPath", it) }
    modelFingerprintSize?.let { obj.put("modelFingerprintSize", it) }
    modelFingerprintModifiedAt?.let { obj.put("modelFingerprintModifiedAt", it) }
    modelFingerprintValidationVersion?.let { obj.put("modelFingerprintValidationVersion", it) }
    modelFingerprintMtpSupported?.let { obj.put("modelFingerprintMtpSupported", it) }
    generationFailureKind?.let { obj.put("generationFailureKind", it) }
    return obj
}

private fun JSONObject.toStoredSession(): StoredAbTestSession? {
    val sessionId = strictString("id") ?: return null
    if (sessionId.isBlank() || sessionId.length > ModelAbTestHistoryStore.MaxSessionIdChars) return null
    val fullPrompt = strictString("fullPrompt", allowNull = false, default = "") ?: ""
    if (fullPrompt.length > ModelAbTestHistoryStore.MaxPromptChars) return null
    val createdAt = strictLong("createdAt", default = 0L)
    // Present-but-non-array results are out-of-writer-contract; an absent
    // results field stays backward compatible and means empty.
    val resultArray = if (has("results")) {
        get("results") as? JSONArray ?: return null
    } else JSONArray()
    if (resultArray.length() > ModelAbTestHistoryStore.MaxResultsPerSession) return null
    return StoredAbTestSession(
        id = sessionId,
        fullPrompt = fullPrompt,
        createdAt = createdAt,
        results = buildList {
            for (index in 0 until resultArray.length()) {
                val result = resultArray.optJSONObject(index)?.toStoredResult() ?: return null
                add(result)
            }
        }
    )
}

private fun JSONObject.toStoredResult(): StoredAbTestResult? {
    val label = strictString("targetLabel") ?: return null
    if (label.isBlank() || label.length > ModelAbTestHistoryStore.MaxTargetLabelChars) return null
    // D2.3: writer-required primitive fields reject writer-impossible types
    // instead of silently coercing through opt* defaults. A present-but-wrong
    // type makes the whole result unparseable (returns null -> Corrupt session).
    // Absent legacy fields retain the original opt* defaults, not today's
    // generation defaults. Nullable fields accept the JSON null emitted by
    // the historical writer.
    val modelName = strictString("modelName", allowNull = false, default = "") ?: ""
    if (modelName.length > ModelAbTestHistoryStore.MaxModelNameChars) return null
    val modelId = strictString("modelId")
    if (modelId != null && modelId.length > ModelAbTestHistoryStore.MaxModelIdChars) return null
    val accelerator = strictString("accelerator", allowNull = false, default = "") ?: ""
    if (accelerator.length > ModelAbTestHistoryStore.MaxAcceleratorChars) return null
    val maxTokens = strictInt("maxTokens", default = 0)
    val temperature = strictNumber("temperature", default = 0.0).toFloat().also {
        if (!it.isFinite()) throw IllegalArgumentException("temperature not finite")
    }
    val topK = strictInt("topK", default = 0)
    val topP = strictNumber("topP", default = 0.0).toFloat().also {
        if (!it.isFinite()) throw IllegalArgumentException("topP not finite")
    }
    val mtpEnabled = strictBoolean("mtpEnabled", default = false)
    val reasoningEnabled = strictBoolean("reasoningEnabled", default = false)
    val memoryEnabled = strictBoolean("memoryEnabled", default = false)
    val answer = strictString("answer")
    if (answer != null && answer.length > ModelAbTestHistoryStore.MaxAnswerChars) return null
    val success = strictBoolean("success", default = false)
    val errorSummary = strictString("errorSummary")
    if (errorSummary != null && errorSummary.length > ModelAbTestHistoryStore.MaxErrorSummaryChars) return null
    val firstTokenLatencyMs = strictNullableLong("firstTokenLatencyMs")?.takeIf { it > 0L }
    val totalGenerationTimeMs = strictLong("totalGenerationTimeMs", default = 0L)
    val estimatedTokens = strictInt("estimatedTokens", default = 0)
    val totalTokensPerSecond = strictNumber("totalTokensPerSecond", default = 0.0)
    val decodeTokensPerSecond = strictNullableNumber("decodeTokensPerSecond")
    val rating = if (has("rating")) {
        val rawRating = strictString("rating", allowNull = false)
            ?: throw IllegalArgumentException("rating missing")
        try {
            AbResultRating.valueOf(rawRating)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("rating invalid")
        }
    } else AbResultRating.NONE
    return StoredAbTestResult(
        targetLabel = label,
        modelName = modelName,
        modelId = modelId?.takeIf { it.isNotBlank() },
        accelerator = accelerator,
        maxTokens = maxTokens,
        temperature = temperature,
        topK = topK,
        topP = topP,
        mtpEnabled = mtpEnabled,
        reasoningEnabled = reasoningEnabled,
        memoryEnabled = memoryEnabled,
        answer = answer?.takeIf { it.isNotBlank() },
        success = success,
        errorSummary = errorSummary?.takeIf { it.isNotBlank() },
        firstTokenLatencyMs = firstTokenLatencyMs,
        totalGenerationTimeMs = totalGenerationTimeMs,
        estimatedTokens = estimatedTokens,
        totalTokensPerSecond = totalTokensPerSecond,
        decodeTokensPerSecond = decodeTokensPerSecond,
        selectedTextBackend = strictBoundedOptionalString("selectedTextBackend", ModelAbTestHistoryStore.MaxSelectedTextBackendChars),
        selectedVisionBackend = strictBoundedOptionalString("selectedVisionBackend", ModelAbTestHistoryStore.MaxSelectedVisionBackendChars),
        mtpRuntimeStatus = strictBoundedOptionalString("mtpRuntimeStatus", ModelAbTestHistoryStore.MaxMtpRuntimeStatusChars),
        fallbackEventCodes = strictBoundedOptionalString("fallbackEventCodes", ModelAbTestHistoryStore.MaxFallbackEventCodesChars),
        nativeTtftSeconds = strictNullableNumber("nativeTtftSeconds", rejectNull = true),
        nativePrefillTokensPerSecond = strictNullableNumber("nativePrefillTokensPerSecond", rejectNull = true),
        nativeDecodeTokensPerSecond = strictNullableNumber("nativeDecodeTokensPerSecond", rejectNull = true),
        modelFingerprintPath = strictBoundedOptionalString("modelFingerprintPath", ModelAbTestHistoryStore.MaxModelFingerprintPathChars),
        modelFingerprintSize = strictNullableLong("modelFingerprintSize", rejectNull = true),
        modelFingerprintModifiedAt = strictNullableLong("modelFingerprintModifiedAt", rejectNull = true),
        modelFingerprintValidationVersion = strictNullableInt("modelFingerprintValidationVersion", rejectNull = true),
        modelFingerprintMtpSupported = strictNullableBoolean("modelFingerprintMtpSupported", rejectNull = true),
        generationFailureKind = strictBoundedOptionalString("generationFailureKind", ModelAbTestHistoryStore.MaxGenerationFailureKindChars),
        rating = rating
    )
}

private fun JSONObject.strictBoundedOptionalString(key: String, maxChars: Int): String? {
    val value = strictString(key, allowNull = false)
    if (value != null && value.length > maxChars) throw IllegalArgumentException("$key too long")
    return value
}

private fun JSONObject.strictString(
    key: String,
    allowNull: Boolean = true,
    default: String? = null,
): String? {
    if (!has(key)) return default
    if (isNull(key)) {
        if (allowNull) return null
        throw IllegalArgumentException("$key not a string")
    }
    return get(key) as? String ?: throw IllegalArgumentException("$key not a string")
}

private fun JSONObject.strictBoolean(key: String, default: Boolean): Boolean {
    if (!has(key)) return default
    if (isNull(key)) throw IllegalArgumentException("$key not a boolean")
    return get(key) as? Boolean ?: throw IllegalArgumentException("$key not a boolean")
}

private fun JSONObject.strictNullableBoolean(key: String, rejectNull: Boolean = false): Boolean? {
    if (!has(key)) return null
    if (isNull(key)) {
        if (!rejectNull) return null
        throw IllegalArgumentException("$key not a boolean")
    }
    return get(key) as? Boolean ?: throw IllegalArgumentException("$key not a boolean")
}

private fun JSONObject.strictInt(key: String, default: Int): Int {
    if (!has(key)) return default
    if (isNull(key)) throw IllegalArgumentException("$key not an int")
    return get(key) as? Int ?: throw IllegalArgumentException("$key not an int")
}

private fun JSONObject.strictNullableInt(key: String, rejectNull: Boolean = false): Int? {
    if (!has(key)) return null
    if (isNull(key)) {
        if (!rejectNull) return null
        throw IllegalArgumentException("$key not an int")
    }
    return get(key) as? Int ?: throw IllegalArgumentException("$key not an int")
}

private fun JSONObject.strictLong(key: String, default: Long): Long {
    if (!has(key)) return default
    if (isNull(key)) throw IllegalArgumentException("$key not a long")
    return when (val raw = get(key)) {
        is Long -> raw
        is Int -> raw.toLong()
        else -> throw IllegalArgumentException("$key not a long")
    }
}

private fun JSONObject.strictNullableLong(key: String, rejectNull: Boolean = false): Long? {
    if (!has(key)) return null
    if (isNull(key)) {
        if (!rejectNull) return null
        throw IllegalArgumentException("$key not a long")
    }
    return when (val raw = get(key)) {
        is Long -> raw
        is Int -> raw.toLong()
        else -> throw IllegalArgumentException("$key not a long")
    }
}

private fun JSONObject.strictNumber(key: String, default: Double): Double {
    if (!has(key)) return default
    if (isNull(key)) throw IllegalArgumentException("$key not a number")
    val value = (get(key) as? Number)?.toDouble()
        ?: throw IllegalArgumentException("$key not a number")
    if (!value.isFinite()) throw IllegalArgumentException("$key not finite")
    return value
}

private fun JSONObject.strictNullableNumber(key: String, rejectNull: Boolean = false): Double? {
    if (!has(key)) return null
    if (isNull(key)) {
        if (!rejectNull) return null
        throw IllegalArgumentException("$key not a number")
    }
    val value = (get(key) as? Number)?.toDouble()
        ?: throw IllegalArgumentException("$key not a number")
    if (!value.isFinite()) throw IllegalArgumentException("$key not finite")
    return value
}

fun StoredAbTestSession.toMarkdown(): String = buildString {
    appendLine("# Fusion A/B 테스트 결과")
    appendLine()
    appendLine("- 날짜: ${formatAbHistoryTime(createdAt)}")
    appendLine("- 프롬프트: $fullPrompt")
    appendLine("- 대상 수: $targetCount")
    results.forEach { result ->
        appendLine()
        appendLine("## ${result.targetLabel}")
        appendLine("- 모델: ${result.modelName}")
        appendLine("- 설정: ${result.settingsSummary()}")
        appendLine("- 속도: 첫 토큰 ${result.firstTokenLatencyMs?.let { "${it}ms" } ?: "측정 불가"} · 총 ${result.totalGenerationTimeMs}ms · 전체 ${result.totalTokensPerSecond.formatAbSpeed()} · 디코딩 ${result.decodeTokensPerSecond?.formatAbSpeed() ?: "측정 불가"}")
        appendLine("- 상태: ${if (result.success) "성공" else "실패"}")
        // Applied runtime info (from immutable snapshot)
        val appliedParts = mutableListOf<String>()
        result.selectedTextBackend?.let { appliedParts.add("백엔드: $it") }
        result.mtpRuntimeStatus?.let { appliedParts.add("MTP: ${mtpStatusKorean(it)}") }
        result.selectedVisionBackend?.let { appliedParts.add("비전: $it") }
        if (appliedParts.isNotEmpty()) {
            appendLine("- 적용: ${appliedParts.joinToString(" · ")}")
        }
        val rendered = FallbackCauseFormatter.renderStoredCodesForDisplay(result.fallbackEventCodes)
        if (rendered.isNotBlank()) {
            appendLine("- 폴백: $rendered")
        }
        if (result.success) {
            appendLine()
            appendLine("답변:")
            appendLine(result.answer.orEmpty())
        } else {
            appendLine("- 오류: ${result.errorSummary ?: "이 대상의 실행에 실패했습니다."}")
        }
    }
    appendLine()
    appendLine("## 비교 요약")
    appendLine(buildStoredAbComparisonSummary(this@toMarkdown))
}.trim()

fun StoredAbTestResult.settingsSummary(): String {
    var result = "가속기=$accelerator · 최대 출력=$maxTokens · 온도=$temperature · Top-K=$topK · Top-P=$topP · MTP=${if (mtpEnabled) "켜짐" else "꺼짐"} · 추론=${if (reasoningEnabled) "켜짐" else "꺼짐"} · 메모리=${if (memoryEnabled) "사용" else "사용 안 함"}"
    if (!fallbackEventCodes.isNullOrBlank()) {
        val rendered = FallbackCauseFormatter.renderStoredCodesForDisplay(fallbackEventCodes)
        if (rendered.isNotBlank()) {
            result += " · 폴백: $rendered"
        }
    }
    return result
}

fun buildStoredAbComparisonSummary(session: StoredAbTestSession): String {
    val successful = session.results.filter { it.success }
    val fastestFirst = successful.filter { it.firstTokenLatencyMs != null }.minByOrNull { it.firstTokenLatencyMs ?: Long.MAX_VALUE }
    val fastestDecode = successful.filter { it.decodeTokensPerSecond != null }.maxByOrNull { it.decodeTokensPerSecond ?: 0.0 }
    val shortest = successful.minByOrNull { it.answer.orEmpty().length }
    val longest = successful.maxByOrNull { it.answer.orEmpty().length }
    return buildString {
        appendLine("- 가장 빠른 응답 시작: ${fastestFirst?.targetLabel ?: "측정 불가"}")
        appendLine("- 가장 높은 디코딩 속도: ${fastestDecode?.targetLabel ?: "측정 불가"}")
        appendLine("- 가장 짧은 답변: ${shortest?.targetLabel ?: "측정 불가"}")
        appendLine("- 가장 긴 답변: ${longest?.targetLabel ?: "측정 불가"}")
        append("- 실패한 대상: ${if (session.failureCount == 0) "없음" else "${session.failureCount}개"}")
    }
}

fun formatAbHistoryTime(timestamp: Long): String {
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}

private fun Double.formatAbSpeed(): String = String.format(java.util.Locale.US, "%.1f tok/s", this)

/** Localizes a persisted MtpRuntimeStatus name into Korean for on-screen display. */
private fun mtpStatusKorean(name: String): String {
    val status = try {
        com.projectnuke.fusion.llm.MtpRuntimeStatus.valueOf(name)
    } catch (_: IllegalArgumentException) {
        null
    } ?: return name
    return status.toKoreanMtpStatus()
}
