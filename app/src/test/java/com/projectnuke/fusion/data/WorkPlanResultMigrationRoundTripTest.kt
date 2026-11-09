package com.projectnuke.fusion.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM replay of the v24 to v25 migration against real SQLite. */
class WorkPlanResultMigrationRoundTripTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("CREATE TABLE projects (id INTEGER PRIMARY KEY NOT NULL)")
            statement.execute("CREATE TABLE project_work_plans (id TEXT PRIMARY KEY NOT NULL, projectId INTEGER NOT NULL)")
            statement.execute("CREATE TABLE project_work_plan_steps (id TEXT PRIMARY KEY NOT NULL, planId TEXT NOT NULL)")
            statement.executeUpdate("INSERT INTO projects (id) VALUES (1)")
            statement.executeUpdate("INSERT INTO project_work_plans (id, projectId) VALUES ('plan-1', 1)")
            statement.executeUpdate("INSERT INTO project_work_plan_steps (id, planId) VALUES ('step-1', 'plan-1')")
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `migration SQL is a non-empty single-statement list`() {
        val sql = AppDatabase.Migration24To25Sql
        assertTrue(sql.statements.isNotEmpty())
        assertTrue(sql.statements.any { it.contains("CREATE TABLE") && it.contains("project_work_plan_result_sources") })
        assertTrue(sql.statements.count { it.contains("CREATE INDEX") } == 3)
    }

    @Test
    fun `migration creates the provenance table with the declared columns`() {
        execute(AppDatabase.Migration24To25Sql.statements)
        assertEquals(
            setOf(
                "resultId", "planId", "stepId", "attemptId", "projectId", "stepType",
                "resultKind", "extractionType", "sourcesJson", "createdAt",
            ),
            columns("project_work_plan_result_sources"),
        )
    }

    @Test
    fun `provenance rows round trip and legacy rows survive`() {
        execute(AppDatabase.Migration24To25Sql.statements)
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO project_work_plan_result_sources " +
                    "(resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) " +
                    "VALUES ('x:abc', 'plan-1', 'step-1', 'attempt-1', 1, 'STRUCTURED_EXTRACT', 'EXTRACTION', 'SPECIFICATIONS', '[]', 5)",
            )
            statement.executeQuery("SELECT extractionType, sourcesJson FROM project_work_plan_result_sources WHERE resultId = 'x:abc'").use { rows ->
                assertTrue(rows.next())
                assertEquals("SPECIFICATIONS", rows.getString("extractionType"))
                assertEquals("[]", rows.getString("sourcesJson"))
            }
            // Duplicate result ids are rejected; existing rows survive.
            var duplicateRejected = false
            try {
                statement.executeUpdate(
                    "INSERT INTO project_work_plan_result_sources " +
                        "(resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) " +
                        "VALUES ('x:abc', 'plan-1', 'step-1', 'attempt-1', 1, 'STRUCTURED_EXTRACT', 'EXTRACTION', 'TABLE', '[]', 6)",
                )
            } catch (_: java.sql.SQLException) {
                duplicateRejected = true
            }
            assertTrue(duplicateRejected)
            statement.executeQuery("SELECT COUNT(*) FROM project_work_plans").use { rows ->
                assertTrue(rows.next())
                assertEquals(1, rows.getInt(1))
            }
        }
    }

    @Test
    fun `v25 to v26 preserves every owned logical row and intentionally prunes owner orphans`() {
        execute(AppDatabase.Migration24To25Sql.statements)
        insertV25Result("fingerprint-1", "plan-1", "step-1", "attempt-1", "selection")
        insertV25Result("fingerprint-2", "plan-1", "step-1", "attempt-2", "selection")
        insertV25Result("orphan-plan", "missing-plan", "step-1", "attempt-3", "orphan")
        insertV25Result("orphan-step", "plan-1", "missing-step", "attempt-4", "orphan")

        execute(AppDatabase.Migration25To26Sql.statements)

        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT attemptId, resultId, sourcesJson FROM project_work_plan_result_sources ORDER BY attemptId"
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals("attempt-1", rows.getString("attemptId"))
                assertEquals("fingerprint-1", rows.getString("resultId"))
                assertEquals("selection", rows.getString("sourcesJson"))
                assertTrue(rows.next())
                assertEquals("attempt-2", rows.getString("attemptId"))
                assertEquals("fingerprint-2", rows.getString("resultId"))
                assertEquals("selection", rows.getString("sourcesJson"))
                assertTrue(!rows.next())
            }
            statement.executeQuery("PRAGMA foreign_key_check").use { rows -> assertTrue(!rows.next()) }
            statement.executeQuery("PRAGMA table_info(project_work_plan_result_sources)").use { rows ->
                var primaryKey = ""
                while (rows.next()) if (rows.getInt("pk") == 1) primaryKey = rows.getString("name")
                assertEquals("attemptId", primaryKey)
            }
        }
        assertMigratedResultSourceMatchesExportedSchema26()
    }

    @Test
    fun `duplicate v25 attempt IDs fail migration atomically without discarding source rows`() {
        execute(AppDatabase.Migration24To25Sql.statements)
        insertV25Result("result-1", "plan-1", "step-1", "duplicate-attempt", "first")
        insertV25Result("result-2", "plan-1", "step-1", "duplicate-attempt", "second")
        connection.autoCommit = false
        var failed = false
        try {
            AppDatabase.Migration25To26Sql.statements.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }
        } catch (_: java.sql.SQLException) {
            failed = true
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
        assertTrue("duplicate attempt IDs must not silently drop rows", failed)
        assertEquals(2, countRows("project_work_plan_result_sources"))
        assertEquals(setOf("result-1", "result-2"), resultIds())
        assertTrue(!tableExists("project_work_plan_result_sources_new"))
    }

    private fun insertV25Result(resultId: String, planId: String, stepId: String, attemptId: String, sourcesJson: String) {
        connection.prepareStatement(
            "INSERT INTO project_work_plan_result_sources " +
                "(resultId, planId, stepId, attemptId, projectId, stepType, resultKind, extractionType, sourcesJson, createdAt) " +
                "VALUES (?, ?, ?, ?, 1, 'REVIEW_KNOWLEDGE', 'SELECTION', NULL, ?, 10)"
        ).use { statement ->
            statement.setString(1, resultId)
            statement.setString(2, planId)
            statement.setString(3, stepId)
            statement.setString(4, attemptId)
            statement.setString(5, sourcesJson)
            statement.executeUpdate()
        }
    }

    private fun countRows(table: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) }
    }

    private fun resultIds(): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT resultId FROM project_work_plan_result_sources").use { rows ->
            buildSet { while (rows.next()) add(rows.getString(1)) }
        }
    }

    private fun tableExists(name: String): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { rows ->
            rows.next(); rows.getInt(1) > 0
        }
    }

    private fun assertMigratedResultSourceMatchesExportedSchema26() {
        val schemaFile = listOf(
            java.io.File("schemas/com.projectnuke.fusion.data.AppDatabase/26.json"),
            java.io.File("app/schemas/com.projectnuke.fusion.data.AppDatabase/26.json"),
        ).firstOrNull { it.isFile } ?: error("exported schema 26 missing")
        val schema = org.json.JSONObject(schemaFile.readText(Charsets.UTF_8))
            .getJSONObject("database").getJSONArray("entities")
        val entity = (0 until schema.length()).asSequence().map { schema.getJSONObject(it) }
            .single { it.getString("tableName") == "project_work_plan_result_sources" }
        val expectedColumns = entity.getJSONArray("fields")
        val schemaColumns = (0 until expectedColumns.length()).map {
            expectedColumns.getJSONObject(it).getString("columnName")
        }.toSet()
        assertEquals(schemaColumns, columns("project_work_plan_result_sources"))
        assertEquals(
            entity.getJSONObject("primaryKey").getJSONArray("columnNames").getString(0),
            primaryKey("project_work_plan_result_sources"),
        )
        val expectedIndexNames = entity.getJSONArray("indices").let { indices ->
            (0 until indices.length()).map { indices.getJSONObject(it).getString("name") }.toSet()
        }
        assertEquals(expectedIndexNames, indexNames("project_work_plan_result_sources"))
        assertTrue(entity.getJSONArray("foreignKeys").length() == 2)
    }

    private fun primaryKey(table: String): String = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { rows ->
            var primary = ""
            while (rows.next()) if (rows.getInt("pk") == 1) primary = rows.getString("name")
            primary
        }
    }

    private fun indexNames(table: String): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA index_list($table)").use { rows ->
            buildSet {
                while (rows.next()) {
                    val name = rows.getString("name")
                    if (!name.startsWith("sqlite_autoindex_")) add(name)
                }
            }
        }
    }

    @Test
    fun `migration result matches Room exported 25_json contract when present`() {
        execute(AppDatabase.Migration24To25Sql.statements)
        val candidates = listOf(
            java.io.File("schemas/com.projectnuke.fusion.data.AppDatabase/25.json"),
            java.io.File("app/schemas/com.projectnuke.fusion.data.AppDatabase/25.json"),
        )
        val schemaFile = candidates.firstOrNull { it.isFile } ?: return
        val root = org.json.JSONObject(schemaFile.readText())
        assertEquals(25, root.getJSONObject("database").getInt("version"))
        val entities = root.getJSONObject("database").getJSONArray("entities")
        val names = mutableSetOf<String>()
        for (index in 0 until entities.length()) {
            names.add(entities.getJSONObject(index).getString("tableName"))
        }
        assertTrue(names.contains("project_work_plan_result_sources"))
        assertTrue(names.contains("project_work_plans"))
        assertTrue(names.contains("project_work_plan_steps"))
    }

    private fun execute(statements: List<String>) {
        statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun columns(table: String): Set<String> {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                val cols = mutableSetOf<String>()
                while (rows.next()) cols.add(rows.getString("name"))
                return cols
            }
        }
    }
}
