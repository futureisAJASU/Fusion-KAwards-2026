package com.projectnuke.fusion.ai.data

import android.content.Context
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Bounded durable definitions for user-owned external model variants. */
class UserExternalVariantStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val lock = Any()

    private data class LoadResult(
        val values: List<UserExternalVariant>,
        val repairFailed: Boolean,
    )

    fun list(providerId: String, modelId: String): List<UserExternalVariant> = synchronized(lock) {
        loadLocked().values.filter { it.providerId == providerId && it.modelId == modelId }
    }

    fun listAll(): List<UserExternalVariant> = synchronized(lock) { loadLocked().values }

    fun save(variant: UserExternalVariant): Boolean = synchronized(lock) {
        val result = loadLocked()
        if (result.repairFailed) return@synchronized false
        if (!validate(variant)) return@synchronized false
        val existing = result.values.toMutableList()
        val sameScope = existing.count {
            it.providerId == variant.providerId && it.modelId == variant.modelId && it.id != variant.id
        }
        if (sameScope >= MAX_VARIANTS_PER_PROVIDER_MODEL && existing.none { it.id == variant.id }) {
            return@synchronized false
        }
        if (existing.none { it.id == variant.id } && existing.size >= MAX_TOTAL_VARIANTS) {
            return@synchronized false
        }
        val updated = existing.filterNot { it.id == variant.id } + variant
        if (updated.size > MAX_TOTAL_VARIANTS ||
            updated.groupingBy { it.providerId to it.modelId }.eachCount().values.any { it > MAX_VARIANTS_PER_PROVIDER_MODEL }
        ) {
            return@synchronized false
        }
        prefs.edit().putString(KeyVariants, encode(updated).toString()).commit()
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (!isValidId(id)) return@synchronized false
        val result = loadLocked()
        if (result.repairFailed) return@synchronized false
        val existing = result.values
        if (existing.none { it.id == id }) return@synchronized true
        prefs.edit().putString(KeyVariants, encode(existing.filterNot { it.id == id }).toString()).commit()
    }

    /** Removes all definitions owned by [providerId] without affecting other providers. */
    fun deleteProvider(providerId: String): Boolean = synchronized(lock) {
        if (providerId.isBlank() || providerId.length > MAX_PROVIDER_ID_LENGTH) return@synchronized false
        val result = loadLocked()
        if (result.repairFailed) return@synchronized false
        val existing = result.values
        val updated = existing.filterNot { it.providerId == providerId }
        if (updated.size == existing.size) return@synchronized true
        prefs.edit().putString(KeyVariants, encode(updated).toString()).commit()
    }

    private fun loadLocked(): LoadResult {
        val raw = prefs.getString(KeyVariants, null) ?: return LoadResult(emptyList(), false)

        // Whole-store structural failure: top-level cannot be parsed as an array,
        // or array exceeds the global maximum.
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return wholeStoreRepair(raw)
        }
        if (array.length() > MAX_TOTAL_VARIANTS) {
            return wholeStoreRepair(raw)
        }

        val survivors = mutableListOf<UserExternalVariant>()
        val scopeEntries = mutableMapOf<Pair<String, String>, MutableList<UserExternalVariant>>()
        var anyRejected = false

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                anyRejected = true
                continue
            }
            val variant = try {
                decode(item)
            } catch (_: Exception) {
                anyRejected = true
                continue
            }
            if (!validate(variant)) {
                anyRejected = true
                continue
            }
            if (survivors.any { it.id == variant.id }) {
                anyRejected = true
                continue
            }
            val scope = variant.providerId to variant.modelId
            val scopeList = scopeEntries.getOrPut(scope) { mutableListOf() }
            if (scopeList.size < MAX_VARIANTS_PER_PROVIDER_MODEL) {
                scopeList.add(variant)
                survivors.add(variant)
            } else {
                anyRejected = true
            }
        }

        if (!anyRejected) {
            return LoadResult(survivors.toList(), false)
        }

        val canonical = encode(survivors.toList()).toString()
        val edit = prefs.edit()
            .putString(KeyCorrupt, raw)
            .putString(KeyVariants, canonical)
        val committed = edit.commit()
        return LoadResult(survivors.toList(), !committed)
    }

    private fun wholeStoreRepair(raw: String): LoadResult {
        val edit = prefs.edit()
            .putString(KeyCorrupt, raw)
            .remove(KeyVariants)
        val committed = edit.commit()
        return LoadResult(emptyList(), !committed)
    }

    private fun encode(values: List<UserExternalVariant>) = JSONArray().also { array ->
        values.forEach { variant ->
            val patch = variant.patch as UserVariantPatch.ReasoningEffort
            array.put(
                JSONObject()
                    .put("id", variant.id)
                    .put("providerId", variant.providerId)
                    .put("modelId", variant.modelId)
                    .put("label", variant.label)
                    .put("patchType", "reasoning_effort")
                    .put("value", patch.value)
                    .put("mapping", patch.mapping.name)
            )
        }
    }

    private fun decode(item: JSONObject): UserExternalVariant {
        require(item.optString("patchType") == "reasoning_effort")
        return UserExternalVariant(
            id = item.getString("id"),
            providerId = item.getString("providerId"),
            modelId = item.getString("modelId"),
            label = item.getString("label"),
            patch = UserVariantPatch.ReasoningEffort(
                value = item.getString("value"),
                mapping = ReasoningEffortMapping.valueOf(item.getString("mapping")),
            ),
        )
    }

    companion object {
        const val MAX_VARIANTS_PER_PROVIDER_MODEL = 16
        const val MAX_TOTAL_VARIANTS = 256
        const val MAX_ID_LENGTH = 128
        const val MAX_PROVIDER_ID_LENGTH = 128
        const val MAX_MODEL_ID_LENGTH = AiProviderValidator.MAX_MODEL_ID
        const val MAX_LABEL_LENGTH = 80
        const val MAX_VALUE_LENGTH = 32
        private const val PrefsName = "fusion_external_user_variants"
        private const val KeyVariants = "variants.v1"
        private const val KeyCorrupt = "variants.v1.corrupt.bak"
        private val IdPattern = Regex("user:[A-Za-z0-9][A-Za-z0-9_-]{0,126}")
        private val ValuePattern = Regex("[A-Za-z0-9_-]{1,32}")

        fun newId(): String = "user:${UUID.randomUUID()}"

        fun validate(variant: UserExternalVariant): Boolean {
            val patch = variant.patch as? UserVariantPatch.ReasoningEffort ?: return false
            return isValidId(variant.id) &&
                variant.providerId.isNotBlank() && variant.providerId.length <= MAX_PROVIDER_ID_LENGTH &&
                variant.modelId.isNotBlank() && variant.modelId.length <= MAX_MODEL_ID_LENGTH &&
                variant.label.isNotBlank() && variant.label.length <= MAX_LABEL_LENGTH &&
                patch.value.length <= MAX_VALUE_LENGTH && ValuePattern.matches(patch.value)
        }

        fun isValidId(id: String): Boolean = id.length <= MAX_ID_LENGTH && IdPattern.matches(id)
    }
}
