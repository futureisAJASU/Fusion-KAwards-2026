package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperLogStoreTest {

    private class TestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File("build/test-developer-log")
    }

    @Test
    fun `normal 50 event round trip`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        repeat(50) { index ->
            DeveloperLogStore.record(
                context = context,
                category = "cat-$index",
                message = "message $index",
                technicalSummary = "tech $index",
            )
        }
        val loaded = DeveloperLogStore.load(context)
        assertTrue("Expected 50 events but got ${loaded.size}", loaded.size == 50)
        assertTrue(loaded[0].timestamp > 0)
        assertTrue(loaded.all { it.message.length <= 180 })
        assertTrue(loaded.all { it.category.length <= 40 })
        assertTrue(loaded.all { it.technicalSummary?.length?.let { it <= 240 } != false })
    }

    @Test
    fun `more than 50 persisted events expose at most 50`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        repeat(55) { index ->
            DeveloperLogStore.record(context, "cat", "msg-$index")
        }
        val loaded = DeveloperLogStore.load(context)
        assertEquals(50, loaded.size)
    }

    @Test
    fun `oversized category message technical fields bounded on load`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        val oversizedCategory = "c".repeat(100)
        val oversizedMessage = "m".repeat(500)
        val oversizedTech = "t".repeat(500)
        DeveloperLogStore.record(context, oversizedCategory, oversizedMessage, oversizedTech)
        val loaded = DeveloperLogStore.load(context)
        assertEquals(1, loaded.size)
        assertTrue(loaded[0].category.length <= 40)
        assertTrue(loaded[0].message.length <= 180)
        assertTrue(loaded[0].technicalSummary!!.length <= 240)
    }

    @Test
    fun `malformed JSON returns empty and clears durable payload`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("events", "not-json").apply()
        val loaded = DeveloperLogStore.load(TestContext(prefs))
        assertTrue(loaded.isEmpty())
        assertNull(prefs.getString("events", null))
    }

    @Test
    fun `durable payload exceeding 64 KiB returns empty and clears durable payload`() {
        val prefs = PlatformSharedPreferences()
        val oversized = "x".repeat(64 * 1024 + 1)
        prefs.edit().putString("events", oversized).apply()
        val loaded = DeveloperLogStore.load(TestContext(prefs))
        assertTrue(loaded.isEmpty())
        assertNull(prefs.getString("events", null))
    }

    @Test
    fun `non-object array rows skipped`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put("not-object")
        array.put(JSONObject().put("timestamp", 100).put("category", "test").put("message", "hello"))
        prefs.edit().putString("events", array.toString()).apply()
        val loaded = DeveloperLogStore.load(TestContext(prefs))
        assertEquals(1, loaded.size)
        assertEquals("test", loaded[0].category)
    }

    @Test
    fun `only first 50 durable array positions are inspected`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        repeat(50) { array.put("not-an-object-$it") }
        array.put(JSONObject().put("timestamp", 100).put("category", "after-bound").put("message", "ignored"))
        prefs.edit().putString("events", array.toString()).apply()
        assertTrue(DeveloperLogStore.load(TestContext(prefs)).isEmpty())
    }

    @Test
    fun `legacy message is sanitized before display bounds`() {
        val prefs = PlatformSharedPreferences()
        val secret = "legacy-message-token"
        val rawMessage = "Authorization: Bearer $secret " + "m".repeat(500)
        val array = JSONArray().put(
            JSONObject()
                .put("timestamp", 100)
                .put("category", "legacy")
                .put("message", rawMessage),
        )
        prefs.edit().putString("events", array.toString()).apply()
        val loaded = DeveloperLogStore.load(TestContext(prefs))
        assertEquals(1, loaded.size)
        assertFalse(loaded[0].message.contains(secret))
        assertTrue(loaded[0].message.contains("[redacted-secret]"))
        assertTrue(loaded[0].message.length <= 180)
    }

    @Test
    fun `legacy technical summary is sanitized before display bounds`() {
        val prefs = PlatformSharedPreferences()
        val secret = "legacy-technical-token"
        val rawTechnical = "api_key=$secret " + "t".repeat(500)
        val array = JSONArray().put(
            JSONObject()
                .put("timestamp", 100)
                .put("category", "legacy")
                .put("message", "message")
                .put("technicalSummary", rawTechnical),
        )
        prefs.edit().putString("events", array.toString()).apply()
        val loaded = DeveloperLogStore.load(TestContext(prefs))
        assertEquals(1, loaded.size)
        assertFalse(loaded[0].technicalSummary!!.contains(secret))
        assertTrue(loaded[0].technicalSummary!!.contains("[redacted-secret]"))
        assertTrue(loaded[0].technicalSummary!!.length <= 240)
    }

    @Test
    fun `clearing is idempotent`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        DeveloperLogStore.record(context, "c", "m")
        assertEquals(1, DeveloperLogStore.load(context).size)
        DeveloperLogStore.clear(context)
        assertTrue(DeveloperLogStore.load(context).isEmpty())
        DeveloperLogStore.clear(context)
        assertTrue(DeveloperLogStore.load(context).isEmpty())
    }

    @Test
    fun `record redaction remains unchanged`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        val secret = "record-token"
        DeveloperLogStore.record(context, "test", "Authorization: Bearer $secret")
        val loaded = DeveloperLogStore.load(context)
        assertEquals(1, loaded.size)
        assertFalse(loaded[0].message.contains(secret))
        assertTrue(loaded[0].message.contains("[redacted-secret]"))
    }

    @Test
    fun `newest-first ordering preserved`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        DeveloperLogStore.record(context, "first", "old")
        DeveloperLogStore.record(context, "second", "newer")
        val loaded = DeveloperLogStore.load(context)
        assertEquals(2, loaded.size)
        assertEquals("newer", loaded[0].message)
        assertEquals("old", loaded[1].message)
    }
}
