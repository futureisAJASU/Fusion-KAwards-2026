package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.ExternalAiChatResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAiChatPresentationTest {
    @Test
    fun nonBlankPartialGetsExactlyOneDurableNotice() {
        val presented = presentTruncatedExternalAnswer("partial")

        assertEquals("partial\n\n$EXTERNAL_OUTPUT_TRUNCATION_NOTICE", presented)
        assertEquals(1, presented!!.windowed(EXTERNAL_OUTPUT_TRUNCATION_NOTICE.length).count { it == EXTERNAL_OUTPUT_TRUNCATION_NOTICE })
    }

    @Test
    fun alreadyDecoratedContentIsNotDoubleNoticed() {
        val once = presentTruncatedExternalAnswer("partial")!!

        assertEquals(once, presentTruncatedExternalAnswer(once))
    }

    @Test
    fun partialContentIsPreservedBeforeSeparator() {
        val partial = "partial\nwith formatting"

        assertTrue(presentTruncatedExternalAnswer(partial)!!.startsWith(partial + "\n\n"))
    }

    @Test
    fun blankTruncatedOutputDoesNotCreateFakeAnswer() {
        assertNull(presentTruncatedExternalAnswer(" \n\t "))
        assertNull(externalAiUserAnswer(ExternalAiChatResult.Truncated("", "P")))
    }

    @Test
    fun ordinarySuccessContentIsNotModified() {
        val result = externalAiUserAnswer(ExternalAiChatResult.Success("complete", "P"))!!

        assertEquals("complete", result.content)
        assertFalse(result.truncated)
    }

    @Test
    fun ordinarySuccessMayBeAdoptedAsMemoryCandidate() {
        assertEquals("candidate", adoptExternalMemoryCandidate(ExternalAiChatResult.Success(" candidate ", "P")))
    }

    @Test
    fun truncatedOutputMayNotBeAdoptedAsMemoryCandidate() {
        assertNull(adoptExternalMemoryCandidate(ExternalAiChatResult.Truncated("candidate", "P")))
    }

    @Test
    fun emptyErrorAndNoProviderRemainNonAdoptable() {
        assertNull(adoptExternalMemoryCandidate(ExternalAiChatResult.Empty("P")))
        assertNull(adoptExternalMemoryCandidate(ExternalAiChatResult.Error("error")))
        assertNull(adoptExternalMemoryCandidate(ExternalAiChatResult.NoProvider("missing")))
    }
}
