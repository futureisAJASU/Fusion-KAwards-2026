package com.projectnuke.fusion.ai.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserExternalVariantStoreTest {
    private class TestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File("build/test-user-variants")
    }

    private fun variant(
        id: String = UserExternalVariantStore.newId(),
        providerId: String = "provider-a",
        modelId: String = "model-a",
        label: String = "Turbo Think",
        value: String = "high",
        mapping: ReasoningEffortMapping = ReasoningEffortMapping.FLAT_REASONING_EFFORT,
    ) = UserExternalVariant(
        id = id,
        providerId = providerId,
        modelId = modelId,
        label = label,
        patch = UserVariantPatch.ReasoningEffort(value, mapping),
    )

    @Test
    fun `create edit rename delete and round trip`() {
        val prefs = PlatformSharedPreferences()
        val first = UserExternalVariantStore(TestContext(prefs))
        val id = UserExternalVariantStore.newId()
        assertTrue(first.save(variant(id = id)))
        assertTrue(first.save(variant(id = id, label = "Renamed", value = "medium", mapping = ReasoningEffortMapping.NESTED_REASONING_EFFORT)))
        val second = UserExternalVariantStore(TestContext(prefs))
        val loaded = second.list("provider-a", "model-a")
        assertEquals("Renamed", loaded.single().label)
        val patch = loaded.single().patch as UserVariantPatch.ReasoningEffort
        assertEquals("medium", patch.value)
        assertEquals(ReasoningEffortMapping.NESTED_REASONING_EFFORT, patch.mapping)
        assertTrue(second.delete(id))
        assertTrue(second.listAll().isEmpty())
    }

    @Test
    fun `provider and model scopes and bounds are enforced`() {
        val store = UserExternalVariantStore(TestContext(PlatformSharedPreferences()))
        assertTrue(store.save(variant(providerId = "provider-a", modelId = "model-a")))
        assertTrue(store.save(variant(providerId = "provider-b", modelId = "model-a")))
        assertTrue(store.save(variant(providerId = "provider-a", modelId = "model-b")))
        assertEquals(1, store.list("provider-a", "model-a").size)
        assertEquals(1, store.list("provider-b", "model-a").size)
        assertFalse(store.save(variant(label = "")))
        assertFalse(store.save(variant(value = "not valid")))
        assertFalse(store.save(variant(id = "wrong-id")))
    }

    @Test
    fun `per scope maximum is bounded`() {
        val store = UserExternalVariantStore(TestContext(PlatformSharedPreferences()))
        repeat(UserExternalVariantStore.MAX_VARIANTS_PER_PROVIDER_MODEL) { index ->
            assertTrue(store.save(variant(label = "v$index")))
        }
        assertFalse(store.save(variant(label = "overflow")))
    }

    @Test
    fun `global maximum rejects new writes without changing the loadable store`() {
        val prefs = PlatformSharedPreferences()
        val store = UserExternalVariantStore(TestContext(prefs))
        repeat(UserExternalVariantStore.MAX_TOTAL_VARIANTS) { index ->
            val providerIndex = index / UserExternalVariantStore.MAX_VARIANTS_PER_PROVIDER_MODEL
            val scopeIndex = index % UserExternalVariantStore.MAX_VARIANTS_PER_PROVIDER_MODEL
            assertTrue(
                store.save(
                    variant(
                        id = "user:bound-$index",
                        providerId = "provider-$providerIndex",
                        modelId = "model-$scopeIndex",
                        label = "v$index",
                    )
                )
            )
        }
        val beforeRejectedWrite = prefs.getString("variants.v1", null)
        assertEquals(UserExternalVariantStore.MAX_TOTAL_VARIANTS, store.listAll().size)

        assertFalse(store.save(variant(id = "user:bound-overflow", providerId = "provider-overflow", modelId = "model")))
        assertEquals(beforeRejectedWrite, prefs.getString("variants.v1", null))
        assertEquals(UserExternalVariantStore.MAX_TOTAL_VARIANTS, UserExternalVariantStore(TestContext(prefs)).listAll().size)
    }

    @Test
    fun `editing at global maximum succeeds but moving into a full scope is rejected`() {
        val prefs = PlatformSharedPreferences()
        val store = UserExternalVariantStore(TestContext(prefs))
        repeat(UserExternalVariantStore.MAX_VARIANTS_PER_PROVIDER_MODEL) { index ->
            assertTrue(store.save(variant(id = "user:edit-$index", providerId = "provider-full", modelId = "model-full", label = "v$index")))
        }
        repeat(UserExternalVariantStore.MAX_TOTAL_VARIANTS - UserExternalVariantStore.MAX_VARIANTS_PER_PROVIDER_MODEL) { index ->
            assertTrue(store.save(variant(id = "user:edit-${index + 16}", providerId = "provider-other-$index", modelId = "model-other", label = "v${index + 16}")))
        }
        assertTrue(store.save(variant(id = "user:edit-0", providerId = "provider-full", modelId = "model-full", label = "edited")))
        assertFalse(store.save(variant(id = "user:edit-16", providerId = "provider-full", modelId = "model-full", label = "invalid move")))
        assertEquals("edited", store.list("provider-full", "model-full").first { it.id == "user:edit-0" }.label)
        assertEquals(UserExternalVariantStore.MAX_TOTAL_VARIANTS, UserExternalVariantStore(TestContext(prefs)).listAll().size)
    }

    @Test
    fun `provider cleanup removes only its variants and is idempotent`() {
        val prefs = PlatformSharedPreferences()
        val store = UserExternalVariantStore(TestContext(prefs))
        assertTrue(store.save(variant(id = "user:a", providerId = "provider-a")))
        assertTrue(store.save(variant(id = "user:a2", providerId = "provider-a", modelId = "model-b")))
        assertTrue(store.save(variant(id = "user:b", providerId = "provider-b")))

        assertTrue(store.deleteProvider("provider-a"))
        assertTrue(store.deleteProvider("provider-a"))
        assertEquals(listOf("user:b"), store.listAll().map { it.id })
        assertFalse(store.deleteProvider(""))
        assertFalse(store.deleteProvider("x".repeat(UserExternalVariantStore.MAX_PROVIDER_ID_LENGTH + 1)))
    }

    @Test
    fun `provider cleanup reports failed durable write without changing durable data`() {
        val prefs = PlatformSharedPreferences()
        val store = UserExternalVariantStore(TestContext(prefs))
        assertTrue(store.save(variant(id = "user:a", providerId = "provider-a")))
        val durableBefore = prefs.durableValues["variants.v1"]
        prefs.commitResult = false

        assertFalse(store.deleteProvider("provider-a"))
        assertEquals(durableBefore, prefs.durableValues["variants.v1"])
    }

    // Phase 1 partial-corruption recovery tests

    @Test
    fun `partial repair preserves unrelated valid variants when one object is malformed`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:v1").put("providerId", "p-a").put("modelId", "m-a").put("label", "L1").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(JSONObject().put("id", "bad-row").put("providerId", "p")) // malformed (missing label/model/patch)
        array.put(JSONObject().put("id", "user:v2").put("providerId", "p-a").put("modelId", "m-a").put("label", "L2").put("patchType", "reasoning_effort").put("value", "low").put("mapping", "FLAT_REASONING_EFFORT"))
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        val all = store.listAll()
        assertEquals(2, all.size)
        assertTrue(all.any { it.id == "user:v1" })
        assertTrue(all.any { it.id == "user:v2" })
        assertEquals(array.toString(), prefs.getString("variants.v1.corrupt.bak", null))
        val repaired = prefs.getString("variants.v1", null)
        assertTrue(repaired != null)
        assertTrue(repaired!!.contains("user:v1"))
        assertTrue(repaired.contains("user:v2"))
    }

    @Test
    fun `non-object array item is skipped during partial repair`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:v1").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(42)
        array.put(JSONObject().put("id", "user:v2").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "low").put("mapping", "FLAT_REASONING_EFFORT"))
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        assertEquals(2, store.listAll().size)
        assertTrue(store.listAll().map { it.id }.contains("user:v1"))
        assertTrue(store.listAll().map { it.id }.contains("user:v2"))
    }

    @Test
    fun `invalid ID row is skipped`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "wrong-id").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(JSONObject().put("id", "user:v2").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "low").put("mapping", "FLAT_REASONING_EFFORT"))
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        val all = store.listAll()
        assertEquals(1, all.size)
        assertEquals("user:v2", all[0].id)
    }

    @Test
    fun `invalid patch mapping row is skipped`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:v1").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "BAD_MAPPING"))
        array.put(JSONObject().put("id", "user:v2").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "low").put("mapping", "FLAT_REASONING_EFFORT"))
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        assertEquals(1, store.listAll().size)
        assertEquals("user:v2", store.listAll()[0].id)
    }

    @Test
    fun `duplicate ID uses first valid occurrence`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:dup").put("providerId", "p").put("modelId", "m").put("label", "First").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(JSONObject().put("id", "user:dup").put("providerId", "p").put("modelId", "m").put("label", "Second").put("patchType", "reasoning_effort").put("value", "low").put("mapping", "NESTED_REASONING_EFFORT"))
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        val all = store.listAll()
        assertEquals(1, all.size)
        assertEquals("First", all[0].label)
        assertEquals("high", (all[0].patch as UserVariantPatch.ReasoningEffort).value)
        assertEquals(array.toString(), prefs.getString("variants.v1.corrupt.bak", null))
    }

    @Test
    fun `per scope overflow retains bounded valid set`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        repeat(18) { index ->
            array.put(JSONObject().put("id", "user:v$index").put("providerId", "provider-full").put("modelId", "model-full").put("label", "v$index").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        }
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        val fullScope = store.list("provider-full", "model-full")
        assertEquals(16, fullScope.size)
        val ids = fullScope.map { it.id }.sorted()
        assertEquals((0..15).map { "user:v$it" }.sorted(), ids)
        assertEquals(array.toString(), prefs.getString("variants.v1.corrupt.bak", null))
    }

    @Test
    fun `partial repair stores original raw payload in corrupt backup`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:v1").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(JSONObject().put("bad", true))
        val raw = array.toString()
        prefs.edit().putString("variants.v1", raw).commit()

        UserExternalVariantStore(TestContext(prefs)).listAll()
        assertEquals(raw, prefs.getString("variants.v1.corrupt.bak", null))
    }

    @Test
    fun `partial repair rewrites active store canonically`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:v1").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(99)
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        store.listAll()
        val repaired = prefs.getString("variants.v1", null)!!
        val repairedArray = JSONArray(repaired)
        assertEquals(1, repairedArray.length())
        assertEquals("user:v1", repairedArray.getJSONObject(0).getString("id"))
    }

    @Test
    fun `new store instance reads repaired canonical survivors`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:v1").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(JSONObject().put("bad", true))
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val first = UserExternalVariantStore(TestContext(prefs))
        assertEquals(1, first.listAll().size)
        val second = UserExternalVariantStore(TestContext(prefs))
        assertEquals(1, second.listAll().size)
        assertEquals("user:v1", second.listAll()[0].id)
    }

    @Test
    fun `failed repair commit prevents mutation from overwriting durable data`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("bad", true))
        prefs.edit().putString("variants.v1", array.toString()).commit()
        prefs.commitResult = false

        val store = UserExternalVariantStore(TestContext(prefs))
        assertFalse(store.deleteProvider("provider-a"))
        // Durable authority remains the original corrupt payload (not overwritten by mutation)
        val durable = prefs.durableValues.get("variants.v1")
        assertTrue(durable is String && (durable as String).contains("bad"))
    }

    @Test
    fun `wholly malformed JSON still quarantines and removes active payload`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("variants.v1", "not-json-at-all").commit()
        val store = UserExternalVariantStore(TestContext(prefs))
        assertEquals(0, store.listAll().size)
        assertEquals("not-json-at-all", prefs.getString("variants.v1.corrupt.bak", null))
        assertTrue(prefs.getString("variants.v1", null) == null || prefs.getString("variants.v1", "") == "")
    }

    @Test
    fun `array larger than global maximum remains whole-store corruption`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        repeat(257) { index ->
            array.put(JSONObject().put("id", "user:v$index").put("providerId", "p").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        }
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        assertEquals(0, store.listAll().size)
        assertTrue(prefs.getString("variants.v1.corrupt.bak", null)!!.contains("user:v0"))
        assertTrue(prefs.getString("variants.v1", null) == null || prefs.getString("variants.v1", "") == "")
    }

    @Test
    fun `ordinary valid round-trip remains byte and semantic compatible`() {
        val prefs = PlatformSharedPreferences()
        val store = UserExternalVariantStore(TestContext(prefs))
        val id = UserExternalVariantStore.newId()
        assertTrue(store.save(variant(id = id, label = "Test", value = "high", mapping = ReasoningEffortMapping.NESTED_REASONING_EFFORT)))
        val before = prefs.getString("variants.v1", null)!!
        val loaded = UserExternalVariantStore(TestContext(prefs)).listAll()
        assertEquals(1, loaded.size)
        assertEquals(id, loaded[0].id)
        val after = prefs.getString("variants.v1", null)!!
        assertEquals(before, after)
    }

    @Test
    fun `provider cleanup remains scoped and idempotent after repair`() {
        val prefs = PlatformSharedPreferences()
        val array = JSONArray()
        array.put(JSONObject().put("id", "user:a").put("providerId", "provider-a").put("modelId", "m").put("label", "L").put("patchType", "reasoning_effort").put("value", "high").put("mapping", "FLAT_REASONING_EFFORT"))
        array.put(JSONObject().put("bad", true))
        prefs.edit().putString("variants.v1", array.toString()).commit()

        val store = UserExternalVariantStore(TestContext(prefs))
        assertTrue(store.deleteProvider("provider-b") == true) // idempotent on missing provider
        assertEquals(1, store.listAll().size)
        assertTrue(store.deleteProvider("provider-a"))
        assertEquals(0, store.listAll().size)
        assertTrue(prefs.getString("variants.v1.corrupt.bak", null)!!.contains("user:a"))
    }
}
