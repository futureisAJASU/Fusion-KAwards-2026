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

/**
 * Phase F: JVM-side replay of Room v3→v4 benchmark_results migration.
 *
 * Uses [AppDatabase.BenchmarkResultsMigration3To4Sql] to replay the
 * migration against an in-memory SQLite database (sqlite-jdbc, no
 * Android framework needed).
 *
 * Tests:
 *  1. The list of migration statements is structurally correct
 *     (2 RENAMEs, 10 ADD COLUMNs, 1 UPDATE for initializedWithMtp).
 *  2. A row with mtpRequested=1 AND mtpStatus='INITIALIZED_WITH_MTP_REQUEST'
 *     derives initializedWithMtp=1 after migration.
 *  3. A row with mtpRequested=1 AND mtpStatus='OFF' derives 0.
 *  4. A row with mtpRequested=0 derives 0.
 *  5. Full v3→v4 round-trip: insert v3 rows, migrate, insert/read v4 row.
 */
class RepairPhaseFMigrationRoundTripTest {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")
            // v3 schema for benchmark_results (from schemas/3.json)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS benchmark_results (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "modelName TEXT NOT NULL, " +
                    "modelPath TEXT, " +
                    "accelerator TEXT NOT NULL, " +
                    "actualBackend TEXT, " +
                    "mtpEnabled INTEGER NOT NULL, " +
                    "mtpStatus TEXT NOT NULL, " +
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

    // ── Structural assertions ────────────────────────────────────────────

    @Test
    fun `F migration SQL list is non-empty and structurally correct`() {
        val sql = AppDatabase.BenchmarkResultsMigration3To4Sql
        assertNotNull(sql)
        assertTrue("migration must contain at least one statement", sql.statements.isNotEmpty())
        val renames = sql.statements.filter { it.startsWith("ALTER TABLE") && it.contains("RENAME") }
        assertEquals("must rename exactly 2 columns", 2, renames.size)
        val addColumns = sql.statements.filter { it.startsWith("ALTER TABLE") && it.contains("ADD COLUMN") }
        assertEquals("must add exactly 10 runtime snapshot columns", 10, addColumns.size)
        val updates = sql.statements.filter { it.startsWith("UPDATE") }
        assertEquals("must have exactly 1 UPDATE for deriving initializedWithMtp", 1, updates.size)
        assertTrue(
            "Phase F regression: UPDATE must contain initializedWithMtp derivation",
            updates[0].contains("initializedWithMtp") &&
                updates[0].contains("mtpRequested") &&
                updates[0].contains("mtpStatus")
        )
    }

    @Test
    fun `F migration SQL list adds all required v4 columns`() {
        val addedColumnNames = AppDatabase.BenchmarkResultsMigration3To4Sql.statements
            .filter { it.startsWith("ALTER TABLE") && it.contains("ADD COLUMN") }
            .map { it.substringAfter("ADD COLUMN ").substringBefore(" ") }
            .toSet()
        val required = setOf(
            "selectedVisionBackend", "samplerBackend", "fallbackEventCodes",
            "initializedWithMtp",
            "nativeTtftSeconds", "nativePrefillTokensPerSecond",
            "nativeDecodeTokensPerSecond", "nativePrefillTokenCount",
            "nativeDecodeTokenCount", "nativeInitTimeSeconds"
        )
        assertEquals(required, addedColumnNames)
    }

    // ── Derivation of initializedWithMtp ─────────────────────────────────

    @Test
    fun testMtpRequestedWithInitStatusDerivesOne() {
        insertV3Row(1L, "m1", "/m1", "GPU", 1, "INITIALIZED_WITH_MTP_REQUEST")
        runMigration()
        val result = connection.createStatement().executeQuery(
            "SELECT initializedWithMtp FROM benchmark_results WHERE id = 1"
        )
        assertTrue(result.next())
        assertEquals(1, result.getInt("initializedWithMtp"))
        result.close()
    }

    @Test
    fun testMtpRequestedWithOffStatusDerivesZero() {
        insertV3Row(1L, "m1", "/m1", "GPU", 1, "OFF")
        runMigration()
        val result = connection.createStatement().executeQuery(
            "SELECT initializedWithMtp FROM benchmark_results WHERE id = 1"
        )
        assertTrue(result.next())
        assertEquals(0, result.getInt("initializedWithMtp"))
        result.close()
    }

