package com.projectnuke.fusion.chat

import java.nio.file.Files
import java.io.File
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class StudyProgressTest {
    private val question = StudySessionPromptPolicy.initialQuestionPrompt
    @Test fun committedActionsHaveDeterministicCountsWithoutGrading() {
        var state = StudyProgressPolicy.submission(StudyProgress(1), "topic", "Linear algebra")
        state = StudyProgressPolicy.submission(state, "q", question)
        state = StudyProgressPolicy.submission(state, "hint", StudySessionPromptPolicy.hintPrompt)
        state = StudyProgressPolicy.submission(state, "answer", "My answer")
        val session = state.sessions.single()
        assertEquals(1, session.questionsRequested); assertEquals(1, session.hints); assertEquals(1, session.attempts)
        assertFalse(session.topics.single().understood)
    }

    @Test fun hintDraftDoesNotCountAsAnswerOrCloseThePendingAttempt() {
        var state = StudyProgressPolicy.submission(StudyProgress(1), "q", question)
        state = StudyProgressPolicy.submission(
            state,
            "hint",
            "I am stuck${StudySessionPromptPolicy.hintPrompt}",
        )
        assertEquals(0, state.sessions.single().attempts)
        assertEquals(1, state.sessions.single().hints)
        assertTrue(state.sessions.single().awaitingAttempt)

        state = StudyProgressPolicy.submission(state, "answer", "My answer")
        assertEquals(1, state.sessions.single().attempts)
        assertFalse(state.sessions.single().awaitingAttempt)
    }

    @Test fun explanationDraftDoesNotCountAsAnAnswerButGradingSubmissionDoes() {
        var state = StudyProgressPolicy.submission(StudyProgress(1), "q", question)
        state = StudyProgressPolicy.submission(
            state,
            "explanation",
            "please show why${StudySessionPromptPolicy.explanationPrompt}",
        )
        assertEquals(0, state.sessions.single().attempts)
        assertTrue(state.sessions.single().awaitingAttempt)

        state = StudyProgressPolicy.submission(
            state,
            "grading",
            "my submitted answer${StudySessionPromptPolicy.gradingPrompt}",
        )
        assertEquals(1, state.sessions.single().attempts)
        assertFalse(state.sessions.single().awaitingAttempt)
    }
    @Test fun reentryIsIdempotent() { val state = StudyProgressPolicy.submission(StudyProgress(1), "q", question)
        assertSame(state, StudyProgressPolicy.submission(state, "q", question)) }
    @Test fun explicitEndStartsAnotherBoundedSession() { var state = StudyProgress(1)
        repeat(20) { state = StudyProgressPolicy.end(StudyProgressPolicy.submission(state, "s$it", "topic$it")) }
        assertEquals(8, state.sessions.size); assertEquals("topic19", state.sessions.last().topics.single().name) }
    @Test fun onlyUserMarksUnderstandingAndReview() { var state = StudyProgressPolicy.submission(StudyProgress(1), "s", "topic")
        state = StudyProgressPolicy.topic(state,"s","topic",true,true)
        assertTrue(state.sessions.single().topics.single().review); assertTrue(state.sessions.single().topics.single().understood) }
    @Test fun topicsAndEventsAreBounded() { var state = StudyProgressPolicy.submission(StudyProgress(1),"s","topic")
        repeat(100) { state = StudyProgressPolicy.topic(state,"s","topic$it",true,false); state = StudyProgressPolicy.submission(state,"q$it",question) }
        assertEquals(8,state.sessions.single().topics.size); assertEquals(64,state.sessions.single().submittedTokens.size) }
    @Test fun anotherConversationAndBranchNeverShareState() { val parent = StudyProgressPolicy.submission(StudyProgress(1),"s","topic")
        assertTrue(StudyProgress(2).sessions.isEmpty()); assertEquals(1L,parent.conversationId) }
    @Test fun persistenceRoundTripAndDeletion() { val root = Files.createTempDirectory("study").toFile()
        try { val store=StudyProgressStore(root); val state=store.update(1) { StudyProgressPolicy.submission(it,"s","topic") }
            assertEquals(state,StudyProgressStore(root).load(1)); assertTrue(store.delete(1)); assertTrue(store.load(1).sessions.isEmpty())
        } finally { root.deleteRecursively() } }
    @Test fun corruptAndOversizedPersistenceFailClearly() { val root=Files.createTempDirectory("study").toFile()
        try { val file=File(root,"study_1.json"); val store=StudyProgressStore(root)
            file.writeBytes(byteArrayOf(0xC3.toByte(),0x28)); assertThrows(Exception::class.java) { store.load(1) }
            file.writeText("x".repeat(65537)); assertThrows(Exception::class.java) { store.load(1) }
        } finally { root.deleteRecursively() } }
    @Test fun failedMutationKeepsLastDurableStateAndFatalIdentity() { val root=Files.createTempDirectory("study").toFile()
        try { val store=StudyProgressStore(root); val original=store.update(1) { StudyProgressPolicy.submission(it,"s","topic") }
            val fatal=AssertionError("fatal"); val seen=assertThrows(AssertionError::class.java) { store.update(1) { throw fatal } }
            assertSame(fatal,seen); assertEquals(original,store.load(1))
        } finally { root.deleteRecursively() } }
    @Test fun concurrentOwnersDoNotLoseAcknowledgedCounts() { val root=Files.createTempDirectory("study").toFile(); val pool=Executors.newFixedThreadPool(4)
        try { val tasks=(0 until 40).map { n -> pool.submit { StudyProgressStore(root).update(1) { StudyProgressPolicy.submission(it,"q$n",question) } } }
            tasks.forEach { it.get() }; assertEquals(40,StudyProgressStore(root).load(1).sessions.single().questionsRequested)
        } finally { pool.shutdownNow(); root.deleteRecursively() } }
}
