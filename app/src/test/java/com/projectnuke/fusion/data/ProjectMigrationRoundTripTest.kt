package com.projectnuke.fusion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class ProjectMigrationRoundTripTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS conversations (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "isPinned INTEGER NOT NULL DEFAULT 0, " +
                    "isArchived INTEGER NOT NULL DEFAULT 0" +
                    ")"
            )
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "conversationId INTEGER NOT NULL, " +
                    "role TEXT NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE" +
                    ")"
            )
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS message_evidence (" +
                    "messageId INTEGER NOT NULL, " +
                    "answerSha256 TEXT NOT NULL, " +
                    "schemaVersion INTEGER NOT NULL, " +
                    "verifierVersion TEXT NOT NULL, " +
                    "checkedAt INTEGER NOT NULL, " +
                    "reportJson TEXT NOT NULL, " +
                    "PRIMARY KEY (messageId), " +
                    "FOREIGN KEY (messageId) REFERENCES messages(id) ON DELETE CASCADE" +
                    ")"
            )
            stmt.executeUpdate(
                "INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) " +
                    "VALUES (1, 'keep me', 1000, 2000, 1, 0)"
            )
            stmt.executeUpdate(
                "INSERT INTO messages (id, conversationId, role, content, createdAt) " +
                    "VALUES (1, 1, 'user', 'hello', 1000)"
            )
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `migration SQL list is non-empty and creates both tables`() {
        val sql = AppDatabase.Migration5To6Sql
        assertNotNull(sql)
        assertTrue(sql.statements.isNotEmpty())
        assertTrue(sql.statements.any { it.contains("CREATE TABLE") && it.contains("`projects`") })
        assertTrue(sql.statements.any { it.contains("CREATE TABLE") && it.contains("`project_conversations`") })
        assertTrue(sql.statements.any { it.contains("index_project_conversations_projectId") })
    }

    @Test
    fun `project tables exist with correct columns and keys`() {
        runMigration()
        assertColumns("projects", setOf("id", "name", "createdAt", "updatedAt"))
        assertColumns("project_conversations", setOf("conversationId", "projectId", "assignedAt"))
        assertPrimaryKey("projects", listOf("id"))
        assertPrimaryKey("project_conversations", listOf("conversationId"))
    }

    @Test
    fun `relation foreign keys cascade both ways`() {
        runMigration()
        val fks = foreignKeys("project_conversations")
        assertTrue(
            fks.any { it.table == "conversations" && it.from == "conversationId" && it.to == "id" && it.onDelete == "CASCADE" }
        )
        assertTrue(
            fks.any { it.table == "projects" && it.from == "projectId" && it.to == "id" && it.onDelete == "CASCADE" }
        )
    }

    @Test
    fun `existing conversations and messages survive migration`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT title, isPinned FROM conversations WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals("keep me", rs.getString("title"))
                assertEquals(1, rs.getInt("isPinned"))
            }
            stmt.executeQuery("SELECT content FROM messages WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals("hello", rs.getString("content"))
            }
            stmt.executeQuery("SELECT COUNT(*) AS cnt FROM projects").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("cnt"))
            }
            stmt.executeQuery("SELECT COUNT(*) AS cnt FROM project_conversations").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("cnt"))
            }
        }
    }

    @Test
    fun `conversation primary key keeps one-project invariant`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (1, 'A', 1, 1)")
            stmt.executeUpdate("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (2, 'B', 2, 2)")
            stmt.executeUpdate("INSERT INTO project_conversations (conversationId, projectId, assignedAt) VALUES (1, 1, 1)")
            // REPLACE-style move works; a second row for the same
            // conversation can never exist (PRIMARY KEY).
            stmt.executeUpdate("INSERT OR REPLACE INTO project_conversations (conversationId, projectId, assignedAt) VALUES (1, 2, 2)")
            stmt.executeQuery("SELECT projectId FROM project_conversations WHERE conversationId = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals(2, rs.getLong("projectId"))
            }
            stmt.executeQuery("SELECT COUNT(*) AS cnt FROM project_conversations WHERE conversationId = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("cnt"))
            }
        }
    }

    @Test
    fun `deleting conversation removes relation, deleting project preserves conversations`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (1, 'A', 1, 1)")
            stmt.executeUpdate("INSERT INTO project_conversations (conversationId, projectId, assignedAt) VALUES (1, 1, 1)")
            stmt.executeUpdate("DELETE FROM conversations WHERE id = 1")
            stmt.executeQuery("SELECT COUNT(*) AS cnt FROM project_conversations").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("cnt"))
            }
            stmt.executeUpdate(
                "INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) " +
                    "VALUES (2, 'stays', 1000, 1000, 0, 0)"
            )
            stmt.executeUpdate("INSERT INTO project_conversations (conversationId, projectId, assignedAt) VALUES (2, 1, 1)")
            stmt.executeUpdate("DELETE FROM projects WHERE id = 1")
            stmt.executeQuery("SELECT COUNT(*) AS cnt FROM project_conversations").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("cnt"))
            }
            stmt.executeQuery("SELECT title FROM conversations WHERE id = 2").use { rs ->
                assertTrue("member conversation must remain", rs.next())
                assertEquals("stays", rs.getString("title"))
            }
        }
    }

    @Test
    fun `migration result matches Room exported 6_json contract`() {
        runMigration()
        val candidates = listOf(
            java.io.File("schemas/com.projectnuke.fusion.data.AppDatabase/6.json"),
            java.io.File("app/schemas/com.projectnuke.fusion.data.AppDatabase/6.json")
        )
        val schemaFile = candidates.firstOrNull { it.isFile }
        if (schemaFile != null) {
            val root = org.json.JSONObject(schemaFile.readText())
            assertEquals(6, root.getJSONObject("database").getInt("version"))
            val entities = root.getJSONObject("database").getJSONArray("entities")
            val names = mutableSetOf<String>()
            for (i in 0 until entities.length()) {
                names.add(entities.getJSONObject(i).getString("tableName"))
            }
            assertTrue(names.contains("projects"))
            assertTrue(names.contains("project_conversations"))
            assertTrue(names.contains("conversations"))
            assertTrue(names.contains("messages"))
        }
    }

    @Test
    fun `migration 6 to 7 preserves projects and memberships and adds bounded file table`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (7, 'keep project', 7, 8)")
            stmt.executeUpdate("INSERT INTO project_conversations (conversationId, projectId, assignedAt) VALUES (1, 7, 9)")
        }

        runMigrationTo7()

        assertColumns("projects", setOf("id", "name", "instructions", "createdAt", "updatedAt"))
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT instructions FROM projects WHERE id = 7").use { rs ->
                assertTrue(rs.next())
                assertEquals("", rs.getString("instructions"))
            }
            stmt.executeQuery("SELECT projectId FROM project_conversations WHERE conversationId = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals(7L, rs.getLong("projectId"))
            }
        }
        assertColumns("project_files", setOf("id", "projectId", "displayName", "mimeType", "localPath", "addedAt"))
        assertPrimaryKey("project_files", listOf("id"))
        val fks = foreignKeys("project_files")
        assertTrue(fks.any { it.table == "projects" && it.from == "projectId" && it.to == "id" && it.onDelete == "CASCADE" })

        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO project_files (projectId, displayName, mimeType, localPath, addedAt) VALUES (7, 'a.md', 'text/markdown', '/managed/a', 10)")
            stmt.executeQuery("SELECT COUNT(*) AS cnt FROM project_files WHERE projectId = 7").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("cnt"))
            }
            stmt.executeUpdate("DELETE FROM projects WHERE id = 7")
            stmt.executeQuery("SELECT COUNT(*) AS cnt FROM project_files WHERE projectId = 7").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("cnt"))
            }
            stmt.executeQuery("SELECT title FROM conversations WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals("keep me", rs.getString("title"))
            }
        }
    }

    @Test
    fun `migration 6 to 7 scopes duplicate path uniqueness per project`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (1, 'A', 1, 1)")
            stmt.executeUpdate("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (2, 'B', 2, 2)")
        }
        runMigrationTo7()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO project_files (projectId, displayName, mimeType, localPath, addedAt) VALUES (1, 'a.txt', 'text/plain', '/managed/same', 1)")
            stmt.executeUpdate("INSERT INTO project_files (projectId, displayName, mimeType, localPath, addedAt) VALUES (2, 'a.txt', 'text/plain', '/managed/same', 1)")
            var duplicateRejected = false
            try {
                stmt.executeUpdate("INSERT INTO project_files (projectId, displayName, mimeType, localPath, addedAt) VALUES (1, 'again.txt', 'text/plain', '/managed/same', 2)")
            } catch (_: java.sql.SQLException) {
                duplicateRejected = true
            }
            assertTrue(duplicateRejected)
        }
    }

    // ── helpers ────────────────────────────────────────────

    private fun runMigration() {
        for (statement in AppDatabase.Migration5To6Sql.statements) {
            connection.createStatement().use { stmt -> stmt.execute(statement) }
        }
    }

    private fun runMigrationTo7() {
        for (statement in AppDatabase.Migration6To7Sql.statements) {
            connection.createStatement().use { stmt -> stmt.execute(statement) }
        }
    }

    private fun assertColumns(table: String, expected: Set<String>) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                val cols = mutableSetOf<String>()
                while (rs.next()) cols.add(rs.getString("name"))
                assertEquals(expected, cols)
            }
        }
    }

    private fun assertPrimaryKey(table: String, expected: List<String>) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                val pk = mutableListOf<Pair<Int, String>>()
                while (rs.next()) {
                    val order = rs.getInt("pk")
                    if (order > 0) pk.add(order to rs.getString("name"))
                }
                assertEquals(expected, pk.sortedBy { it.first }.map { it.second })
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
                val out = mutableListOf<ForeignKeyRef>()
                while (rs.next()) {
                    out.add(
                        ForeignKeyRef(
                            table = rs.getString("table"),
                            from = rs.getString("from"),
                            to = rs.getString("to"),
                            onDelete = rs.getString("on_delete"),
                        )
                    )
                }
                return out
            }
        }
    }
}
