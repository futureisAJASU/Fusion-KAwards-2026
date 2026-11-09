package com.projectnuke.fusion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class SubmissionWitnessMigrationRoundTripTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")
            stmt.execute(
                "CREATE TABLE conversations (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "isPinned INTEGER NOT NULL DEFAULT 0, isArchived INTEGER NOT NULL DEFAULT 0)"
            )
            stmt.execute(
                "CREATE TABLE messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, " +
                    "role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                    "FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE)"
            )
            stmt.execute(
                "CREATE TABLE message_evidence (" +
                    "messageId INTEGER PRIMARY KEY NOT NULL, answerSha256 TEXT NOT NULL, " +
                    "schemaVersion INTEGER NOT NULL, verifierVersion TEXT NOT NULL, " +
                    "checkedAt INTEGER NOT NULL, reportJson TEXT NOT NULL, " +
                    "FOREIGN KEY (messageId) REFERENCES messages(id) ON DELETE CASCADE)"
            )
            stmt.execute(
                "CREATE TABLE projects (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, instructions TEXT NOT NULL)"
            )
            stmt.execute(
                "CREATE TABLE project_files (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, projectId INTEGER NOT NULL, " +
                    "displayName TEXT NOT NULL, mimeType TEXT NOT NULL, localPath TEXT NOT NULL, addedAt INTEGER NOT NULL)"
            )
            stmt.execute(
                "CREATE TABLE project_conversations (" +
                    "conversationId INTEGER PRIMARY KEY NOT NULL, projectId INTEGER NOT NULL, assignedAt INTEGER NOT NULL)"
            )
            stmt.execute(
                "CREATE TABLE benchmark_results (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, modelName TEXT NOT NULL, createdAt INTEGER NOT NULL)"
            )
            stmt.executeUpdate("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (1, 'keep', 10, 20, 1, 0)")
            stmt.executeUpdate("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (11, 1, 'assistant', 'answer', 30)")
            stmt.executeUpdate("INSERT INTO message_evidence (messageId, answerSha256, schemaVersion, verifierVersion, checkedAt, reportJson) VALUES (11, 'sha', 1, 'v1', 31, '{}')")
            stmt.executeUpdate("INSERT INTO projects (id, name, createdAt, updatedAt, instructions) VALUES (7, 'project', 1, 2, 'local')")
            stmt.executeUpdate("INSERT INTO project_files (id, projectId, displayName, mimeType, localPath, addedAt) VALUES (8, 7, 'a.txt', 'text/plain', '/managed/a', 3)")
            stmt.executeUpdate("INSERT INTO project_conversations (conversationId, projectId, assignedAt) VALUES (1, 7, 4)")
            stmt.executeUpdate("INSERT INTO benchmark_results (id, modelName, createdAt) VALUES (9, 'model', 5)")
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `migration preserves schema 7 rows and creates both witness tables`() {
        runMigration()

        assertColumns(
            "submission_commit_witnesses",
            setOf("token", "draftKey", "committedConversationId", "userMessageId", "capturedRawInput", "committedPathsJson", "createdAt"),
        )
        assertColumns(
            "response_version_commit_witnesses",
            setOf("messageId", "conversationId", "targetAssistantMessageId", "groupId", "createdAt"),
        )
        assertTrue(
            foreignKeys("response_version_commit_witnesses").any {
                it.table == "messages" && it.from == "messageId" && it.to == "id" && it.onDelete == "CASCADE"
            }
        )
        assertTrue(indexNames("response_version_commit_witnesses").contains("index_response_version_commit_witnesses_conversationId"))

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT title FROM conversations WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals("keep", rs.getString(1))
            }
            stmt.executeQuery("SELECT content FROM messages WHERE id = 11").use { rs ->
                assertTrue(rs.next())
                assertEquals("answer", rs.getString(1))
            }
            stmt.executeQuery("SELECT localPath FROM project_files WHERE id = 8").use { rs ->
                assertTrue(rs.next())
                assertEquals("/managed/a", rs.getString(1))
            }
            stmt.executeQuery("SELECT reportJson FROM message_evidence WHERE messageId = 11").use { rs ->
                assertTrue(rs.next())
                assertEquals("{}", rs.getString(1))
            }
            stmt.executeQuery("SELECT projectId FROM project_conversations WHERE conversationId = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals(7, rs.getLong(1))
            }
            stmt.executeQuery("SELECT modelName FROM benchmark_results WHERE id = 9").use { rs ->
                assertTrue(rs.next())
                assertEquals("model", rs.getString(1))
            }
        }
    }

    @Test
    fun `response witness cascades but submission witness remains historical proof`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO submission_commit_witnesses (token, draftKey, committedConversationId, userMessageId, capturedRawInput, committedPathsJson, createdAt) " +
                    "VALUES ('s8:test', 0, 1, 12, 'hello', '[]', 40)"
            )
            stmt.executeUpdate(
                "INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (12, 1, 'user', 'hello', 40)"
            )
            stmt.executeUpdate(
                "INSERT INTO response_version_commit_witnesses (messageId, conversationId, targetAssistantMessageId, groupId, createdAt) " +
                    "VALUES (11, 1, 11, 1, 41)"
            )
            stmt.executeUpdate("DELETE FROM messages WHERE id = 11")
            stmt.executeQuery("SELECT COUNT(*) FROM response_version_commit_witnesses").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1))
            }
            stmt.executeUpdate("DELETE FROM conversations WHERE id = 1")
            stmt.executeQuery("SELECT COUNT(*) FROM submission_commit_witnesses WHERE token = 's8:test'").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test
    fun `migration statement list is bounded to the two new tables and index`() {
        assertEquals(3, AppDatabase.Migration7To8Sql.statements.size)
        assertFalse(AppDatabase.Migration7To8Sql.statements.any { it.contains("DROP TABLE", ignoreCase = true) })
    }

    private fun runMigration() {
        AppDatabase.Migration7To8Sql.statements.forEach { statement ->
            connection.createStatement().use { stmt -> stmt.execute(statement) }
        }
    }

    private fun assertColumns(table: String, expected: Set<String>) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                val actual = mutableSetOf<String>()
                while (rs.next()) actual += rs.getString("name")
                assertEquals(expected, actual)
            }
        }
    }

    private data class ForeignKeyRef(
        val table: String,
        val from: String,
        val to: String,
        val onDelete: String,
    )

    private fun foreignKeys(table: String): List<ForeignKeyRef> {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA foreign_key_list($table)").use { rs ->
                val actual = mutableListOf<ForeignKeyRef>()
                while (rs.next()) {
                    actual += ForeignKeyRef(
                        table = rs.getString("table"),
                        from = rs.getString("from"),
                        to = rs.getString("to"),
                        onDelete = rs.getString("on_delete"),
                    )
                }
                return actual
            }
        }
    }

    private fun indexNames(table: String): Set<String> {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA index_list($table)").use { rs ->
                val actual = mutableSetOf<String>()
                while (rs.next()) actual += rs.getString("name")
                return actual
            }
        }
    }
}
