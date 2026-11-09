package com.projectnuke.fusion.handoff

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase

/**
 * Signals that a project-file batch transaction must roll back.
 *
 * A normal (non-exceptional) return from a Room transaction body COMMITS it:
 * Room has no "abort" result value. A batch that admits only part of its
 * documents must therefore refuse by throwing this private marker, which is
 * caught inside [ProjectFileBatchCommit] and never escapes to callers, so the
 * partial INSERTs are rolled back and no caller can accidentally swallow it as
 * an ordinary failure.
 */
internal class ProjectFileBatchRollback : RuntimeException(null, null, false, false) {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * The durable commit boundary for one project document batch.
 *
 * Contract: the lambda's mutations roll back when it throws and commit when it
 * returns normally. Host tests supply a faithful harness with exactly these
 * semantics; production supplies the real Room seam.
 */
internal fun interface ProjectFileBatchCommit {
    suspend fun inTransaction(block: suspend () -> Boolean): Boolean
}

/** Production [ProjectFileBatchCommit]: the real Room transaction seam. */
internal class RoomProjectFileBatchCommit(private val database: AppDatabase) : ProjectFileBatchCommit {
    override suspend fun inTransaction(block: suspend () -> Boolean): Boolean = try {
        // `RoomDatabase.withTransaction` is a suspend extension function (imported
        // above), so it is invoked directly on the database instance.
        database.withTransaction { block() }
    } catch (rollback: ProjectFileBatchRollback) {
        false
    }
}

/**
 * Everything a project document batch needs from durable storage: the DAO it
 * reads and writes, plus the transaction boundary its INSERTs must share.
 *
 * Injecting this seam keeps the commit semantics host-testable without
 * constructing an abstract Room [AppDatabase] on the JVM, while production
 * supplies the real database through [RoomProjectFileCommitSource].
 */
internal interface ProjectFileCommitSource {
    fun projectDao(): com.projectnuke.fusion.data.ProjectDao
    fun inboxDao(): com.projectnuke.fusion.data.InboxItemDao
    val commit: ProjectFileBatchCommit

    /**
     * Runs a receipt-settlement block in a real Room transaction, or runs it
     * directly when no database is available. Only durable inbox settlement
     * uses this; document batch admission uses [commit].
     */
    suspend fun inSettlementTransaction(block: suspend () -> Boolean): Boolean
}

internal class RoomProjectFileCommitSource(
    internal val database: AppDatabase,
) : ProjectFileCommitSource {
    override fun projectDao(): com.projectnuke.fusion.data.ProjectDao = database.projectDao()
    override fun inboxDao(): com.projectnuke.fusion.data.InboxItemDao = database.inboxItemDao()
    override val commit: ProjectFileBatchCommit = RoomProjectFileBatchCommit(database)

    override suspend fun inSettlementTransaction(block: suspend () -> Boolean): Boolean =
        // `RoomDatabase.withTransaction` is a suspend extension function.
        database.withTransaction { block() }
}
