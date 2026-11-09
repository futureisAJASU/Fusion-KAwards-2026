package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelRuntimeFormat
import com.projectnuke.fusion.model.toRequestedEngineProfile

class LocalModelSettingsProfileTest {
    @Test
    fun `resolver applies the shared bounds to low and high values`() {
        val result = LocalModelSettingsResolver.resolve(
            modelId = "bounds",
            globalDefaults = LocalModelSettingsOverride(
                topK = 1,
                reasoningBudgetTokens = 1,
            ),
        )

        assertEquals(1, result.generationSettings.topK)
        assertEquals(1, result.generationSettings.reasoningBudgetTokens)

        val upper = LocalModelSettingsResolver.resolve(
            modelId = "bounds-upper",
            globalDefaults = LocalModelSettingsOverride(
                topK = 100,
                reasoningBudgetTokens = 8192,
            ),
        )
        assertEquals(100, upper.generationSettings.topK)
        assertEquals(8192, upper.generationSettings.reasoningBudgetTokens)
    }

    @Test
    fun `model override beats global defaults and recommendation`() {
        val result = LocalModelSettingsResolver.resolve(
            modelId = "gemma-id",
            modelRecommendation = LocalModelRecommendation(1024, 4096),
            globalDefaults = LocalModelSettingsOverride(maxOutputTokens = 2048, manualKvCapacityTokens = 8192, contextMode = LocalContextMode.MANUAL),
            perModelOverride = LocalModelSettingsOverride(maxOutputTokens = 3072, manualKvCapacityTokens = 12288, contextMode = LocalContextMode.MANUAL),
        )
        assertEquals(3072, result.generationSettings.maxTokens)
        assertEquals(12288, result.effectiveKvCapacityTokens)
        assertEquals("model", result.provenance.output)
    }

    @Test
    fun `global defaults beat catalog recommendation`() {
        val result = LocalModelSettingsResolver.resolve(
            modelId = "qwen-id",
            modelRecommendation = LocalModelRecommendation(1024, 4096),
            globalDefaults = LocalModelSettingsOverride(maxOutputTokens = 2048, contextMode = LocalContextMode.MANUAL, manualKvCapacityTokens = 8192),
        )
        assertEquals(2048, result.generationSettings.maxTokens)
        assertEquals(8192, result.effectiveKvCapacityTokens)
        assertEquals("global", result.provenance.output)
    }

    @Test
    fun `global edit profile does not inherit selected model recommendation`() {
        val modelA = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            modelRecommendation = LocalModelRecommendation(1024, 4096),
        )
        val modelB = LocalModelSettingsResolver.resolve(
            modelId = "model-b",
            modelRecommendation = LocalModelRecommendation(4096, 8192),
        )
        val global = resolveGlobalLocalSettingsProfile(globalDefaults = null)

