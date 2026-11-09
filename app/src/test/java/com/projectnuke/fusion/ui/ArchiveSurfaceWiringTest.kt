package com.projectnuke.fusion.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security regressions for consumers that previously bypassed the tested policy. */
class ArchiveSurfaceWiringTest {
    private fun source(name: String): String {
        val relative = "src/main/java/com/projectnuke/fusion/ui/$name.kt"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }

    @Test fun `project rows and brief cannot consume unfiltered conversations`() {
        val source = source("ConversationListScreenV2")
        assertFalse(source.contains("items(projectConversations,"))
        assertFalse(source.contains("val conversationInputs = projectConversations.map"))
        assertTrue(source.contains("ArchiveAccessPolicy.accessibleConversations"))
    }

    @Test fun `final drawer navigation checks durable archive admission before selecting`() {
        val source = source("FusionApp").substringAfter("onOpenConversation =")
            .substringBefore("onConversationRemovedFromList =")
        assertTrue(source.contains("archiveConversationAdmission"))
    }

    @Test fun `memory summary display and dialogs use authorized conversation IDs`() {
        val source = source("MemoryManagerScreen")
        assertTrue(source.contains("ArchiveAccessPolicy.accessibleSummaries"))
        assertTrue(source.contains("editingSummary?.takeIf"))
        assertTrue(source.contains("deletingSummary?.takeIf"))
    }
}
