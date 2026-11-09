package com.projectnuke.fusion.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLibraryStructureTest {
    @Test fun `library starts student-first with exactly three top tabs`() {
        val structure = ModelLibraryStructure()
        assertEquals(ModelLibraryTab.RECOMMENDED, structure.initialTab)
        assertEquals(listOf(ModelLibraryTab.RECOMMENDED, ModelLibraryTab.MY_MODELS, ModelLibraryTab.EXPLORE), structure.topTabs)
        assertTrue(structure.searchAndFiltersOnlyInExplore)
    }
}
