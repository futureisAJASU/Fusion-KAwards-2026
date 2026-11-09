package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.util.SharedPreferencesDurability
import com.projectnuke.fusion.util.SharedPreferencesScopedLock
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ResponseVersionState(
    val groupByMessageId: Map<Long, Long> = emptyMap(),
    val activeMessageIdByGroup: Map<Long, Long> = emptyMap()
)

internal sealed interface ChatTimelineItem {
    data class User(val message: MessageEntity) : ChatTimelineItem
    data class AssistantVersions(
        val groupId: Long,
        val versions: List<MessageEntity>,
        val activeIndex: Int
    ) : ChatTimelineItem {
        val activeMessage: MessageEntity
            get() = versions[activeIndex]
    }
}

private const val ResponseVersionPrefsName = "fusion_response_versions"
private const val ResponseVersionQuarantineSuffix = ".corrupt.bak"

internal sealed interface ResponseVersionLoad {
    data object Missing : ResponseVersionLoad
    data class Valid(val state: ResponseVersionState) : ResponseVersionLoad
    data object Corrupt : ResponseVersionLoad
    data object Oversized : ResponseVersionLoad
}

internal const val MAX_RESPONSE_VERSION_BYTES = 4 * 1024 * 1024
internal const val MAX_RESPONSE_VERSION_ENTRIES_PER_MAP = 16_384

private class ResponseVersionOversizedException : IllegalArgumentException()

internal fun loadResponseVersionState(context: Context, conversationId: Long): ResponseVersionState {
    return when (val load = loadResponseVersionStateResult(context, conversationId)) {
        is ResponseVersionLoad.Valid -> load.state
        // Display fallback only: loads never write, so corrupt evidence is
        // preserved until an explicit save quarantines it (see below). The
        // cross-store Room/SharedPreferences atomicity gap is a deferred
        // architecture item, not something a loader can paper over.
        is ResponseVersionLoad.Missing,
        is ResponseVersionLoad.Corrupt,
        is ResponseVersionLoad.Oversized,
        -> ResponseVersionState()
    }
}

internal fun loadResponseVersionStateResult(context: Context, conversationId: Long): ResponseVersionLoad {
    val prefs = context.getSharedPreferences(ResponseVersionPrefsName, Context.MODE_PRIVATE)
    if (SharedPreferencesDurability.isUnknown(prefs, conversationId.toString())) {
        return ResponseVersionLoad.Corrupt
    }
    val raw = prefs
        .getString(conversationId.toString(), null)
        ?: return ResponseVersionLoad.Missing
    return parseResponseVersionState(raw)
}

/**
 * Typed semantic parser shared by the loader and the quarantine path. The
 * writer always emits both maps with exact-integral identities, so any
 * malformed key or value in a PRESENT map makes the WHOLE conversation
 * version state CORRUPT — durable response-version evidence must never be
 * silently dropped entry-by-entry and later rewritten. Absent maps stay
 * backward compatible with older writers and mean empty.
 */
// Test seam for the JSON document boundary: production always constructs the
// real document; tests substitute it to inject deterministic fatal failures.
internal var responseVersionDocumentFactory: (String) -> JSONObject = { JSONObject(it) }

internal fun parseResponseVersionState(raw: String): ResponseVersionLoad {
    if (raw.length > MAX_RESPONSE_VERSION_BYTES) return ResponseVersionLoad.Oversized
    if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_VERSION_BYTES) {
        return ResponseVersionLoad.Oversized
    }
    return try {
        val root = responseVersionDocumentFactory(raw)
        if (root.has("groups") && root.get("groups") !is JSONObject) {
            return ResponseVersionLoad.Corrupt
        }
        if (root.has("active") && root.get("active") !is JSONObject) {
            return ResponseVersionLoad.Corrupt
        }
        ResponseVersionLoad.Valid(
            ResponseVersionState(
                groupByMessageId = root.optJSONObject("groups").toStrictLongMap(),
                activeMessageIdByGroup = root.optJSONObject("active").toStrictLongMap()
            )
        )
    } catch (_: ResponseVersionOversizedException) {
        ResponseVersionLoad.Oversized
    } catch (_: Exception) {
        // Malformed JSON/identity is durable-corruption evidence; a fatal
        // runtime Error is NOT and must propagate, not become Corrupt.
        ResponseVersionLoad.Corrupt
    }
}

