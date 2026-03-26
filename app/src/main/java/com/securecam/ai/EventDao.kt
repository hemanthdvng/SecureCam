package com.securecam.ai

import androidx.room.*

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventRecord): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT 500")
    suspend fun getAll(): List<EventRecord>

    @Query("SELECT * FROM events WHERE type = :type ORDER BY timestamp DESC LIMIT 200")
    suspend fun getByType(type: String): List<EventRecord>

    @Query("DELETE FROM events WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}
