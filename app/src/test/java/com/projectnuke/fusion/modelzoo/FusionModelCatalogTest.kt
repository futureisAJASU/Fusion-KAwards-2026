package com.projectnuke.fusion.modelzoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionModelCatalogTest {
    @Test
    fun `kanana textual inference is specific and precedes generic qwen inference`() {
        assertEquals(ModelFamily.KANANA, FusionModelCatalog.inferFamilyFromText("kanana-2-1.3b-instruct"))
        assertEquals(ModelFamily.QWEN, FusionModelCatalog.inferFamilyFromText("Qwen3-1.7B"))
        assertEquals(ModelFamily.QWEN, FusionModelCatalog.inferFamilyFromText("custom-qwen-like-model"))
    }

    @Test
    fun `kanana reasoning is conservatively disabled in every catalog construction path`() {
        val imported = FusionModelCatalog.importedSpec(
            displayName = "Kanana imported",
            path = "kanana-2-1.3b-instruct.litertlm",
            family = ModelFamily.CUSTOM,
        )
        val external = FusionModelCatalog.externalLinkedSpec(
            displayName = "Kanana external",
            originalFileName = "kanana-2-1.3b-instruct.litertlm",
            uriString = "content://kanana",
            fileSizeBytes = null,
            family = ModelFamily.CUSTOM,
        )
        assertEquals(ModelFamily.KANANA, imported.family)
        assertEquals(ModelFamily.KANANA, external.family)
        assertFalse(imported.supportsReasoning)
        assertFalse(external.supportsReasoning)
    }

    @Test
    fun `explicit qwen family never becomes kanana without kanana identity`() {
        assertEquals(
            ModelFamily.QWEN,
            FusionModelCatalog.resolveImportedFamily(
                fileName = "qwen3-1.7b.litertlm",
                sizeBytes = 1L,
                fallback = ModelFamily.QWEN,
            ),
        )
    }

    @Test
    fun `effective reasoning follows explicit spec capability`() {
        val kanana = FusionModelCatalog.importedSpec(
            displayName = "Imported Kanana",
            path = "/tmp/kanana.litertlm",
            family = ModelFamily.KANANA,
        )
        val qwen = FusionModelCatalog.builtIn.first { it.family == ModelFamily.QWEN }
        val gemma = FusionModelCatalog.builtIn.first { it.family == ModelFamily.GEMMA }
        val importedQwen = FusionModelCatalog.importedSpec("Imported Qwen", "/tmp/qwen.litertlm", ModelFamily.QWEN)

        assertFalse(FusionModelCatalog.effectiveReasoningEnabled(kanana, true))
        assertFalse(FusionModelCatalog.effectiveReasoningEnabled(gemma, true))
        assertTrue(FusionModelCatalog.effectiveReasoningEnabled(qwen, true))
        assertFalse(FusionModelCatalog.effectiveReasoningEnabled(qwen, false))
        assertFalse(FusionModelCatalog.effectiveReasoningEnabled(importedQwen, true))
        assertFalse(FusionModelCatalog.effectiveReasoningEnabled(null, true))
    }

    @Test
    fun `Gemma LiteRT catalog separates page from pinned binary and records trusted digest`() {
        val expected = mapOf(
            "gemma-4-e2b-it" to Triple(
                "6e5c4f1e395deb959c494953478fa5cec4b8008f",
                "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                2_588_147_712L,
            ),
            "gemma-4-e4b-it" to Triple(
                "28299f30ee4d43294517a4ac93abd6163412f07f",
                "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
                3_659_530_240L,
            ),
        )
        expected.forEach { (id, artifact) ->
            val spec = FusionModelCatalog.builtIn.single { it.id == id }
            assertTrue(spec.downloadUrl == null)
            assertTrue(spec.modelPageUrl?.startsWith("https://huggingface.co/") == true)
            assertTrue(spec.directDownloadUrl?.contains("/resolve/${artifact.first}/") == true)
            assertTrue(spec.directDownloadUrl?.endsWith(".litertlm") == true)
            assertEquals("litertlm", spec.directDownloadFormat)
            assertEquals(artifact.second, spec.directDownloadSha256)
            assertEquals(artifact.third.toFloat() / 1_000_000_000f, spec.directDownloadSizeGb!!, 0.0001f)
        }
    }
}
