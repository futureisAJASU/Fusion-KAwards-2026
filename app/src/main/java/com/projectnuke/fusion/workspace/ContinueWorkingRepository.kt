package com.projectnuke.fusion.workspace

import com.projectnuke.fusion.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded read-only projection over existing local owners. */
class ContinueWorkingRepository(private val database: AppDatabase) {
    suspend fun load(): List<ContinueWorkCandidate> = withContext(Dispatchers.IO) {
        try {
            ContinueWorkingPolicy.select(
                projects = database.projectDao().getRecentProjectsBounded(ContinueWorkingPolicy.QUERY_LIMIT_PER_KIND),
                openTasks = database.projectTaskDao().getRecentOpenShortcuts(ContinueWorkingPolicy.QUERY_LIMIT_PER_KIND),
                inboxItems = database.inboxItemDao().getLatestBounded(ContinueWorkingPolicy.QUERY_LIMIT_PER_KIND),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }
}
