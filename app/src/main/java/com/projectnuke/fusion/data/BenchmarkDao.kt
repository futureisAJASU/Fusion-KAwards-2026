package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchmarkDao {
    @Insert
    suspend fun insert(result: BenchmarkResultEntity): Long

    @Query("SELECT * FROM benchmark_results ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BenchmarkResultEntity>>

    @Query("SELECT * FROM benchmark_results ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BenchmarkResultEntity>>

    @Query("SELECT * FROM benchmark_results ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(): BenchmarkResultEntity?

    @Query("SELECT COUNT(*) FROM benchmark_results")
    suspend fun countResults(): Int

    @Delete
    suspend fun delete(result: BenchmarkResultEntity)

    @Query("DELETE FROM benchmark_results")
    suspend fun deleteAll()
}
