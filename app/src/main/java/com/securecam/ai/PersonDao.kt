package com.securecam.ai

import androidx.room.*

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(person: KnownPerson): Long

    @Query("SELECT * FROM known_persons")
    suspend fun getAll(): List<KnownPerson>

    @Query("SELECT * FROM known_persons WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): KnownPerson?

    @Query("DELETE FROM known_persons WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT COUNT(*) FROM known_persons")
    suspend fun count(): Int
}
