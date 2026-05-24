package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.TransactionCreateDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class TransactionRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalTransactionDao,
) {
    fun observeTransactions(businessId: String): Flow<List<LocalTransactionEntity>> =
        localDao.observeByBusiness(businessId)

    fun observeFiltered(
        businessId: String,
        direction: String?,
        search: String?,
    ): Flow<List<LocalTransactionEntity>> =
        localDao.observeFiltered(businessId, direction, search)

    fun observeTransaction(transactionId: String): Flow<LocalTransactionEntity?> =
        localDao.observeById(transactionId)

    suspend fun refreshFromRemote(businessId: String) {
        val remote = runCatching { api.listTransactions(businessId) }.getOrNull() ?: return
        localDao.upsertAll(remote.map { it.toEntity(SyncStatus.SYNCED) })
    }

    suspend fun createTransaction(
        businessId: String,
        amountMinor: Long,
        direction: String,
        transactionDate: String,
        counterpartyName: String?,
        category: String?,
        note: String?,
        bankName: String?,
        referenceNumber: String?,
    ): LocalTransactionEntity {
        val now = System.currentTimeMillis()
        val local = LocalTransactionEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            amountMinor = amountMinor,
            currency = "NGN",
            direction = direction,
            transactionDate = transactionDate,
            counterpartyName = counterpartyName,
            category = category,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
            source = "manual",
            status = "confirmed",
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(local)
        val remote = runCatching {
            api.createTransaction(businessId, local.toCreateDto())
        }.getOrNull()
        return if (remote != null) {
            val synced = remote.toEntity(SyncStatus.SYNCED)
            localDao.upsert(synced)
            synced
        } else {
            local
        }
    }

    suspend fun updateTransaction(
        transactionId: String,
        amountMinor: Long,
        direction: String,
        transactionDate: String,
        counterpartyName: String?,
        category: String?,
        note: String?,
        bankName: String?,
        referenceNumber: String?,
    ): LocalTransactionEntity {
        val current = localDao.getById(transactionId)
            ?: throw IllegalStateException("Transaction not found.")
        val now = System.currentTimeMillis()
        val updated = current.copy(
            amountMinor = amountMinor,
            direction = direction,
            transactionDate = transactionDate,
            counterpartyName = counterpartyName,
            category = category,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(updated)
        val remote = runCatching {
            api.updateTransaction(current.businessId, current.id, updated.toUpdateDto())
        }.getOrNull()
        return if (remote != null) {
            val synced = remote.toEntity(SyncStatus.SYNCED)
            localDao.upsert(synced)
            synced
        } else {
            updated
        }
    }

    suspend fun deleteTransaction(transactionId: String) {
        val current = localDao.getById(transactionId)
            ?: throw IllegalStateException("Transaction not found.")
        val now = System.currentTimeMillis()
        localDao.upsert(
            current.copy(
                deletedAt = now,
                syncStatus = SyncStatus.PENDING_DELETE,
                lastSyncAttemptAt = now,
            )
        )
        val deletedRemote = runCatching {
            api.deleteTransaction(current.businessId, current.id)
        }.isSuccess
        if (deletedRemote) {
            localDao.upsert(
                current.copy(
                    deletedAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncAttemptAt = now,
                )
            )
        }
    }

    suspend fun clearLocal() {
        localDao.clear()
    }

    private fun TransactionDto.toEntity(syncStatus: SyncStatus): LocalTransactionEntity {
        val now = System.currentTimeMillis()
        return LocalTransactionEntity(
            id = id,
            businessId = businessId,
            amountMinor = amountMinor,
            currency = currency,
            direction = direction,
            transactionDate = transactionDate,
            counterpartyName = counterpartyName,
            category = category,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
            source = source,
            status = status,
            createdAt = createdAt.toEpochMillisOrNull() ?: now,
            updatedAt = updatedAt.toEpochMillisOrNull() ?: now,
            deletedAt = deletedAt?.toEpochMillisOrNull(),
            syncStatus = syncStatus,
            lastSyncAttemptAt = now,
        )
    }

    private fun String.toEpochMillisOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()

    private fun LocalTransactionEntity.toCreateDto(): TransactionCreateDto =
        TransactionCreateDto(
            id = id,
            amountMinor = amountMinor,
            currency = currency,
            direction = direction,
            transactionDate = transactionDate,
            counterpartyName = counterpartyName,
            category = category,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
        )

    private fun LocalTransactionEntity.toUpdateDto(): TransactionUpdateDto =
        TransactionUpdateDto(
            amountMinor = amountMinor,
            currency = currency,
            direction = direction,
            transactionDate = transactionDate,
            counterpartyName = counterpartyName,
            category = category,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
        )
}
