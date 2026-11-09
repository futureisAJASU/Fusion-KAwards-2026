package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.MAX_PROJECT_DECISIONS
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectDecisionWriteResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectDecisionDraft(
    val title: String,
    val rationale: String,
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val supersedesDecisionId: String? = null,
)

sealed interface ProjectDecisionCreateResult {
    data class Created(val decisionId: String) : ProjectDecisionCreateResult
    data class Invalid(val message: String) : ProjectDecisionCreateResult
    data object ProjectMissing : ProjectDecisionCreateResult
    data object SupersededDecisionMissing : ProjectDecisionCreateResult
    data object SupersededDecisionInactive : ProjectDecisionCreateResult
    data object CapacityReached : ProjectDecisionCreateResult
}

object ProjectDecisionPolicy {
    const val MAX_TITLE_CHARS = 200
    const val MAX_RATIONALE_CHARS = 1_000

    data class Validated(val title: String, val rationale: String)

    fun validate(draft: ProjectDecisionDraft): Validated? {
        val title = draft.title.trim().replace(Regex("\\s+"), " ")
        val rationale = draft.rationale.trim()
        if (title.isBlank() || title.length > MAX_TITLE_CHARS || rationale.length > MAX_RATIONALE_CHARS) return null
        if ((draft.sourceConversationId == null) != (draft.sourceMessageId == null)) return null
        if (draft.sourceConversationId != null && (draft.sourceConversationId <= 0L || draft.sourceMessageId!! <= 0L)) return null
        if (draft.supersedesDecisionId?.isBlank() == true) return null
        return Validated(title, rationale)
    }
}

class ProjectDecisionRepository(private val database: AppDatabase) {
    private val lineageLookup = ProjectDecisionLineageResolver { decisionId, projectId ->
        database.projectDecisionDao().get(decisionId, projectId)
    }
    fun observeRecent(projectId: Long, limit: Int = 32): Flow<List<ProjectDecisionEntity>> =
        database.projectDecisionDao().observeRecent(projectId, limit.coerceIn(1, MAX_PROJECT_DECISIONS))

    fun observeActive(projectId: Long, limit: Int = 32): Flow<List<ProjectDecisionEntity>> =
        database.projectDecisionDao().observeActive(projectId, limit.coerceIn(1, MAX_PROJECT_DECISIONS))

    suspend fun recent(projectId: Long, limit: Int = 32): List<ProjectDecisionEntity> = withContext(Dispatchers.IO) {
        require(projectId > 0L)
        database.projectDecisionDao().getRecent(projectId, limit.coerceIn(1, MAX_PROJECT_DECISIONS))
    }

    /** Durable project-bound lineage lookup; independent of the recent list window. */
    suspend fun get(decisionId: String, projectId: Long): ProjectDecisionEntity? = withContext(Dispatchers.IO) {
        require(decisionId.isNotBlank() && projectId > 0L)
        lineageLookup.resolve(decisionId, projectId)
    }

    suspend fun create(
        projectId: Long,
        draft: ProjectDecisionDraft,
        now: Long,
        id: String = UUID.randomUUID().toString(),
    ): ProjectDecisionCreateResult = withContext(Dispatchers.IO) {
        require(projectId > 0L)
        val validated = ProjectDecisionPolicy.validate(draft)
            ?: return@withContext ProjectDecisionCreateResult.Invalid("결정 내용과 출처를 확인해 주세요.")
        val result = try {
            database.projectDecisionDao().insertDecision(
                ProjectDecisionEntity(
                    id = id,
                    projectId = projectId,
                    title = validated.title,
                    rationale = validated.rationale,
                    status = "ACTIVE",
                    supersedesDecisionId = draft.supersedesDecisionId,
                    sourceConversationId = draft.sourceConversationId,
                    sourceMessageId = draft.sourceMessageId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        when (result) {
            is ProjectDecisionWriteResult.Inserted -> ProjectDecisionCreateResult.Created(result.id)
            ProjectDecisionWriteResult.ProjectMissing -> ProjectDecisionCreateResult.ProjectMissing
            ProjectDecisionWriteResult.SupersededDecisionMissing -> ProjectDecisionCreateResult.SupersededDecisionMissing
            ProjectDecisionWriteResult.SupersededDecisionInactive -> ProjectDecisionCreateResult.SupersededDecisionInactive
            ProjectDecisionWriteResult.CapacityReached -> ProjectDecisionCreateResult.CapacityReached
        }
    }
}

internal fun interface ProjectDecisionExactLookup {
    suspend fun get(decisionId: String, projectId: Long): ProjectDecisionEntity?
}

/** Exact durable lookup: read errors and moved/mismatched rows fail closed. */
internal class ProjectDecisionLineageResolver(private val lookup: ProjectDecisionExactLookup) {
    suspend fun resolve(decisionId: String, projectId: Long): ProjectDecisionEntity? = try {
        lookup.get(decisionId, projectId)
            ?.takeIf { it.id == decisionId && it.projectId == projectId }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

data class ProjectDecisionListState(
    val items: List<ProjectDecisionEntity> = emptyList(),
    val activeItems: List<ProjectDecisionEntity> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

internal fun projectDecisionListState(
    recent: List<ProjectDecisionEntity>,
    active: List<ProjectDecisionEntity>,
): ProjectDecisionListState {
    val activeRows = active.filter { it.status == "ACTIVE" }.distinctBy { it.id }
    val visibleRows = (recent + activeRows).distinctBy { it.id }
        .sortedWith(compareByDescending<ProjectDecisionEntity> { it.createdAt }.thenByDescending { it.id })
    return ProjectDecisionListState(items = visibleRows, activeItems = activeRows, loading = false)
}

class ProjectDecisionOwner(
    repository: ProjectDecisionRepository,
    projectId: Long,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ProjectDecisionListState())
    val state = mutableState.asStateFlow()
    private val observation: Job = scope.launch(Dispatchers.IO) {
        combine(
            repository.observeRecent(projectId),
            repository.observeActive(projectId, MAX_PROJECT_DECISIONS),
            ::projectDecisionListState,
        )
            .catch { error ->
                if (error is CancellationException) throw error
                mutableState.value = ProjectDecisionListState(loading = false, failed = true)
            }
            .collect { state -> mutableState.value = state }
    }

    fun close() = observation.cancel()
}