/**
 * Bounded evidence preservation for timeline grouping state: a present-but-
 * corrupt entry is copied to a single quarantine key before replacement.
 * Best-effort (fire-and-forget callers keep their signature); the durable
 * variant below reports persistence truthfully.
 */
private fun quarantineCorruptVersion(
    prefs: SharedPreferences,
    conversationId: Long,
): Boolean {
    val key = conversationId.toString()
    if (SharedPreferencesDurability.isUnknown(prefs, key)) return false
    val raw = prefs.getString(key, null) ?: return true
    // Same typed semantic parser as the loader: a syntactically valid but
    // semantically corrupt state is evidence too and must be quarantined.
    when (parseResponseVersionState(raw)) {
        is ResponseVersionLoad.Valid -> return true
        is ResponseVersionLoad.Oversized -> return false
        is ResponseVersionLoad.Missing,
        is ResponseVersionLoad.Corrupt,
        -> Unit
    }
    return prefs.edit().putString(key + ResponseVersionQuarantineSuffix, raw).commit()
}

internal fun deleteResponseVersionState(context: Context, conversationId: Long): Boolean {
    if (conversationId <= 0L) return true
    val prefs = context.getSharedPreferences(ResponseVersionPrefsName, Context.MODE_PRIVATE)
    val key = conversationId.toString()
    if (SharedPreferencesDurability.isUnknown(prefs, key)) return false
    return SharedPreferencesScopedLock.withKeyLock(prefs, key) {
        if (SharedPreferencesDurability.isUnknown(prefs, key)) return@withKeyLock false
        val snapshot = prefs.snapshotKeys(setOf(key))
        val committed = prefs.edit()
            .remove(key)
            .commit()
        if (!committed) restoreResponseSnapshot(prefs, key, snapshot)
        committed
    }
}

/**
 * Boolean save path. Synchronously reports whether the state was persisted;
 * a false result means corrupt evidence could not be quarantined or the
 * commit failed. Failed commits restore the process-visible snapshot; if
 * restoration also fails, the entry is marked unknown and future mutations
 * fail closed rather than treating an uncertain value as durable.
 */
internal fun saveResponseVersionState(
    context: Context,
    conversationId: Long,
    state: ResponseVersionState
): Boolean {
    val encoded = state.toStorageJson() ?: return false
    val prefs = context.getSharedPreferences(ResponseVersionPrefsName, Context.MODE_PRIVATE)
    val key = conversationId.toString()
    if (SharedPreferencesDurability.isUnknown(prefs, key)) return false
    return SharedPreferencesScopedLock.withKeyLock(prefs, key) {
        if (SharedPreferencesDurability.isUnknown(prefs, key)) return@withKeyLock false
        val snapshot = prefs.snapshotKeys(responseOwnedKeys(key))
        if (!quarantineCorruptVersion(prefs, conversationId)) {
            restoreResponseSnapshot(prefs, key, snapshot)
            return@withKeyLock false
        }
        val committed = prefs
            .edit()
            .putString(key, encoded)
            .commit()
        if (!committed) restoreResponseSnapshot(prefs, key, snapshot)
        committed
    }
}

