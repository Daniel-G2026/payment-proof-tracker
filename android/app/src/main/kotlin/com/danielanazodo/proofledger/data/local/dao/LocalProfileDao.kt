package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: LocalProfileEntity)

    @Query("SELECT * FROM local_profile WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LocalProfileEntity?

    @Query("SELECT * FROM local_profile LIMIT 1")
    suspend fun getFirst(): LocalProfileEntity?

    /** Live mirror, so screens showing the profile update when it's edited. */
    @Query("SELECT * FROM local_profile LIMIT 1")
    fun observeFirst(): Flow<LocalProfileEntity?>

    @Query("DELETE FROM local_profile")
    suspend fun clear()
}
