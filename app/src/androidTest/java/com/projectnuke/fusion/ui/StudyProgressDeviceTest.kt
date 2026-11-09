package com.projectnuke.fusion.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StudyProgressDeviceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)
    @Test fun durableTopicReviewAndDeletedOwnerFailClosed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val id = 9_000_000_000L + System.nanoTime() % 100_000_000L
        val store = StudyProgressStore(File(context.filesDir,"study_progress"))
        try {
            db.chatDao().insertConversation(ConversationEntity(id=id,title="study smoke",createdAt=0,updatedAt=0))
            val owner=StudyProgressOwner(context,db.chatDao())
            owner.submission(id,"s","긴 한국어 학습 주제")
            owner.submission(id,"q",StudySessionPromptPolicy.initialQuestionPrompt)
            compose.setContentOnResumedTestHost { MaterialTheme { StudyProgressSurface(id,owner,{}, {}, null, false) } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("긴 한국어 학습 주제").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("다시 보기").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("다시 볼 항목 ✓").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(owner.load(id).sessions.single().topics.single().review)
            db.chatDao().deleteConversation(id)
            try { owner.submission(id,"deleted","must not persist"); fail("deleted owner admitted") } catch (_: IllegalStateException) { }
            assertEquals(2,store.load(id).sessions.single().submittedTokens.size)
            val job=launch(start=CoroutineStart.LAZY) { owner.submission(id,"cancelled","no write") }; job.cancelAndJoin()
        } finally { store.delete(id); db.close() }
    }
}
