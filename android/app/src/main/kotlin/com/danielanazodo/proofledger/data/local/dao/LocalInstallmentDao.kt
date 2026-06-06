package com.danielanazodo.proofledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danielanazodo.proofledger.data.local.entity.LocalInstallmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalInstallmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(installment: LocalInstallmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(installments: List<LocalInstallmentEntity>)

    @Query(
        """
        SELECT * FROM local_installment
        WHERE scheduleVersionId = :versionId AND deletedAt IS NULL
        ORDER BY dueDate ASC, sequence ASC
        """
    )
    suspend fun getForVersion(versionId: String): List<LocalInstallmentEntity>

    @Query(
        """
        SELECT * FROM local_installment
        WHERE scheduleVersionId = :versionId AND deletedAt IS NULL
        ORDER BY dueDate ASC, sequence ASC
        """
    )
    fun observeForVersion(versionId: String): Flow<List<LocalInstallmentEntity>>

    @Query("DELETE FROM local_installment WHERE scheduleVersionId = :versionId")
    suspend fun deleteForVersion(versionId: String)

    @Query("DELETE FROM local_installment")
    suspend fun clear()
}
