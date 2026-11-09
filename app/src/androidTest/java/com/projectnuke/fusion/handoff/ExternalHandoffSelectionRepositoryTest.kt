package com.projectnuke.fusion.handoff

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalHandoffSelectionRepositoryTest {
    private val databaseName = "handoff-selection-test.db"
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun selectedDestinationSurvivesDatabaseRecreationAndCannotBeChanged() = runBlocking {
        val selection = ExternalHandoffSelection("handoff-recreation", ExternalHandoffDestination.PROJECT, 42L)
        val repository = ExternalHandoffSelectionRepository(database)

        assertEquals(HandoffSelectionResult.Stored, repository.select(selection))
        database.close()

        database = openDatabase()
        val restored = ExternalHandoffSelectionRepository(database)
        assertEquals(HandoffSelectionResult.Existing, restored.select(selection))
        assertEquals("PROJECT", restored.get(selection.eventId)?.destination)
        assertEquals(42L, restored.get(selection.eventId)?.targetProjectId)
        assertEquals(
            HandoffSelectionResult.Conflict,
            restored.select(selection.copy(destination = ExternalHandoffDestination.INBOX, projectId = null)),
        )
        assertEquals(true, restored.settle(selection.eventId, success = true))
        assertNotNull(restored.get(selection.eventId)?.settledAt)
    }

    @Test
    fun partiallyImportedOutcomeSurvivesDatabaseRecreation() = runBlocking {
        val selection = ExternalHandoffSelection("handoff-partial", ExternalHandoffDestination.INBOX)
        val repository = ExternalHandoffSelectionRepository(database)
        assertEquals(HandoffSelectionResult.Stored, repository.select(selection))
        assertEquals(true, repository.settle(selection.eventId, HandoffImportOutcome.PARTIALLY_IMPORTED.name))
        database.close()

        database = openDatabase()
        val restored = ExternalHandoffSelectionRepository(database).get(selection.eventId)
        assertEquals(HandoffImportOutcome.PARTIALLY_IMPORTED.name, restored?.outcome)
        assertNotNull(restored?.settledAt)
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
}