    @Test
    fun testMtpNotRequestedDerivesZero() {
        insertV3Row(1L, "m1", "/m1", "GPU", 0, "OFF")
        runMigration()
        val result = connection.createStatement().executeQuery(
            "SELECT initializedWithMtp FROM benchmark_results WHERE id = 1"
        )
        assertTrue(result.next())
        assertEquals(0, result.getInt("initializedWithMtp"))
        result.close()
    }

    @Test
    fun testMtpRequestedWithInitFailedDerivesZero() {
        insertV3Row(1L, "m1", "/m1", "GPU", 1, "MTP_ENGINE_INIT_FAILED")
        runMigration()
        val result = connection.createStatement().executeQuery(
            "SELECT initializedWithMtp FROM benchmark_results WHERE id = 1"
        )
        assertTrue(result.next())
        assertEquals(0, result.getInt("initializedWithMtp"))
        result.close()
    }

    // ── Round-trip: v3 insert → migration → v4 read → v4 insert → read ──

    @Test
    fun testV3ToV4RoundTripPreservesDataAndAcceptsV4Inserts() {
        insertV3Row(1L, "qwen3-0_6b", "/models/qwen.litertlm", "GPU", 1, "INITIALIZED_WITH_MTP_REQUEST")
        insertV3Row(2L, "llama-3_2-1b", "/models/llama.litertlm", "CPU", 0, "OFF")

        runMigration()

        // Verify v4 column names exist and v3 columns are gone.
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info(benchmark_results)")
            val colSet = mutableSetOf<String>()
            while (rs.next()) colSet.add(rs.getString("name"))
            assertTrue(colSet.contains("selectedTextBackend"))
            assertTrue(colSet.contains("mtpRequested"))
            assertTrue(!colSet.contains("actualBackend"))
            assertTrue(!colSet.contains("mtpEnabled"))
            assertTrue(colSet.contains("initializedWithMtp"))
            assertTrue(colSet.contains("samplerBackend"))
        }

        // Read migrated rows.
        val r1 = querySingle("SELECT selectedTextBackend, mtpRequested, mtpStatus, samplerBackend, initializedWithMtp FROM benchmark_results WHERE id = 1")
        assertEquals("GPU", r1.getString("selectedTextBackend"))
        assertEquals(1, r1.getInt("mtpRequested"))
        assertEquals("INITIALIZED_WITH_MTP_REQUEST", r1.getString("mtpStatus"))
        assertEquals("UNKNOWN", r1.getString("samplerBackend"))
        assertEquals(1, r1.getInt("initializedWithMtp"))

        val r2 = querySingle("SELECT selectedTextBackend, mtpRequested, mtpStatus, initializedWithMtp FROM benchmark_results WHERE id = 2")
        assertEquals("CPU", r2.getString("selectedTextBackend"))
        assertEquals(0, r2.getInt("mtpRequested"))
        assertEquals("OFF", r2.getString("mtpStatus"))
        assertEquals(0, r2.getInt("initializedWithMtp"))

