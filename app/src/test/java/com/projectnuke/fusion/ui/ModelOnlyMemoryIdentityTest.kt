package com.projectnuke.fusion.ui

import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.model.resolveStableLocalModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOnlyMemoryIdentityTest {
    @Test
    fun `legacy built-in display name normalizes to stable id`() {
        val spec = FusionModelCatalog.builtIn.first()
        assertEquals(spec.id, normalizeModelOnlyModelId(spec.displayName, listOf(spec)))
    }

    @Test
    fun `stable and imported ids remain unchanged and unknown is preserved`() {
        val builtIn = FusionModelCatalog.builtIn.first()
        val imported = builtIn.copy(id = "imported-uuid", displayName = "Imported Model")
        assertEquals(builtIn.id, normalizeModelOnlyModelId(builtIn.id, listOf(builtIn)))
        assertEquals(imported.id, normalizeModelOnlyModelId(imported.id, listOf(imported)))
        assertEquals("Unknown Legacy Model", normalizeModelOnlyModelId("Unknown Legacy Model", listOf(builtIn)))
    }

    @Test
    fun `memory manager display selection and AB target converge on same stable id`() {
        val spec = FusionModelCatalog.builtIn.first()
        val selectedFromUi = resolveStableLocalModelId(spec.displayName, null, listOf(spec))
        val candidate = ConversationMemoryCandidate(
            id = "memory",
            text = "shared preference",
            conversationId = 1L,
            createdAt = 1L,
            scope = MemoryScope.MODEL_ONLY,
            modelId = selectedFromUi,
        )
        assertEquals(spec.id, selectedFromUi)
        assertTrue(isSavedMemoryCandidateApplicable(candidate, null, spec.id, false))
        assertTrue(isSavedMemoryCandidateApplicable(candidate, null, spec.id, false))
        assertFalse(isSavedMemoryCandidateApplicable(candidate, null, "different-id", false))
    }
}
