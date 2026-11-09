package com.projectnuke.fusion.projects

import org.json.JSONArray
import org.json.JSONObject
import com.projectnuke.fusion.util.SafeWebUrlPolicy
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class ProjectSnapshotConversation(
    val sourceId: Long,
    val title: String,
    val summary: String,
    val updatedAt: Long,
)

internal data class ProjectSnapshotKnowledge(
    val title: String,
    val text: String,
    val provenanceType: String,
    val sourceConversationId: Long?,
    val sourceMessageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceUrl: String? = null,
    val sourcePageTitle: String? = null,
    val retrievedAt: Long? = null,
)

internal data class ProjectSnapshotTask(
    val title: String,
    val note: String,
    val state: String,
    val sourceConversationId: Long?,
    val sourceMessageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ProjectSnapshotReviewCard(
    val front: String,
    val back: String,
    val sourceKind: String,
    val reviewCount: Int,
    val lastRating: String?,
    val dueAt: Long,
    val lastReviewedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ProjectSnapshotFile(
    val displayName: String,
    val mimeType: String,
    val addedAt: Long,
    val sizeBytes: Long,
    val sha256: String?,
    val payloadIndex: Int?,
)

internal data class ProjectSnapshot(
    val name: String,
    val instructions: String,
    val createdAt: Long,
    val exportedAt: Long,
    val conversations: List<ProjectSnapshotConversation>,
    val knowledge: List<ProjectSnapshotKnowledge>,
    val tasks: List<ProjectSnapshotTask>,
    val reviewCards: List<ProjectSnapshotReviewCard>,
    val files: List<ProjectSnapshotFile>,
)

internal data class ProjectSnapshotArchive(
    val snapshot: ProjectSnapshot,
    val payloads: Map<Int, ByteArray>,
)

internal object ProjectSnapshotBounds {
    /**
     * Schema 3 widens knowledge text from 12,000 to the legal Research Note
     * maximum (20,000). No fields were added or removed, so version 3 reads
     * exactly like version 2; readers pinned to version 2 reject version 3
     * at the version gate ("Unsupported snapshot version") instead of
     * misreading longer texts. Versions 1 and 2 remain importable.
     * The 1 MiB manifest cap is unchanged: a single 20,000-char note fits,
     * while a full cross-product of maximum items x maximum text remains
     * truthfully capacity-rejected exactly as 256 x 12,000 was before.
     */
    const val SCHEMA_VERSION = 3
    const val MAX_ARCHIVE_INPUT_BYTES = 36L * 1024L * 1024L
    const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 32L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 1L * 1024L * 1024L
    const val MAX_SOURCE_FILE_BYTES = 20L * 1024L * 1024L
    const val MAX_PROJECT_NAME_CHARS = 80
    const val MAX_INSTRUCTIONS_CHARS = 4_000
    const val MAX_CONVERSATIONS = 32
    const val MAX_SUMMARY_CHARS = 4_000
    const val MAX_KNOWLEDGE_ITEMS = 256
    const val MAX_KNOWLEDGE_TITLE_CHARS = 160
    /**
     * Derived from the authoritative Research Note domain bound: every note
     * body accepted by normal storage must be representable in a snapshot.
     * Other knowledge pipelines still bound their own writes to 12,000 via
     * [SavedKnowledgePolicy.MAX_TEXT_CHARS]; only the snapshot text envelope
     * is widened here, with no truncation anywhere on the path.
     */
    const val MAX_KNOWLEDGE_TEXT_CHARS = MAX_SAVED_NOTE_BODY_CHARS
    const val MAX_TASKS = 64
    const val MAX_TASK_TITLE_CHARS = 200
    const val MAX_TASK_NOTE_CHARS = 4_000
    const val MAX_REVIEW_CARDS = 512
    const val MAX_REVIEW_FRONT_CHARS = 600
    const val MAX_REVIEW_BACK_CHARS = 12_000
    const val MAX_FILE_DISPLAY_NAME_CHARS = 180
    const val MAX_FILES = 8
    const val MAX_TIMESTAMP = 253_402_300_799_999L
}

/** Versioned, path-free ZIP format. Every member and its declared hash is validated before use. */
internal object ProjectSnapshotArchiveCodec {
    fun write(snapshot: ProjectSnapshot, payloads: Map<Int, ByteArray>, output: OutputStream) {
        validate(snapshot, payloads)
        val manifest = encodeManifest(snapshot).toByteArray(StandardCharsets.UTF_8)
        require(manifest.size.toLong() <= ProjectSnapshotBounds.MAX_MANIFEST_BYTES)
        require(manifest.size.toLong() + payloads.values.sumOf { it.size.toLong() } <=
            ProjectSnapshotBounds.MAX_ARCHIVE_UNCOMPRESSED_BYTES)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifest)
            zip.closeEntry()
            snapshot.files.forEachIndexed { index, file ->
                val payloadIndex = file.payloadIndex ?: return@forEachIndexed
                zip.putNextEntry(ZipEntry(sourceEntryName(index)))
                zip.write(payloads.getValue(payloadIndex))
                zip.closeEntry()
            }
        }
    }

    fun encode(snapshot: ProjectSnapshot, payloads: Map<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        write(snapshot, payloads, output)
        require(output.size().toLong() <= ProjectSnapshotBounds.MAX_ARCHIVE_INPUT_BYTES)
        return output.toByteArray()
    }

    fun read(input: InputStream): ProjectSnapshotArchive {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        val boundedInput = LimitedInputStream(input, ProjectSnapshotBounds.MAX_ARCHIVE_INPUT_BYTES)
        ZipInputStream(NonClosingInputStream(boundedInput)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && isAllowedEntryName(entry.name)) { "Invalid archive member name" }
                require(entry.name !in entries) { "Duplicate archive member" }
                require(entries.size < ProjectSnapshotBounds.MAX_FILES + 1) { "Too many archive members" }
                val limit = if (entry.name == MANIFEST_NAME) ProjectSnapshotBounds.MAX_MANIFEST_BYTES
                else ProjectSnapshotBounds.MAX_SOURCE_FILE_BYTES
                val bytes = readEntryBounded(zip, limit)
                totalBytes += bytes.size
                require(totalBytes <= ProjectSnapshotBounds.MAX_ARCHIVE_UNCOMPRESSED_BYTES) { "Archive is too large" }
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        val trailing = ByteArray(8192)
        while (boundedInput.read(trailing) >= 0) Unit
        val manifestBytes = entries.remove(MANIFEST_NAME) ?: error("Manifest is missing")
        val manifestText = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(manifestBytes))
            .toString()
        val snapshot = decodeManifest(manifestText)
        val payloads = linkedMapOf<Int, ByteArray>()
        snapshot.files.forEachIndexed { index, file ->
            val entryName = sourceEntryName(index)
            val bytes = entries.remove(entryName)
            if (file.payloadIndex != null) {
                require(bytes != null) { "Packaged source file is missing" }
                require(bytes.size.toLong() == file.sizeBytes) { "Source size mismatch" }
                require(sha256(bytes) == file.sha256) { "Source hash mismatch" }
                payloads[file.payloadIndex] = bytes
            } else {
                require(bytes == null) { "Unexpected source member" }
            }
        }
        require(entries.isEmpty()) { "Unexpected archive member" }
        validate(snapshot, payloads)
        return ProjectSnapshotArchive(snapshot, payloads)
    }

    private fun validate(snapshot: ProjectSnapshot, payloads: Map<Int, ByteArray>) {
        require(snapshot.name.isNotBlank() && snapshot.name.length <= ProjectSnapshotBounds.MAX_PROJECT_NAME_CHARS)
        require(snapshot.name.none(Char::isISOControl))
        require(snapshot.instructions.length <= ProjectSnapshotBounds.MAX_INSTRUCTIONS_CHARS)
        require(validTimestamp(snapshot.createdAt) && validTimestamp(snapshot.exportedAt))
        require(snapshot.conversations.size <= ProjectSnapshotBounds.MAX_CONVERSATIONS)
        require(snapshot.knowledge.size <= ProjectSnapshotBounds.MAX_KNOWLEDGE_ITEMS)
        require(snapshot.tasks.size <= ProjectSnapshotBounds.MAX_TASKS)
        require(snapshot.reviewCards.size <= ProjectSnapshotBounds.MAX_REVIEW_CARDS)
        require(snapshot.files.size <= ProjectSnapshotBounds.MAX_FILES)
        require(snapshot.conversations.map { it.sourceId }.distinct().size == snapshot.conversations.size)
        snapshot.conversations.forEach {
            require(it.sourceId > 0L && it.title.isNotBlank() && it.title.length <= 200)
            require(it.summary.isNotBlank() && it.summary.length <= ProjectSnapshotBounds.MAX_SUMMARY_CHARS)
            require(validTimestamp(it.updatedAt))
        }
        snapshot.knowledge.forEach {
            require(it.title.isNotBlank() && it.title.length <= ProjectSnapshotBounds.MAX_KNOWLEDGE_TITLE_CHARS)
            require(it.text.isNotBlank() && it.text.length <= ProjectSnapshotBounds.MAX_KNOWLEDGE_TEXT_CHARS)
            require(it.provenanceType.isNotBlank() && it.provenanceType.length <= 80)
            require((it.sourceConversationId == null) == (it.sourceMessageId == null))
            require(it.sourceConversationId?.let { id -> id > 0L } != false)
            require(it.sourceMessageId?.let { id -> id > 0L } != false)
            require(validTimestamp(it.createdAt) && validTimestamp(it.updatedAt))
            require(it.sourceUrl == null ||
                (it.sourceUrl.length <= 2_000 && SafeWebUrlPolicy.normalize(it.sourceUrl) == it.sourceUrl &&
                    !SavedKnowledgePolicy.containsSensitiveMaterial(it.sourceUrl)))
            require(it.sourcePageTitle == null ||
                (it.sourcePageTitle.length <= 160 && it.sourcePageTitle.none(Char::isISOControl)))
            require(it.retrievedAt?.let(::validTimestamp) != false)
            if (it.provenanceType == "WEB_CLIP") require(it.sourceUrl != null && it.retrievedAt != null)
        }
        snapshot.tasks.forEach {
            require(it.title.isNotBlank() && it.title.length <= ProjectSnapshotBounds.MAX_TASK_TITLE_CHARS)
            require(it.note.length <= ProjectSnapshotBounds.MAX_TASK_NOTE_CHARS)
            require(it.state == "OPEN" || it.state == "DONE")
            require((it.sourceConversationId == null) == (it.sourceMessageId == null))
            require(it.sourceConversationId?.let { id -> id > 0L } != false)
            require(it.sourceMessageId?.let { id -> id > 0L } != false)
            require(validTimestamp(it.createdAt) && validTimestamp(it.updatedAt))
        }
        snapshot.reviewCards.forEach {
            require(it.front.isNotBlank() && it.front.length <= ProjectSnapshotBounds.MAX_REVIEW_FRONT_CHARS)
            require(it.back.isNotBlank() && it.back.length <= ProjectSnapshotBounds.MAX_REVIEW_BACK_CHARS)
            require(it.sourceKind.isNotBlank() && it.sourceKind.length <= 80)
            require(it.reviewCount in 0..1_000_000)
            require(it.lastRating == null || it.lastRating in setOf("AGAIN", "HARD", "UNDERSTOOD"))
            require(validTimestamp(it.dueAt) && validTimestamp(it.createdAt) && validTimestamp(it.updatedAt))
            require(it.lastReviewedAt?.let(::validTimestamp) != false)
        }
        val referencedPayloads = mutableSetOf<Int>()
        var total = 0L
        snapshot.files.forEachIndexed { index, file ->
            require(isSafeDisplayName(file.displayName) &&
                file.displayName.length <= ProjectSnapshotBounds.MAX_FILE_DISPLAY_NAME_CHARS)
            require(file.mimeType.length in 1..120 && file.mimeType.none(Char::isISOControl))
            require(validTimestamp(file.addedAt) && file.sizeBytes in 0..(1L shl 40))
            if (file.payloadIndex == null) {
                require(file.sha256 == null)
            } else {
                require(file.payloadIndex == index && referencedPayloads.add(file.payloadIndex))
                require(file.sizeBytes in 1..ProjectSnapshotBounds.MAX_SOURCE_FILE_BYTES)
                require(file.sha256?.matches(SHA256_PATTERN) == true)
            }
            if (file.payloadIndex != null) total += file.sizeBytes
            require(total <= ProjectSnapshotBounds.MAX_ARCHIVE_UNCOMPRESSED_BYTES)
        }
        require(referencedPayloads == payloads.keys)
        payloads.forEach { (index, bytes) ->
            val file = snapshot.files.getOrNull(index) ?: error("Unknown source payload")
            require(index in referencedPayloads && bytes.size.toLong() in 1..ProjectSnapshotBounds.MAX_SOURCE_FILE_BYTES)
            require(bytes.size.toLong() == file.sizeBytes && sha256(bytes) == file.sha256)
        }
    }

    private fun encodeManifest(snapshot: ProjectSnapshot): String = JSONObject().apply {
        put("schemaVersion", ProjectSnapshotBounds.SCHEMA_VERSION)
        put("name", snapshot.name)
        put("instructions", snapshot.instructions)
        put("createdAt", snapshot.createdAt)
        put("exportedAt", snapshot.exportedAt)
        put("conversations", JSONArray().apply { snapshot.conversations.forEach { item ->
            put(JSONObject().put("sourceId", item.sourceId).put("title", item.title)
                .put("summary", item.summary).put("updatedAt", item.updatedAt))
        } })
        put("knowledge", JSONArray().apply { snapshot.knowledge.forEach { item ->
            put(JSONObject().put("title", item.title).put("text", item.text).put("provenanceType", item.provenanceType)
                .putNullable("sourceConversationId", item.sourceConversationId)
                .putNullable("sourceMessageId", item.sourceMessageId)
                .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
                .putNullable("sourceUrl", item.sourceUrl)
                .putNullable("sourcePageTitle", item.sourcePageTitle)
                .putNullable("retrievedAt", item.retrievedAt))
        } })
        put("tasks", JSONArray().apply { snapshot.tasks.forEach { item ->
            put(JSONObject().put("title", item.title).put("note", item.note).put("state", item.state)
                .putNullable("sourceConversationId", item.sourceConversationId)
                .putNullable("sourceMessageId", item.sourceMessageId)
                .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt))
        } })
        put("reviewCards", JSONArray().apply { snapshot.reviewCards.forEach { item ->
            put(JSONObject().put("front", item.front).put("back", item.back).put("sourceKind", item.sourceKind)
                .put("reviewCount", item.reviewCount).putNullable("lastRating", item.lastRating)
                .put("dueAt", item.dueAt).putNullable("lastReviewedAt", item.lastReviewedAt)
                .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt))
        } })
        put("files", JSONArray().apply { snapshot.files.forEachIndexed { index, item ->
            put(JSONObject().put("displayName", item.displayName).put("mimeType", item.mimeType)
                .put("addedAt", item.addedAt).put("sizeBytes", item.sizeBytes)
                .putNullable("sha256", item.sha256).putNullable("payloadIndex", item.payloadIndex)
                .put("entry", if (item.payloadIndex == null) JSONObject.NULL else sourceEntryName(index)))
        } })
    }.toString()

    private fun decodeManifest(text: String): ProjectSnapshot {
        val root = JSONObject(text)
        root.requireKeys("schemaVersion", "name", "instructions", "createdAt", "exportedAt", "conversations", "knowledge", "tasks", "reviewCards", "files")
        val schemaVersion = root.strictInt("schemaVersion")
        require(schemaVersion in 1..ProjectSnapshotBounds.SCHEMA_VERSION) { "Unsupported snapshot version" }
        val conversations = root.getJSONArray("conversations").mapObjects { obj ->
            obj.requireKeys("sourceId", "title", "summary", "updatedAt")
            ProjectSnapshotConversation(obj.strictLong("sourceId"), obj.strictString("title"), obj.strictString("summary"), obj.strictLong("updatedAt"))
        }
        val knowledge = root.getJSONArray("knowledge").mapObjects { obj ->
            if (schemaVersion == 1) {
                obj.requireKeys("title", "text", "provenanceType", "sourceConversationId", "sourceMessageId", "createdAt", "updatedAt")
            } else {
                obj.requireKeys("title", "text", "provenanceType", "sourceConversationId", "sourceMessageId", "createdAt", "updatedAt", "sourceUrl", "sourcePageTitle", "retrievedAt")
            }
            ProjectSnapshotKnowledge(obj.strictString("title"), obj.strictString("text"), obj.strictString("provenanceType"),
                obj.nullableLong("sourceConversationId"), obj.nullableLong("sourceMessageId"), obj.strictLong("createdAt"), obj.strictLong("updatedAt"),
                if (schemaVersion == 1) null else obj.nullableString("sourceUrl"),
                if (schemaVersion == 1) null else obj.nullableString("sourcePageTitle"),
                if (schemaVersion == 1) null else obj.nullableLong("retrievedAt"))
        }
        val tasks = root.getJSONArray("tasks").mapObjects { obj ->
            obj.requireKeys("title", "note", "state", "sourceConversationId", "sourceMessageId", "createdAt", "updatedAt")
            ProjectSnapshotTask(obj.strictString("title"), obj.strictString("note"), obj.strictString("state"),
                obj.nullableLong("sourceConversationId"), obj.nullableLong("sourceMessageId"), obj.strictLong("createdAt"), obj.strictLong("updatedAt"))
        }
        val cards = root.getJSONArray("reviewCards").mapObjects { obj ->
            obj.requireKeys("front", "back", "sourceKind", "reviewCount", "lastRating", "dueAt", "lastReviewedAt", "createdAt", "updatedAt")
            ProjectSnapshotReviewCard(obj.strictString("front"), obj.strictString("back"), obj.strictString("sourceKind"),
                obj.strictInt("reviewCount"), obj.nullableString("lastRating"), obj.strictLong("dueAt"), obj.nullableLong("lastReviewedAt"),
                obj.strictLong("createdAt"), obj.strictLong("updatedAt"))
        }
        val files = root.getJSONArray("files").mapObjects { obj ->
            obj.requireKeys("displayName", "mimeType", "addedAt", "sizeBytes", "sha256", "payloadIndex", "entry")
            val payloadIndex = obj.nullableInt("payloadIndex")
            val entry = obj.nullableString("entry")
            require(entry == if (payloadIndex == null) null else sourceEntryName(payloadIndex))
            ProjectSnapshotFile(obj.strictString("displayName"), obj.strictString("mimeType"), obj.strictLong("addedAt"),
                obj.strictLong("sizeBytes"), obj.nullableString("sha256"), payloadIndex)
        }
        files.forEachIndexed { index, item ->
            require(item.payloadIndex == null || item.payloadIndex == index) { "Invalid source file index" }
        }
        return ProjectSnapshot(root.strictString("name"), root.strictString("instructions"), root.strictLong("createdAt"),
            root.strictLong("exportedAt"), conversations, knowledge, tasks, cards, files)
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.requireKeys(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet()) { "Unexpected or missing manifest field" }
    }
    private fun JSONObject.strictString(key: String): String = get(key).let { require(it is String); it }
    private fun JSONObject.strictLong(key: String): Long = get(key).let {
        require(it is Number)
        it.toString().toLongOrNull() ?: throw IllegalArgumentException("Expected exact integer: $key")
    }
    private fun JSONObject.strictInt(key: String): Int = strictLong(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else strictLong(key)
    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else strictInt(key)
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else strictString(key)
    private inline fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> {
        val source = this
        return buildList(source.length()) {
            for (index in 0 until source.length()) add(block(source.get(index).let { require(it is JSONObject); it }))
        }
    }

    private fun isAllowedEntryName(name: String): Boolean =
        name == MANIFEST_NAME || Regex("sources/[0-9]{3}\\.bin").matches(name)

    private fun sourceEntryName(index: Int): String = "sources/${index.toString().padStart(3, '0')}.bin"
    private fun validTimestamp(value: Long): Boolean = value in 0..ProjectSnapshotBounds.MAX_TIMESTAMP
    private fun containsPathSeparator(name: String): Boolean = '/' in name || '\\' in name || ".." in name || ':' in name

    private fun isSafeDisplayName(value: String): Boolean = value.isNotBlank() && !containsPathSeparator(value) &&
        value.none(Char::isISOControl) && value.trim() == value

    private fun readEntryBounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64L * 1024L).toInt())
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Archive member exceeds size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = super.read(buffer, offset, length)
            if (value > 0) count(value)
            return value
        }
        private fun count(amount: Int) {
            total += amount
            require(total <= limit) { "Archive input exceeds size limit" }
        }
    }

    private class NonClosingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
        override fun close() = Unit
    }

    private const val MANIFEST_NAME = "manifest.json"
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
}
