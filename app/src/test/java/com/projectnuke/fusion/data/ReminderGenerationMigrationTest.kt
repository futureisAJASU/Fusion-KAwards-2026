package com.projectnuke.fusion.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReminderGenerationMigrationTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE projects (id INTEGER PRIMARY KEY, name TEXT NOT NULL, instructions TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
            )
            statement.execute(
                "CREATE TABLE project_tasks (id INTEGER PRIMARY KEY, reminderAt INTEGER, reminderEnabled INTEGER NOT NULL DEFAULT 0)",
            )
            statement.execute(
                "CREATE TABLE study_review_cards (id TEXT PRIMARY KEY, reminderAt INTEGER, reminderEnabled INTEGER NOT NULL DEFAULT 0)",
            )
            statement.executeUpdate("INSERT INTO project_tasks VALUES (7, 12345, 1)")
            statement.executeUpdate("INSERT INTO study_review_cards VALUES ('card-7', 54321, 1)")
            statement.executeUpdate("INSERT INTO projects VALUES (3, 'preserve', '', 1, 2)")
        }
    }

    @After
    fun tearDown() = connection.close()

    @Test
    fun `v26 to v27 adds generation without changing existing reminders`() {
        val migration = AppDatabase.Migration26To27Sql
        assertEquals(2, migration.statements.size)
        migration.statements.forEach { sql ->
            assertTrue(sql.startsWith("ALTER TABLE"))
            connection.createStatement().use { it.execute(sql) }
        }

        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT reminderAt, reminderEnabled, reminderGeneration FROM project_tasks WHERE id = 7",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(12345L, rows.getLong("reminderAt"))
                assertEquals(1, rows.getInt("reminderEnabled"))
                assertEquals(0L, rows.getLong("reminderGeneration"))
            }
            statement.executeQuery(
                "SELECT reminderAt, reminderEnabled, reminderGeneration FROM study_review_cards WHERE id = 'card-7'",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(54321L, rows.getLong("reminderAt"))
                assertEquals(1, rows.getInt("reminderEnabled"))
                assertEquals(0L, rows.getLong("reminderGeneration"))
            }
        }
    }

    @Test
    fun `v27 to v28 adds cleanup debt default without changing reminder rows`() {
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE project_tasks ADD COLUMN reminderGeneration INTEGER NOT NULL DEFAULT 0")
            statement.execute("ALTER TABLE study_review_cards ADD COLUMN reminderGeneration INTEGER NOT NULL DEFAULT 0")
        }
        AppDatabase.Migration27To28Sql.statements.forEach { sql ->
            connection.createStatement().use { it.execute(sql) }
        }
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT reminderAt, reminderEnabled, reminderGeneration, notificationCleanupPending FROM project_tasks WHERE id = 7",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(12345L, rows.getLong("reminderAt"))
                assertEquals(1, rows.getInt("reminderEnabled"))
                assertEquals(0L, rows.getLong("reminderGeneration"))
                assertEquals(1, rows.getInt("notificationCleanupPending"))
            }
            statement.executeQuery(
                "SELECT reminderAt, reminderEnabled, reminderGeneration, notificationCleanupPending FROM study_review_cards WHERE id = 'card-7'",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(54321L, rows.getLong("reminderAt"))
                assertEquals(1, rows.getInt("reminderEnabled"))
                assertEquals(0L, rows.getLong("reminderGeneration"))
                assertEquals(1, rows.getInt("notificationCleanupPending"))
            }
        }
    }

    @Test
    fun `v28 to v29 adds durable deletion intent defaults without changing reminders`() {
        AppDatabase.Migration26To27Sql.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        AppDatabase.Migration27To28Sql.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        AppDatabase.Migration28To29Sql.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }

        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT reminderAt, reminderEnabled, reminderGeneration, notificationCleanupPending, deletionPending FROM project_tasks WHERE id = 7",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(12345L, rows.getLong("reminderAt"))
                assertEquals(1, rows.getInt("reminderEnabled"))
                assertEquals(0L, rows.getLong("reminderGeneration"))
                assertEquals(1, rows.getInt("notificationCleanupPending"))
                assertEquals(0, rows.getInt("deletionPending"))
            }
            statement.executeQuery(
                "SELECT reminderAt, reminderEnabled, reminderGeneration, notificationCleanupPending, deletionPending FROM study_review_cards WHERE id = 'card-7'",
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(54321L, rows.getLong("reminderAt"))
                assertEquals(1, rows.getInt("reminderEnabled"))
                assertEquals(0L, rows.getLong("reminderGeneration"))
                assertEquals(1, rows.getInt("notificationCleanupPending"))
                assertEquals(0, rows.getInt("deletionPending"))
            }
            statement.executeQuery("SELECT name, deletionPending FROM projects WHERE id = 3").use { rows ->
                assertTrue(rows.next())
                assertEquals("preserve", rows.getString("name"))
                assertEquals(0, rows.getInt("deletionPending"))
            }
        }
    }

    @Test
    fun `v29 to v30 creates empty recovery cursor table and preserves existing rows`() {
        AppDatabase.Migration26To27Sql.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        AppDatabase.Migration27To28Sql.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        AppDatabase.Migration28To29Sql.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        AppDatabase.Migration29To30Sql.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT reminderAt, reminderEnabled FROM project_tasks WHERE id = 7").use { rows ->
                assertTrue(rows.next())
                assertEquals(12345L, rows.getLong("reminderAt"))
                assertEquals(1, rows.getInt("reminderEnabled"))
            }
            statement.executeQuery("SELECT COUNT(*) FROM reminder_recovery_cursors").use { rows ->
                assertTrue(rows.next())
                assertEquals(0, rows.getInt(1))
            }
        }
    }
}
