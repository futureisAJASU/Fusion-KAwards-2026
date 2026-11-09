package com.projectnuke.fusion.test

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Models the SharedPreferencesImpl boundary used by durability tests:
 * editor mutations become process-visible before commit reports its disk
 * result. [durableValues] represents the last successfully persisted map.
 *
 * [beforeResultHook], when non-null, runs inside [Editor.commit] AFTER the
 * process-memory mutation has been applied but BEFORE the scripted disk result
 * is returned. Tests use it to deterministically interleave a second writer
 * "while a failed mutation is between its process mutation and its recovery"
 * instead of depending on thread timing.
 */
class PlatformSharedPreferences(
    var commitResult: Boolean = true,
) : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    val durableValues = mutableMapOf<String, Any?>()
    val getStringCalls = AtomicInteger(0)
    val getStringBytesRead = AtomicLong(0)
    val putStringBytesWritten = AtomicLong(0)
    val commitCalls = AtomicInteger(0)
    val applyCalls = AtomicInteger(0)
    private val scriptedResults = ArrayDeque<Boolean>()
    private var beforeResultHook: (() -> Unit)? = null
    private var inHook = false

    fun enqueueCommitResults(vararg results: Boolean) {
        scriptedResults.addAll(results.toList())
    }

    fun onCommitBeforeResult(hook: (() -> Unit)?) {
        beforeResultHook = hook
    }

    fun resetScriptedResults() {
        scriptedResults.clear()
    }

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?): String? {
        getStringCalls.incrementAndGet()
        val value = values[key] as? String ?: defValue
        if (value != null) getStringBytesRead.addAndGet(value.toByteArray(Charsets.UTF_8).size.toLong())
        return value
    }
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?) = apply {
            pending[key] = value
            if (value != null) putStringBytesWritten.addAndGet(value.toByteArray(Charsets.UTF_8).size.toLong())
        }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clearRequested = true }

        override fun commit(): Boolean {
            commitCalls.incrementAndGet()
            if (clearRequested) values.clear()
            removed.forEach(values::remove)
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            val result = if (scriptedResults.isEmpty()) commitResult else scriptedResults.removeFirst()
            // The hook models an interleaving window; running it recursively
            // would re-enter commit and stack-overflow. The hook is allowed to
            // perform ANY OTHER action, but any further commit() on the same
            // fake is a no-op from the hook's perspective.
            val hook = beforeResultHook.also { beforeResultHook = null }
            if (hook != null && !inHook) {
                inHook = true
                try { hook() } finally { inHook = false }
            }
            if (result) {
                durableValues.clear()
                durableValues.putAll(values)
            }
            return result
        }

        override fun apply() {
            applyCalls.incrementAndGet()
            commit()
        }
    }
}
