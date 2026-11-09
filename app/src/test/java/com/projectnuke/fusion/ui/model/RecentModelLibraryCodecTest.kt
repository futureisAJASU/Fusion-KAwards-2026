package com.projectnuke.fusion.ui.model

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentModelLibraryCodecTest {
    private class InMemoryPrefs : SharedPreferences {
        private var raw: String? = null

        override fun getAll(): MutableMap<String, *> = mutableMapOf(RecentModelLibraryCodec.KEY to raw)
        override fun getString(key: String, defValue: String?): String? =
            if (key == RecentModelLibraryCodec.KEY) raw ?: defValue else defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = key == RecentModelLibraryCodec.KEY && raw != null
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (key == RecentModelLibraryCodec.KEY) raw = value
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String): SharedPreferences.Editor {
                if (key == RecentModelLibraryCodec.KEY) raw = null
                return this
            }
            override fun clear(): SharedPreferences.Editor { raw = null; return this }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }

    @Test
    fun `encode writes the exact production object contract bounded to MaxRecentModels`() {
        val items = (0 until MaxRecentModels + 5).map { index ->
            RecentModelEntry("model-$index", "Name $index", 1_000L + index, index + 1)
        }
        val arr = JSONArray(RecentModelLibraryCodec.encode(items))

        assertEquals(MaxRecentModels, arr.length())
        val newest = arr.getJSONObject(0)
        assertEquals("model-0", newest.getString("id"))
        assertEquals("Name 0", newest.getString("name"))
        assertEquals(1_000L, newest.getLong("lastUsedAt"))
        assertEquals(1, newest.getInt("useCount"))
        assertEquals(4, newest.length())
    }

    @Test
    fun `decode skips malformed entries and normalizes to newest first`() {
        val raw = JSONArray()
            .put(JSONObject().put("id", "ok").put("name", "Ok").put("lastUsedAt", 5L).put("useCount", 2))
            .put("not-an-object")
            .put(JSONObject().put("id", " ").put("name", "Blank").put("lastUsedAt", 6L).put("useCount", 1))
            .put(JSONObject().put("lastUsedAt", 7L).put("useCount", 1))
            .put(JSONObject().put("id", "older").put("name", "Older").put("lastUsedAt", 1L).put("useCount", 9))
            .toString()

        val decoded = RecentModelLibraryCodec.decode(raw)

        assertEquals(listOf("ok", "older"), decoded.map { it.modelId })
        assertEquals(2, decoded.first().useCount)
        assertEquals(9, decoded.last().useCount)
    }

    @Test
    fun `decode rejects raw values over the serialized byte bound`() {
        val oversized = JSONArray().put(
            JSONObject()
                .put("id", "large")
                .put("name", "x".repeat(RecentModelLibraryCodec.MAX_RECENT_RAW_BYTES))
                .put("lastUsedAt", 1L)
                .put("useCount", 1),
        ).toString()

        assertTrue(oversized.toByteArray(Charsets.UTF_8).size > RecentModelLibraryCodec.MAX_RECENT_RAW_BYTES)
        assertTrue(RecentModelLibraryCodec.decode(oversized).isEmpty())
    }

    @Test
    fun `decode scans only the bounded prefix`() {
        val arr = JSONArray()
        repeat(RecentModelLibraryCodec.MAX_RECENT_SCAN_ITEMS) { index ->
            arr.put(JSONObject().put("id", "prefix-$index").put("name", "n").put("lastUsedAt", index).put("useCount", 1))
        }
        arr.put(JSONObject().put("id", "late").put("name", "late").put("lastUsedAt", 99).put("useCount", 1))

        val decoded = RecentModelLibraryCodec.decode(arr.toString())

        assertTrue(decoded.none { it.modelId == "late" })
        assertEquals(MaxRecentModels, decoded.size)
    }

    @Test
    fun `decode requires strict row field types and ranges`() {
        val raw = JSONArray()
            .put(JSONObject().put("id", 9).put("name", "wrong-id").put("lastUsedAt", 0L).put("useCount", 1))
            .put(JSONObject().put("id", "missing-name").put("lastUsedAt", 1L).put("useCount", 1))
            .put(JSONObject().put("id", "wrong-name").put("name", 7).put("lastUsedAt", 2L).put("useCount", 1))
            .put(JSONObject().put("id", "missing-time").put("name", "n").put("useCount", 1))
            .put(JSONObject().put("id", "fraction-time").put("name", "n").put("lastUsedAt", 1.5).put("useCount", 1))
            .put(JSONObject().put("id", "negative-time").put("name", "n").put("lastUsedAt", -1L).put("useCount", 1))
            .put(JSONObject().put("id", "missing-count").put("name", "n").put("lastUsedAt", 3L))
            .put(JSONObject().put("id", "zero-count").put("name", "n").put("lastUsedAt", 4L).put("useCount", 0))
            .put(JSONObject().put("id", "negative-count").put("name", "n").put("lastUsedAt", 5L).put("useCount", -1))
            .put(JSONObject().put("id", "fraction-count").put("name", "n").put("lastUsedAt", 6L).put("useCount", 1.5))
            .put(JSONObject().put("id", "large-count").put("name", "n").put("lastUsedAt", 7L).put("useCount", Int.MAX_VALUE.toLong() + 1L))
            .put(JSONObject().put("id", "valid").put("name", "").put("lastUsedAt", 8L).put("useCount", 1))

        val decoded = RecentModelLibraryCodec.decode(raw.toString())

        assertEquals(listOf("valid"), decoded.map { it.modelId })
        assertEquals("", decoded.single().displayName)
    }

    @Test
    fun `decode keeps newest valid duplicate and deterministic newest order`() {
        val raw = JSONArray()
            .put(JSONObject().put("id", "same").put("name", "old").put("lastUsedAt", 1L).put("useCount", 1))
            .put(JSONObject().put("id", "same").put("name", "new").put("lastUsedAt", 9L).put("useCount", 2))
            .put(JSONObject().put("id", "z").put("name", "Z").put("lastUsedAt", 9L).put("useCount", 1))
            .put(JSONObject().put("id", "a").put("name", "A").put("lastUsedAt", 9L).put("useCount", 1))

        val decoded = RecentModelLibraryCodec.decode(raw.toString())

        assertEquals(listOf("same", "z", "a"), decoded.map { it.modelId })
        assertEquals("new", decoded.first { it.modelId == "same" }.displayName)
    }

    @Test
    fun `decode preserves durable order for equal timestamps and duplicate ties`() {
        val raw = JSONArray()
            .put(JSONObject().put("id", "first").put("name", "First").put("lastUsedAt", 9L).put("useCount", 1))
            .put(JSONObject().put("id", "same").put("name", "first").put("lastUsedAt", 9L).put("useCount", 1))
            .put(JSONObject().put("id", "same").put("name", "second").put("lastUsedAt", 9L).put("useCount", 2))
            .put(JSONObject().put("id", "last").put("name", "Last").put("lastUsedAt", 9L).put("useCount", 1))

        val decoded = RecentModelLibraryCodec.decode(raw.toString())

        assertEquals(listOf("first", "same", "last"), decoded.map { it.modelId })
        assertEquals("first", decoded.first { it.modelId == "same" }.displayName)
    }

    @Test
    fun `record places a new model first when existing chronology is Long MAX`() {
        val prefs = InMemoryPrefs()
        RecentModelLibraryCodec.save(
            prefs,
            listOf(RecentModelEntry("old", "Old", Long.MAX_VALUE, 1)),
        )

        RecentModelLibraryCodec.record(prefs, "new", "New")

        assertEquals(listOf("new", "old"), RecentModelLibraryCodec.load(prefs).map { it.modelId })
        RecentModelLibraryCodec.record(prefs, "new", "New")
        assertEquals(listOf("new", "old"), RecentModelLibraryCodec.load(prefs).map { it.modelId })
    }

    @Test
    fun `record saturates use count at Int MAX_VALUE and increments normally`() {
        val prefs = InMemoryPrefs()
        RecentModelLibraryCodec.save(prefs, listOf(RecentModelEntry("max", "Max", 1L, Int.MAX_VALUE)))

        RecentModelLibraryCodec.record(prefs, "max", "Max")
        assertEquals(Int.MAX_VALUE, RecentModelLibraryCodec.load(prefs).single().useCount)

        RecentModelLibraryCodec.save(prefs, listOf(RecentModelEntry("one", "One", 1L, 1)))
        RecentModelLibraryCodec.record(prefs, "one", "One")
        assertEquals(2, RecentModelLibraryCodec.load(prefs).single().useCount)
    }

    @Test
    fun `decode survives corrupt raw data and bounds the result`() {
        assertTrue(RecentModelLibraryCodec.decode(null).isEmpty())
        assertTrue(RecentModelLibraryCodec.decode("").isEmpty())
        assertTrue(RecentModelLibraryCodec.decode("not-json").isEmpty())
        val oversized = (0 until MaxRecentModels + 10).joinToString(prefix = "[", postfix = "]") { index ->
            "{\"id\":\"model-$index\",\"name\":\"n\",\"lastUsedAt\":$index,\"useCount\":1}"
        }
        assertEquals(MaxRecentModels, RecentModelLibraryCodec.decode(oversized).size)
    }

    @Test
    fun `codec round trips entries through encode and decode`() {
        val items = listOf(
            RecentModelEntry("a", "A", 3L, 1),
            RecentModelEntry("b", "B", 2L, 4),
            RecentModelEntry("c", "C", 1L, 7),
        )
        val decoded = RecentModelLibraryCodec.decode(RecentModelLibraryCodec.encode(items))
        assertEquals(items, decoded)
    }
}