internal fun saveResponseVersionStateDurably(
    context: Context,
    conversationId: Long,
    state: ResponseVersionState
) {
    val encoded = checkNotNull(state.toStorageJson()) {
        "Response version state exceeds durable bounds"
    }
    val prefs = context.getSharedPreferences(ResponseVersionPrefsName, Context.MODE_PRIVATE)
    val key = conversationId.toString()
    check(!SharedPreferencesDurability.isUnknown(prefs, key)) {
        "Response version state is unknown after a failed restoration"
    }
    SharedPreferencesScopedLock.withKeyLock(prefs, key) {
        check(!SharedPreferencesDurability.isUnknown(prefs, key)) {
            "Response version state is unknown after a failed restoration"
        }
        val snapshot = prefs.snapshotKeys(responseOwnedKeys(key))
        check(quarantineCorruptVersion(prefs, conversationId)) {
            "Failed to quarantine corrupt response version state"
        }
        val committed = prefs
            .edit()
            .putString(key, encoded)
            .commit()
        if (!committed) restoreResponseSnapshot(prefs, key, snapshot)
        check(committed) { "Failed to persist response version state" }
    }
}

/**
 * Changes only one active-version selection from the current durable state.
 * The load and save are one serialized logical mutation; callers must not
 * supply a Compose snapshot as the base state.
 */
internal enum class ResponseVersionDirection {
    PREVIOUS,
    NEXT,
}

/** Pure durable-state move used by both production persistence and host tests. */
internal fun moveResponseVersionFromDurableState(
    current: ResponseVersionState,
    groupId: Long,
    expectedOrderedMessageIds: List<Long>,
    direction: ResponseVersionDirection,
): ResponseVersionState? {
    if (expectedOrderedMessageIds.isEmpty() ||
        expectedOrderedMessageIds.distinct().size != expectedOrderedMessageIds.size
    ) return null
    val expected = expectedOrderedMessageIds.toSet()
    if (expectedOrderedMessageIds.any { messageId ->
            (current.groupByMessageId[messageId] ?: -messageId) != groupId
        }
    ) return null
    val explicitDurableGroupMembers = current.groupByMessageId
        .filterValues { it == groupId }
        .keys
    // A newer persisted version outside the UI membership snapshot means the
    // caller cannot safely derive a target from that stale snapshot. Implicit
    // originals are validated above through the same fallback group rule as
    // buildChatTimeline().
    if (!expected.containsAll(explicitDurableGroupMembers)) return null
    // Legacy durable state may contain valid membership without an explicit
    // active map. This is the same effective-active rule used by
    // buildChatTimeline(): the newest member is active by default.
    val activeId = current.activeMessageIdByGroup[groupId]
        ?: expectedOrderedMessageIds.last()
    val activeIndex = expectedOrderedMessageIds.indexOf(activeId)
    if (activeIndex < 0) return null
    val targetIndex = when (direction) {
        ResponseVersionDirection.PREVIOUS -> activeIndex - 1
        ResponseVersionDirection.NEXT -> activeIndex + 1
    }
    if (targetIndex !in expectedOrderedMessageIds.indices) return null
    return current.copy(
        activeMessageIdByGroup = current.activeMessageIdByGroup +
            (groupId to expectedOrderedMessageIds[targetIndex]),
    )
}

internal suspend fun moveResponseVersionDurably(
    context: Context,
    conversationId: Long,
    groupId: Long,
    expectedOrderedMessageIds: List<Long>,
    direction: ResponseVersionDirection,
    validateConversationLive: suspend () -> Boolean,
): ResponseVersionState? = withContext(Dispatchers.IO) {
    try {
        ResponseVersionMutationCoordinator.withMutation(conversationId) {
            if (!validateConversationLive()) return@withMutation null
            val current = loadResponseVersionState(context, conversationId)
            val updated = moveResponseVersionFromDurableState(
                current = current,
                groupId = groupId,
                expectedOrderedMessageIds = expectedOrderedMessageIds,
                direction = direction,
            ) ?: return@withMutation null
            if (!saveResponseVersionState(context, conversationId, updated)) null else updated
        }
    } catch (_: ResponseVersionMutationRejectedException) {
        null
    }
}

private fun responseOwnedKeys(key: String): Collection<String> =
    setOf(key, key + ResponseVersionQuarantineSuffix)

