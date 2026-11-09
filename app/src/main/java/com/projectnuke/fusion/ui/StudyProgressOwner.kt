package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.tasks.AndroidProjectTaskReminderScheduler
import com.projectnuke.fusion.study.QuickReviewCardCaptureController
import com.projectnuke.fusion.study.QuickReviewCardRepository
import com.projectnuke.fusion.study.QuickReviewCardDeleteResult
import com.projectnuke.fusion.study.QuickReviewCardReminderResult
import com.projectnuke.fusion.study.QuickReviewRatingResult
import com.projectnuke.fusion.study.ReviewCardRating
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class StudyProgressOwner(context: Context, private val dao: ChatDao) {
    private val store = StudyProgressStore(File(context.applicationContext.filesDir, "study_progress"))
    private val reviewCards = QuickReviewCardRepository(
        AppDatabase.getInstance(context.applicationContext),
        AndroidProjectTaskReminderScheduler(context.applicationContext),
    )

    fun newReviewCardCaptureController(scope: kotlinx.coroutines.CoroutineScope) =
        QuickReviewCardCaptureController(reviewCards, scope)

    fun observeReviewCards(projectId: Long?) = reviewCards.observeQueue(projectId)

    suspend fun rateReviewCard(id: String, rating: ReviewCardRating): QuickReviewRatingResult = reviewCards.review(id, rating)
    suspend fun scheduleReviewCardReminder(id: String, at: Long, notificationsAllowed: Boolean): QuickReviewCardReminderResult =
        reviewCards.scheduleReminder(id, at, notificationsAllowed = notificationsAllowed)
    suspend fun disableReviewCardReminder(id: String, clearTime: Boolean = false): QuickReviewCardReminderResult =
        reviewCards.disableReminder(id, clearTime)
    suspend fun deleteReviewCard(id: String): QuickReviewCardDeleteResult = reviewCards.delete(id)
    suspend fun load(id: Long): StudyProgress = withContext(Dispatchers.IO) {
        check(id > 0 && dao.getConversationById(id) != null) { "대화를 찾을 수 없습니다." }
        store.load(id)
    }
    suspend fun update(id: Long, transform: (StudyProgress) -> StudyProgress): StudyProgress = withContext(Dispatchers.IO) {
        val lease = ConversationStructuralMutationRegistry.tryAcquire(id) ?: error("대화가 삭제 중입니다.")
        try {
            currentCoroutineContext().ensureActive()
            check(id > 0 && dao.getConversationById(id) != null) { "대화를 찾을 수 없습니다." }
            val value = store.update(id, transform)
            currentCoroutineContext().ensureActive()
            value
        } finally { lease.release() }
    }
    suspend fun submission(id: Long, token: String, text: String) = update(id) { StudyProgressPolicy.submission(it, token, text) }
}
