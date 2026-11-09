package com.projectnuke.fusion.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOwnershipWiringTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/projectnuke/fusion/ui/$name.kt"
        return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
    }
    @Test fun `URI release consults authoritative catalog ownership`() {
        val source = source("ModelStorageSurface").substringAfter("internal fun releasePersistedReadPermission")
            .substringBefore("internal suspend fun persistCopiedModelSpec")
        assertTrue(source.contains("releaseUriPermissionIfUnowned"))
    }
    @Test fun `download completion checks user selection ownership`() {
        val source = source("ChatScreen").substringAfter("pendingDownloadModel?.let")
            .substringBefore("if (showAdvancedSettingsDialog)")
        assertTrue(source.contains("selectionOwnership.canSelect"))
    }
}
