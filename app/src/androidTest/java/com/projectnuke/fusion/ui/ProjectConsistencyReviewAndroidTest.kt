package com.projectnuke.fusion.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.projects.ProjectConsistencyIssueKind
import com.projectnuke.fusion.audit.UnavailableVerifierBackend
import com.projectnuke.fusion.audit.AuditVerificationLabel
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierBackendAvailability
import com.projectnuke.fusion.audit.VerifierBackendDescriptor
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.DisabledSymmetricEmbeddingSource
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectConsistencyReviewAndroidTest {
    @Test
    fun reviewUsesRecordedSupersedesLinksFromTheCurrentProject() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val projectId = database.projectDao().insertProject(ProjectEntity(name = "HBM", createdAt = 1, updatedAt = 1))
            database.projectDecisionDao().insert(
                ProjectDecisionEntity(
                    id = "old", projectId = projectId, title = "Cache policy", rationale = "Cache is enabled.",
                    status = "SUPERSEDED", createdAt = 2, updatedAt = 2,
                ),
            )
            database.projectDecisionDao().insert(
                ProjectDecisionEntity(
                    id = "current", projectId = projectId, title = "Cache policy", rationale = "Cache is disabled.",
                    status = "ACTIVE", supersedesDecisionId = "old", createdAt = 3, updatedAt = 3,
                ),
            )

            val report = ProjectConsistencyReviewRepository(
                context, database, DisabledSymmetricEmbeddingSource, UnavailableVerifierBackend,
            ).review(projectId, includeArchived = false)

            assertEquals(2, report.sourceCount)
            assertEquals(ProjectConsistencyIssueKind.SUPERSEDED_REFERENCE, report.issues.single().kind)
        } finally {
            database.close()
        }
    }

    @Test
    fun archivedConversationKnowledgeIsWithheldUntilAccessIsIncluded() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val projectId = database.projectDao().insertProject(ProjectEntity(name = "Archive boundary", createdAt = 1, updatedAt = 1))
            val conversationId = database.chatDao().insertConversation(
                ConversationEntity(title = "Archived summary", isArchived = true, createdAt = 2, updatedAt = 2),
            )
            database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectId, assignedAt = 3))
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = "archived-knowledge", scope = "PROJECT", projectId = projectId,
                    title = "Archived finding", savedText = "Archived text stays private while locked.",
                    provenanceType = "ASSISTANT_ANSWER", sourceConversationId = conversationId,
                    sourceMessageId = null, createdAt = 4, updatedAt = 4,
                ),
            )
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = "visible-knowledge", scope = "PROJECT", projectId = projectId,
                    title = "Visible finding", savedText = "Visible content is available in this Project.",
                    provenanceType = "ASSISTANT_ANSWER", sourceConversationId = null,
                    sourceMessageId = null, createdAt = 5, updatedAt = 5,
                ),
            )
            val embeddings = RecordingEmbeddings()
            val verifier = RecordingVerifier()
            val repository = ProjectConsistencyReviewRepository(context, database, embeddings, verifier)

            val locked = repository.review(projectId, includeArchived = false)
            assertEquals(1, locked.sourceCount)
            assertTrue(embeddings.batches.isEmpty())
            assertTrue(verifier.requests.isEmpty())
            val unlocked = repository.review(projectId, includeArchived = true)

            assertEquals(2, unlocked.sourceCount)
            assertTrue(unlocked.issues.isEmpty())
            assertEquals(2, embeddings.batches.single().size)
            assertTrue(embeddings.batches.single().any { it.contains("Archived text stays private") })
            assertEquals(2, verifier.requests.size)
        } finally {
            database.close()
        }
    }

    private class RecordingEmbeddings : SymmetricEmbeddingSource {
        val batches = mutableListOf<List<String>>()
        override suspend fun embedSources(texts: List<String>): EmbeddingResult {
            batches += texts.toList()
            return EmbeddingResult.Success(texts.map { floatArrayOf(1f, 0f) }, 2)
        }
    }

    private class RecordingVerifier : VerifierBackend {
        override val descriptor = VerifierBackendDescriptor(
            id = "test", version = "1", supportedLanguages = setOf("ko", "en"), experimental = true,
            availability = VerifierBackendAvailability.AVAILABLE,
        )
        val requests = mutableListOf<VerificationPairRequest>()
        override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
            requests += request
            return VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
        }
    }
}
