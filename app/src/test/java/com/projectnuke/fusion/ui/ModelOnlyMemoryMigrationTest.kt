package com.projectnuke.fusion.ui

import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.ModelAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelOnlyMemoryMigrationTest {
    @Test
    fun `unique imported display name migrates to stable uuid`() {
        val imported = FusionModelCatalog.builtIn.first().copy(
            id = "imported-uuid", displayName = "Imported Legacy", availability = ModelAvailability.CUSTOM_IMPORTED
        )
        assertEquals(imported.id, normalizeModelOnlyModelId(imported.displayName, listOf(imported)))
    }

    @Test
    fun `ambiguous imported display name remains unchanged`() {
        val a = FusionModelCatalog.builtIn.first().copy(id = "a", displayName = "Same", availability = ModelAvailability.CUSTOM_IMPORTED)
        val b = a.copy(id = "b")
        assertEquals("Same", normalizeModelOnlyModelId("Same", listOf(a, b)))
    }
    @Test
    fun `legacy display-name migration is idempotent`() {
        val spec = FusionModelCatalog.builtIn.first()
        val first = normalizeModelOnlyModelId(spec.displayName, listOf(spec))
        val second = normalizeModelOnlyModelId(first, listOf(spec))
        assertEquals(spec.id, first)
        assertEquals(first, second)
    }

    @Test
    fun `unresolvable legacy identity remains unchanged`() {
        val original = "unresolvable legacy model"
        val migrated = normalizeModelOnlyModelId(original, FusionModelCatalog.builtIn)
        assertEquals(original, migrated)
    }
}
