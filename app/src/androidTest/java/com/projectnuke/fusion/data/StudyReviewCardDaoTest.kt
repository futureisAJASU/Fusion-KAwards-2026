package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.study.QuickReviewCardRepository
import com.projectnuke.fusion.study.QuickReviewCardDeleteResult
import com.projectnuke.fusion.study.QuickReviewCardProposal
import com.projectnuke.fusion.study.QuickReviewCardWriteResult
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.study.ReviewCardScope
import com.projectnuke.fusion.study.StudyReviewCardReminderScheduler
import com.projectnuke.fusion.study.QuickReviewRatingResult
import com.projectnuke.fusion.study.ReviewCardRating
import com.projectnuke.fusion.tasks.ProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.reconcilePendingReminderNotificationCleanup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyReviewCardDaoTest {
    @Test
    fun `source deletion cannot pass card source validation before insert commits`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceRead = CountDownLatch(1)
        val releaseSourceRead = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (armed.get() && sql.contains("FROM conversations WHERE id = ?", ignoreCase = true)) {
                        armed.set(false)
                        sourceRead.countDown()
                        check(releaseSourceRead.await(5, TimeUnit.SECONDS)) { "source read barrier timed out" }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            val conversationId = database.chatDao().insertConversation(
                ConversationEntity(title = "source", createdAt = 1L, updatedAt = 1L),
            )
            val proposal = QuickReviewCardProposal(
                id = "source-race-card",
                scope = ReviewCardScope.PERSONAL,
                projectId = null,
                front = "front",
                back = "back",
                sourceKind = ReviewCardOrigin.STUDY_TOPIC,
                sourceConversationId = conversationId,
                sourceMessageId = null,
                sourceSavedKnowledgeId = null,
                sourceProjectId = null,
                sourceProjectFileId = null,
                dueAt = 1L,
            )
            val repository = QuickReviewCardRepository(database, RecordingCardReminderScheduler())
            armed.set(true)
            val save = async(Dispatchers.IO) { repository.save(proposal, archiveAccessIncluded = false) }
            assertTrue("repository did not reach source validation", sourceRead.await(5, TimeUnit.SECONDS))

            val deleteAttempted = CountDownLatch(1)
            val deleteCompleted = CountDownLatch(1)
            val delete = async(Dispatchers.IO) {
                deleteAttempted.countDown()
                database.chatDao().deleteConversation(conversationId)
                deleteCompleted.countDown()
            }
            assertTrue(deleteAttempted.await(5, TimeUnit.SECONDS))
            val deletionCrossedValidation = deleteCompleted.await(150, TimeUnit.MILLISECONDS)
            releaseSourceRead.countDown()

            assertEquals(QuickReviewCardWriteResult.Stored, withTimeout(5_000L) { save.await() })
            withTimeout(5_000L) { delete.await() }
            assertTrue("source deletion committed during the validation/write transaction", !deletionCrossedValidation)
            assertEquals(null, database.chatDao().getConversationById(conversationId))
            assertTrue(database.studyReviewCardDao().get(proposal.id) != null)
        } finally {
            releaseSourceRead.countDown()
            database.close()
        }
    }

    @Test
    fun `cards survive reopen and project deletion cascades project scope`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "study-cards-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val projectId = database.projectDao().insertProject(
                ProjectEntity(name = "review project", createdAt = 1, updatedAt = 1),
            )
            val personal = StudyReviewCardEntity(
                id = "personal-${System.nanoTime()}", scope = "PERSONAL", projectId = null,
                front = "front", back = "back", sourceKind = "USER_TEXT", sourceConversationId = null,
                sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                sourceProjectFileId = null, dueAt = 10, createdAt = 1, updatedAt = 1,
                reminderAt = 50, reminderEnabled = true,
            )
            val project = personal.copy(
                id = "project-${System.nanoTime()}", scope = "PROJECT", projectId = projectId,
                reminderAt = null, reminderEnabled = false,
            )
            database.studyReviewCardDao().insert(personal)
            database.studyReviewCardDao().insert(project)
            assertEquals(personal.id, database.studyReviewCardDao().observePersonal(32).first().single().id)
            assertEquals(project.id, database.studyReviewCardDao().observeProject(projectId, 32).first().single().id)
            assertEquals(1, database.studyReviewCardDao().recordReview(personal.id, 1, "HARD", 100, 20))
            database.close()

            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val restored = database.studyReviewCardDao().get(personal.id)!!
            assertEquals(1, restored.reviewCount)
            assertEquals("HARD", restored.lastRating)
            assertEquals(100L, restored.dueAt)
            assertNull(restored.reminderAt)
            assertEquals(false, restored.reminderEnabled)
            database.projectDao().deleteProject(projectId)
            assertNull(database.studyReviewCardDao().get(project.id))
            assertEquals(1, database.studyReviewCardDao().count())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `getOverdueReminders returns only enabled overdue rows`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "study-cards-overdue-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val now = 1_000_000L
            val overdueCard = StudyReviewCardEntity(
                id = "overdue", scope = "PERSONAL", projectId = null,
                front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                sourceProjectFileId = null, dueAt = 0, createdAt = 1, updatedAt = 1,
                reminderAt = 500L, reminderEnabled = true,
            )
            val futureCard = overdueCard.copy(
                id = "future", reminderAt = 2_000_000L, reminderEnabled = true,
            )
            val disabledCard = overdueCard.copy(
                id = "disabled", reminderAt = 500L, reminderEnabled = false,
            )
            listOf(overdueCard, futureCard, disabledCard).forEach { database.studyReviewCardDao().insert(it) }

            val overdue = database.studyReviewCardDao().getOverdueReminders(now, MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS)
            assertEquals(1, overdue.size)
            assertEquals("overdue", overdue.single().id)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun consumeReminderIsAtomicCompareAndConsume() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "study-cards-race-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val card = StudyReviewCardEntity(
                id = "race-card", scope = "PERSONAL", projectId = null,
                front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                sourceProjectFileId = null, dueAt = 0L, createdAt = 1, updatedAt = 1,
                reminderAt = 500L, reminderEnabled = true,
            )
            database.studyReviewCardDao().insert(card)
            assertEquals(1, database.studyReviewCardDao().consumeReminder("race-card", 500L, 0L))
            assertEquals(0, database.studyReviewCardDao().consumeReminder("race-card", 500L, 0L))
            assertEquals(false, database.studyReviewCardDao().get("race-card")?.reminderEnabled)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `review clears posted notification even after reminder generation was consumed`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        try {
            val scheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += "consumed-card" }
            database.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "consumed-card", scope = "PERSONAL", projectId = null,
                    front = "front", back = "back", sourceKind = "USER_TEXT",
                    sourceConversationId = null, sourceMessageId = null,
                    sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null,
                    dueAt = 10L, createdAt = 1L, updatedAt = 1L,
                    reminderAt = 5L, reminderEnabled = false, reminderGeneration = 1L,
                ),
            )

            val result = QuickReviewCardRepository(database, scheduler)
                .review("consumed-card", ReviewCardRating.UNDERSTOOD, now = 100L)

            assertEquals(QuickReviewRatingResult.Updated, result)
            assertEquals(1, scheduler.cancelCalls)
            assertTrue(scheduler.visibleNotifications.isEmpty())
            assertEquals(1, database.studyReviewCardDao().get("consumed-card")?.reviewCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun `review reports durable success when notification cleanup fails`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        try {
            val scheduler = RecordingCardReminderScheduler(throwOnCancel = true)
                .also { it.visibleNotifications += "cleanup-failure-card" }
            database.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "cleanup-failure-card", scope = "PERSONAL", projectId = null,
                    front = "front", back = "back", sourceKind = "USER_TEXT",
                    sourceConversationId = null, sourceMessageId = null,
                    sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null,
                    dueAt = 10L, createdAt = 1L, updatedAt = 1L,
                    reminderAt = null, reminderEnabled = false,
                ),
            )

            val result = QuickReviewCardRepository(database, scheduler)
                .review("cleanup-failure-card", ReviewCardRating.HARD, now = 100L)

            assertEquals(QuickReviewRatingResult.UpdatedReminderCleanupFailed, result)
            assertEquals(1, database.studyReviewCardDao().get("cleanup-failure-card")?.reviewCount)
            assertTrue(scheduler.visibleNotifications.contains("cleanup-failure-card"))
            assertTrue(database.studyReviewCardDao().get("cleanup-failure-card")?.notificationCleanupPending == true)
            val recoveryScheduler = RecordingCardReminderScheduler().also {
                it.visibleNotifications += "cleanup-failure-card"
            }
            reconcilePendingReminderNotificationCleanup(database, RecordingTaskReminderScheduler(), recoveryScheduler)
            assertTrue(recoveryScheduler.visibleNotifications.isEmpty())
            assertEquals(false, database.studyReviewCardDao().get("cleanup-failure-card")?.notificationCleanupPending)
        } finally {
            database.close()
        }
    }

    @Test
    fun `delete removes consumed notification before deleting card`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        try {
            val scheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += "delete-card" }
            database.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "delete-card", scope = "PERSONAL", projectId = null,
                    front = "front", back = "back", sourceKind = "USER_TEXT",
                    sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null,
                    sourceProjectId = null, sourceProjectFileId = null, dueAt = 10L,
                    createdAt = 1L, updatedAt = 1L, reminderEnabled = false, reminderGeneration = 1L,
                ),
            )
            assertEquals(QuickReviewCardDeleteResult.DELETED, QuickReviewCardRepository(database, scheduler).delete("delete-card"))
            assertNull(database.studyReviewCardDao().get("delete-card"))
            assertTrue(scheduler.visibleNotifications.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `notification cleanup failure leaves a recoverable card deletion intent`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        try {
            val scheduler = RecordingCardReminderScheduler(throwOnCancel = true)
            database.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "delete-failure-card", scope = "PERSONAL", projectId = null,
                    front = "front", back = "back", sourceKind = "USER_TEXT",
                    sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null,
                    sourceProjectId = null, sourceProjectFileId = null, dueAt = 10L,
                    createdAt = 1L, updatedAt = 1L, reminderEnabled = false,
                ),
            )
            assertEquals(QuickReviewCardDeleteResult.CLEANUP_PENDING, QuickReviewCardRepository(database, scheduler).delete("delete-failure-card"))
            assertEquals(true, database.studyReviewCardDao().get("delete-failure-card")?.deletionPending)
            assertEquals(false, database.studyReviewCardDao().get("delete-failure-card")?.notificationCleanupPending)
            val recoveryScheduler = RecordingCardReminderScheduler().also {
                it.visibleNotifications += "delete-failure-card"
            }
            reconcilePendingReminderNotificationCleanup(database, RecordingTaskReminderScheduler(), recoveryScheduler)
            assertNull(database.studyReviewCardDao().get("delete-failure-card"))
            assertTrue(recoveryScheduler.visibleNotifications.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `card deletion intent survives Room delete failure and is recovered`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        try {
            database.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "delete-trigger-card", scope = "PERSONAL", projectId = null,
                    front = "front", back = "back", sourceKind = "USER_TEXT",
                    sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null,
                    sourceProjectId = null, sourceProjectFileId = null, dueAt = 10L,
                    createdAt = 1L, updatedAt = 1L, reminderAt = 500L, reminderEnabled = true,
                ),
            )
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_card_delete BEFORE DELETE ON study_review_cards BEGIN SELECT RAISE(ABORT, 'injected card delete failure'); END",
            )
            val scheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += "delete-trigger-card" }
            assertEquals(QuickReviewCardDeleteResult.CLEANUP_PENDING, QuickReviewCardRepository(database, scheduler).delete("delete-trigger-card"))
            val pending = database.studyReviewCardDao().get("delete-trigger-card")
            assertEquals(true, pending?.deletionPending)
            assertEquals(false, pending?.reminderEnabled)
            assertTrue(scheduler.visibleNotifications.isEmpty())

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_card_delete")
            reconcilePendingReminderNotificationCleanup(database, RecordingTaskReminderScheduler(), scheduler)
            assertNull(database.studyReviewCardDao().get("delete-trigger-card"))
            assertTrue(scheduler.visibleNotifications.isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `card deletion intent recovers after database close and reopen`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "card-delete-recovery-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var opened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            opened.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "reopen-delete-card", scope = "PERSONAL", projectId = null,
                    front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                    sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                    sourceProjectFileId = null, dueAt = 1, createdAt = 1, updatedAt = 1,
                    reminderAt = 600L, reminderEnabled = true,
                ),
            )
            opened.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_card_delete BEFORE DELETE ON study_review_cards BEGIN SELECT RAISE(ABORT, 'injected card delete failure'); END",
            )
            val initialScheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += "reopen-delete-card" }
            assertEquals(QuickReviewCardDeleteResult.CLEANUP_PENDING, QuickReviewCardRepository(opened, initialScheduler).delete("reopen-delete-card"))
            assertEquals(true, opened.studyReviewCardDao().get("reopen-delete-card")?.deletionPending)
            opened.close()

            opened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            opened.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_card_delete")
            val recoveryScheduler = RecordingCardReminderScheduler().also {
                it.visibleNotifications += "reopen-delete-card"
            }
            reconcilePendingReminderNotificationCleanup(opened, RecordingTaskReminderScheduler(), recoveryScheduler)
            assertNull(opened.studyReviewCardDao().get("reopen-delete-card"))
            assertTrue(recoveryScheduler.visibleNotifications.isEmpty())
            assertTrue(recoveryScheduler.cancelCalls > 0)
        } finally {
            opened.close()
            context.deleteDatabase(name)
        }
    }

    private class RecordingCardReminderScheduler(
        private val throwOnCancel: Boolean = false,
    ) : StudyReviewCardReminderScheduler {
        val visibleNotifications = mutableSetOf<String>()
        var cancelCalls = 0
        override fun scheduleCard(cardId: String, at: Long, generation: Long) = Unit
        override fun cancelCard(cardId: String) {
            cancelCalls++
            if (throwOnCancel) error("injected notification cancellation failure")
            visibleNotifications.remove(cardId)
        }
    }

    private class RecordingTaskReminderScheduler : ProjectTaskReminderScheduler {
        override fun schedule(taskId: Long, at: Long, generation: Long) = Unit
        override fun cancel(taskId: Long) = Unit
    }

    @Test
    fun sameTimeRescheduleRejectsAnOldCardReminderGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "study-cards-generation-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            database.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "generation-card", scope = "PERSONAL", projectId = null,
                    front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                    sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                    sourceProjectFileId = null, dueAt = 0L, createdAt = 1, updatedAt = 1,
                    reminderAt = 500L, reminderEnabled = true,
                ),
            )
            val dao = database.studyReviewCardDao()
            val oldGeneration = dao.get("generation-card")!!.reminderGeneration
            assertEquals(1, dao.updateReminder("generation-card", 500L, true))
            val newGeneration = dao.get("generation-card")!!.reminderGeneration

            assertTrue(newGeneration > oldGeneration)
            assertEquals(0, dao.consumeReminder("generation-card", 500L, oldGeneration))
            assertEquals(1, dao.consumeReminder("generation-card", 500L, newGeneration))
            assertEquals(false, dao.get("generation-card")?.reminderEnabled)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
