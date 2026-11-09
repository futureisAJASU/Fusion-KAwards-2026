package com.projectnuke.fusion.chat

import android.content.Context
import android.util.Log
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.DebtQuarantine
import com.projectnuke.fusion.util.DebtQuarantineOutcome
import com.projectnuke.fusion.util.BoundedUtf8FileRead
import com.projectnuke.fusion.util.readUtf8FileBounded
import com.projectnuke.fusion.util.writeTextAtomically
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface DraftStoreLoad {
    data object Missing : DraftStoreLoad
    data class Valid(val drafts: Map<Long, ComposerDraftState>) : DraftStoreLoad
    data object Corrupt : DraftStoreLoad
    data object Oversized : DraftStoreLoad
}

internal sealed interface DraftStoreRecoveryResult {
    data object Recovered : DraftStoreRecoveryResult
    data object NotNeeded : DraftStoreRecoveryResult
    data object Failed : DraftStoreRecoveryResult
}

internal sealed interface DurableDraftReferences {
    data class Available(val paths: Set<String>) : DurableDraftReferences
    data object Unknown : DurableDraftReferences
}

internal open class PersistentComposerDraftStore(
    private val file: File,
    private val resolveManagedAttachment: (String) -> File?,
    private val registerPendingAttachment: (File) -> String?,
) {
    constructor(context: Context) : this(
        file = File(context.applicationContext.filesDir, FILE_NAME),
        resolveManagedAttachment = { path: String ->
            AttachmentStorageManager.resolveManagedAttachment(context.applicationContext, path)
        },
        registerPendingAttachment = AttachmentStorageManager::registerPendingAttachment,
    )

    private val writeMutex = Mutex()
    private var latestWriteId = 0L

    /** Test seam for quarantine failure injection. */
    internal var quarantineHook: ((File, Boolean) -> DebtQuarantineOutcome)? = null

    open suspend fun load(): Map<Long, ComposerDraftState> = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            when (val result = loadResultLocked()) {
                is DraftStoreLoad.Valid -> result.drafts
                // In-memory callers get an empty view for unreadable state, but
                // must consult loadResult()/write semantics: CORRUPT/OVERSIZED
                // does NOT mean no drafts and must not be silently replaced.
                is DraftStoreLoad.Missing,
                is DraftStoreLoad.Corrupt,
                is DraftStoreLoad.Oversized -> emptyMap()
            }
        }
    }

    open suspend fun loadResult(): DraftStoreLoad = withContext(Dispatchers.IO) {
        writeMutex.withLock { loadResultLocked() }
    }

    open suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                if (writeId < latestWriteId) return@withLock false
                // Exact writer contract: reject out-of-contract content BEFORE
                // touching durable state. Never truncate user/recovery semantics.
                if (!isValidDraftMap(drafts)) return@withLock false
                // Fail closed: never silently replace unreadable evidence.
                when (val current = loadResultLocked(rawReadOnly = true)) {
                    is DraftStoreLoad.Corrupt,
                    is DraftStoreLoad.Oversized -> {
                        val allowCopyFallback = current !is DraftStoreLoad.Oversized
                        val outcome = quarantineHook?.invoke(file, allowCopyFallback) ?: quarantineLocked(
                            allowCopyFallback = allowCopyFallback,
                        )
                        if (outcome is DebtQuarantineOutcome.Failed) return@withLock false
                    }
                    is DraftStoreLoad.Missing,
                    is DraftStoreLoad.Valid -> Unit
                }
                val encoded = encode(drafts)
                if (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) return@withLock false
                val ok = try {
                    writeTextAtomically(file, encoded)
                    true
                } catch (_: Exception) {
                    false
                }
                if (ok) latestWriteId = writeId
                ok
            }
        }

    internal fun isValidDraftMap(drafts: Map<Long, ComposerDraftState>): Boolean {
        if (drafts.size > MAX_DRAFT_COUNT) return false
        drafts.forEach { (id, draft) ->
            if (id < 0L) return false
            if (draft.version < 0L) return false
            if (draft.rawInput.length > ComposerDraftPolicy.MAX_INPUT_CHARS) return false
            if (draft.pendingAttachments.size > MAX_ATTACHMENT_COUNT) return false
            draft.pendingAttachments.forEach { attachment ->
                if (attachment.name.length > MAX_FIELD_CHARS) return false
                if (attachment.mimeType.length > MAX_FIELD_CHARS) return false
                if (attachment.localPath.length !in 1..MAX_PATH_CHARS) return false
            }
            val token = draft.activeSubmissionToken
            if (token != null && token.length !in 1..MAX_TOKEN_CHARS) return false
            val handoffId = draft.lastExternalHandoffId
            if (handoffId != null && handoffId.length !in 1..PreparedExternalHandoff.MAX_EVENT_ID_CHARS) return false
            val partialNotice = draft.lastExternalHandoffPartialNotice
            if (partialNotice != null && (
                    handoffId == null || partialNotice.length !in 1..PreparedExternalHandoff.MAX_PARTIAL_NOTICE_CHARS
                )
            ) return false
        }
        return true
    }

    /** The draft owner assigns the sequence; callers must not write this file directly. */
    open suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean {
        val nextWriteId = synchronized(this@PersistentComposerDraftStore) {
            latestWriteId += 1L
            latestWriteId
        }
        return write(nextWriteId, drafts)
    }

    /**
     * Explicit recovery boundary for unreadable durable ownership. Callers
     * supply the current in-memory map only after user confirmation. The old
     * artifact is secured before a replacement is written; a failed secure
     * operation leaves the original evidence untouched.
     */
    open suspend fun recoverUnreadable(
        drafts: Map<Long, ComposerDraftState>,
    ): DraftStoreRecoveryResult = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (!isValidDraftMap(drafts)) return@withLock DraftStoreRecoveryResult.Failed
            val encoded = encode(drafts)
            if (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                return@withLock DraftStoreRecoveryResult.Failed
            }
            when (val current = loadResultLocked(rawReadOnly = true)) {
                is DraftStoreLoad.Valid -> return@withLock DraftStoreRecoveryResult.NotNeeded
                is DraftStoreLoad.Corrupt,
                is DraftStoreLoad.Oversized -> {
                    val allowCopyFallback = current !is DraftStoreLoad.Oversized
                    val outcome = quarantineHook?.invoke(file, allowCopyFallback) ?: quarantineLocked(
                        allowCopyFallback = allowCopyFallback,
                    )
                    if (outcome is DebtQuarantineOutcome.Failed) {
                        return@withLock DraftStoreRecoveryResult.Failed
                    }
                }
                DraftStoreLoad.Missing -> Unit
            }
            val recoveredWrite = try {
                writeTextAtomically(file, encoded)
                true
            } catch (_: Exception) {
                false
            }
            if (!recoveredWrite) {
                return@withLock DraftStoreRecoveryResult.Failed
            }
            if (loadResultLocked(rawReadOnly = true) !is DraftStoreLoad.Valid) {
                return@withLock DraftStoreRecoveryResult.Failed
            }
            latestWriteId += 1L
            DraftStoreRecoveryResult.Recovered
        }
    }

    private fun loadResultLocked(rawReadOnly: Boolean = false): DraftStoreLoad {
        if (!file.isFile) return DraftStoreLoad.Missing
        val length = try {
            file.length()
        } catch (_: Exception) {
            return DraftStoreLoad.Corrupt
        }
        if (length > MAX_FILE_BYTES) return DraftStoreLoad.Oversized
        return when (val read = readUtf8FileBounded(file, MAX_FILE_BYTES)) {
            is BoundedUtf8FileRead.Success -> parseStrict(read.text, rawReadOnly)
            is BoundedUtf8FileRead.Oversized -> DraftStoreLoad.Oversized
            is BoundedUtf8FileRead.Failed -> DraftStoreLoad.Corrupt
        }
    }

    /**
     * Strict parse: any malformed required entry makes the WHOLE file CORRUPT.
     * Missing-file attachment resolution still skips (runtime, not corruption);
     * malformed JSON types/counts are CORRUPT, never silently truncated.
     */
    private fun parseStrict(raw: String, rawReadOnly: Boolean): DraftStoreLoad {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return DraftStoreLoad.Corrupt
        }
        if (array.length() > MAX_DRAFT_COUNT) return DraftStoreLoad.Corrupt
        val result = LinkedHashMap<Long, ComposerDraftState>()
        for (index in 0 until array.length()) {
            val item = try {
                array.optJSONObject(index) ?: return DraftStoreLoad.Corrupt
            } catch (_: Exception) {
                return DraftStoreLoad.Corrupt
            }
            if (!item.has("id")) return DraftStoreLoad.Corrupt
            val idRaw = try { item.get("id") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
            val id = com.projectnuke.fusion.util.exactLongOrNull(idRaw) ?: return DraftStoreLoad.Corrupt
            if (id < 0L) return DraftStoreLoad.Corrupt
            if (result.containsKey(id)) return DraftStoreLoad.Corrupt
            if (!item.has("input")) return DraftStoreLoad.Corrupt
            val inputRaw = try { item.get("input") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
            if (inputRaw !is String) return DraftStoreLoad.Corrupt
            if (inputRaw.length > ComposerDraftPolicy.MAX_INPUT_CHARS) return DraftStoreLoad.Corrupt
            if (!item.has("version")) return DraftStoreLoad.Corrupt
            val versionRaw = try { item.get("version") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
            val version = com.projectnuke.fusion.util.exactLongOrNull(versionRaw) ?: return DraftStoreLoad.Corrupt
            if (version < 0L) return DraftStoreLoad.Corrupt
            val submissionToken = if (!item.has("submissionToken") || item.isNull("submissionToken")) {
                null
            } else {
                val tokenRaw = try { item.get("submissionToken") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
                if (tokenRaw !is String) return DraftStoreLoad.Corrupt
                if (tokenRaw.length !in 1..MAX_TOKEN_CHARS) return DraftStoreLoad.Corrupt
                tokenRaw
            }
            val lastExternalHandoffId = if (!item.has("lastExternalHandoffId") || item.isNull("lastExternalHandoffId")) {
                null
            } else {
                val handoffIdRaw = try { item.get("lastExternalHandoffId") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
                if (handoffIdRaw !is String) return DraftStoreLoad.Corrupt
                if (handoffIdRaw.length !in 1..PreparedExternalHandoff.MAX_EVENT_ID_CHARS) return DraftStoreLoad.Corrupt
                handoffIdRaw
            }
            val lastExternalHandoffPartialNotice = if (
                !item.has("lastExternalHandoffPartialNotice") || item.isNull("lastExternalHandoffPartialNotice")
            ) {
                null
            } else {
                val noticeRaw = try {
                    item.get("lastExternalHandoffPartialNotice")
                } catch (_: Exception) {
                    return DraftStoreLoad.Corrupt
                }
                if (noticeRaw !is String || noticeRaw.length !in 1..PreparedExternalHandoff.MAX_PARTIAL_NOTICE_CHARS ||
                    lastExternalHandoffId == null
                ) return DraftStoreLoad.Corrupt
                noticeRaw
            }
            if (!item.has("attachments")) return DraftStoreLoad.Corrupt
            val attachmentRaw = try { item.get("attachments") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
            if (attachmentRaw !is JSONArray) return DraftStoreLoad.Corrupt
            if (attachmentRaw.length() > MAX_ATTACHMENT_COUNT) return DraftStoreLoad.Corrupt
            val attachments = ArrayList<PendingAttachmentIdentity>(attachmentRaw.length())
            for (attachmentIndex in 0 until attachmentRaw.length()) {
                val attachment = try {
                    attachmentRaw.optJSONObject(attachmentIndex) ?: return DraftStoreLoad.Corrupt
                } catch (_: Exception) {
                    return DraftStoreLoad.Corrupt
                }
                if (!attachment.has("path")) return DraftStoreLoad.Corrupt
                val pathRaw = try { attachment.get("path") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
                if (pathRaw !is String) return DraftStoreLoad.Corrupt
                if (pathRaw.length !in 1..MAX_PATH_CHARS) return DraftStoreLoad.Corrupt
                val nameRaw = if (attachment.has("name") && !attachment.isNull("name")) {
                    val n = try { attachment.get("name") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
                    if (n !is String) return DraftStoreLoad.Corrupt
                    if (n.length > MAX_FIELD_CHARS) return DraftStoreLoad.Corrupt
                    n
                } else ""
                val mimeRaw = if (attachment.has("mime") && !attachment.isNull("mime")) {
                    val m = try { attachment.get("mime") } catch (_: Exception) { return DraftStoreLoad.Corrupt }
                    if (m !is String) return DraftStoreLoad.Corrupt
                    if (m.length > MAX_FIELD_CHARS) return DraftStoreLoad.Corrupt
                    m
                } else ""
                if (rawReadOnly) {
                    // Reference check only; resolution happens on Valid load.
                    attachments.add(PendingAttachmentIdentity(nameRaw, mimeRaw, pathRaw))
                } else {
                    val resolved = resolveManagedAttachment(pathRaw) ?: continue
                    registerPendingAttachment(resolved)
                    attachments.add(
                        PendingAttachmentIdentity(
                            name = nameRaw,
                            mimeType = mimeRaw,
                            localPath = resolved.absolutePath,
                        ),
                    )
                }
            }
            result[id] = ComposerDraftState(
                rawInput = inputRaw,
                pendingAttachments = attachments,
                version = version,
                activeSubmissionToken = submissionToken,
                lastExternalHandoffId = lastExternalHandoffId,
                lastExternalHandoffPartialNotice = lastExternalHandoffPartialNotice,
            )
        }
        return DraftStoreLoad.Valid(result)
    }

    private fun quarantineLocked(allowCopyFallback: Boolean = true): DebtQuarantineOutcome {
        val outcome = DebtQuarantine.quarantine(file, QUARANTINE_SUFFIX, allowCopyFallback)
        when (outcome) {
            is DebtQuarantineOutcome.Secured ->
                Log.w("FusionRecovery", "Quarantined unreadable composer drafts to ${outcome.quarantine.name}")
            is DebtQuarantineOutcome.SourceMissing -> Unit
            is DebtQuarantineOutcome.Failed ->
                Log.w("FusionRecovery", "Quarantine FAILED for composer drafts; preserving original")
        }
        return outcome
    }

    private fun encode(drafts: Map<Long, ComposerDraftState>): String {
        // Exact serialization: callers guarantee validity via isValidDraftMap.
        // Empty drafts without ownership carry no semantics and are omitted;
        // token-carrying drafts are always preserved.
        val array = JSONArray()
        drafts.entries.asSequence()
            .filter { (_, draft) ->
                draft.rawInput.isNotEmpty() || draft.pendingAttachments.isNotEmpty() ||
                    draft.activeSubmissionToken != null || draft.lastExternalHandoffId != null ||
                    draft.lastExternalHandoffPartialNotice != null
            }
            .forEach { (id, draft) ->
                val attachments = JSONArray()
                draft.pendingAttachments.forEach { attachment ->
                    attachments.put(JSONObject()
                        .put("name", attachment.name)
                        .put("mime", attachment.mimeType)
                        .put("path", attachment.localPath))
                }
                array.put(JSONObject()
                    .put("id", id)
                    .put("input", draft.rawInput)
                    .put("version", draft.version)
                    .put("submissionToken", draft.activeSubmissionToken)
                    .put("lastExternalHandoffId", draft.lastExternalHandoffId)
                    .put("lastExternalHandoffPartialNotice", draft.lastExternalHandoffPartialNotice)
                    .put("attachments", attachments))
            }
        return array.toString()
    }

    companion object {
        const val FILE_NAME = "composer_drafts_v2.json"
        const val MAX_DRAFT_COUNT = 64
        const val MAX_ATTACHMENT_COUNT = 32
        const val MAX_INPUT_CHARS = ComposerDraftPolicy.MAX_INPUT_CHARS
        const val MAX_FIELD_CHARS = 512
        const val MAX_PATH_CHARS = 4_096
        const val MAX_TOKEN_CHARS = 128
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val QUARANTINE_SUFFIX = ".corrupt.bak"
        fun durableAttachmentPaths(context: Context): Set<String> = when (val refs = durableDraftReferences(context)) {
            is DurableDraftReferences.Available -> refs.paths
            is DurableDraftReferences.Unknown -> emptySet()
        }

        fun durableDraftReferences(context: Context): DurableDraftReferences = try {
            run {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.isFile) return@run DurableDraftReferences.Available(emptySet())
            if (file.length() > MAX_FILE_BYTES) return@run DurableDraftReferences.Unknown
            val root = AttachmentStorageManager.getAttachmentDirectory(context)
                .canonicalFile
            val raw = when (val read = readUtf8FileBounded(file, MAX_FILE_BYTES)) {
                is BoundedUtf8FileRead.Success -> read.text
                is BoundedUtf8FileRead.Oversized,
                is BoundedUtf8FileRead.Failed -> return@run DurableDraftReferences.Unknown
            }
            val array = try {
                JSONArray(raw)
            } catch (_: Exception) {
                return@run DurableDraftReferences.Unknown
            }
            if (array.length() > MAX_DRAFT_COUNT) return@run DurableDraftReferences.Unknown
            val out = HashSet<String>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return@run DurableDraftReferences.Unknown
                if (item.has("lastExternalHandoffId") && !item.isNull("lastExternalHandoffId")) {
                    val handoffId = item.opt("lastExternalHandoffId")
                    if (handoffId !is String || handoffId.length !in 1..PreparedExternalHandoff.MAX_EVENT_ID_CHARS) {
                        return@run DurableDraftReferences.Unknown
                    }
                }
                if (item.has("lastExternalHandoffPartialNotice") && !item.isNull("lastExternalHandoffPartialNotice")) {
                    val notice = item.opt("lastExternalHandoffPartialNotice")
                    val hasEventId = item.has("lastExternalHandoffId") && !item.isNull("lastExternalHandoffId")
                    if (!hasEventId || notice !is String ||
                        notice.length !in 1..PreparedExternalHandoff.MAX_PARTIAL_NOTICE_CHARS
                    ) return@run DurableDraftReferences.Unknown
                }
                val attachments = try {
                    item.optJSONArray("attachments") ?: return@run DurableDraftReferences.Unknown
                } catch (_: Exception) {
                    return@run DurableDraftReferences.Unknown
                }
                if (attachments.length() > MAX_ATTACHMENT_COUNT) return@run DurableDraftReferences.Unknown
                for (attachmentIndex in 0 until attachments.length()) {
                    val attachment = try {
                        attachments.optJSONObject(attachmentIndex)
                            ?: return@run DurableDraftReferences.Unknown
                    } catch (_: Exception) {
                        return@run DurableDraftReferences.Unknown
                    }
                    val path = try {
                        val rawPath = attachment.get("path")
                        if (rawPath !is String) return@run DurableDraftReferences.Unknown
                        rawPath.takeIf { it.length in 1..MAX_PATH_CHARS }
                            ?: return@run DurableDraftReferences.Unknown
                    } catch (_: Exception) {
                        return@run DurableDraftReferences.Unknown
                    }
                    val candidate = File(path)
                    val canonical = candidate.canonicalFile
                    if (canonical.parentFile?.canonicalPath == root.canonicalPath &&
                        canonical.isFile && !java.nio.file.Files.isSymbolicLink(candidate.toPath())
                    ) out += canonical.path
                }
            }
                DurableDraftReferences.Available(out)
            }
        } catch (_: Exception) {
            DurableDraftReferences.Unknown
        }
    }
}
