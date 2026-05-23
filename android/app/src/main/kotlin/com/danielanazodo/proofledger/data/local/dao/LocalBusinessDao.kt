package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity

@Dao
interface LocalBusinessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(business: LocalBusinessEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(businesses: List<LocalBusinessEntity>)

    @Query("SELECT * FROM local_business WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LocalBusinessEntity?

    @Query("SELECT * FROM local_business ORDER BY createdAt ASC")
    suspend fun getAll(): List<LocalBusinessEntity>

    @Query("SELECT COUNT(*) FROM local_business")
    suspend fun count(): Int

    @Query("DELETE FROM local_business")
    suspend fun clear()
}