private fun restoreResponseSnapshot(
    prefs: SharedPreferences,
    key: String,
    snapshot: Map<String, Any?>,
): Boolean {
    val restored = prefs.restoreKeys(snapshot)
    if (!restored) SharedPreferencesDurability.markUnknown(prefs, key)
    return restored
}

private fun ResponseVersionState.toStorageJson(): String? {
    if (groupByMessageId.size > MAX_RESPONSE_VERSION_ENTRIES_PER_MAP ||
        activeMessageIdByGroup.size > MAX_RESPONSE_VERSION_ENTRIES_PER_MAP
    ) return null
    val encoded = JSONObject()
        .put("groups", groupByMessageId.toJsonObject())
        .put("active", activeMessageIdByGroup.toJsonObject())
        .toString()
    return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_VERSION_BYTES }
}

/**
 * Tells a structurally oversized state (cannot ever be saved at this size)
 * apart from an ordinary commit failure (transient; retry unchanged). The
 * recovery bisection preserves witnesses in both cases, but only the
 * oversized case may be split for further progress.
 */
internal fun responseVersionStateEncodesWithinBounds(state: ResponseVersionState): Boolean =
    state.toStorageJson() != null

internal fun buildChatTimeline(
    messages: List<MessageEntity>,
    state: ResponseVersionState
): List<ChatTimelineItem> {
    val items = mutableListOf<ChatTimelineItem>()
    val groups = linkedMapOf<Long, MutableList<MessageEntity>>()
    messages.forEach { message ->
        if (message.role == "user") {
            items += ChatTimelineItem.User(message)
        } else {
            val groupId = state.groupByMessageId[message.id] ?: -message.id
            val versions = groups[groupId]
            if (versions == null) {
                groups[groupId] = mutableListOf(message)
                items += ChatTimelineItem.AssistantVersions(groupId, emptyList(), 0)
            } else {
                versions += message
            }
        }
    }

    return items.map { item ->
        if (item !is ChatTimelineItem.AssistantVersions) return@map item
        val versions = groups[item.groupId].orEmpty()
        val selectedId = state.activeMessageIdByGroup[item.groupId]
        val activeIndex = versions.indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 }
            ?: versions.lastIndex
        item.copy(versions = versions, activeIndex = activeIndex.coerceAtLeast(0))
    }
}

internal fun activeTimelineMessages(
    messages: List<MessageEntity>,
    state: ResponseVersionState
): List<MessageEntity> = buildChatTimeline(messages, state).map {
    when (it) {
        is ChatTimelineItem.User -> it.message
        is ChatTimelineItem.AssistantVersions -> it.activeMessage
    }
}

private fun JSONObject?.toStrictLongMap(): Map<Long, Long> {
    if (this == null) return emptyMap()
    if (length() > MAX_RESPONSE_VERSION_ENTRIES_PER_MAP) {
        throw ResponseVersionOversizedException()
    }
    // Fail-closed identity contract: one malformed entry corrupts the WHOLE
    // state. Skipping a bad pair would silently rewrite durable evidence and
    // can silently regroup a conversation under the fallback identity.
    return buildMap<Long, Long> {
        keys().forEach { key ->
            val mapKey = key.toLongOrNull()
                ?: throw IllegalArgumentException("malformed response version key: $key")
            val rawValue = try {
                this@toStrictLongMap.get(key)
            } catch (_: Exception) {
                // Only an ordinary access failure makes the value unreadable;
                // a fatal Error must propagate, not masquerade as corrupt
                // durable input.
                throw IllegalArgumentException("unreadable response version value for key: $key")
            }
            val value = com.projectnuke.fusion.util.exactLongOrNull(rawValue)
                ?: throw IllegalArgumentException("non-integral response version value for key: $key")
            put(mapKey, value)
        }
    }
}

private fun Map<Long, Long>.toJsonObject(): JSONObject = JSONObject().also { json ->
    forEach { (key, value) -> json.put(key.toString(), value) }
}
