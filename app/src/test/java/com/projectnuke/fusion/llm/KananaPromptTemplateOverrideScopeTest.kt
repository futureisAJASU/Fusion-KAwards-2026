package com.projectnuke.fusion.llm

import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalApi::class)
class KananaPromptTemplateOverrideScopeTest {
    @Test
    fun `previous override is restored after successful conversation creation`() {
        val previous = "pre-existing-template"
        ExperimentalFlags.overwritePromptTemplate = previous
        try {
            val result = withPromptTemplateOverride("kanana-template") {
                assertEquals("kanana-template", ExperimentalFlags.overwritePromptTemplate)
                "conversation"
            }
            assertEquals("conversation", result)
            assertEquals(previous, ExperimentalFlags.overwritePromptTemplate)
        } finally {
            ExperimentalFlags.overwritePromptTemplate = null
        }
    }

    @Test
    fun `previous override is restored when conversation creation fails`() {
        val previous = "pre-existing-template"
        ExperimentalFlags.overwritePromptTemplate = previous
        try {
            try {
                withPromptTemplateOverride("kanana-template") {
                    throw IllegalStateException("createConversation failed")
                }
                fail("expected createConversation failure")
            } catch (expected: IllegalStateException) {
                assertEquals("createConversation failed", expected.message)
            }
            assertEquals(previous, ExperimentalFlags.overwritePromptTemplate)
        } finally {
            ExperimentalFlags.overwritePromptTemplate = null
        }
    }

    @Test
    fun `previous override is restored after cancellation`() {
        val previous = "pre-existing-template"
        ExperimentalFlags.overwritePromptTemplate = previous
        try {
            try {
                withPromptTemplateOverride("kanana-template") {
                    throw CancellationException("cancelled during createConversation")
                }
                fail("expected cancellation")
            } catch (expected: CancellationException) {
                assertEquals("cancelled during createConversation", expected.message)
            }
            assertEquals(previous, ExperimentalFlags.overwritePromptTemplate)
        } finally {
            ExperimentalFlags.overwritePromptTemplate = null
        }
    }

    @Test
    fun `unrelated model does not change flag and next conversation sees no leak`() {
        ExperimentalFlags.overwritePromptTemplate = null
        try {
            withPromptTemplateOverride(null) {
                assertEquals(null, ExperimentalFlags.overwritePromptTemplate)
            }
            withPromptTemplateOverride("kanana-template") {
                assertEquals("kanana-template", ExperimentalFlags.overwritePromptTemplate)
            }
            assertEquals(null, ExperimentalFlags.overwritePromptTemplate)
            withPromptTemplateOverride(null) {
                assertEquals(null, ExperimentalFlags.overwritePromptTemplate)
            }
        } finally {
            ExperimentalFlags.overwritePromptTemplate = null
        }
    }

    @Test
    fun `concurrent unrelated conversation cannot observe Kanana override`() {
        ExperimentalFlags.overwritePromptTemplate = null
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        var unrelatedObserved: String? = "not-run"
        try {
            val kanana = thread {
                withPromptTemplateOverride("kanana-template") {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val unrelated = thread {
                withPromptTemplateOverride(null) {
                    unrelatedObserved = ExperimentalFlags.overwritePromptTemplate
                    secondEntered.countDown()
                    assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
                }
            }

            assertFalse("unrelated creation must wait for the global override owner", secondEntered.await(200, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            assertEquals(null, unrelatedObserved)
            releaseSecond.countDown()
            kanana.join(5_000)
            unrelated.join(5_000)
            assertFalse(kanana.isAlive)
            assertFalse(unrelated.isAlive)
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            ExperimentalFlags.overwritePromptTemplate = null
        }
    }
}
