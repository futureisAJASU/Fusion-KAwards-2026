package com.projectnuke.fusion.ui
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RecreationOwnershipWiringTest {
    private fun source(path: String): String = listOf(File("src/main/$path"), File("app/src/main/$path"))
        .first { it.isFile }.readText()
    private fun ui(name: String) = source("java/com/projectnuke/fusion/ui/$name.kt")
    @Test fun `export pending token survives recreation without bundling payload`() {
        assertTrue(ui("ConversationListScreenV2").contains("pendingChatMarkdownExport by rememberSaveable"))
        assertTrue(ui("ConversationListScreenV2").contains("pendingMarkdownStore.stage"))
    }
    @Test fun `model import retains URI and confirmation authority`() {
        assertTrue(ui("ChatScreen").contains("rememberSaveable(stateSaver = PendingModelImportSaver)"))
    }
    @Test fun `translation presentation and execution have retained owner`() {
        assertTrue(ui("FusionApp").contains("StandaloneTranslationViewModel"))
        assertFalse(ui("FusionApp").contains("mainSurface by remember {"))
    }
    @Test fun `editor drafts survive recreation without saving API key in bundle`() {
        assertFalse(ui("PromptLabScreen").contains("customPrompt by remember {"))
        assertTrue(ui("MemoryManagerScreen").contains("MemoryEditorViewModel"))
        val provider = source("kotlin/com/projectnuke/fusion/ai/ui/AiProviderSettingsScreen.kt")
        assertFalse(provider.contains("displayName by remember {"))
        assertTrue(provider.contains("ProviderCredentialDraftViewModel"))
        assertFalse(provider.contains("apiKey by rememberSaveable"))
    }
    @Test fun `singleton verifier policy captures extracted application context`() {
        val provider = ui("ChatScreen").substringAfter("cpuPolicyProvider = {").substringBefore("semanticMatcher =")
        assertFalse(provider.contains("context.applicationContext"))
    }
}
