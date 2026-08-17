package com.revshield.spamprobe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationDao {
    /** Dedupe by id (append-only, never overwrite). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ObservationRecord)

    /** Upload candidates: anything not yet delivered and not past the retry cap. */
    @Query("SELECT * FROM observations WHERE syncState != 'SYNCED' AND attempts < :maxAttempts ORDER BY createdAt ASC LIMIT :limit")
    suspend fun uploadable(limit: Int, maxAttempts: Int): List<ObservationRecord>

    /** Delivered — the ONLY transition to SYNCED, made only on a verified 2xx. */
    @Query("UPDATE observations SET syncState = 'SYNCED', failReason = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    /** A non-2xx / error: FAILED with a visible reason; bump attempts (drops out past the cap). */
    @Query("UPDATE observations SET syncState = 'FAILED', failReason = :reason, attempts = attempts + 1 WHERE id = :id")
    suspend fun markFailed(id: String, reason: String)

    @Query("SELECT * FROM observations ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ObservationRecord>>

    @Query("SELECT * FROM observations ORDER BY createdAt DESC")
    suspend fun all(): List<ObservationRecord>

    @Query("SELECT * FROM observations WHERE syncState != 'SYNCED' ORDER BY createdAt DESC")
    suspend fun allPending(): List<ObservationRecord>

    @Query("SELECT COUNT(*) FROM observations")
    fun observeTotal(): Flow<Int>

    /** One-shot counts for the heartbeat payload. */
    @Query("SELECT COUNT(*) FROM observations")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM observations WHERE syncState = :state")
    suspend fun countByState(state: String): Int

    @Query("SELECT COUNT(*) FROM observations WHERE syncState = :state")
    fun observeCount(state: String): Flow<Int>

    @Query("SELECT * FROM observations WHERE id = :id")
    suspend fun byId(id: String): ObservationRecord?
}
