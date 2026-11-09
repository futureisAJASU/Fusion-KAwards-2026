package com.projectnuke.fusion.ai.provider

import android.content.res.AssetManager
import com.projectnuke.fusion.ai.data.UserExternalVariantStore
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalCapabilitySource
import com.projectnuke.fusion.ai.model.ExternalDiscoveredModel
import com.projectnuke.fusion.ai.model.ExternalModelVariant
import com.projectnuke.fusion.ai.model.ExternalReasoningEffort
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import com.projectnuke.fusion.ai.network.ExternalRequestCompatibility
import com.projectnuke.fusion.ai.network.ExternalOutputTokenField
import com.projectnuke.fusion.ai.network.ExternalTemperaturePolicy
import com.projectnuke.fusion.ai.network.ExternalProviderDialectResolver
import org.json.JSONArray
import org.json.JSONObject

fun interface ExternalModelCapabilityResolver {
    fun resolveVariants(providerType: AiProviderType, modelId: String): List<ExternalModelVariant>
}

interface ExternalProviderCapabilityAdapter {
    fun resolve(provider: AiProviderConfig, discoveredModel: ExternalDiscoveredModel): List<ExternalModelVariant>
}

object EmptyExternalProviderCapabilityAdapter : ExternalProviderCapabilityAdapter {
    override fun resolve(provider: AiProviderConfig, discoveredModel: ExternalDiscoveredModel): List<ExternalModelVariant> = emptyList()
}

private data class BuiltinCatalogEntry(
    val providerType: AiProviderType,
    val modelIds: Set<String>,
    val aliases: Set<String>,
    val variants: List<ExternalModelVariant>,
    val requestCompatibility: ExternalRequestCompatibility,
)

private object ExternalBuiltinCapabilityCatalog {
    fun fromAsset(assets: AssetManager): List<BuiltinCatalogEntry> =
        runCatching { assets.open("external_model_capabilities_v1.json").bufferedReader().use { parse(it.readText()) } }
            .getOrDefault(emptyList())

    internal fun parseForTest(raw: String): List<BuiltinCatalogEntry> = runCatching { parse(raw) }.getOrDefault(emptyList())

    private fun parse(raw: String): List<BuiltinCatalogEntry> {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val entries = root.getJSONArray("entries")
        require(entries.length() <= 128)
        return buildList {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val providerType = AiProviderType.valueOf(item.getString("providerType"))
                val modelIds = strings(item.getJSONArray("modelIds"), 32)
                val aliases = if (item.has("aliases")) strings(item.getJSONArray("aliases"), 32) else emptySet()
                require(modelIds.isNotEmpty())
                val verifiedAt = item.optString("verifiedAt").takeIf { it.isNotBlank() }
                val providerNote = item.optString("providerNote").takeIf { it.isNotBlank() }
                val requestCompatibility = if (item.has("request")) {
                    val request = item.getJSONObject("request")
                    ExternalRequestCompatibility(
                        outputTokenField = ExternalOutputTokenField.valueOf(request.getString("outputTokenField")),
                        temperaturePolicy = ExternalTemperaturePolicy.valueOf(
                            request.optString("temperaturePolicy", ExternalTemperaturePolicy.EMIT.name),
                        ),
                    )
                } else {
                    ExternalRequestCompatibility.FALLBACK
                }
                val variants = buildList {
                    val rawVariants = item.getJSONArray("variants")
                    require(rawVariants.length() in 1..16)
                    for (variantIndex in 0 until rawVariants.length()) {
                        val variant = rawVariants.getJSONObject(variantIndex)
                        val id = variant.getString("id")
                        require(id.startsWith("builtin:") && id.length <= 128)
                        val effort = ExternalReasoningEffort.valueOf(variant.getString("effort").uppercase())
                        val mapping = ReasoningEffortMapping.valueOf(
                            variant.optString("mapping", ReasoningEffortMapping.FLAT_REASONING_EFFORT.name)
                        )
                        add(
                            ExternalModelVariant(
                                id = id,
                                label = variant.getString("label"),
                                requestPatch = ExternalRequestPatch(effort, reasoningEffortMapping = mapping),
                                source = ExternalCapabilitySource.FUSION_VERIFIED,
                                verifiedAt = verifiedAt,
                                providerNote = providerNote,
                            )
                        )
                    }
                }
                require(variants.map { it.id }.toSet().size == variants.size)
                add(BuiltinCatalogEntry(providerType, modelIds, aliases, variants, requestCompatibility))
            }
        }
    }

    private fun strings(array: JSONArray, max: Int): Set<String> {
        require(array.length() <= max)
        return buildSet {
            for (index in 0 until array.length()) {
                val value = array.getString(index)
                require(value.isNotBlank() && value.length <= 256)
                add(value)
            }
        }
    }
}

