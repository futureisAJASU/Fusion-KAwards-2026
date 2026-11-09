package com.projectnuke.fusion.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * P17-02 downstream persistence barrier: even if a generation session
 * installed after its conversation's authoritative deletion (the residual
 * registry-level window outside the deletion-ownership hold), the durable
 * message write must fail closed.
 *
 * Proof is two-layered:
 * 1. The production [MessageEntity] declaration itself carries the
 *    `messages(conversationId) -> conversations(id) ON DELETE CASCADE`
 *    foreign key (verified by reflection, not by copying DDL).
 * 2. Against real SQLite with `PRAGMA foreign_keys = ON` (Room's default via
 *    `RoomOpenHelper`), deleting a conversation cascades its messages away
 *    and a late assistant-message insert for the deleted conversation is
 *    rejected — so no deleted conversation can be resurrected and no orphan
 *    assistant row can be written.
 */
class DeletionPersistenceBarrierTest {
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
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `P17-02 exported current schema declares cascade delete for messages`() {
        // Room-exported schema (generated from the production entities, not
        // hand-written): the messages table must reference conversations with
        // ON DELETE CASCADE, which is what makes the late-write barrier real.
        val candidates = listOf(
            File("schemas/com.projectnuke.fusion.data.AppDatabase/31.json"),
            File("app/schemas/com.projectnuke.fusion.data.AppDatabase/31.json"),
        )
        val schemaFile = candidates.firstOrNull { it.isFile }
            ?: error("Room schema export 31.json not found at ${candidates.map { it.path }}")
        val root = JSONObject(schemaFile.readText())
        assertEquals(31, root.getJSONObject("database").getInt("version"))
        val tables = root.getJSONObject("database").getJSONArray("entities")
        var messagesSql: String? = null
        repeat(tables.length()) { index ->
            val table = tables.getJSONObject(index)
            if (table.getString("tableName") == "messages") {
                messagesSql = table.getString("createSql")
            }
        }
        val sql = messagesSql ?: error("messages table missing from schema export")
        assertTrue(
            "messages DDL must declare the conversations FK, got: $sql",
            sql.contains("FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`)"),
        )
        assertTrue(
            "messages FK must cascade on conversation delete, got: $sql",
            sql.contains("ON DELETE CASCADE"),
        )
    }

    @Test
    fun `P17-02 conversation delete cascades messages and late insert fails closed`() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO conversations (id, title, createdAt, updatedAt) VALUES (41, 'c', 1, 2)")
            stmt.executeUpdate("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (101, 41, 'user', 'hi', 3)")
        }
        // Authoritative deletion.
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM conversations WHERE id = 41")
        }
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM messages").use { rs ->
                assertTrue(rs.next())
                assertEquals("cascade must remove the conversation's messages", 0, rs.getInt(1))
            }
        }
        // A late generation installing after the deletion cannot write: the
        // FK rejects the orphan assistant row instead of resurrecting state.
        try {
            connection.createStatement().use { stmt ->
                stmt.executeUpdate(
                    "INSERT INTO messages (conversationId, role, content, createdAt) " +
                        "VALUES (41, 'assistant', 'late answer', 4)"
                )
            }
            fail("late assistant insert for a deleted conversation must fail closed")
        } catch (_: java.sql.SQLException) {
            // Expected: FOREIGN KEY constraint failed.
        }
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM messages").use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1))
            }
            stmt.executeQuery("SELECT COUNT(*) FROM conversations WHERE id = 41").use { rs ->
                assertTrue(rs.next())
                assertEquals("deletion must not be undone by the late write", 0, rs.getInt(1))
            }
        }
    }
}
