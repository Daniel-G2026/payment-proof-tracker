package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity

@Dao
interface LocalProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: LocalProfileEntity)

    @Query("SELECT * FROM local_profile WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LocalProfileEntity?

    @Query("SELECT * FROM local_profile LIMIT 1")
    suspend fun getFirst(): LocalProfileEntity?

    @Query("DELETE FROM local_profile")
    suspend fun clear()
}