        assertEquals(1024, modelA.generationSettings.maxTokens)
        assertEquals(4096, modelB.generationSettings.maxTokens)
        assertEquals(global.generationSettings.maxTokens, 1024)
        assertEquals(global.generationSettings, resolveGlobalLocalSettingsProfile(null).generationSettings)
    }

    @Test
    fun `resetting an override returns immediately to global values`() {
        val global = LocalModelSettingsOverride(maxOutputTokens = 2048, contextMode = LocalContextMode.MANUAL, manualKvCapacityTokens = 8192)
        val overridden = LocalModelSettingsResolver.resolve("m", globalDefaults = global, perModelOverride = LocalModelSettingsOverride(maxOutputTokens = 4096))
        val reset = LocalModelSettingsResolver.resolve("m", globalDefaults = global)
        assertEquals(4096, overridden.generationSettings.maxTokens)
        assertEquals(2048, reset.generationSettings.maxTokens)
    }

    @Test
    fun `two model overrides remain independent`() {
        val a = LocalModelSettingsResolver.resolve("a", perModelOverride = LocalModelSettingsOverride(maxOutputTokens = 1111))
        val b = LocalModelSettingsResolver.resolve("b", perModelOverride = LocalModelSettingsOverride(maxOutputTokens = 2222))
        assertEquals(1111, a.generationSettings.maxTokens)
        assertEquals(2222, b.generationSettings.maxTokens)
    }

    @Test
    fun `display name is absent from identity and codec keeps stable id`() {
        val document = LocalModelSettingsDocument(modelOverrides = mapOf("stable-id" to LocalModelSettingsOverride(maxOutputTokens = 3000)))
        val decoded = LocalModelSettingsProfileCodec.decode(LocalModelSettingsProfileCodec.encode(document))
        assertTrue(decoded.modelOverrides.containsKey("stable-id"))
        assertFalse(decoded.modelOverrides.containsKey("old display name"))
    }

    @Test
    fun `malformed and future profile data recover safely`() {
        assertEquals(LocalModelSettingsDocument(), LocalModelSettingsProfileCodec.decode("not-json"))
        assertEquals(LocalModelSettingsDocument(), LocalModelSettingsProfileCodec.decode("{\"version\":99,\"models\":{}}"))
    }

    @Test
    fun `strict profile recovery catches malformed exceptions but propagates fatal errors`() {
        assertEquals(null, decodeRecoverableProfileOrNull<String> { throw IllegalArgumentException("malformed") })
        val fatal = LinkageError("fatal decode failure")
        assertTrue(runCatching { decodeRecoverableProfileOrNull<String> { throw fatal } }.exceptionOrNull() === fatal)
    }

    @Test
    fun `unknown future fields do not break known profile fields`() {
        val decoded = LocalModelSettingsProfileCodec.decode(
            "{\"version\":1,\"future\":{\"newField\":true},\"models\":{\"id\":{\"maxOutputTokens\":2048,\"futureValue\":7}}}"
        )
        assertEquals(2048, decoded.modelOverrides["id"]?.maxOutputTokens)
    }

    @Test
    fun `built in identity is stable before and after managed download`() {
        val builtIn = FusionModelCatalog.builtIn
        val selected = builtIn.first { it.id == "gemma-4-e2b-it" }

        assertEquals(
            selected.id,
            resolveStableLocalModelId(selected.displayName, null, builtIn),
        )
        assertEquals(
            selected.id,
            resolveStableLocalModelId(
                selected.displayName,
                "/managed/models/${selected.fileName}",
                builtIn,
            ),
        )
    }

    @Test
    fun `imported identity survives display name changes through its persisted path`() {
        val imported = com.projectnuke.fusion.modelzoo.FusionModelCatalog.importedSpec(
            displayName = "Original name",
            path = "/managed/custom/model.litertlm",
            family = ModelFamily.CUSTOM,
        )

        assertEquals(
            imported.id,
            resolveStableLocalModelId(
                selectedModel = "Renamed model",
                selectedModelPath = imported.localPath,
                catalog = listOf(imported),
            ),
        )
    }

    @Test
    fun `stable identity resolver is shared for chat and alternate local request owners`() {
        val selected = FusionModelCatalog.builtIn.first { it.id == "gemma-4-e2b-it" }
        val chatId = resolveStableLocalModelId(selected.displayName, "/download/${selected.fileName}", FusionModelCatalog.builtIn)
        val alternateOwnerId = resolveStableLocalModelId(selected.displayName, "/download/${selected.fileName}", FusionModelCatalog.builtIn)
        assertEquals(chatId, alternateOwnerId)
        assertEquals(selected.id, chatId)
    }

    @Test
    fun `auto recommendation uses explicit 8GB and 12GB RAM classes`() {
        val gib = 1024L * 1024L * 1024L
        assertEquals(LocalDeviceRamTier.RAM_8GB_CLASS, resolveLocalDeviceRamTier(8L * gib))
        assertEquals(LocalDeviceRamTier.RAM_12GB_OR_MORE, resolveLocalDeviceRamTier(12L * gib))
        assertEquals(
            LocalDeviceRamTier.RAM_8GB_CLASS,
            resolveLocalDeviceRamTier(12L * gib, isLowRamDevice = true),
        )
        assertEquals(
            LocalDeviceRamTier.RAM_8GB_CLASS,
            resolveLocalDeviceRamTier(12L * gib, systemLowMemory = true),
        )
    }

    @Test
    fun `8GB Kanana recommendation is conservative without changing explicit overrides`() {
        val spec = FusionModelCatalog.externalLinkedSpec(
            displayName = "Kanana",
            originalFileName = "kanana.litertlm",
            uriString = "content://kanana",
            fileSizeBytes = 1_315_000_000L,
            family = ModelFamily.KANANA,
        )
        val recommendation = recommendedLocalModelRecommendation(spec, lowRam = true)

        assertEquals(1024, recommendation.recommendedMaxOutputTokens)
        assertEquals(2048, recommendation.recommendedKvCapacityTokens)
        assertEquals(false, recommendation.mtpEnabled)
        assertEquals(AcceleratorMode.CPU, recommendation.recommendedAccelerator)

        val explicitGpu = LocalModelSettingsResolver.resolve(
            modelId = spec.id,
            modelRecommendation = recommendation,
            perModelOverride = LocalModelSettingsOverride(
                accelerator = AcceleratorMode.GPU,
                maxOutputTokens = 4096,
                contextMode = LocalContextMode.MANUAL,
                manualKvCapacityTokens = 4096,
            ),
        )
        assertEquals(AcceleratorMode.GPU, explicitGpu.generationSettings.accelerator)
        assertEquals(4096, explicitGpu.generationSettings.maxTokens)
        assertEquals(4096, explicitGpu.effectiveKvCapacityTokens)
    }

    @Test
    fun `AUTO capacity grows for requested output while MANUAL capacity stays fixed`() {
        val recommendation = LocalModelRecommendation(
            recommendedMaxOutputTokens = 1024,
            recommendedKvCapacityTokens = 4096,
        )
        val autoSmall = LocalModelSettingsResolver.resolve(
            modelId = "auto",
            modelRecommendation = recommendation,
            perModelOverride = LocalModelSettingsOverride(
                maxOutputTokens = 1024,
                contextMode = LocalContextMode.AUTO,
            ),
        )
        val autoLarge = LocalModelSettingsResolver.resolve(
            modelId = "auto",
            modelRecommendation = recommendation,
            perModelOverride = LocalModelSettingsOverride(
                maxOutputTokens = 4096,
                contextMode = LocalContextMode.AUTO,
            ),
        )
        assertEquals(4096, autoSmall.effectiveKvCapacityTokens)
        assertEquals(8192, autoLarge.effectiveKvCapacityTokens)
        assertTrue(autoSmall.effectiveKvCapacityTokens != autoLarge.effectiveKvCapacityTokens)

        val manualSmall = LocalModelSettingsResolver.resolve(
            modelId = "manual",
            modelRecommendation = recommendation,
            perModelOverride = LocalModelSettingsOverride(
                maxOutputTokens = 1024,
                contextMode = LocalContextMode.MANUAL,
                manualKvCapacityTokens = 8192,
            ),
        )
        val manualLarge = LocalModelSettingsResolver.resolve(
            modelId = "manual",
            modelRecommendation = recommendation,
            perModelOverride = LocalModelSettingsOverride(
                maxOutputTokens = 2048,
                contextMode = LocalContextMode.MANUAL,
                manualKvCapacityTokens = 8192,
            ),
        )
        assertEquals(8192, manualSmall.effectiveKvCapacityTokens)
        assertEquals(8192, manualLarge.effectiveKvCapacityTokens)
        assertEquals(
            manualSmall.generationSettings.toRequestedEngineProfile("manual", false),
            manualLarge.generationSettings.toRequestedEngineProfile("manual", false),
        )
        assertTrue(
            autoSmall.generationSettings.toRequestedEngineProfile("auto", false) !=
                autoLarge.generationSettings.toRequestedEngineProfile("auto", false)
        )
    }

    @Test
    fun `resolveCapturedLocalModelIdentity without file evidence returns null family on catalog miss`() {
        val catalogMissResult = resolveCapturedLocalModelIdentity(
            selectedModel = "kanana-k0-control-dynamic.litertlm",
            selectedModelPath = null,
            catalog = emptyList(),
        )
        assertEquals("kanana-k0-control-dynamic.litertlm", catalogMissResult.stableId)
        assertEquals(null, catalogMissResult.family)

        val catalogWithKanana = listOf(
            FusionModelCatalog.importedSpec(
                displayName = "kanana-k0-control-dynamic.litertlm",
                path = "/managed/models/kanana-k0-control-dynamic.litertlm",
                family = ModelFamily.KANANA,
            )
        )
        val withCatalogResult = resolveCapturedLocalModelIdentity(
            selectedModel = "kanana-k0-control-dynamic.litertlm",
            selectedModelPath = "/managed/models/kanana-k0-control-dynamic.litertlm",
            catalog = catalogWithKanana,
        )
        assertEquals(catalogWithKanana[0].id, withCatalogResult.stableId)
        assertEquals(ModelFamily.KANANA, withCatalogResult.family)
    }

    @Test
    fun `resolveCapturedLocalModelIdentity returns catalog family when present`() {
        val catalogSpec = FusionModelCatalog.importedSpec(
            displayName = "Custom Kanana",
            path = "/managed/models/custom.litertlm",
            family = ModelFamily.KANANA,
        )
        val result = resolveCapturedLocalModelIdentity(
            selectedModel = catalogSpec.id,
            selectedModelPath = catalogSpec.localPath,
            catalog = listOf(catalogSpec),
        )
        assertEquals(catalogSpec.id, result.stableId)
        assertEquals(ModelFamily.KANANA, result.family)
    }

    // A. catalog empty + valid kanana-k0-control-dynamic.litertlm file metadata
    // -> transient runtime admission spec exists, KANANA, LITERT_LM, size 1,317,271,136.
    // Calls production resolveRuntimeAdmissionModelSpecPure directly.
    @Test
    fun `catalog empty with valid control file metadata admits transient Kanana spec`() {
        val evidence = RuntimeAdmissionFileEvidence(
            fileName = "kanana-k0-control-dynamic.litertlm",
            sizeBytes = 1_317_271_136L,
            exists = true,
            isFile = true,
        )
        val spec = resolveRuntimeAdmissionModelSpecPure(
            catalog = emptyList(),
            selectedModelStableId = "kanana-k0-control-dynamic.litertlm",
            selectedModelId = "kanana-k0-control-dynamic.litertlm",
            selectedModelPath = "/managed/models/kanana-k0-control-dynamic.litertlm",
            modelPath = "/managed/models/kanana-k0-control-dynamic.litertlm",
            fileEvidence = evidence,
        )
        assertNotNull(spec)
        assertEquals(ModelFamily.KANANA, spec!!.family)
        assertEquals(ModelRuntimeFormat.LITERT_LM, spec.runtimeFormat)
        assertEquals(1_317_271_136L, spec.fileSizeBytes)
    }

    // B. duplicate display names -> display lookup is NOT arbitrarily first-match.
    // Exact path determines truth; ambiguous display alone resolves to null fallback.
    @Test
    fun `duplicate display names do not resolve by arbitrary first match`() {
        val first = FusionModelCatalog.importedSpec(
            displayName = "Duplicate",
            path = "/managed/models/first.litertlm",
            family = ModelFamily.CUSTOM,
        )
        val second = FusionModelCatalog.importedSpec(
            displayName = "Duplicate",
            path = "/managed/models/second.litertlm",
            family = ModelFamily.KANANA,
        )
        val catalog = listOf(first, second)

        // Exact path wins over ambiguous display identity.
        val byPath = resolveRuntimeAdmissionModelSpecPure(
            catalog = catalog,
            selectedModelStableId = "unknown-stable-id",
            selectedModelId = "Duplicate",
            selectedModelPath = second.localPath,
            modelPath = null,
            fileEvidence = null,
        )
        assertEquals(second.id, byPath?.id)

        // Ambiguous display with no exact path must not arbitrarily pick first.
        val ambiguous = resolveRuntimeAdmissionModelSpecPure(
            catalog = catalog,
            selectedModelStableId = "unknown-stable-id",
            selectedModelId = "Duplicate",
            selectedModelPath = "/managed/models/unknown.litertlm",
            modelPath = null,
            fileEvidence = null,
        )
        assertNull(ambiguous)
    }

    // C. catalog miss + valid Kanana path/file metadata + 8GB device
    // -> recommendation output 1024, KV 2048, MTP false, accelerator CPU.
    // Calls production resolveLocalModelRecommendationContextPure directly.
    @Test
    fun `catalog miss with valid Kanana file metadata yields 8GB safe recommendation`() {
        val evidence = RuntimeAdmissionFileEvidence(
            fileName = "kanana-k0-control-dynamic.litertlm",
            sizeBytes = 1_317_271_136L,
            exists = true,
            isFile = true,
        )
        val context = resolveLocalModelRecommendationContextPure(
            catalog = emptyList(),
            selectedModel = "kanana-k0-control-dynamic.litertlm",
            selectedModelPath = "/managed/models/kanana-k0-control-dynamic.litertlm",
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            fileEvidence = evidence,
        )
        assertTrue(context.deviceIsLowRam)
        assertNotNull(context.recommendation)
        assertEquals(1024, context.recommendation!!.recommendedMaxOutputTokens)
        assertEquals(2048, context.recommendation!!.recommendedKvCapacityTokens)
        assertEquals(false, context.recommendation!!.mtpEnabled)
        assertEquals(AcceleratorMode.CPU, context.recommendation!!.recommendedAccelerator)
    }

    // D. invalid/nonexistent path -> no fabricated runnable spec.
    @Test
    fun `invalid nonexistent path admits no runnable spec`() {
        val missingEvidence = RuntimeAdmissionFileEvidence(
            fileName = "model.litertlm",
            sizeBytes = 0L,
            exists = false,
            isFile = false,
        )
        val spec = resolveRuntimeAdmissionModelSpecPure(
            catalog = emptyList(),
            selectedModelStableId = "custom-id",
            selectedModelId = "model.litertlm",
            selectedModelPath = "/managed/models/model.litertlm",
            modelPath = "/managed/models/model.litertlm",
            fileEvidence = missingEvidence,
        )
        assertNull(spec)

        val nullPathSpec = resolveRuntimeAdmissionModelSpecPure(
            catalog = emptyList(),
            selectedModelStableId = "custom-id",
            selectedModelId = "model.litertlm",
            selectedModelPath = null,
            modelPath = null,
            fileEvidence = null,
        )
        assertNull(nullPathSpec)

        val context = resolveLocalModelRecommendationContextPure(
            catalog = emptyList(),
            selectedModel = "nonexistent.litertlm",
            selectedModelPath = "/nonexistent/path.litertlm",
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            fileEvidence = missingEvidence,
        )
        assertTrue(context.deviceIsLowRam)
        assertNull(context.recommendation)
    }

    // E. non-LiteRT-LM file -> no runnable admission fallback.
    @Test
    fun `non LiteRT-LM file admits no runnable spec`() {
        val evidence = RuntimeAdmissionFileEvidence(
            fileName = "model.gguf",
            sizeBytes = 1_000_000L,
            exists = true,
            isFile = true,
        )
        val spec = resolveRuntimeAdmissionModelSpecPure(
            catalog = emptyList(),
            selectedModelStableId = "custom-id",
            selectedModelId = "model.gguf",
            selectedModelPath = "/managed/models/model.gguf",
            modelPath = "/managed/models/model.gguf",
            fileEvidence = evidence,
        )
        assertNull(spec)
    }
}
