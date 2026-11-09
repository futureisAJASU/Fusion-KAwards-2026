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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EvidenceMigrationAndroidTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    var helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation,
        AppDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun `migrate v4 to v5 creates message_evidence table`() {
        val db = helper.createDatabase("fusion-evidence-test-db", 4)

        // Create v4 schema with conversations, messages, benchmark_results
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "isPinned INTEGER NOT NULL DEFAULT 0, " +
                "isArchived INTEGER NOT NULL DEFAULT 0" +
                ")"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "conversationId INTEGER NOT NULL, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL" +
                ")"
        )
        db.execSQL(
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

        // Insert a message and conversation at v4
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (1, 'Test', 1000, 1000, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (1, 1, 'assistant', 'Hello', 1000)"
        )

        // Close and migrate to v5
        db.close()
        val migratedDb = helper.runMigrationsAndValidate("fusion-evidence-test-db", 5, true, AppDatabase.Migration4To5)
        migratedDb.close()

        // Open through production AppDatabase
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val appDb = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "fusion-evidence-test-db"
        ).addMigrations(*appDatabaseMigrationsForTest()).build()

        // Verify messages survived
        val messages = runBlocking { appDb.chatDao().getMessagesForConversation(1) }
        assertEquals(1, messages.size)

        // Verify evidenceDao is accessible
        val evidenceDao = appDb.evidenceDao()
        assertNotNull(evidenceDao)

        // Insert evidence through DAO
        val evidence = MessageEvidenceEntity(
            messageId = 1,
            answerSha256 = "a".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1000L,
            reportJson = "{}"
        )
        runBlocking { evidenceDao.insertEvidence(evidence) }

        // Load evidence
        val loaded = runBlocking { evidenceDao.getEvidenceForMessage(1) }
        assertNotNull(loaded)
        assertEquals(1, loaded!!.schemaVersion)

        // Verify conversation-scoped retrieval
        val conversationEvidence = runBlocking { evidenceDao.getEvidenceForConversation(1) }
        assertEquals(1, conversationEvidence.size)

        // Close and reopen to verify persistence
        appDb.close()
        val reopenedDb = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "fusion-evidence-test-db"
        ).addMigrations(*appDatabaseMigrationsForTest()).build()

        val reopenedEvidence = runBlocking { reopenedDb.evidenceDao().getEvidenceForMessage(1) }
        assertNotNull(reopenedEvidence)

        reopenedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun `v4 to v5 migration preserves existing data and adds evidence table`() {
        val db = helper.createDatabase("fusion-preserve-test", 4)

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "isPinned INTEGER NOT NULL DEFAULT 0, " +
                "isArchived INTEGER NOT NULL DEFAULT 0" +
                ")"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "conversationId INTEGER NOT NULL, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL" +
                ")"
        )
        db.execSQL(
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

        // Insert legacy benchmark row
        db.execSQL(
            "INSERT INTO benchmark_results (createdAt, modelName, modelPath, accelerator, selectedTextBackend, selectedVisionBackend, samplerBackend, mtpRequested, mtpStatus, fallbackEventCodes, initializedWithMtp, maxTokens, temperature, topK, topP, reasoningEnabled, webSearchEnabled, promptLabel, promptText, modelLoadingMs, firstTokenLatencyMs, totalGenerationMs, estimatedOutputTokens, totalTokensPerSecond, decodeTokensPerSecond, success, errorMessage, appVersion, deviceModel, androidVersion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                1000L, "test-model", "/path/model", "GPU", "CPU", null, "CPU", 0, "OFF", null, 0,
                4096, 1.0f, 64, 0.95f, 0, 0, "test", "prompt", null, null, 2000, 100, 10.0f, 20.0f, 1, null, "1.0.0", "test-device", "32"
            )
        )

        db.close()

        val migratedDb = helper.runMigrationsAndValidate("fusion-preserve-test", 5, true, AppDatabase.Migration4To5)
        migratedDb.close()

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val appDb = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "fusion-preserve-test"
        ).addMigrations(*appDatabaseMigrationsForTest()).build()

        val legacyRows = runBlocking { appDb.benchmarkDao().observeAll().first() as List<BenchmarkResultEntity> }
        assertEquals(1, legacyRows.size)
        assertEquals("test-model", legacyRows[0].modelName)

        appDb.close()
    }
}
