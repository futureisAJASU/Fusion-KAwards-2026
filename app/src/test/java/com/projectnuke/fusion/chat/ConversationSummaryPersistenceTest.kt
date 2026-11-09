package com.projectnuke.fusion.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.test.PlatformSharedPreferences
import com.projectnuke.fusion.ui.loadConversationSummary
import com.projectnuke.fusion.ui.saveConversationSummary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias InMemoryPrefs = PlatformSharedPreferences

class ConversationSummaryPersistenceTest {

    private class TestContext(
        private val prefs: InMemoryPrefs = InMemoryPrefs(),
    ) : ContextWrapper(null) {

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            prefs

        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `automatic compaction leaves persisted manual summary unchanged`() = runBlocking {
        val context = TestContext()
        saveConversationSummary(context, 1L, "user-managed summary")
        val before = loadConversationSummary(context, 1L)
        assertNotNull(before)

        val messages = buildList {
            add(AutoCompactMessage(1L, "system", "policy"))
            repeat(8) { index ->
                add(AutoCompactMessage(10L + index * 2, "user", "u$index" + "x".repeat(200)))
                add(AutoCompactMessage(11L + index * 2, "assistant", "a$index" + "y".repeat(200)))
            }
            add(AutoCompactMessage(26L, "user", "current"))
        }
        AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 1024,
            requiredOutputTokens = 384,
            policy = LocalCompactPolicy(),
            existingMemory = null,
        ) { _, _, _ -> "runtime compact" }

        assertEquals(before, loadConversationSummary(context, 1L))
        assertEquals("user-managed summary", loadConversationSummary(context, 1L)?.summary)
    }

    @Test
    fun `summary save reports failure and fails closed when restoration fails`() {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)
        val before = saveConversationSummary(context, 1L, "before")
        assertNotNull(before)
        val durableBefore = prefs.durableValues["summary_1"]

        prefs.commitResult = false
        assertNull(saveConversationSummary(context, 1L, "after"))
        assertNull(loadConversationSummary(context, 1L))
        assertEquals(durableBefore, prefs.durableValues["summary_1"])
    }

    @Test
    fun `cross-conversation failed recovery cannot roll back a successful write to another conversation`() {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)
        // initial: summary_1 = A, summary_2 = B
        assertNotNull(saveConversationSummary(context, 1L, "A"))
        assertNotNull(saveConversationSummary(context, 2L, "B"))

        // While conversation 1's failed mutation is between its process
        // mutation and its recovery, conversation 2 is successfully written.
        prefs.onCommitBeforeResult {
            assertNotNull(saveConversationSummary(context, 2L, "B2"))
        }
        // With the hook re-entrancy guard, nested commits from the hook keep
        // the scripted queue: #1 conversation 1 commit fails; #2 conversation 2
        // commit succeeds; #3 conversation 1 recovery restores only its owned
        // keys.
        prefs.enqueueCommitResults(false, true, true)

        assertNull(saveConversationSummary(context, 1L, "A2"))

        // Required correct result: the successful independent write survives.
        assertEquals("B2", loadConversationSummary(context, 2L)?.summary)
        // Conversation 1 restored to its pre-mutation value.
        assertEquals("A", loadConversationSummary(context, 1L)?.summary)
        prefs.onCommitBeforeResult(null)
    }

    @Test
    fun `same-conversation mutation is serialized so recovery cannot clobber a newer write`() {
        val prefs = InMemoryPrefs()
        val context = TestContext(prefs)
        assertNotNull(saveConversationSummary(context, 1L, "A"))

        val entered = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        val t2Started = CountDownLatch(1)
        val t2Done = CountDownLatch(1)

        prefs.enqueueCommitResults(false, true)
        prefs.onCommitBeforeResult {
            // T1 is between its failed mutation and its recovery: pause it.
            entered.countDown()
            releaseRecovery.await(10, TimeUnit.SECONDS)
        }
        Thread { saveConversationSummary(context, 1L, "A2") }.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        Thread {
            t2Started.countDown()
            saveConversationSummary(context, 1L, "B2")
            t2Done.countDown()
        }.start()
        assertTrue(t2Started.await(10, TimeUnit.SECONDS))
        // T2 must NOT complete while T1 holds the conversation-1 lock; it can
        // only write after T1's recovery, so a newer value cannot be clobbered.
        assertFalse(t2Done.await(100, TimeUnit.MILLISECONDS))

        releaseRecovery.countDown()
        assertTrue(t2Done.await(10, TimeUnit.SECONDS))

        assertEquals("B2", loadConversationSummary(context, 1L)?.summary)
        prefs.onCommitBeforeResult(null)
    }
}
