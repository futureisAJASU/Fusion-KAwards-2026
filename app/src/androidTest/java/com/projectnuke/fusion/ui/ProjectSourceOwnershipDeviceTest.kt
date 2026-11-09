package com.projectnuke.fusion.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectSourceOwnershipDeviceTest {
    @Test fun removedProjectRecordCannotReuseAStillReadableManagedFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val source = File.createTempFile("scope-ownership-", ".txt", AttachmentStorageManager.getAttachmentDirectory(context))
        try {
            source.writeText("An independently retained readable document.")
            val project = db.projectDao().insertProject(ProjectEntity(name = "fixture", createdAt = 0, updatedAt = 0))
            val id = db.projectDao().insertProjectFile(ProjectFileEntity(projectId = project, displayName = source.name,
                mimeType = "text/plain", localPath = source.canonicalPath, addedAt = 0))
            val captured = ProjectContextSnapshot(project, "", listOf(ProjectFileSnapshot(id, source.name, "text/plain", source.canonicalPath)))
            val selected = ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, project, listOf(id))
            val owner = ProjectKnowledgeController(context, db)
            assertTrue(owner.valid(selected, captured))
            db.projectDao().deleteProjectFile(id)
            assertTrue(source.isFile)
            assertFalse(owner.valid(selected, captured))
            assertFalse(owner.valid(ProjectSourceScope(ProjectSourceScopeKind.PROJECT, project), captured))
            db.projectDao().deleteProject(project)
            assertFalse(owner.valid(selected, captured))
        } finally { source.delete(); db.close() }
    }
}
