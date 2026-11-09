package com.projectnuke.fusion.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "study_review_cards",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["scope", "projectId", "dueAt"]),
        Index(value = ["reminderEnabled", "reminderAt"]),
    ],
)
data class StudyReviewCardEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val projectId: Long?,
    val front: String,
    val back: String,
    val sourceKind: String,
    val sourceConversationId: Long?,
    val sourceMessageId: Long?,
    val sourceSavedKnowledgeId: String?,
    val sourceProjectId: Long?,
    val sourceProjectFileId: Long?,
    val reviewCount: Int = 0,
    val lastRating: String? = null,
    val dueAt: Long,
    val lastReviewedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val reminderAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val reminderEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val reminderGeneration: Long = 0L,
    @ColumnInfo(defaultValue = "0") val notificationCleanupPending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val deletionPending: Boolean = false,
)

@Dao
interface StudyReviewCardDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(card: StudyReviewCardEntity)

    @Query("SELECT COUNT(*) FROM study_review_cards")
    suspend fun count(): Int

    @Transaction
    suspend fun insertBounded(card: StudyReviewCardEntity): ReviewCardInsertResult {
        if (card.projectId != null && isProjectDeleting(card.projectId)) return ReviewCardInsertResult.ProjectUnavailable
        if (count() >= MAX_STUDY_REVIEW_CARDS) return ReviewCardInsertResult.CapacityReached
        insert(card)
        return ReviewCardInsertResult.Inserted
    }

    @Query("SELECT EXISTS(SELECT 1 FROM projects WHERE id = :projectId AND deletionPending = 1)")
    suspend fun isProjectDeleting(projectId: Long): Boolean

    @Query("SELECT * FROM study_review_cards WHERE scope = 'PERSONAL' AND projectId IS NULL AND deletionPending = 0 ORDER BY dueAt ASC, createdAt ASC LIMIT :limit")
    fun observePersonal(limit: Int): Flow<List<StudyReviewCardEntity>>

    @Query("SELECT * FROM study_review_cards WHERE scope = 'PROJECT' AND projectId = :projectId AND deletionPending = 0 ORDER BY dueAt ASC, createdAt ASC LIMIT :limit")
    fun observeProject(projectId: Long, limit: Int): Flow<List<StudyReviewCardEntity>>

    @Query("SELECT * FROM study_review_cards WHERE scope = 'PROJECT' AND projectId = :projectId AND deletionPending = 0 ORDER BY dueAt ASC, createdAt ASC LIMIT :limit")
    suspend fun getProjectCardsBounded(projectId: Long, limit: Int): List<StudyReviewCardEntity>

    @Query("SELECT * FROM study_review_cards WHERE projectId = :projectId ORDER BY id ASC LIMIT :limit")
    suspend fun getProjectCardsForDeletion(projectId: Long, limit: Int): List<StudyReviewCardEntity>

    @Query("SELECT * FROM study_review_cards WHERE id = :id LIMIT 1")
    suspend fun get(id: String): StudyReviewCardEntity?

    @Query("UPDATE study_review_cards SET reviewCount = :reviewCount, lastRating = :rating, dueAt = :dueAt, lastReviewedAt = :now, updatedAt = :now, reminderAt = NULL, reminderEnabled = 0, reminderGeneration = reminderGeneration + 1, notificationCleanupPending = 1 WHERE id = :id AND dueAt <= :now AND deletionPending = 0 AND (projectId IS NULL OR projectId IN (SELECT id FROM projects WHERE deletionPending = 0))")
    suspend fun recordReview(id: String, reviewCount: Int, rating: String, dueAt: Long, now: Long): Int

    @Query("SELECT * FROM study_review_cards WHERE notificationCleanupPending = 1 AND deletionPending = 0 AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingNotificationCleanupAfter(afterId: String, limit: Int): List<StudyReviewCardEntity>

    @Query("SELECT * FROM study_review_cards WHERE deletionPending = 1 AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingDeletionAfter(afterId: String, limit: Int): List<StudyReviewCardEntity>

    @Query("UPDATE study_review_cards SET deletionPending = 1, reminderEnabled = 0, reminderAt = NULL, reminderGeneration = reminderGeneration + 1, notificationCleanupPending = 0 WHERE id = :id AND deletionPending = 0")
    suspend fun beginDeletion(id: String): Int

    @Query("DELETE FROM study_review_cards WHERE id = :id AND deletionPending = 1 AND reminderGeneration = :expectedGeneration")
    suspend fun finishDeletion(id: String, expectedGeneration: Long): Int

    @Query("UPDATE study_review_cards SET deletionPending = 1, reminderEnabled = 0, reminderAt = NULL, reminderGeneration = reminderGeneration + 1, notificationCleanupPending = 0 WHERE projectId = :projectId AND deletionPending = 0")
    suspend fun beginProjectChildrenDeletion(projectId: Long): Int

    @Query("UPDATE study_review_cards SET notificationCleanupPending = 0 WHERE id = :id AND reminderGeneration = :expectedGeneration AND notificationCleanupPending = 1 AND deletionPending = 0")
    suspend fun clearNotificationCleanupPending(id: String, expectedGeneration: Long): Int

    @Query("UPDATE study_review_cards SET notificationCleanupPending = 1 WHERE id = :id AND deletionPending = 0 AND (projectId IS NULL OR projectId IN (SELECT id FROM projects WHERE deletionPending = 0))")
    suspend fun markNotificationCleanupPending(id: String): Int

    @Query("DELETE FROM study_review_cards WHERE id = :id AND deletionPending = 1")
    suspend fun delete(id: String): Int

    @Query("SELECT study_review_cards.* FROM study_review_cards LEFT JOIN projects ON projects.id = study_review_cards.projectId WHERE study_review_cards.reminderEnabled = 1 AND study_review_cards.reminderAt IS NOT NULL AND study_review_cards.deletionPending = 0 AND (study_review_cards.projectId IS NULL OR projects.deletionPending = 0) ORDER BY study_review_cards.reminderAt ASC, study_review_cards.id ASC LIMIT :limit")
    suspend fun getEnabledReminders(limit: Int): List<StudyReviewCardEntity>

    @Query("SELECT study_review_cards.* FROM study_review_cards LEFT JOIN projects ON projects.id = study_review_cards.projectId WHERE study_review_cards.reminderEnabled = 1 AND study_review_cards.reminderAt IS NOT NULL AND study_review_cards.reminderAt <= :now AND study_review_cards.deletionPending = 0 AND (study_review_cards.projectId IS NULL OR projects.deletionPending = 0) ORDER BY study_review_cards.reminderAt ASC, study_review_cards.id ASC LIMIT :limit")
    suspend fun getOverdueReminders(now: Long, limit: Int): List<StudyReviewCardEntity>

    @Query("SELECT * FROM study_review_cards WHERE projectId = :projectId AND reminderEnabled = 1 AND reminderAt IS NOT NULL AND deletionPending = 0 ORDER BY reminderAt ASC, id ASC LIMIT :limit")
    suspend fun getEnabledRemindersForProject(projectId: Long, limit: Int): List<StudyReviewCardEntity>

    @Query("SELECT COUNT(*) FROM study_review_cards LEFT JOIN projects ON projects.id = study_review_cards.projectId WHERE study_review_cards.reminderEnabled = 1 AND study_review_cards.deletionPending = 0 AND (study_review_cards.projectId IS NULL OR projects.deletionPending = 0)")
    suspend fun countEnabledReminders(): Int

    @Query("UPDATE study_review_cards SET reminderAt = :at, reminderEnabled = :enabled, reminderGeneration = reminderGeneration + 1, notificationCleanupPending = 1 WHERE id = :id AND deletionPending = 0 AND (projectId IS NULL OR projectId IN (SELECT id FROM projects WHERE deletionPending = 0))")
    suspend fun updateReminder(id: String, at: Long?, enabled: Boolean): Int

    @Transaction
    suspend fun updateReminderBounded(id: String, at: Long): ReviewCardReminderUpdateResult {
        val card = get(id) ?: return ReviewCardReminderUpdateResult.UnknownCard
        if (!card.reminderEnabled && countEnabledReminders() >= MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS) {
            return ReviewCardReminderUpdateResult.CapacityReached
        }
        return if (updateReminder(id, at, true) == 1) ReviewCardReminderUpdateResult.Updated
        else ReviewCardReminderUpdateResult.UnknownCard
    }

    @Query("UPDATE study_review_cards SET reminderAt = NULL, reminderEnabled = 0 WHERE id = :id AND reminderAt = :expectedAt AND reminderGeneration = :expectedGeneration AND reminderEnabled = 1 AND deletionPending = 0 AND (projectId IS NULL OR projectId IN (SELECT id FROM projects WHERE deletionPending = 0))")
    suspend fun consumeReminder(id: String, expectedAt: Long, expectedGeneration: Long): Int
}

sealed interface ReviewCardInsertResult {
    data object Inserted : ReviewCardInsertResult
    data object CapacityReached : ReviewCardInsertResult
    data object ProjectUnavailable : ReviewCardInsertResult
}

sealed interface ReviewCardReminderUpdateResult {
    data object Updated : ReviewCardReminderUpdateResult
    data object UnknownCard : ReviewCardReminderUpdateResult
    data object CapacityReached : ReviewCardReminderUpdateResult
}

const val MAX_STUDY_REVIEW_CARDS = 512
const val MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS = 128
