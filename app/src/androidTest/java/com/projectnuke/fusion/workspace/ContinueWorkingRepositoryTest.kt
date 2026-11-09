package com.projectnuke.fusion.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinueWorkingRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `bounded shortcut projection excludes completed tasks and sorts recent local work`() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "Project", createdAt = 1, updatedAt = 10))
        database.projectTaskDao().insert(ProjectTaskEntity(projectId = projectId, title = "Open task", note = "", createdAt = 1, updatedAt = 30))
        database.projectTaskDao().insert(ProjectTaskEntity(projectId = projectId, title = "Done task", note = "", state = "DONE", createdAt = 1, updatedAt = 50))
        database.inboxItemDao().insert(InboxItemEntity("inbox-1", "NOTE", "Recent capture", "text", null, null, null, null, 1, 40, "READY"))

        val result = ContinueWorkingRepository(database).load()
        assertEquals(listOf("inbox:inbox-1", "task:1", "project:$projectId"), result.map { it.stableId })
        assertEquals(3, result.size)
    }
}
