package com.projectnuke.fusion.util

import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap

/**
 * Sentinel stored in a key-scoped snapshot for a key that was ABSENT before
 * the mutation. Restoration must therefore REMOVE that key rather than leave
 * the failed process-visible value behind.
 */
internal object KeyAbsentInSnapshot

/**
 * Key-scoped durability snapshot. Unlike a whole-file rollback, this only
 * captures the exact keys owned by the calling mutation, so a failed-write
 * recovery can never roll back an unrelated key that a separate operation
 * successfully wrote after the snapshot was captured.
 *
 * The returned map records [KeyAbsentInSnapshot] for keys that were absent,
 * so [restoreKeys] can remove them rather than restore a phantom value.
 */
internal fun SharedPreferences.snapshotKeys(keys: Collection<String>): Map<String, Any?> =
    keys.associate { key ->
        key to if (all.containsKey(key)) {
            when (val value = all[key]) {
                is Set<*> -> value.filterIsInstance<String>().toMutableSet()
                else -> value
            }
        } else {
            KeyAbsentInSnapshot
        }
    }

/**
 * Restores exactly the keys held in [snapshot] (see [snapshotKeys]). It never
 * calls `clear()` and never rewrites any key outside the snapshot, so it is
 * safe to use for failed-mutation recovery without clobbering unrelated keys
 * that other operations may have written concurrently.
 */
internal fun SharedPreferences.restoreKeys(snapshot: Map<String, Any?>): Boolean {
    val editor = edit()
    snapshot.forEach { (key, value) ->
        if (value === KeyAbsentInSnapshot) {
            editor.remove(key)
        } else {
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
    }
    return editor.commit()
}

/**
 * Serializes the complete snapshot -> mutation commit -> failed-commit
 * recovery transaction for every mutation that owns the same logical key on
 * the same SharedPreferences file. This deliberately is NOT SharedPreferences'
 * internal synchronization (which only serializes map operations, not the
 * app-level snapshot/write/recovery transaction), and it is NOT a Java monitor
 * held across suspending work: it is a plain re-entrant-safe lock used around
 * synchronous preference mutations only.
 */
internal object SharedPreferencesScopedLock {
    private val locks = Collections.synchronizedMap(
        WeakHashMap<SharedPreferences, MutableMap<String, Any>>()
    )

    fun <T> withKeyLock(prefs: SharedPreferences, key: String, block: () -> T): T {
        val lock = synchronized(locks) {
            locks.getOrPut(prefs) { mutableMapOf<String, Any>() }.getOrPut(key) { Any() }
        }
        return synchronized(lock) { block() }
    }
}

internal object SharedPreferencesDurability {
    private val unknownKeys = Collections.synchronizedMap(
        WeakHashMap<SharedPreferences, MutableSet<String>>()
    )

    fun isUnknown(prefs: SharedPreferences, key: String): Boolean =
        unknownKeys[prefs]?.contains(key) == true

    fun markUnknown(prefs: SharedPreferences, key: String) {
        synchronized(unknownKeys) {
            unknownKeys.getOrPut(prefs) { mutableSetOf() }.add(key)
        }
    }

    fun clearUnknown(prefs: SharedPreferences, key: String) {
        synchronized(unknownKeys) {
            val keys = unknownKeys[prefs] ?: return
            keys.remove(key)
            if (keys.isEmpty()) unknownKeys.remove(prefs)
        }
    }

}
