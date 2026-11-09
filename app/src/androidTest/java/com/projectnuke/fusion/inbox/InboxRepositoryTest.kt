package com.projectnuke.fusion.inbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun duplicateExternalEventIsIdempotentAndConflictingReuseFails() = runBlocking {
        val repository = InboxRepository(database)
        val capture = InboxCapture(
            type = InboxCaptureType.SHARED_TEXT,
            title = "shared",
            content = "useful text",
            sourceHandoffEventId = "event-1",
        )

        assertEquals(InboxWriteResult.Stored("event-1"), repository.capture(capture, now = 1, id = "event-1"))
        assertEquals(InboxWriteResult.Stored("event-1"), repository.capture(capture, now = 2, id = "event-1"))
        assertEquals(1, database.inboxItemDao().count())
        assertEquals(
            InboxWriteResult.Failed,
            repository.capture(capture.copy(content = "different"), now = 3, id = "event-1"),
        )
        assertEquals("useful text", repository.get("event-1")?.content)
    }
}