        // Insert a new row using v4 columns (DAO round-trip).
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                "INSERT INTO benchmark_results (" +
                    "createdAt, modelName, modelPath, accelerator, selectedTextBackend, selectedVisionBackend, " +
                    "samplerBackend, mtpRequested, mtpStatus, fallbackEventCodes, initializedWithMtp, " +
                    "nativeTtftSeconds, nativePrefillTokensPerSecond, nativeDecodeTokensPerSecond, " +
                    "nativePrefillTokenCount, nativeDecodeTokenCount, nativeInitTimeSeconds, " +
                    "maxTokens, temperature, topK, topP, reasoningEnabled, webSearchEnabled, " +
                    "promptLabel, promptText, modelLoadingMs, firstTokenLatencyMs, totalGenerationMs, " +
                    "estimatedOutputTokens, totalTokensPerSecond, decodeTokensPerSecond, success, " +
                    "errorMessage, appVersion, deviceModel, androidVersion" +
                    ") VALUES (" +
                    "3000, 'phi-4-mini-instruct', '/models/phi.tflite', 'GPU', 'CPU_fallback', 'TFLITE_WEB_GPU', " +
                    "'CPU', 1, 'MTP_UNSUPPORTED', '[\"MTP_REQUESTED\"]', 0, " +
                    "0.15, 512.0, 128.0, 512, 128, 2.5, " +
                    "2048, 0.7, 40, 0.9, 0, 0, " +
                    "'test-label', 'test-prompt', 100, 50, 2000, " +
                    "2048, 20.0, 15.0, 1, " +
                    "'test-error', '1.0.0', 'test-device', '34'" +
                    ")"
            )
        }

        val r3 = querySingle("SELECT selectedTextBackend, modelName, selectedVisionBackend, samplerBackend, mtpRequested, mtpStatus, fallbackEventCodes, initializedWithMtp, nativeTtftSeconds, nativePrefillTokensPerSecond, nativeDecodeTokensPerSecond, nativePrefillTokenCount, nativeDecodeTokenCount, nativeInitTimeSeconds FROM benchmark_results WHERE id = 3")
        assertEquals("CPU_fallback", r3.getString("selectedTextBackend"))
        assertEquals("phi-4-mini-instruct", r3.getString("modelName"))
        assertEquals("TFLITE_WEB_GPU", r3.getString("selectedVisionBackend"))
        assertEquals("CPU", r3.getString("samplerBackend"))
        assertEquals(1, r3.getInt("mtpRequested"))
        assertEquals("MTP_UNSUPPORTED", r3.getString("mtpStatus"))
        assertEquals("[\"MTP_REQUESTED\"]", r3.getString("fallbackEventCodes"))
        assertEquals(0, r3.getInt("initializedWithMtp"))
        assertEquals(0.15, r3.getDouble("nativeTtftSeconds"), 0.001)
        assertEquals(512.0, r3.getDouble("nativePrefillTokensPerSecond"), 0.001)
        assertEquals(128.0, r3.getDouble("nativeDecodeTokensPerSecond"), 0.001)
        assertEquals(512, r3.getInt("nativePrefillTokenCount"))
        assertEquals(128, r3.getInt("nativeDecodeTokenCount"))
        assertEquals(2.5, r3.getDouble("nativeInitTimeSeconds"), 0.001)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun insertV3Row(
        id: Long,
        modelName: String,
        modelPath: String?,
        accelerator: String,
        mtpEnabled: Int,
        mtpStatus: String
    ) {
        val sql = "INSERT OR REPLACE INTO benchmark_results " +
            "(id, createdAt, modelName, modelPath, accelerator, actualBackend, " +
            "mtpEnabled, mtpStatus, maxTokens, temperature, topK, topP, " +
            "reasoningEnabled, webSearchEnabled, promptLabel, promptText, " +
            "modelLoadingMs, firstTokenLatencyMs, totalGenerationMs, " +
            "estimatedOutputTokens, totalTokensPerSecond, decodeTokensPerSecond, " +
            "success, errorMessage, appVersion, deviceModel, androidVersion) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        connection.prepareStatement(sql).use { ps ->
            ps.setLong(1, id)
            ps.setLong(2, id + 9000)
            ps.setString(3, modelName)
            ps.setString(4, if (modelPath != null) modelPath else null)
            ps.setString(5, accelerator)
            ps.setString(6, accelerator)
            ps.setInt(7, mtpEnabled)
            ps.setString(8, mtpStatus)
            ps.setInt(9, 4096)
            ps.setFloat(10, 1.0f)
            ps.setInt(11, 64)
            ps.setFloat(12, 0.95f)
            ps.setInt(13, 0)
            ps.setInt(14, 0)
            ps.setString(15, "test")
            ps.setString(16, "prompt-$id")
            ps.setNull(17, java.sql.Types.INTEGER)
            ps.setNull(18, java.sql.Types.INTEGER)
            ps.setLong(19, 2000)
            ps.setInt(20, 100)
            ps.setFloat(21, 10.0f)
            ps.setFloat(22, 20.0f)
            ps.setInt(23, 1)
            ps.setNull(24, java.sql.Types.VARCHAR)
            ps.setString(25, "1.0.0")
            ps.setString(26, "test-device")
            ps.setString(27, "32")
            ps.executeUpdate()
        }
    }

    private fun runMigration() {
        for (statement in AppDatabase.BenchmarkResultsMigration3To4Sql.statements) {
            connection.createStatement().use { stmt -> stmt.execute(statement) }
        }
    }

    private fun querySingle(sql: String): ResultSet {
        val stmt = connection.createStatement()
        return stmt.executeQuery(sql).also {
            assertTrue("Query returned no rows: $sql", it.next())
        }
    }
}