object ExternalModelCapabilityRegistry : ExternalModelCapabilityResolver {
    @Volatile private var builtinEntries: List<BuiltinCatalogEntry> = emptyList()
    @Volatile private var initialized = false
    private val initializationLock = Any()

    /** Loads the bundled catalog once per process; malformed data means default-only. */
    fun initialize(assets: AssetManager) {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return
            // Parse before publishing, then mark completion last. Readers therefore
            // observe either the fallback catalog or one complete bundled catalog.
            builtinEntries = ExternalBuiltinCapabilityCatalog.fromAsset(assets)
            initialized = true
        }
    }

    /** Pure test seam: parses one catalog without mutating the process singleton. */
    internal fun resolveCatalogForTest(
        raw: String,
        providerType: AiProviderType,
        modelId: String,
    ): List<ExternalModelVariant> = compose(
        default = defaultVariant(),
        builtin = ExternalBuiltinCapabilityCatalog.parseForTest(raw)
            .filter { it.providerType == providerType && (modelId in it.modelIds || modelId in it.aliases) }
            .flatMap { it.variants },
        advertised = emptyList(),
        user = emptyList(),
    )

    internal fun resolveCompatibilityForTest(
        raw: String,
        providerType: AiProviderType,
        modelId: String,
    ): ExternalRequestCompatibility = ExternalBuiltinCapabilityCatalog.parseForTest(raw)
        .firstOrNull { it.providerType == providerType && (modelId in it.modelIds || modelId in it.aliases) }
        ?.requestCompatibility
        ?: ExternalRequestCompatibility.FALLBACK

    fun resolveRequestCompatibility(providerType: AiProviderType, modelId: String): ExternalRequestCompatibility =
        ExternalProviderDialectResolver.resolveRequestCompatibility(
            ExternalProviderDialectResolver.resolveDialect(providerType),
            builtinEntries
                .firstOrNull { it.providerType == providerType && (modelId in it.modelIds || modelId in it.aliases) }
                ?.requestCompatibility
                ?: ExternalRequestCompatibility.FALLBACK,
        )

    override fun resolveVariants(providerType: AiProviderType, modelId: String): List<ExternalModelVariant> {
        val builtin = builtinEntries
            .filter { it.providerType == providerType && (modelId in it.modelIds || modelId in it.aliases) }
            .flatMap { it.variants }
        return compose(defaultVariant(), builtin, emptyList(), emptyList())
    }

    fun resolveComposed(
        provider: AiProviderConfig,
        userVariants: List<UserExternalVariant> = emptyList(),
        discoveredModel: ExternalDiscoveredModel? = null,
        adapters: List<ExternalProviderCapabilityAdapter> = listOf(EmptyExternalProviderCapabilityAdapter),
    ): List<ExternalModelVariant> {
        val builtin = resolveVariants(provider.type, provider.modelId).drop(1)
        val advertised = if (discoveredModel == null) emptyList() else adapters.flatMap { adapter ->
            adapter.resolve(provider, discoveredModel).filter {
                it.source == ExternalCapabilitySource.PROVIDER_ADVERTISED && it.id.startsWith("provider:")
            }
        }
        val user = userVariants
            .filter { it.providerId == provider.id && it.modelId == provider.modelId && UserExternalVariantStore.validate(it) }
            .map(::toVariant)
        return compose(
            default = defaultVariant(),
            builtin = builtin,
            advertised = advertised,
            user = user,
        )
    }

    private fun defaultVariant() = ExternalModelVariant(
        id = "default",
        label = "기본",
        requestPatch = ExternalRequestPatch(),
    )

    fun resolveVariant(providerType: AiProviderType, modelId: String, variantId: String): ExternalModelVariant? =
        resolveVariants(providerType, modelId).firstOrNull { it.id == variantId }

    private fun toVariant(user: UserExternalVariant): ExternalModelVariant {
        val patch = user.patch as UserVariantPatch.ReasoningEffort
        return ExternalModelVariant(
            id = user.id,
            label = user.label,
            requestPatch = ExternalRequestPatch(customReasoningEffort = patch.value, reasoningEffortMapping = patch.mapping),
            source = ExternalCapabilitySource.USER_DEFINED,
        )
    }

    private fun compose(
        default: ExternalModelVariant,
        builtin: List<ExternalModelVariant>,
        advertised: List<ExternalModelVariant>,
        user: List<ExternalModelVariant>,
    ): List<ExternalModelVariant> = listOf(default) + (builtin + advertised + user)
        .distinctBy { it.source.name + "|" + it.id }
}
