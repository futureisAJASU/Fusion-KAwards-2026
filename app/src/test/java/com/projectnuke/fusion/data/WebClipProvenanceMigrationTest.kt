package com.projectnuke.fusion.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebClipProvenanceMigrationTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `v30 migration adds nullable effective source columns without inventing legacy origins`() {
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE inbox_items (id TEXT NOT NULL PRIMARY KEY, originalUri TEXT)")
            statement.execute("CREATE TABLE saved_knowledge (id TEXT NOT NULL PRIMARY KEY, sourceUrl TEXT)")
            statement.execute("INSERT INTO inbox_items(id, originalUri) VALUES ('inbox-old', 'https://entered.example/old')")
            statement.execute("INSERT INTO saved_knowledge(id, sourceUrl) VALUES ('saved-old', 'https://entered.example/old')")
        }

        AppDatabase.Migration30To31Sql.statements.forEach { sql ->
            connection.createStatement().use { it.execute(sql) }
        }

        assertTrue(columns("inbox_items").containsAll(setOf("originalUri", "effectiveSourceUri")))
        assertTrue(columns("saved_knowledge").containsAll(setOf("sourceUrl", "effectiveSourceUrl")))
        assertFalse(columnIsNotNull("inbox_items", "effectiveSourceUri"))
        assertFalse(columnIsNotNull("saved_knowledge", "effectiveSourceUrl"))
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT originalUri, effectiveSourceUri FROM inbox_items WHERE id = 'inbox-old'").use { rows ->
                assertTrue(rows.next())
                assertEquals("https://entered.example/old", rows.getString("originalUri"))
                assertNull(rows.getString("effectiveSourceUri"))
            }
            statement.executeQuery("SELECT sourceUrl, effectiveSourceUrl FROM saved_knowledge WHERE id = 'saved-old'").use { rows ->
                assertTrue(rows.next())
                assertEquals("https://entered.example/old", rows.getString("sourceUrl"))
                assertNull(rows.getString("effectiveSourceUrl"))
            }
        }
    }

    private fun columns(table: String): Set<String> = buildSet {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                while (rows.next()) add(rows.getString("name"))
            }
        }
    }

    private fun columnIsNotNull(table: String, column: String): Boolean {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                while (rows.next()) {
                    if (rows.getString("name") == column) return rows.getInt("notnull") != 0
                }
            }
        }
        error("Missing column $table.$column")
    }
}
