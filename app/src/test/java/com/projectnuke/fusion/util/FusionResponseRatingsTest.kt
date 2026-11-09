package com.projectnuke.fusion.util

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionResponseRatingsTest {
    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    private fun rating(id: Long, value: String = "up"): JSONObject = JSONObject()
        .put("messageId", id)
        .put("rating", value)
        .put("modelName", "model")
        .put("settingsSnapshot", "snapshot")
        .put("createdAt", 10L)

    private fun ratingsFile(dir: File): File = File(dir, "fusion_response_ratings.json")

    @Test
    fun `corrupt toggle quarantines evidence and lands fresh state`() {
        val dir = Files.createTempDirectory("fusion-ratings").toFile()
        val context = TestContext(dir)
        ratingsFile(dir).writeText("[{broken")
        assertNull(FusionResponseRatings.get(context, 1L))

        assertTrue(
            FusionResponseRatings.toggle(
                context,
                FusionResponseRating(1L, "up", "model", "snapshot", 10L),
            ),
        )
        assertEquals("[{broken", File(dir, "fusion_response_ratings.json.corrupt.bak").readText())
        assertEquals("up", FusionResponseRatings.get(context, 1L))
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt delete fails closed without touching evidence`() {
        val dir = Files.createTempDirectory("fusion-ratings").toFile()
        val context = TestContext(dir)
        ratingsFile(dir).writeText("[{broken")

        assertFalse(FusionResponseRatings.deleteForMessages(context, setOf(1L)))
        assertEquals("[{broken", ratingsFile(dir).readText())
        dir.deleteRecursively()
    }

    @Test
    fun `malformed and duplicate entries fail closed as corrupt`() {
        val dir = Files.createTempDirectory("fusion-ratings").toFile()
        val context = TestContext(dir)
        // Non-object entry among valid entries: reads fail closed, while a
        // toggle quarantines the evidence and lands the fresh rating.
        ratingsFile(dir).writeText(JSONArray().put(rating(1L)).put("stray").toString())
        assertNull(FusionResponseRatings.get(context, 1L))
        assertTrue(
            FusionResponseRatings.toggle(context, FusionResponseRating(2L, "up", "m", "s", 1L)),
        )
        assertEquals("up", FusionResponseRatings.get(context, 2L))
        assertNull(FusionResponseRatings.get(context, 1L))
        // Duplicate message IDs can never come from the writer.
        ratingsFile(dir).writeText(JSONArray().put(rating(1L)).put(rating(1L, "down")).toString())
        assertFalse(FusionResponseRatings.deleteForMessages(context, setOf(1L)))
        assertEquals(2, JSONArray(ratingsFile(dir).readText()).length())
        dir.deleteRecursively()
    }

    @Test
    fun `writer-impossible metadata types are corrupt instead of defaulted`() {
        val dir = Files.createTempDirectory("fusion-ratings").toFile()
        val context = TestContext(dir)
        ratingsFile(dir).writeText(
            JSONArray().put(rating(1L).apply { put("createdAt", "10") }).toString(),
        )
        assertNull(FusionResponseRatings.get(context, 1L))
        assertFalse(FusionResponseRatings.deleteForMessages(context, setOf(1L)))
        assertEquals(1, JSONArray(ratingsFile(dir).readText()).length())
        dir.deleteRecursively()
    }

    @Test
    fun `cap keeps exactly max ratings, evicting oldest first`() {
        val dir = Files.createTempDirectory("fusion-ratings").toFile()
        val context = TestContext(dir)
        val full = JSONArray()
        repeat(FusionResponseRatings.MaxRatings) { i ->
            full.put(rating((i + 1).toLong()))
        }
        ratingsFile(dir).writeText(full.toString())

        assertTrue(
            FusionResponseRatings.toggle(
                context,
                FusionResponseRating(9_999L, "up", "model", "snapshot", 11L),
            ),
        )
        val after = JSONArray(ratingsFile(dir).readText())
        assertEquals(FusionResponseRatings.MaxRatings, after.length())
        val ids = (0 until after.length()).map { after.optJSONObject(it).optLong("messageId") }.toSet()
        assertTrue(ids.contains(9_999L))
        assertFalse(ids.contains(1L))
        assertTrue(ids.contains(2L))
        dir.deleteRecursively()
    }

    @Test
    fun `toggle off removes the rating and reports unrated`() {
        val dir = Files.createTempDirectory("fusion-ratings").toFile()
        val context = TestContext(dir)
        val entry = FusionResponseRating(1L, "up", "model", "snapshot", 10L)
        assertTrue(FusionResponseRatings.toggle(context, entry))
        assertEquals("up", FusionResponseRatings.get(context, 1L))
        assertFalse(FusionResponseRatings.toggle(context, entry))
        assertNull(FusionResponseRatings.get(context, 1L))
        dir.deleteRecursively()
    }
}
