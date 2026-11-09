package com.projectnuke.fusion.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RemainingSurfaceWiringTest {
    private fun source(name: String) = File("src/main/java/com/projectnuke/fusion/ui/$name.kt").readText()
    @Test fun `download surface receives owner progress and cancellation`() {
        val chat = source("ChatScreen")
        val library = source("model/ModelLibraryScreen")
        assertTrue(chat.contains("downloads = downloadStates.values.toList()"))
        assertTrue(chat.contains("downloadJobs[key]?.cancel()"))
        assertTrue(chat.contains("downloadStates = downloadStates - downloadKey"))
        assertTrue(library.contains("LinearProgressIndicator"))
        assertTrue(library.contains("취소 요청 중"))
    }
    @Test fun `project counts observe unlink and authoritative conversation updates`() {
        assertTrue(source("ConversationListScreenV2").contains("LaunchedEffect(projects, isDrawerOpen, projectResourceRevision, conversations, archivedConversations)"))
    }
    @Test fun `glyph actions expose their actual action`() {
        assertTrue(source("ChatComposerComponents").contains("contentDescription = \"첨부 제거:"))
        assertTrue(source("StandaloneTranslationScreen").contains("actionDescription = \"번역에서 돌아가기\""))
    }
}
