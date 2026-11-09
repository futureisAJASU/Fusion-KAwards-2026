package com.projectnuke.fusion.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM replay of the complete Room migration chain against real SQLite. */
class LegacyAppDatabaseMigrationChainTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `v1 to v25 preserves legacy rows and builds the final structure`() {
        createV1()
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO conversations (id, title, createdAt, updatedAt) VALUES (1, 'legacy', 10, 20)")
            statement.executeUpdate("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (1, 1, 'user', 'survives', 21)")
        }

        execute(AppDatabase.Migration1To2Sql.statements)
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT isPinned, isArchived FROM conversations WHERE id = 1").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt("isPinned"))
                assertEquals(0, rows.getInt("isArchived"))
            }
        }
        execute(AppDatabase.Migration2To3Sql.statements)
        assertTrue(tableExists("benchmark_results"))

        execute(AppDatabase.BenchmarkResultsMigration3To4Sql.statements)
        execute(AppDatabase.Migration4To5Sql.statements)
        execute(AppDatabase.Migration5To6Sql.statements)
        execute(AppDatabase.Migration6To7Sql.statements)
        execute(AppDatabase.Migration7To8Sql.statements)
        execute(AppDatabase.Migration8To9Sql.statements)
        execute(AppDatabase.Migration9To10Sql.statements)
        execute(AppDatabase.Migration10To11Sql.statements)
        execute(AppDatabase.Migration11To12Sql.statements)
        execute(AppDatabase.Migration12To13Sql.statements)
        execute(AppDatabase.Migration13To14Sql.statements)
        execute(AppDatabase.Migration14To15Sql.statements)
        execute(AppDatabase.Migration15To16Sql.statements)
        execute(AppDatabase.Migration16To17Sql.statements)
        execute(AppDatabase.Migration17To18Sql.statements)
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (10, 'Project', '', 1, 1)")
            statement.executeUpdate("INSERT INTO project_files (id, projectId, displayName, mimeType, localPath, addedAt, sourceHandoffEventId) VALUES (20, 10, 'paper.pdf', 'application/pdf', 'managed-file', 1, NULL)")
            statement.executeUpdate("INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES ('highlight-1', 'PROJECT', 10, 'paper.pdf · p.2', 'quoted text', 'DOCUMENT_HIGHLIGHT', NULL, NULL, 1, 1)")
        }
        execute(AppDatabase.Migration18To19Sql.statements)
        execute(AppDatabase.Migration19To20Sql.statements)
        execute(AppDatabase.Migration20To21Sql.statements)
        execute(AppDatabase.Migration21To22Sql.statements)
        execute(AppDatabase.Migration22To23Sql.statements)
        execute(AppDatabase.Migration23To24Sql.statements)
        execute(AppDatabase.Migration24To25Sql.statements)
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO document_highlight_sources (savedKnowledgeId, projectId, projectFileId, pageNumber, sourceChunkId, sourceContentHashHex, userNote, createdAt) VALUES ('highlight-1', 10, 20, 2, 'page:2', '${"a".repeat(64)}', 'note', 2)")
            statement.executeUpdate("DELETE FROM project_files WHERE id = 20")
            statement.executeQuery("SELECT COUNT(*) FROM saved_knowledge WHERE id = 'highlight-1'").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt(1))
            }
        }

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title, isPinned, isArchived, branchedFromConversationId, branchedFromMessageId FROM conversations WHERE id = 1").use { rows ->
                assertTrue(rows.next())
                assertEquals("legacy", rows.getString("title"))
                assertEquals(0, rows.getInt("isPinned"))
                assertEquals(0, rows.getInt("isArchived"))
                assertEquals(null, rows.getObject("branchedFromConversationId"))
                assertEquals(null, rows.getObject("branchedFromMessageId"))
            }
            statement.executeQuery("SELECT content FROM messages WHERE id = 1").use { rows ->
                assertTrue(rows.next())
                assertEquals("survives", rows.getString("content"))
            }
        }
        setOf(
            "benchmark_results",
            "message_evidence",
            "projects",
            "project_conversations",
            "project_files",
            "submission_commit_witnesses",
            "response_version_commit_witnesses",
            "saved_knowledge",
            "project_tasks",
            "study_review_cards",
            "branch_synthesis_sources",
            "inbox_items",
            "external_handoff_receipts",
            "knowledge_tags",
            "saved_knowledge_tags",
            "inbox_item_tags",
            "knowledge_collections",
            "collection_saved_knowledge",
            "collection_inbox_items",
            "document_highlight_sources",
            "project_decisions",
            "saved_knowledge_note_sources",
            "conversation_checkpoints",
            "project_work_plans",
            "project_work_plan_steps",
            "project_work_plan_result_sources",
        ).forEach { table -> assertTrue("missing $table", tableExists(table)) }
        assertTrue(columns("benchmark_results").containsAll(setOf("selectedTextBackend", "mtpRequested", "initializedWithMtp")))
        assertTrue(columns("projects").contains("instructions"))
        assertTrue(columns("saved_knowledge").containsAll(setOf("sourceUrl", "sourcePageTitle", "retrievedAt")))
        assertTrue(columns("conversations").containsAll(setOf("refreshedFromConversationId", "refreshedFromMessageId")))
    }

    @Test
    fun `v2 to v25 preserves v2 conversation state and builds final structure`() {
        createV2()
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (2, 'legacy-v2', 10, 20, 1, 1)")
            statement.executeUpdate("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (2, 2, 'user', 'survives-v2', 21)")
        }

        assertFalse(tableExists("benchmark_results"))
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT isPinned, isArchived FROM conversations WHERE id = 2").use { rows ->
                assertTrue(rows.next())
                assertEquals(1, rows.getInt("isPinned"))
                assertEquals(1, rows.getInt("isArchived"))
            }
        }

        execute(AppDatabase.Migration2To3Sql.statements)
        execute(AppDatabase.BenchmarkResultsMigration3To4Sql.statements)
        execute(AppDatabase.Migration4To5Sql.statements)
        execute(AppDatabase.Migration5To6Sql.statements)
        execute(AppDatabase.Migration6To7Sql.statements)
        execute(AppDatabase.Migration7To8Sql.statements)
        execute(AppDatabase.Migration8To9Sql.statements)
        execute(AppDatabase.Migration9To10Sql.statements)
        execute(AppDatabase.Migration10To11Sql.statements)
        execute(AppDatabase.Migration11To12Sql.statements)
        execute(AppDatabase.Migration12To13Sql.statements)
        execute(AppDatabase.Migration13To14Sql.statements)
        execute(AppDatabase.Migration14To15Sql.statements)
        execute(AppDatabase.Migration15To16Sql.statements)
        execute(AppDatabase.Migration16To17Sql.statements)
        execute(AppDatabase.Migration17To18Sql.statements)
        execute(AppDatabase.Migration18To19Sql.statements)
        execute(AppDatabase.Migration19To20Sql.statements)
        execute(AppDatabase.Migration20To21Sql.statements)
        execute(AppDatabase.Migration21To22Sql.statements)
        execute(AppDatabase.Migration22To23Sql.statements)
        execute(AppDatabase.Migration23To24Sql.statements)
        execute(AppDatabase.Migration24To25Sql.statements)

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title, isPinned, isArchived, branchedFromConversationId, branchedFromMessageId FROM conversations WHERE id = 2").use { rows ->
                assertTrue(rows.next())
                assertEquals("legacy-v2", rows.getString("title"))
                assertEquals(1, rows.getInt("isPinned"))
                assertEquals(1, rows.getInt("isArchived"))
                assertEquals(null, rows.getObject("branchedFromConversationId"))
                assertEquals(null, rows.getObject("branchedFromMessageId"))
            }
            statement.executeQuery("SELECT content FROM messages WHERE id = 2 AND conversationId = 2").use { rows ->
                assertTrue(rows.next())
                assertEquals("survives-v2", rows.getString("content"))
            }
        }
        setOf(
            "benchmark_results",
            "message_evidence",
            "projects",
            "project_conversations",
            "project_files",
            "submission_commit_witnesses",
            "response_version_commit_witnesses",
            "saved_knowledge",
            "project_tasks",
            "study_review_cards",
            "branch_synthesis_sources",
            "inbox_items",
            "external_handoff_receipts",
            "knowledge_tags",
            "saved_knowledge_tags",
            "inbox_item_tags",
            "knowledge_collections",
            "collection_saved_knowledge",
            "collection_inbox_items",
            "document_highlight_sources",
            "project_decisions",
            "saved_knowledge_note_sources",
            "conversation_checkpoints",
            "project_work_plans",
            "project_work_plan_steps",
            "project_work_plan_result_sources",
        ).forEach { table -> assertTrue("missing $table", tableExists(table)) }
        assertTrue(columns("benchmark_results").containsAll(setOf("selectedTextBackend", "mtpRequested", "initializedWithMtp")))
        assertTrue(columns("projects").contains("instructions"))
        assertTrue(columns("response_version_commit_witnesses").containsAll(setOf("messageId", "conversationId", "targetAssistantMessageId")))
        assertTrue(columns("conversations").containsAll(setOf("branchedFromConversationId", "branchedFromMessageId")))
        assertTrue(columns("conversations").containsAll(setOf("refreshedFromConversationId", "refreshedFromMessageId")))
    }

    @Test
    fun `v22 to v23 creates anchored checkpoint rows with source cascades`() {
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY NOT NULL)")
            statement.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY NOT NULL, conversationId INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
            statement.executeUpdate("INSERT INTO conversations (id) VALUES (1)")
            statement.executeUpdate("INSERT INTO messages (id, conversationId) VALUES (2, 1)")
        }
        execute(AppDatabase.Migration22To23Sql.statements)
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO conversation_checkpoints (conversationId, messageId, title, createdAt, note) VALUES (1, 2, 'anchor', 3, '')")
            statement.executeUpdate("DELETE FROM messages WHERE id = 2")
            statement.executeQuery("SELECT COUNT(*) FROM conversation_checkpoints").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt(1))
            }
        }
    }

    @Test
    fun `v23 to v24 creates durable plan and step tables with bounded provenance links`() {
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE projects (id INTEGER PRIMARY KEY NOT NULL)")
            statement.execute("CREATE TABLE project_tasks (id INTEGER PRIMARY KEY NOT NULL)")
            statement.executeUpdate("INSERT INTO projects (id) VALUES (1)")
            statement.executeUpdate("INSERT INTO project_tasks (id) VALUES (7)")
        }

        execute(AppDatabase.Migration23To24Sql.statements)

        assertTrue(columns("project_work_plans").containsAll(setOf(
            "id", "projectId", "title", "goal", "desiredOutput", "constraintText", "status", "currentStepPosition", "archivedAt", "createdAt", "updatedAt",
        )))
        assertTrue(columns("project_work_plan_steps").containsAll(setOf(
            "id", "planId", "position", "title", "instruction", "type", "state", "attemptCount", "activeAttemptId", "resultKind", "resultId", "previousResultRefs", "linkedTaskId", "interrupted", "failureKind", "createdAt", "updatedAt",
        )))
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO project_work_plans (id, projectId, title, goal, desiredOutput, constraintText, status, currentStepPosition, createdAt, updatedAt) VALUES ('plan-1', 1, 'title', 'goal', '', '', 'ACTIVE', 0, 1, 1)")
            statement.executeUpdate("INSERT INTO project_work_plan_steps (id, planId, position, title, instruction, type, state, linkedTaskId, resultKind, resultId, createdAt, updatedAt) VALUES ('step-1', 'plan-1', 0, 'step', 'do work', 'CREATE_TASK', 'REVIEW', 7, 'TASK', '7', 1, 1)")
            statement.executeUpdate("DELETE FROM project_tasks WHERE id = 7")
            statement.executeQuery("SELECT linkedTaskId, resultKind, resultId FROM project_work_plan_steps WHERE id = 'step-1'").use { rows ->
                assertTrue(rows.next())
                assertEquals(null, rows.getObject("linkedTaskId"))
                assertEquals("TASK", rows.getString("resultKind"))
                assertEquals("7", rows.getString("resultId"))
            }
            statement.executeUpdate("DELETE FROM projects WHERE id = 1")
            statement.executeQuery("SELECT COUNT(*) FROM project_work_plans").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt(1))
            }
        }
    }

    @Test
    fun `v24 to v25 creates durable specialized result provenance without touching plans`() {
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE projects (id INTEGER PRIMARY KEY NOT NULL)")
            statement.execute("CREATE TABLE project_work_plans (id TEXT PRIMARY KEY NOT NULL, projectId INTEGER NOT NULL)")
            statement.execute("CREATE TABLE project_work_plan_steps (id TEXT PRIMARY KEY NOT NULL, planId TEXT NOT NULL)")
            statement.executeUpdate("INSERT INTO projects (id) VALUES (1)")
            statement.executeUpdate("INSERT INTO project_work_plans (id, projectId) VALUES ('plan-1', 1)")
            statement.executeUpdate("INSERT INTO project_work_plan_steps (id, planId) VALUES ('step-1', 'plan-1')")
        }

        execute(AppDatabase.Migration24To25Sql.statements)

        assertTrue(columns("project_work_plan_result_sources").containsAll(setOf(
            "resultId", "planId", "stepId", "attemptId", "projectId", "stepType",
            "resultKind", "extractionType", "sourcesJson", "createdAt",
        )))
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO project_work_plan_result_sources " +
                    "(resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) " +
                    "VALUES ('s:abc', 'plan-1', 'step-1', 'attempt-1', 1, 'REVIEW_KNOWLEDGE', 'SELECTION', NULL, '[]', 5)",
            )
            statement.executeQuery("SELECT sourcesJson FROM project_work_plan_result_sources WHERE resultId = 's:abc'").use { rows ->
                assertTrue(rows.next())
                assertEquals("[]", rows.getString("sourcesJson"))
            }
            // Existing plans and steps survive the migration untouched.
            statement.executeQuery("SELECT COUNT(*) FROM project_work_plans").use { rows ->
                assertTrue(rows.next())
                assertEquals(1, rows.getInt(1))
            }
            statement.executeQuery("SELECT COUNT(*) FROM project_work_plan_steps").use { rows ->
                assertTrue(rows.next())
                assertEquals(1, rows.getInt(1))
            }
        }
    }

    @Test
    fun `v8 to v9 preserves existing conversation data and adds nullable provenance`() {
        createV8()
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (8, 'legacy-v8', 80, 90, 1, 1)")
            statement.executeUpdate("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (8, 8, 'assistant', 'keep-v8', 91)")
        }

        execute(AppDatabase.Migration8To9Sql.statements)

        assertTrue(columns("conversations").containsAll(setOf("branchedFromConversationId", "branchedFromMessageId")))
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title, isPinned, isArchived, branchedFromConversationId, branchedFromMessageId FROM conversations WHERE id = 8").use { rows ->
                assertTrue(rows.next())
                assertEquals("legacy-v8", rows.getString("title"))
                assertEquals(1, rows.getInt("isPinned"))
                assertEquals(1, rows.getInt("isArchived"))
                assertEquals(null, rows.getObject("branchedFromConversationId"))
                assertEquals(null, rows.getObject("branchedFromMessageId"))
            }
            statement.executeQuery("SELECT content FROM messages WHERE id = 8 AND conversationId = 8").use { rows ->
                assertTrue(rows.next())
                assertEquals("keep-v8", rows.getString("content"))
            }
        }
    }

    @Test
    fun `v9 to v10 adds bounded saved knowledge table with project ownership`() {
        createV8()
        execute(AppDatabase.Migration8To9Sql.statements)
        execute(AppDatabase.Migration9To10Sql.statements)

        assertTrue(tableExists("saved_knowledge"))
        assertTrue(columns("saved_knowledge").containsAll(setOf(
            "id", "scope", "projectId", "title", "savedText", "provenanceType",
            "sourceConversationId", "sourceMessageId", "createdAt", "updatedAt",
        )))
        connection.createStatement().use { statement ->
            statement.execute("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (7, 'p', '', 1, 1)")
            statement.execute("INSERT INTO saved_knowledge (id, scope, projectId, title, savedText, provenanceType, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES ('saved-1', 'PROJECT', 7, 'answer', 'copy', 'ASSISTANT_ANSWER', 44, 55, 9, 9)")
            statement.execute("DELETE FROM projects WHERE id = 7")
            statement.executeQuery("SELECT COUNT(*) FROM saved_knowledge WHERE id = 'saved-1'").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt(1))
            }
        }
    }

    @Test
    fun `v10 to v11 adds project owned task table and project cascade`() {
        createV8()
        execute(AppDatabase.Migration8To9Sql.statements)
        execute(AppDatabase.Migration9To10Sql.statements)
        execute(AppDatabase.Migration10To11Sql.statements)
        execute(AppDatabase.Migration11To12Sql.statements)
        execute(AppDatabase.Migration12To13Sql.statements)
        execute(AppDatabase.Migration13To14Sql.statements)
        assertTrue(tableExists("project_tasks"))
        assertTrue(columns("project_tasks").containsAll(setOf(
            "id", "projectId", "title", "note", "state", "sourceConversationId", "sourceMessageId", "createdAt", "updatedAt",
        )))
        connection.createStatement().use { statement ->
            statement.execute("INSERT INTO projects (id, name, instructions, createdAt, updatedAt) VALUES (9, 'p', '', 1, 1)")
            statement.execute("INSERT INTO project_tasks (projectId, title, note, state, sourceConversationId, sourceMessageId, createdAt, updatedAt) VALUES (9, 't', '', 'OPEN', NULL, NULL, 1, 1)")
            statement.execute("DELETE FROM projects WHERE id = 9")
            statement.executeQuery("SELECT COUNT(*) FROM project_tasks").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt(1))
            }
        }
    }

    @Test
    fun `reconstructed v1 v2 and v3 archives match the declared migration contract`() {
        val v1 = schema(1)
        val v2 = schema(2)
        val v3 = schema(3)
        val v8 = schema(8)
        val v9 = schema(9)
        val v10 = schema(10)
        val v11 = schema(11)

        assertEquals(setOf("conversations", "messages"), tables(v1))
        assertFalse(columns(v1, "conversations").contains("isPinned"))
        assertFalse(columns(v1, "conversations").contains("isArchived"))

        assertEquals(setOf("conversations", "messages"), tables(v2))
        assertTrue(columns(v2, "conversations").containsAll(setOf("isPinned", "isArchived")))

        assertTrue(tables(v3).containsAll(setOf("conversations", "messages", "benchmark_results")))
        assertTrue(columns(v3, "conversations").containsAll(setOf("isPinned", "isArchived")))
        assertFalse(columns(v8, "conversations").contains("branchedFromConversationId"))
        assertTrue(columns(v9, "conversations").containsAll(setOf("branchedFromConversationId", "branchedFromMessageId")))
        assertTrue(tables(v10).contains("saved_knowledge"))
        assertTrue(columns(v10, "saved_knowledge").containsAll(setOf("id", "scope", "projectId", "savedText", "provenanceType")))
        assertTrue(tables(v11).contains("project_tasks"))
        assertTrue(columns(v11, "project_tasks").containsAll(setOf("projectId", "title", "note", "state")))
    }

    private fun createV1() {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
            )
            statement.execute(
                "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            statement.execute("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
        }
    }

    private fun createV2() {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, isPinned INTEGER NOT NULL, isArchived INTEGER NOT NULL)",
            )
            statement.execute(
                "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            statement.execute("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
        }
    }

    private fun createV8() {
        createV2()
        execute(AppDatabase.Migration2To3Sql.statements)
        execute(AppDatabase.BenchmarkResultsMigration3To4Sql.statements)
        execute(AppDatabase.Migration4To5Sql.statements)
        execute(AppDatabase.Migration5To6Sql.statements)
        execute(AppDatabase.Migration6To7Sql.statements)
        execute(AppDatabase.Migration7To8Sql.statements)
    }

    private fun execute(statements: List<String>) {
        statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun tableExists(table: String): Boolean = connection.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
    ).use { statement ->
        statement.setString(1, table)
        statement.executeQuery().use { it.next() }
    }

    private fun columns(table: String): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { rows ->
            buildSet {
                while (rows.next()) add(rows.getString("name"))
            }
        }
    }

    private fun schema(version: Int): JSONObject {
        val file = listOf(
            File("schemas/com.projectnuke.fusion.data.AppDatabase/$version.json"),
            File("app/schemas/com.projectnuke.fusion.data.AppDatabase/$version.json"),
        ).first { it.isFile }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun tables(schema: JSONObject): Set<String> = schema.getJSONArray("entities").let { entities ->
        buildSet {
            for (index in 0 until entities.length()) add(entities.getJSONObject(index).getString("tableName"))
        }
    }

    private fun columns(schema: JSONObject, table: String): Set<String> = schema.getJSONArray("entities").let { entities ->
        val entity = (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == table }
        buildSet {
            val fields = entity.getJSONArray("fields")
            for (index in 0 until fields.length()) add(fields.getJSONObject(index).getString("columnName"))
        }
    }
}
