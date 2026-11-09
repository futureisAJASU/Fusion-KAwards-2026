package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Real Room v3-to-v4 migration test using MigrationTestHelper.
 * Runs on API 32+ emulator/device to verify the benchmark_results table migration.
 *
 * Verifies the full lifecycle:
 * 1. Create v3 database and insert legacy rows
 * 2. Run Migration3To4 explicitly
 * 3. Validate v4 schema
 * 4. Open migrated database through production AppDatabase
 * 5. Read legacy rows through BenchmarkDao
 * 6. Verify data preservation, defaults, and MTP backfill
 * 7. Insert a new v4 row through the DAO
 * 8. Close and reopen the database
 * 9. Verify the new row through the DAO
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    var helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation,
        AppDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun `migrate v14 to v15 creates bounded inbox store`() {
        val db = helper.createDatabase("fusion-v14-v15", 14)
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v14-v15", 15, true, AppDatabase.Migration14To15)
        migrated.execSQL("INSERT INTO inbox_items (id, captureType, title, content, originalUri, managedFileId, createdAt, updatedAt, processingState) VALUES ('inbox-1', 'NOTE', 'note', 'saved locally', NULL, NULL, 1, 1, 'READY')")
        migrated.query("SELECT captureType, title, content, processingState FROM inbox_items WHERE id = 'inbox-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("NOTE", rows.getString(0))
            assertEquals("note", rows.getString(1))
            assertEquals("saved locally", rows.getString(2))
            assertEquals("READY", rows.getString(3))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v15 to v16 creates durable external handoff selection receipt`() {
        val db = helper.createDatabase("fusion-v15-v16", 15)
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v15-v16", 16, true, AppDatabase.Migration15To16)
        migrated.execSQL("INSERT INTO external_handoff_receipts (eventId, destination, targetProjectId, selectedAt, settledAt, outcome) VALUES ('handoff-1', 'INBOX', NULL, 1, NULL, 'SELECTED')")
        migrated.query("SELECT destination, targetProjectId, outcome FROM external_handoff_receipts WHERE eventId = 'handoff-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("INBOX", rows.getString(0))
            assertTrue(rows.isNull(1))
            assertEquals("SELECTED", rows.getString(2))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v30 to v31 preserves existing clip sources and leaves effective sources unknown`() {
        val databaseName = "fusion-v30-v31-webclip-${System.nanoTime()}"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = helper.createDatabase(databaseName, 30)
        legacy.execSQL(
            "INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt, sourceUrl, sourcePageTitle, retrievedAt) " +
                "VALUES ('saved-old', 'PERSONAL', NULL, 'Legacy clip', 'Legacy saved text', 'WEB_CLIP', NULL, NULL, 10, 11, 'https://entered.example/old', 'Legacy title', 9)",
        )
        legacy.execSQL(
            "INSERT INTO inbox_items (id, captureType, title, content, originalUri, managedFileId, managedMimeType, sourceHandoffEventId, createdAt, updatedAt, processingState) " +
                "VALUES ('inbox-old', 'WEB_CLIP', 'Legacy inbox clip', 'Legacy inbox text', 'https://entered.example/old-inbox', NULL, NULL, NULL, 12, 13, 'READY')",
        )
        legacy.close()

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            31,
            true,
            *appDatabaseMigrationsForTest(),
        )
        migrated.query("SELECT sourceUrl, effectiveSourceUrl, savedText FROM saved_knowledge WHERE id = 'saved-old'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("https://entered.example/old", rows.getString(0))
            assertTrue(rows.isNull(1))
            assertEquals("Legacy saved text", rows.getString(2))
        }
        migrated.query("SELECT originalUri, effectiveSourceUri, content FROM inbox_items WHERE id = 'inbox-old'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("https://entered.example/old-inbox", rows.getString(0))
            assertTrue(rows.isNull(1))
            assertEquals("Legacy inbox text", rows.getString(2))
        }
        migrated.close()

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*appDatabaseMigrationsForTest())
            .build()
        try {
            val saved = runBlocking { reopened.savedKnowledgeDao().getById("saved-old") }
            val inbox = runBlocking { reopened.inboxItemDao().get("inbox-old") }
            assertNotNull(saved)
            assertEquals("https://entered.example/old", saved?.sourceUrl)
            assertNull(saved?.effectiveSourceUrl)
            assertEquals("Legacy saved text", saved?.savedText)
            assertNotNull(inbox)
            assertEquals("https://entered.example/old-inbox", inbox?.originalUri)
            assertNull(inbox?.effectiveSourceUri)
            assertEquals("Legacy inbox text", inbox?.content)
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v16 to v17 adds web clip provenance to saved knowledge`() {
        val db = helper.createDatabase("fusion-v16-v17", 16)
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v16-v17", 17, true, AppDatabase.Migration16To17)
        migrated.execSQL("INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt, sourceUrl, sourcePageTitle, retrievedAt) VALUES ('clip-1', 'PERSONAL', NULL, 'page', 'snapshot', 'WEB_CLIP', NULL, NULL, 1, 2, 'https://example.com', 'Example page', 1)")
        migrated.query("SELECT sourceUrl, sourcePageTitle, retrievedAt FROM saved_knowledge WHERE id = 'clip-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("https://example.com", rows.getString(0))
            assertEquals("Example page", rows.getString(1))
            assertEquals(1L, rows.getLong(2))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v17 to v18 creates normalized tag and collection link tables`() {
        val db = helper.createDatabase("fusion-v17-v18", 17)
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v17-v18", 18, true, AppDatabase.Migration17To18)
        migrated.execSQL("INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES ('saved-1', 'PERSONAL', NULL, 'item', 'content', 'WEB_CLIP', NULL, NULL, 1, 1)")
        migrated.execSQL("INSERT INTO inbox_items (id, captureType, title, content, originalUri, managedFileId, managedMimeType, sourceHandoffEventId, createdAt, updatedAt, processingState) VALUES ('inbox-1', 'NOTE', 'item', 'content', NULL, NULL, NULL, NULL, 1, 1, 'READY')")
        migrated.execSQL("INSERT INTO knowledge_tags (tagKey, displayName, createdAt, updatedAt) VALUES ('hbm', 'HBM', 1, 1)")
        migrated.execSQL("INSERT INTO saved_knowledge_tags (itemId, tagKey) VALUES ('saved-1', 'hbm')")
        migrated.execSQL("INSERT INTO inbox_item_tags (itemId, tagKey) VALUES ('inbox-1', 'hbm')")
        migrated.execSQL("INSERT INTO knowledge_collections (collectionId, collectionKey, displayName, createdAt, updatedAt) VALUES ('c-1', 'papers', 'Papers', 1, 1)")
        migrated.execSQL("INSERT INTO collection_saved_knowledge (collectionId, itemId) VALUES ('c-1', 'saved-1')")
        migrated.execSQL("INSERT INTO collection_inbox_items (collectionId, itemId) VALUES ('c-1', 'inbox-1')")
        migrated.query("SELECT COUNT(*) FROM collection_saved_knowledge").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(1, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v18 to v19 adds highlight source anchors and cascades source deletion`() {
        val db = helper.createDatabase("fusion-v18-v19", 18)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
        db.execSQL("INSERT INTO project_files (id, projectId, displayName, mimeType, localPath, addedAt, sourceHandoffEventId) VALUES (20, 10, 'paper.pdf', 'application/pdf', 'managed-file', 1, NULL)")
        db.execSQL("INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES ('highlight-1', 'PROJECT', 10, 'paper.pdf · p.2', 'quoted text', 'DOCUMENT_HIGHLIGHT', NULL, NULL, 1, 1)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v18-v19", 19, true, AppDatabase.Migration18To19)
        migrated.execSQL("INSERT INTO document_highlight_sources (savedKnowledgeId, projectId, projectFileId, pageNumber, sourceChunkId, sourceContentHashHex, userNote, createdAt) VALUES ('highlight-1', 10, 20, 2, 'page:2', '${"a".repeat(64)}', 'note', 2)")
        migrated.execSQL("DELETE FROM project_files WHERE id = 20")
        migrated.query("SELECT COUNT(*) FROM document_highlight_sources WHERE savedKnowledgeId = 'highlight-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM saved_knowledge WHERE id = 'highlight-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v19 to v20 creates durable project decision history`() {
        val db = helper.createDatabase("fusion-v19-v20", 19)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v19-v20", 20, true, AppDatabase.Migration19To20)
        migrated.execSQL("INSERT INTO project_decisions (id, projectId, title, rationale, status, supersedesDecisionId, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES ('decision-1', 10, 'Use INT8', 'Smaller artifact', 'ACTIVE', NULL, NULL, NULL, 2, 2)")
        migrated.execSQL("UPDATE project_decisions SET status = 'SUPERSEDED', updatedAt = 3 WHERE id = 'decision-1'")
        migrated.execSQL("INSERT INTO project_decisions (id, projectId, title, rationale, status, supersedesDecisionId, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES ('decision-2', 10, 'Use INT4', '', 'ACTIVE', 'decision-1', NULL, NULL, 3, 3)")
        migrated.query("SELECT status FROM project_decisions WHERE id = 'decision-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("SUPERSEDED", rows.getString(0))
        }
        migrated.query("SELECT supersedesDecisionId, title FROM project_decisions WHERE id = 'decision-2'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("decision-1", rows.getString(0))
            assertEquals("Use INT4", rows.getString(1))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v20 to v21 adds explicit research refresh identity`() {
        val db = helper.createDatabase("fusion-v20-v21", 20)
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived, branchedFromConversationId, branchedFromMessageId) VALUES (30, 'Old research', 1, 1, 0, 0, NULL, NULL)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v20-v21", 21, true, AppDatabase.Migration20To21)
        migrated.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived, branchedFromConversationId, branchedFromMessageId, refreshedFromConversationId, refreshedFromMessageId) VALUES (31, 'Refreshed research', 2, 2, 0, 0, 30, 4, 30, 4)")
        migrated.query("SELECT refreshedFromConversationId, refreshedFromMessageId FROM conversations WHERE id = 31").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(30L, rows.getLong(0))
            assertEquals(4L, rows.getLong(1))
        }
        migrated.query("SELECT refreshedFromConversationId FROM conversations WHERE id = 30").use { rows ->
            assertTrue(rows.moveToFirst())
            assertTrue(rows.isNull(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v21 to v22 adds note source identity table`() {
        val db = helper.createDatabase("fusion-v21-v22", 21)
        db.execSQL("INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt, sourceUrl, sourcePageTitle, retrievedAt) VALUES ('note-1', 'PERSONAL', NULL, 'Research note', 'draft', 'USER_NOTE', NULL, NULL, 1, 1, NULL, NULL, NULL)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v21-v22", 22, true, AppDatabase.Migration21To22)
        migrated.execSQL("INSERT INTO saved_knowledge_note_sources (noteId, orderIndex, sourceKey, sourceKind, conversationId, projectId, projectFileId, savedKnowledgeId, sourceTitle, createdAt) VALUES ('note-1', 0, 'p:9', 'PROJECT', NULL, 9, NULL, NULL, 'Project 9', 2)")
        migrated.query("SELECT sourceKind, projectId, sourceTitle FROM saved_knowledge_note_sources WHERE noteId = 'note-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("PROJECT", rows.getString(0))
            assertEquals(9L, rows.getLong(1))
            assertEquals("Project 9", rows.getString(2))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v22 to v23 creates message anchored checkpoints`() {
        val db = helper.createDatabase("fusion-v22-v23", 22)
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived, branchedFromConversationId, branchedFromMessageId, refreshedFromConversationId, refreshedFromMessageId) VALUES (1, 'checkpoint', 1, 1, 0, 0, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (1, 1, 'assistant', 'anchor', 2)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v22-v23", 23, true, AppDatabase.Migration22To23)
        migrated.execSQL("INSERT INTO conversation_checkpoints (conversationId, messageId, title, createdAt, note) VALUES (1, 1, 'Useful point', 3, '')")
        migrated.execSQL("DELETE FROM messages WHERE id = 1")
        migrated.query("SELECT COUNT(*) FROM conversation_checkpoints").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v23 to v24 creates Project work plans and preserves missing task references`() {
        val db = helper.createDatabase("fusion-v23-v24-work-plans", 23)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
        db.execSQL("INSERT INTO project_tasks (id, projectId, title, note, state, sourceConversationId, sourceMessageId, createdAt, updatedAt, reminderAt, reminderEnabled) VALUES (7, 10, 'Task', '', 'OPEN', NULL, NULL, 2, 2, NULL, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v23-v24-work-plans", 24, true, AppDatabase.Migration23To24)
        migrated.execSQL("INSERT INTO project_work_plans (id, projectId, title, goal, desiredOutput, constraintText, status, currentStepPosition, archivedAt, createdAt, updatedAt) VALUES ('plan-1', 10, 'Submit', 'Prepare submission', '', '', 'ACTIVE', 0, NULL, 3, 3)")
        migrated.execSQL("INSERT INTO project_work_plan_steps (id, planId, position, title, instruction, type, state, attemptCount, activeAttemptId, resultKind, resultId, previousResultRefs, linkedTaskId, interrupted, failureKind, createdAt, updatedAt) VALUES ('step-1', 'plan-1', 0, 'Task', 'Review', 'CREATE_TASK', 'REVIEW', 1, NULL, 'TASK', '7', '', 7, 0, NULL, 3, 3)")
        migrated.execSQL("DELETE FROM project_tasks WHERE id = 7")
        migrated.query("SELECT linkedTaskId, resultKind, resultId FROM project_work_plan_steps WHERE id = 'step-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertTrue(rows.isNull(0))
            assertEquals("TASK", rows.getString(1))
            assertEquals("7", rows.getString(2))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v24 to v25 creates durable specialized result provenance`() {
        val db = helper.createDatabase("fusion-v24-v25-result-sources", 24)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
        db.execSQL("INSERT INTO project_work_plans (id, projectId, title, goal, desiredOutput, constraintText, status, currentStepPosition, archivedAt, createdAt, updatedAt) VALUES ('plan-1', 10, 'Submit', 'Prepare submission', '', '', 'ACTIVE', 0, NULL, 3, 3)")
        db.execSQL("INSERT INTO project_work_plan_steps (id, planId, position, title, instruction, type, state, attemptCount, activeAttemptId, resultKind, resultId, previousResultRefs, linkedTaskId, interrupted, failureKind, createdAt, updatedAt) VALUES ('step-1', 'plan-1', 0, 'Review', 'Confirm', 'REVIEW_KNOWLEDGE', 'RUNNING', 1, 'attempt-1', NULL, NULL, '', NULL, 0, NULL, 3, 3)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v24-v25-result-sources", 25, true, AppDatabase.Migration24To25)
        migrated.execSQL("INSERT INTO project_work_plan_result_sources (resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) VALUES ('s:abc', 'plan-1', 'step-1', 'attempt-1', 10, 'REVIEW_KNOWLEDGE', 'SELECTION', NULL, '[{\"kind\":\"PROJECT\",\"conversationId\":null,\"projectId\":10,\"fileId\":null,\"managedPath\":null,\"memoryId\":null,\"savedKnowledgeId\":null,\"title\":\"Project\"}]', 4)")
        migrated.query("SELECT stepType, resultKind, sourcesJson FROM project_work_plan_result_sources WHERE resultId = 's:abc'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("REVIEW_KNOWLEDGE", rows.getString(0))
            assertEquals("SELECTION", rows.getString(1))
            assertTrue(rows.getString(2).contains("\"projectId\":10"))
        }
        // Existing plans and steps survive the migration untouched.
        migrated.query("SELECT COUNT(*) FROM project_work_plan_steps WHERE id = 'step-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(1, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `v25 to v26 rebuilds attempt-scoped results and creates witness table`() {
        val db = helper.createDatabase("fusion-v25-v26", 25)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
        db.execSQL("INSERT INTO project_work_plans (id, projectId, title, goal, desiredOutput, constraintText, status, currentStepPosition, archivedAt, createdAt, updatedAt) VALUES ('plan-1', 10, 'Submit', '', '', 'ACTIVE', 0, NULL, 3, 3)")
        db.execSQL("INSERT INTO project_work_plan_steps (id, planId, position, title, instruction, type, state, attemptCount, activeAttemptId, resultKind, resultId, previousResultRefs, linkedTaskId, interrupted, failureKind, createdAt, updatedAt) VALUES ('step-1', 'plan-1', 0, 'Review', 'Confirm', 'REVIEW_KNOWLEDGE', 'RUNNING', 2, 'attempt-2', NULL, NULL, '', NULL, 0, NULL, 3, 3)")
        // Insert a v25-style result row (old schema used a different primary key shape); the migration preserves it.
        db.execSQL("INSERT INTO project_work_plan_result_sources (resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) VALUES ('s:old', 'plan-1', 'step-1', 'attempt-old', 10, 'REVIEW_KNOWLEDGE', 'SELECTION', NULL, '[]', 4)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v25-v26", 26, true, AppDatabase.Migration25To26)
        // The rebuilt table uses attemptId as the durable key; the preserved row survives.
        migrated.query("SELECT attemptId, resultId FROM project_work_plan_result_sources WHERE attemptId = 'attempt-old'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("attempt-old", rows.getString(0))
            assertEquals("s:old", rows.getString(1))
        }
        // Same content fingerprint (SELECTION key) can appear for a different attempt.
        migrated.execSQL(
            "INSERT INTO project_work_plan_result_sources (attemptId, resultId, planId, stepId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) VALUES ('attempt-2', 's:old', 'plan-1', 'step-1', 10, 'REVIEW_KNOWLEDGE', 'SELECTION', NULL, '[]', 5)",
        )
        migrated.query("SELECT COUNT(*) FROM project_work_plan_result_sources WHERE resultId = 's:old'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(2, rows.getInt(0))
        }
        // Deleting the owning Project cascades both the plan/step and their result provenance.
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("DELETE FROM projects WHERE id = 10")
        migrated.query("SELECT COUNT(*) FROM project_work_plan_result_sources").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        // The generation witness table exists and supports insertion.
        migrated.execSQL(
            "INSERT INTO project_work_plan_generation_witnesses (attemptId, requestId, planId, stepId, projectId, conversationId, userMessageId, assistantMessageId, createdAt) VALUES ('witness-1', 'request-1', 'plan-1', 'step-1', 10, 1, 2, NULL, 6)",
        )
        migrated.query("SELECT attemptId, requestId FROM project_work_plan_generation_witnesses WHERE attemptId = 'witness-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("witness-1", rows.getString(0))
            assertEquals("request-1", rows.getString(1))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `v25 to v26 drops orphan v25 result rows and creates witness table`() {
        val db = helper.createDatabase("fusion-v25-v26-orphan", 25)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
        db.execSQL("INSERT INTO project_work_plans (id, projectId, title, goal, desiredOutput, constraintText, status, currentStepPosition, archivedAt, createdAt, updatedAt) VALUES ('plan-1', 10, 'Submit', '', '', 'ACTIVE', 0, NULL, 3, 3)")
        db.execSQL("INSERT INTO project_work_plan_steps (id, planId, position, title, instruction, type, state, attemptCount, activeAttemptId, resultKind, resultId, previousResultRefs, linkedTaskId, interrupted, failureKind, createdAt, updatedAt) VALUES ('step-1', 'plan-1', 0, 'Review', 'Confirm', 'REVIEW_KNOWLEDGE', 'RUNNING', 1, 'attempt-1', NULL, NULL, '', NULL, 0, NULL, 3, 3)")
        db.execSQL("INSERT INTO project_work_plan_result_sources (resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) VALUES ('s:valid', 'plan-1', 'step-1', 'attempt-valid', 10, 'REVIEW_KNOWLEDGE', 'SELECTION', NULL, '[]', 4)")
        // Orphan: step no longer exists in v26 rebuilt table (historical sequence: step deleted before upgrade; old v25 table kept it due to no FKs).
        db.execSQL("INSERT INTO project_work_plan_result_sources (resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) VALUES ('s:orphan', 'plan-1', 'step-ghost', 'attempt-ghost', 10, 'REVIEW_KNOWLEDGE', 'SELECTION', NULL, '[]', 5)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v25-v26-orphan", 26, true, AppDatabase.Migration25To26)
        migrated.query("SELECT attemptId FROM project_work_plan_result_sources WHERE attemptId = 'attempt-valid'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("attempt-valid", rows.getString(0))
        }
        // Orphan (step-ghost not in rebuilt table) must be absent — the migration filters by existing step ownership.
        migrated.query("SELECT COUNT(*) FROM project_work_plan_result_sources WHERE resultId = 's:orphan'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.execSQL(
            "INSERT INTO project_work_plan_generation_witnesses (attemptId, requestId, planId, stepId, projectId, conversationId, userMessageId, assistantMessageId, createdAt) VALUES ('witness-1', 'request-1', 'plan-1', 'step-1', 10, 1, 2, NULL, 6)",
        )
        migrated.query("SELECT requestId FROM project_work_plan_generation_witnesses WHERE attemptId = 'witness-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("request-1", rows.getString(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `v26 to v27 preserves reminders and initializes generation`() {
        val db = helper.createDatabase("fusion-v26-v27-reminders", 26)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
        db.execSQL("INSERT INTO project_tasks (id, projectId, title, note, state, sourceConversationId, sourceMessageId, createdAt, updatedAt, reminderAt, reminderEnabled) VALUES (20, 10, 'Task', '', 'OPEN', NULL, NULL, 1, 2, 3000, 1)")
        db.execSQL("INSERT INTO study_review_cards (id, scope, projectId, front, back, sourceKind, sourceConversationId, sourceMessageId, sourceSavedKnowledgeId, sourceProjectId, sourceProjectFileId, reviewCount, lastRating, dueAt, lastReviewedAt, createdAt, updatedAt, reminderAt, reminderEnabled) VALUES ('card-20', 'PERSONAL', NULL, 'front', 'back', 'USER_TEXT', NULL, NULL, NULL, NULL, NULL, 0, NULL, 1, NULL, 1, 2, 4000, 1)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "fusion-v26-v27-reminders",
            27,
            true,
            AppDatabase.Migration26To27,
        )
        migrated.query("SELECT reminderAt, reminderEnabled, reminderGeneration FROM project_tasks WHERE id = 20").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(3000L, rows.getLong(0))
            assertEquals(1, rows.getInt(1))
            assertEquals(0L, rows.getLong(2))
        }
        migrated.query("SELECT reminderAt, reminderEnabled, reminderGeneration FROM study_review_cards WHERE id = 'card-20'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(4000L, rows.getLong(0))
            assertEquals(1, rows.getInt(1))
            assertEquals(0L, rows.getLong(2))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v1 to v9 from reconstructed archive`() {
        val db = helper.createDatabase("fusion-v1-v9", 1)
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt) VALUES (1, 'legacy-v1', 1, 2)")
        db.execSQL("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (1, 1, 'user', 'keep', 3)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v1-v9", 9, true, *appDatabaseMigrationsForTest())
        migrated.query("SELECT isPinned, isArchived FROM conversations WHERE id = 1").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
            assertEquals(0, rows.getInt(1))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v2 to v9 from reconstructed archive`() {
        val db = helper.createDatabase("fusion-v2-v9", 2)
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (1, 'legacy-v2', 1, 2, 1, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v2-v9", 9, true, *appDatabaseMigrationsForTest())
        migrated.query("SELECT isPinned, isArchived FROM conversations WHERE id = 1").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(1, rows.getInt(0))
            assertEquals(0, rows.getInt(1))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v8 to v9 preserves conversation data and adds null provenance`() {
        val db = helper.createDatabase("fusion-v8-v9", 8)
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (8, 'legacy-v8', 80, 90, 1, 1)")
        db.execSQL("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (8, 8, 'assistant', 'keep-v8', 91)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v8-v9", 9, true, AppDatabase.Migration8To9)
        migrated.query("SELECT title, isPinned, isArchived, branchedFromConversationId, branchedFromMessageId FROM conversations WHERE id = 8").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("legacy-v8", rows.getString(0))
            assertEquals(1, rows.getInt(1))
            assertEquals(1, rows.getInt(2))
            assertTrue(rows.isNull(3))
            assertTrue(rows.isNull(4))
        }
        migrated.query("SELECT content FROM messages WHERE id = 8 AND conversationId = 8").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("keep-v8", rows.getString(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v9 to v10 creates saved knowledge with project cascade`() {
        val db = helper.createDatabase("fusion-v9-v10", 9)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (7, 'project', '', 1, 2)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v9-v10", 10, true, AppDatabase.Migration9To10)
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES ('saved-1', 'PROJECT', 7, 'title', 'copy', 'ASSISTANT_ANSWER', 8, 9, 10, 10)")
        migrated.query("SELECT title, savedText, provenanceType FROM saved_knowledge WHERE id = 'saved-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("title", rows.getString(0))
            assertEquals("copy", rows.getString(1))
            assertEquals("ASSISTANT_ANSWER", rows.getString(2))
        }
        migrated.execSQL("DELETE FROM projects WHERE id = 7")
        migrated.query("SELECT COUNT(*) FROM saved_knowledge WHERE id = 'saved-1'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v10 to v11 creates project tasks and project cascade`() {
        val db = helper.createDatabase("fusion-v10-v11", 10)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (9, 'project', '', 1, 2)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v10-v11", 11, true, AppDatabase.Migration10To11)
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("INSERT INTO project_tasks (projectId, title, note, state, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES (9, 'task', 'note', 'OPEN', NULL, NULL, 3, 3)")
        migrated.query("SELECT title, note, state FROM project_tasks WHERE projectId = 9").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("task", rows.getString(0))
            assertEquals("note", rows.getString(1))
            assertEquals("OPEN", rows.getString(2))
        }
        migrated.execSQL("DELETE FROM projects WHERE id = 9")
        migrated.query("SELECT COUNT(*) FROM project_tasks").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v11 to v12 adds disabled reminder metadata`() {
        val db = helper.createDatabase("fusion-v11-v12", 11)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (12, 'project', '', 1, 2)")
        db.execSQL("INSERT INTO project_tasks (projectId, title, note, state, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES (12, 'task', '', 'OPEN', NULL, NULL, 3, 3)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v11-v12", 12, true, AppDatabase.Migration11To12)
        migrated.query("SELECT reminderAt, reminderEnabled FROM project_tasks WHERE projectId = 12").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(null, rows.getString(0))
            assertEquals(0, rows.getInt(1))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v12 to v13 creates empty review card store with project cascade`() {
        val db = helper.createDatabase("fusion-v12-v13", 12)
        db.execSQL("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (13, 'project', '', 1, 2)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v12-v13", 13, true, AppDatabase.Migration12To13)
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("INSERT INTO study_review_cards (id, scope, projectId, front, back, sourceKind, sourceConversationId, sourceMessageId, sourceSavedKnowledgeId, sourceProjectId, sourceProjectFileId, reviewCount, lastRating, dueAt, lastReviewedAt, createdAt, updatedAt) VALUES ('card-13', 'PROJECT', 13, 'front', 'back', 'USER_TEXT', NULL, NULL, NULL, NULL, NULL, 0, NULL, 3, NULL, 3, 3)")
        migrated.query("SELECT reminderEnabled FROM study_review_cards WHERE id = 'card-13'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.execSQL("DELETE FROM projects WHERE id = 13")
        migrated.query("SELECT COUNT(*) FROM study_review_cards WHERE id = 'card-13'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v13 to v14 preserves multi branch provenance after source deletion`() {
        val db = helper.createDatabase("fusion-v13-v14", 13)
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (14, 'synthesis', 1, 2, 0, 0)")
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (15, 'source', 1, 2, 0, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate("fusion-v13-v14", 14, true, AppDatabase.Migration13To14)
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("INSERT INTO branch_synthesis_sources (synthesisConversationId, sourceConversationId, sourceTitle, sourceOrdinal, intent, createdAt) VALUES (14, 15, 'source branch', 0, 'OVERVIEW', 3)")
        migrated.query("SELECT sourceConversationId, sourceTitle, sourceOrdinal, intent FROM branch_synthesis_sources WHERE synthesisConversationId = 14").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(15L, rows.getLong(0))
            assertEquals("source branch", rows.getString(1))
            assertEquals(0, rows.getInt(2))
            assertEquals("OVERVIEW", rows.getString(3))
        }
        migrated.execSQL("DELETE FROM conversations WHERE id = 15")
        migrated.query("SELECT COUNT(*) FROM branch_synthesis_sources WHERE synthesisConversationId = 14").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(1, rows.getInt(0))
        }
        migrated.execSQL("DELETE FROM conversations WHERE id = 14")
        migrated.query("SELECT COUNT(*) FROM branch_synthesis_sources").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v3 to v4 renames columns and adds runtime snapshot columns`() {
        // Phase 1: Create database at version 3
        val db = helper.createDatabase("fusion-test-db", 3)

        // Verify version 3 schema
        val cursorV3 = db.query("PRAGMA table_info(benchmark_results)")
        val v3Columns = mutableSetOf<String>()
        while (cursorV3.moveToNext()) {
            v3Columns.add(cursorV3.getString(1))
        }
        cursorV3.close()

        assertTrue("v3 should have actualBackend", v3Columns.contains("actualBackend"))
        assertTrue("v3 should have mtpEnabled", v3Columns.contains("mtpEnabled"))
        assertTrue("v3 should NOT have selectedTextBackend", !v3Columns.contains("selectedTextBackend"))
        assertTrue("v3 should NOT have selectedVisionBackend", !v3Columns.contains("selectedVisionBackend"))
        assertTrue("v3 should NOT have samplerBackend", !v3Columns.contains("samplerBackend"))
        assertTrue("v3 should NOT have fallbackEventCodes", !v3Columns.contains("fallbackEventCodes"))
        assertTrue("v3 should NOT have initializedWithMtp", !v3Columns.contains("initializedWithMtp"))

        // Phase 2: Insert representative legacy rows at v3
        // Row 1: MTP requested and succeeded → initializedWithMtp should be 1
        db.execSQL(
            "INSERT INTO benchmark_results (createdAt, modelName, modelPath, accelerator, actualBackend, mtpEnabled, mtpStatus, maxTokens, temperature, topK, topP, reasoningEnabled, webSearchEnabled, promptLabel, promptText, modelLoadingMs, firstTokenLatencyMs, totalGenerationMs, estimatedOutputTokens, totalTokensPerSecond, decodeTokensPerSecond, success, errorMessage, appVersion, deviceModel, androidVersion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                1000L, "test-model", "/path/model", "AUTO", "GPU", 1, "INITIALIZED_WITH_MTP_REQUEST",
                4096, 1.0f, 64, 0.95f, 0, 0, "test", "prompt", 100L, 50L, 2000L, 100, 10.0, 20.0, 1, null, "1.0.0", "test-device", "32"
            )
        )

        // Row 2: MTP requested but fallback → initializedWithMtp should be 0
        db.execSQL(
            "INSERT INTO benchmark_results (createdAt, modelName, modelPath, accelerator, actualBackend, mtpEnabled, mtpStatus, maxTokens, temperature, topK, topP, reasoningEnabled, webSearchEnabled, promptLabel, promptText, modelLoadingMs, firstTokenLatencyMs, totalGenerationMs, estimatedOutputTokens, totalTokensPerSecond, decodeTokensPerSecond, success, errorMessage, appVersion, deviceModel, androidVersion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                2000L, "test-model-2", "/path/model2", "AUTO", "GPU", 1, "OFF",
                4096, 1.0f, 64, 0.95f, 0, 0, "test", "prompt2", 100L, 50L, 2000L, 100, 10.0, 20.0, 1, null, "1.0.0", "test-device", "32"
            )
        )

        // Phase 3: Run Migration3To4
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        db.close()
        val migratedDb = helper.runMigrationsAndValidate("fusion-test-db", 4, true, AppDatabase.Migration3To4)

        // Phase 4: Verify v4 schema
        val cursorV4 = migratedDb.query("PRAGMA table_info(benchmark_results)")
        val v4Columns = mutableSetOf<String>()
        while (cursorV4.moveToNext()) {
            v4Columns.add(cursorV4.getString(1))
        }
        cursorV4.close()

        assertTrue("v4 should have selectedTextBackend", v4Columns.contains("selectedTextBackend"))
        assertTrue("v4 should have mtpRequested", v4Columns.contains("mtpRequested"))
        assertTrue("v4 should NOT have actualBackend", !v4Columns.contains("actualBackend"))
        assertTrue("v4 should NOT have mtpEnabled", !v4Columns.contains("mtpEnabled"))

        assertTrue("v4 should have selectedVisionBackend", v4Columns.contains("selectedVisionBackend"))
        assertTrue("v4 should have samplerBackend", v4Columns.contains("samplerBackend"))
        assertTrue("v4 should have fallbackEventCodes", v4Columns.contains("fallbackEventCodes"))
        assertTrue("v4 should have initializedWithMtp", v4Columns.contains("initializedWithMtp"))
        assertTrue("v4 should have nativeTtftSeconds", v4Columns.contains("nativeTtftSeconds"))
        assertTrue("v4 should have nativePrefillTokensPerSecond", v4Columns.contains("nativePrefillTokensPerSecond"))
        assertTrue("v4 should have nativeDecodeTokensPerSecond", v4Columns.contains("nativeDecodeTokensPerSecond"))
        assertTrue("v4 should have nativePrefillTokenCount", v4Columns.contains("nativePrefillTokenCount"))
        assertTrue("v4 should have nativeDecodeTokenCount", v4Columns.contains("nativeDecodeTokenCount"))
        assertTrue("v4 should have nativeInitTimeSeconds", v4Columns.contains("nativeInitTimeSeconds"))

        // Close migratedDb before opening production AppDatabase
        migratedDb.close()

        // Phase 5: Open migrated database through production AppDatabase
        val appDb = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "fusion-test-db"
        ).addMigrations(*appDatabaseMigrationsForTest()).build()

        val dao = appDb.benchmarkDao()

        // Phase 6: Read legacy rows through BenchmarkDao
        val legacyRows: List<BenchmarkResultEntity> = runBlocking { dao.observeAll().first() as List<BenchmarkResultEntity> }
        assertEquals(2, legacyRows.size)

        // Row 1: mtpRequested=1, mtpStatus=INITIALIZED_WITH_MTP_REQUEST → initializedWithMtp=1
        val row1 = legacyRows.find { it.modelName == "test-model" }
        assertNotNull(row1)
        assertEquals("GPU", row1!!.selectedTextBackend)
        assertEquals(true, row1.mtpRequested)
        assertEquals("INITIALIZED_WITH_MTP_REQUEST", row1.mtpStatus)
        assertEquals("UNKNOWN", row1.samplerBackend)
        assertEquals(1, if (row1.initializedWithMtp) 1 else 0)

        // Row 2: mtpRequested=1 but mtpStatus=OFF → initializedWithMtp=0
        val row2 = legacyRows.find { it.modelName == "test-model-2" }
        assertNotNull(row2)
        assertEquals("GPU", row2!!.selectedTextBackend)
        assertEquals(true, row2.mtpRequested)
        assertEquals("OFF", row2.mtpStatus)
        assertEquals("UNKNOWN", row2.samplerBackend)
        assertEquals(0, if (row2.initializedWithMtp) 1 else 0)

        // Phase 7: Insert a new version 4 row through the DAO
        val newEntity = BenchmarkResultEntity(
            createdAt = 3000L,
            modelName = "test-model-v4",
            modelPath = "/path/model3",
            accelerator = "CPU",
            selectedTextBackend = "CPU",
            selectedVisionBackend = "TEXT_WEB_GPU",
            samplerBackend = "CPU",
            mtpRequested = true,
            mtpStatus = "INITIALIZED_WITH_MTP_REQUEST",
            fallbackEventCodes = "[\"MTP_REQUESTED\"]",
            initializedWithMtp = true,
            nativeTtftSeconds = 0.15,
            nativePrefillTokensPerSecond = 512.0,
            nativeDecodeTokensPerSecond = 128.0,
            nativePrefillTokenCount = 512,
            nativeDecodeTokenCount = 128,
            nativeInitTimeSeconds = 2.5,
            maxTokens = 2048,
            temperature = 0.7f,
            topK = 40,
            topP = 0.9f,
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabel = "test-label",
            promptText = "test-prompt",
            modelLoadingMs = 100L,
            firstTokenLatencyMs = 50L,
            totalGenerationMs = 2000L,
            estimatedOutputTokens = 2048,
            totalTokensPerSecond = 20.0f,
            decodeTokensPerSecond = 15.0f,
            success = true,
            errorMessage = null,
            appVersion = "1.0.0",
            deviceModel = "test-device",
            androidVersion = "34"
        )
        runBlocking { dao.insert(newEntity) }

        // Phase 8: Close and reopen the database
        appDb.close()
        val reopenedDb = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "fusion-test-db"
        ).addMigrations(*appDatabaseMigrationsForTest()).build()
        val reopenedDao = reopenedDb.benchmarkDao()

        // Phase 9: Verify the new row through the DAO
        val allRows: List<BenchmarkResultEntity> = runBlocking { reopenedDao.observeAll().first() as List<BenchmarkResultEntity> }
        assertEquals(3, allRows.size)

        val newRow = allRows.find { it.modelName == "test-model-v4" }
        assertNotNull(newRow)
        assertEquals("CPU", newRow!!.selectedTextBackend)
        assertEquals("TEXT_WEB_GPU", newRow.selectedVisionBackend)
        assertEquals("CPU", newRow.samplerBackend)
        assertEquals(true, newRow.mtpRequested)
        assertEquals("INITIALIZED_WITH_MTP_REQUEST", newRow.mtpStatus)
        assertEquals("[\"MTP_REQUESTED\"]", newRow.fallbackEventCodes)
        assertEquals(true, newRow.initializedWithMtp)
        assertEquals(0.15, newRow.nativeTtftSeconds!!, 0.001)
        assertEquals(512.0, newRow.nativePrefillTokensPerSecond!!, 0.001)
        assertEquals(128.0, newRow.nativeDecodeTokensPerSecond!!, 0.001)
        assertEquals(512, newRow.nativePrefillTokenCount)
        assertEquals(128, newRow.nativeDecodeTokenCount)
        assertEquals(2.5, newRow.nativeInitTimeSeconds!!, 0.001)

        reopenedDb.close()
    }
}
