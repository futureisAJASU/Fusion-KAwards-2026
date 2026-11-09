package com.projectnuke.fusion.ui

import org.junit.Assert.*
import org.junit.Test

class ModelSelectionOwnershipTest {
    @Test fun `older download cannot steal explicit later model selection`() {
        val owner = ModelSelectionOwnership()
        val downloadA = owner.beginDownload()
        owner.userSelected()
        assertFalse(owner.canSelect(downloadA))
    }
    @Test fun `completion without later selection retains intended automatic activation`() {
        val owner = ModelSelectionOwnership()
        assertTrue(owner.canSelect(owner.beginDownload()))
    }
    @Test fun `latest requested download owns activation regardless of completion order`() {
        val owner = ModelSelectionOwnership()
        val first = owner.beginDownload()
        val second = owner.beginDownload()
        assertFalse(owner.canSelect(first))
        assertTrue(owner.canSelect(second))
    }
}
