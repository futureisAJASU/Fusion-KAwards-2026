package com.projectnuke.fusion.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DurableOutcomeWiringTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/projectnuke/fusion/ui/$name.kt"
        return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
    }
    @Test fun `drawer toggle cannot write a stale sibling setting`() {
        assertFalse(source("ConversationListScreenV2").contains("fun saveSettings()"))
    }
    @Test fun `profile save result cannot be ignored`() {
        assertFalse(source("ChatScreen").contains("if (resetModelOverride) profileStore.deleteModelOverride"))
    }
    @Test fun `project unlink cannot ignore deletion failure`() {
        assertFalse(source("ConversationListScreenV2").contains("runCatching { projectDao.removeAssignment(conversation.id) }"))
    }
    @Test fun `summary delete result controls dialog settlement`() {
        assertTrue(source("MemoryManagerScreen").contains("if (withContext(Dispatchers.IO) { deleteConversationSummary"))
    }
    @Test fun `AB rating and deletion branch on durable result`() {
        val source = source("ModelAbTestHistoryScreen")
        assertTrue(source.contains("if (!ModelAbTestHistoryStore.updateRating"))
        assertTrue(source.contains("if (!deleted)"))
    }
    @Test fun `AB completion distinguishes execution from durable recording`() {
        assertTrue(source("ModelAbTestLabScreen").contains("historySaved"))
    }
}
