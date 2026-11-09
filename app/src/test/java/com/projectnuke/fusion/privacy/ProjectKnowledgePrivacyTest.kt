package com.projectnuke.fusion.privacy

import com.projectnuke.fusion.chat.ProjectInstructionPromptPolicy
import com.projectnuke.fusion.chat.prepareExternalPromptMessages
import com.projectnuke.fusion.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectKnowledgePrivacyTest {
    @Test
    fun `web query uses only current explicit user text`() {
        val projectName = "PRIVATE_PROJECT_NAME"
        val instruction = "PRIVATE_PROJECT_INSTRUCTION"
        val fileName = "PRIVATE_PROJECT_FILE.txt"
        val document = "PRIVATE_PROJECT_DOCUMENT_CONTENT"
        val current = "CURRENT_USER_QUERY"

        val query = DataEgressPolicy.buildWebQuery(
            userQuery = current,
            context = RequestPrivacyContext(
                hasAttachments = true,
                attachmentCount = 1,
                hasLocalRagExcerpts = true,
                localExcerptChars = document.length,
                fileNames = listOf(projectName, fileName),
                userText = current,
            ),
        )

        assertEquals(current, query)
        listOf(projectName, instruction, fileName, document).forEach { sentinel ->
            assertFalse(query.contains(sentinel))
        }
        assertEquals(
            "$current $document",
            DataEgressPolicy.buildWebQuery(
                "$current $document",
                RequestPrivacyContext(userText = "$current $document"),
            ),
        )
    }

    @Test
    fun `external prompt drops local-only project instruction envelope`() {
        val privateInstruction = "PRIVATE_PROJECT_INSTRUCTION"
        val prepared = prepareExternalPromptMessages(
            listOf(
                ChatMessage("system", ProjectInstructionPromptPolicy.modelMessage(privateInstruction)),
                ChatMessage("user", "CURRENT_USER_QUERY"),
            ),
        )

        assertTrue(prepared.any { it.role == "user" && it.content == "CURRENT_USER_QUERY" })
        assertFalse(prepared.any { it.content.contains(privateInstruction) })
    }
}
