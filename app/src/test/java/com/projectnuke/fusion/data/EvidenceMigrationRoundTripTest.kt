package com.projectnuke.fusion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class EvidenceMigrationRoundTripTest {

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
                "CREATE TABLE IF NOT EXISTS benchmark_results (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "modelName TEXT NOT NULL, " +
                    "modelPath TEXT, " +
                    "accelerator TEXT NOT NULL, " +
                    "selectedTextBackend TEXT, " +
                    "selectedVisionBackend TEXT, " +
                    "samplerBackend TEXT NOT NULL DEFAULT 'UNKNOWN', " +
                    "mtpRequested INTEGER NOT NULL, " +
                    "mtpStatus TEXT NOT NULL, " +
                    "fallbackEventCodes TEXT, " +
                    "initializedWithMtp INTEGER NOT NULL DEFAULT 0, " +
                    "nativeTtftSeconds REAL, " +
                    "nativePrefillTokensPerSecond REAL, " +
                    "nativeDecodeTokensPerSecond REAL, " +
                    "nativePrefillTokenCount INTEGER, " +
                    "nativeDecodeTokenCount INTEGER, " +
                    "nativeInitTimeSeconds REAL, " +
                    "maxTokens INTEGER NOT NULL, " +
                    "temperature REAL NOT NULL, " +
                    "topK INTEGER NOT NULL, " +
                    "topP REAL NOT NULL, " +
                    "reasoningEnabled INTEGER NOT NULL, " +
                    "webSearchEnabled INTEGER NOT NULL, " +
                    "promptLabel TEXT NOT NULL, " +
                    "promptText TEXT NOT NULL, " +
                    "modelLoadingMs INTEGER, " +
                    "firstTokenLatencyMs INTEGER, " +
                    "totalGenerationMs INTEGER NOT NULL, " +
                    "estimatedOutputTokens INTEGER NOT NULL, " +
                    "totalTokensPerSecond REAL NOT NULL, " +
                    "decodeTokensPerSecond REAL, " +
                    "success INTEGER NOT NULL, " +
                    "errorMessage TEXT, " +
                    "appVersion TEXT, " +
                    "deviceModel TEXT, " +
                    "androidVersion TEXT" +
                    ")"
            )
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `migration SQL list is non-empty and structurally correct`() {
        val sql = AppDatabase.Migration4To5Sql
        assertNotNull(sql)
        assertTrue("migration must contain at least one statement", sql.statements.isNotEmpty())
        val creates = sql.statements.filter { it.trimStart().startsWith("CREATE TABLE") }
        assertEquals("must have exactly 1 CREATE TABLE for message_evidence", 1, creates.size)
        assertTrue("must create message_evidence table", creates[0].contains("message_evidence"))
        val indexStatements = sql.statements.filter { it.contains("index_message_evidence_messageId") }
        assertEquals("must have exactly 1 index statement", 1, indexStatements.size)
        assertTrue(
            indexStatements[0].contains("CREATE INDEX IF NOT EXISTS `index_message_evidence_messageId`") &&
                indexStatements[0].contains("ON `message_evidence` (`messageId`)")
        )
    }

    @Test
    fun `message_evidence table exists after migration`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'message_evidence'"
            ).use { rs ->
                assertTrue("message_evidence exists", rs.next())
            }
        }
    }

    @Test
    fun `migration creates message_evidence table with correct columns`() {
        runMigration()
        val rs = connection.createStatement().executeQuery("PRAGMA table_info(message_evidence)")
        val colSet = mutableSetOf<String>()
        while (rs.next()) colSet.add(rs.getString("name"))
        assertTrue("must have messageId", colSet.contains("messageId"))
        assertTrue("must have answerSha256", colSet.contains("answerSha256"))
        assertTrue("must have schemaVersion", colSet.contains("schemaVersion"))
        assertTrue("must have verifierVersion", colSet.contains("verifierVersion"))
        assertTrue("must have checkedAt", colSet.contains("checkedAt"))
        assertTrue("must have reportJson", colSet.contains("reportJson"))
        rs.close()
    }

    @Test
    fun `message_evidence table has correct primary key`() {
        runMigration()
        val rs = connection.createStatement().executeQuery("PRAGMA table_info(message_evidence)")
        var pkCount = 0
        var hasPrimaryKey = false
        while (rs.next()) {
            if (rs.getString("pk").toInt() > 0) {
                pkCount++
                if (rs.getString("name") == "messageId") {
                    hasPrimaryKey = true
                }
            }
        }
        rs.close()
        assertEquals(1, pkCount)
        assertTrue("messageId must be the primary key", hasPrimaryKey)
    }

    @Test
    fun `message_evidence table has foreign key to messages(id)`() {
        runMigration()
        val rs = connection.createStatement().executeQuery(
            "PRAGMA foreign_key_list(message_evidence)"
        )
        var found = false
        while (rs.next()) {
            if (rs.getString("table") == "messages" && rs.getString("from") == "messageId" && rs.getString("to") == "id") {
                found = true
            }
        }
        rs.close()
        assertTrue("must have FK referencing messages(id)", found)
    }

    @Test
    fun `cascade delete is configured`() {
        runMigration()
        val rs = connection.createStatement().executeQuery(
            "PRAGMA foreign_key_list(message_evidence)"
        )
        var onDeleteCascade = false
        while (rs.next()) {
            if (rs.getString("on_delete") == "CASCADE") {
                onDeleteCascade = true
            }
        }
        rs.close()
        assertTrue("must have ON DELETE CASCADE", onDeleteCascade)
    }

    @Test
    fun `previous rows survive migration`() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (1, 'Test', 1000, 1000, 0, 0)")
            stmt.executeUpdate("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (1, 1, 'assistant', 'Hello', 1000)")
            stmt.executeUpdate("INSERT INTO benchmark_results (createdAt, modelName, modelPath, accelerator, selectedTextBackend, selectedVisionBackend, samplerBackend, mtpRequested, mtpStatus, fallbackEventCodes, initializedWithMtp, maxTokens, temperature, topK, topP, reasoningEnabled, webSearchEnabled, promptLabel, promptText, modelLoadingMs, firstTokenLatencyMs, totalGenerationMs, estimatedOutputTokens, totalTokensPerSecond, decodeTokensPerSecond, success, errorMessage, appVersion, deviceModel, androidVersion) VALUES (1000, 'test', '/path', 'GPU', 'CPU', NULL, 'CPU', 0, 'OFF', NULL, 0, 4096, 1.0, 64, 0.95, 0, 0, 'test', 'prompt', NULL, NULL, 2000, 100, 10.0, 20.0, 1, NULL, '1.0.0', 'test-device', '32')")
        }
        runMigration()
        val msgRs = connection.createStatement().executeQuery("SELECT * FROM messages WHERE id = 1")
        assertTrue(msgRs.next())
        assertEquals("Hello", msgRs.getString("content"))
        msgRs.close()
    }

    @Test
    fun `new table is empty after migration`() {
        runMigration()
        val rs = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM message_evidence")
        assertTrue(rs.next())
        assertEquals(0, rs.getInt("cnt"))
        rs.close()
    }

    @Test
    fun `full v4_to_v5 round trip with evidence insert and message delete`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (1, 'Test', 1000, 1000, 0, 0)")
            stmt.executeUpdate("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (1, 1, 'assistant', 'Hello', 1000)")
            stmt.executeUpdate(
                "INSERT INTO message_evidence (messageId, answerSha256, schemaVersion, verifierVersion, checkedAt, reportJson) " +
                    "VALUES (1, '" + "a".repeat(64) + "', 1, '1.0.0', 1000, '{}')"
            )
        }
        val rs = connection.createStatement().executeQuery("SELECT * FROM message_evidence WHERE messageId = 1")
        assertTrue(rs.next())
        assertEquals(1, rs.getLong("messageId"))
        rs.close()
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM messages WHERE id = 1")
        }
        val rs2 = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM message_evidence")
        assertTrue(rs2.next())
        assertEquals(0, rs2.getInt("cnt"))
        rs2.close()
    }

    @Test
    fun `migration creates expected messageId index`() {
        runMigration()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_message_evidence_messageId'"
            ).use { rs ->
                assertTrue("index_message_evidence_messageId exists", rs.next())
            }
        }
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA index_list(message_evidence)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name") == "index_message_evidence_messageId") found = true
                }
                assertTrue("index listed in PRAGMA index_list", found)
            }
        }
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA index_info(index_message_evidence_messageId)").use { rs ->
                val cols = mutableListOf<String>()
                while (rs.next()) cols.add(rs.getString("name"))
                assertEquals(listOf("messageId"), cols)
            }
        }
    }

    @Test
    fun `migration result matches Room exported 5_json contract`() {
        runMigration()
        // Intentional portions of app/schemas/.../5.json for message_evidence:
        // table message_evidence, columns, PK(messageId), FK->messages(id)
        // ON DELETE CASCADE, index index_message_evidence_messageId on (messageId).
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(message_evidence)").use { rs ->
                val cols = mutableMapOf<String, Int>()
                var pkCols = mutableListOf<String>()
                while (rs.next()) {
                    cols[rs.getString("name")] = rs.getInt("pk")
                    if (rs.getInt("pk") > 0) pkCols.add(rs.getString("name"))
                }
                assertEquals(
                    setOf("messageId", "answerSha256", "schemaVersion", "verifierVersion", "checkedAt", "reportJson"),
                    cols.keys
                )
                assertEquals(listOf("messageId"), pkCols)
            }
        }
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA foreign_key_list(message_evidence)").use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("table") == "messages" &&
                        rs.getString("from") == "messageId" &&
                        rs.getString("to") == "id" &&
                        rs.getString("on_delete") == "CASCADE"
                    ) {
                        found = true
                    }
                }
                assertTrue("5.json FK contract", found)
            }
        }
        // Compare against the exported schema file when available.
        val candidates = listOf(
            java.io.File("schemas/com.projectnuke.fusion.data.AppDatabase/5.json"),
            java.io.File("app/schemas/com.projectnuke.fusion.data.AppDatabase/5.json")
        )
        val schemaFile = candidates.firstOrNull { it.isFile }
        if (schemaFile != null) {
            val root = org.json.JSONObject(schemaFile.readText())
            val entities = root.getJSONObject("database").getJSONArray("entities")
            var evidence: org.json.JSONObject? = null
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.getString("tableName") == "message_evidence") evidence = e
            }
            assertNotNull("5.json must contain message_evidence", evidence)
            val indices = evidence!!.getJSONArray("indices")
            var indexFound = false
            for (i in 0 until indices.length()) {
                val idx = indices.getJSONObject(i)
                if (idx.getString("name") == "index_message_evidence_messageId") {
                    indexFound = true
                    val cols = idx.getJSONArray("columnNames")
                    assertEquals(1, cols.length())
                    assertEquals("messageId", cols.getString(0))
                }
            }
            assertTrue("5.json must declare index_message_evidence_messageId", indexFound)
        }
    }

    // ── helpers ────────────────────────────────────────────

    private fun runMigration() {
        for (statement in AppDatabase.Migration4To5Sql.statements) {
            connection.createStatement().use { stmt -> stmt.execute(statement) }
        }
    }
}
