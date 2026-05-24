package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.TransactionCreateDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto
import com.danielanazodo.proofledger.data.sync.toSyncErrorMessage
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
    private val proofRepository: ProofRepository,
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

    fun observeByCounterparty(
        businessId: String,
        counterpartyId: String,
    ): Flow<List<LocalTransactionEntity>> =
        localDao.observeByCounterparty(businessId, counterpartyId)

    suspend fun refreshFromRemote(businessId: String) {
        val remote = runCatching { api.listTransactions(businessId) }.getOrNull() ?: return
        localDao.upsertAll(remote.map { it.toEntity(SyncStatus.SYNCED) })
    }

    /** Back-compat alias — sync worker now uses [syncPending]. */
    suspend fun syncPendingUploads(businessId: String) = syncPending(businessId)

    /**
     * Drain every transaction row that isn't SYNCED. PENDING_UPLOAD and prior
     * FAILED uploads go through create; PENDING_UPDATE or FAILED updates go
     * through update; PENDING_DELETE goes through delete. Any retry that fails
     * again lands back in FAILED with an updated lastSyncError.
     */
    suspend fun syncPending(businessId: String) {
        val rows = localDao.getRowsNeedingSync(businessId)
        rows.forEach { local ->
            when {
                local.deletedAt != null || local.syncStatus == SyncStatus.PENDING_DELETE ->
                    syncDelete(local)
                local.syncStatus == SyncStatus.PENDING_UPDATE -> syncUpdate(local)
                else -> syncCreate(businessId, local)
            }
        }
    }

    private suspend fun syncCreate(businessId: String, local: LocalTransactionEntity) {
        runCatching { api.createTransaction(businessId, local.toCreateDto()) }
            .onSuccess { remote -> localDao.upsert(remote.toEntity(SyncStatus.SYNCED)) }
            .onFailure { t -> localDao.upsert(local.toFailed(t)) }
    }

    private suspend fun syncUpdate(local: LocalTransactionEntity) {
        runCatching { api.updateTransaction(local.businessId, local.id, local.toUpdateDto()) }
            .onSuccess { remote -> localDao.upsert(remote.toEntity(SyncStatus.SYNCED)) }
            .onFailure { t -> localDao.upsert(local.toFailed(t)) }
    }

    private suspend fun syncDelete(local: LocalTransactionEntity) {
        runCatching { api.deleteTransaction(local.businessId, local.id) }
            .onSuccess {
                localDao.upsert(
                    local.copy(
                        syncStatus = SyncStatus.SYNCED,
                        lastSyncAttemptAt = System.currentTimeMillis(),
                        lastSyncError = null,
                    )
                )
            }
            .onFailure { t -> localDao.upsert(local.toFailed(t)) }
    }

    private fun LocalTransactionEntity.toFailed(t: Throwable): LocalTransactionEntity =
        copy(
            syncStatus = SyncStatus.FAILED,
            lastSyncAttemptAt = System.currentTimeMillis(),
            lastSyncError = t.toSyncErrorMessage(),
        )

    suspend fun createTransaction(
        businessId: String,
        amountMinor: Long,
        direction: String,
        transactionDate: String,
        counterpartyId: String?,
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
            counterpartyId = counterpartyId,
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
        return runCatching { api.createTransaction(businessId, local.toCreateDto()) }
            .fold(
                onSuccess = { remote ->
                    val synced = remote.toEntity(SyncStatus.SYNCED)
                    localDao.upsert(synced)
                    synced
                },
                onFailure = { t ->
                    val failed = local.toFailed(t)
                    localDao.upsert(failed)
                    failed
                },
            )
    }

    suspend fun updateTransaction(
        transactionId: String,
        amountMinor: Long,
        direction: String,
        transactionDate: String,
        counterpartyId: String?,
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
            counterpartyId = counterpartyId,
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
        return runCatching {
            api.updateTransaction(current.businessId, current.id, updated.toUpdateDto())
        }.fold(
            onSuccess = { remote ->
                val synced = remote.toEntity(SyncStatus.SYNCED)
                localDao.upsert(synced)
                synced
            },
            onFailure = { t ->
                val failed = updated.toFailed(t)
                localDao.upsert(failed)
                failed
            },
        )
    }

    suspend fun deleteTransaction(transactionId: String) {
        val current = localDao.getById(transactionId)
            ?: throw IllegalStateException("Transaction not found.")
        val now = System.currentTimeMillis()
        // Cascade-delete proofs immediately. The user said "delete this
        // transaction" — there's no undo path, so keeping the proof files on
        // disk just leaks storage.
        proofRepository.deleteForTransaction(transactionId)
        val pending = current.copy(
            deletedAt = now,
            syncStatus = SyncStatus.PENDING_DELETE,
            lastSyncAttemptAt = now,
            lastSyncError = null,
        )
        localDao.upsert(pending)
        runCatching { api.deleteTransaction(current.businessId, current.id) }
            .onSuccess {
                localDao.upsert(
                    pending.copy(
                        updatedAt = now,
                        syncStatus = SyncStatus.SYNCED,
                    )
                )
            }
            .onFailure { t -> localDao.upsert(pending.toFailed(t)) }
    }

    suspend fun clearLocal() {
        localDao.clear()
        // Belt-and-suspenders — even if the caller forgot to wipe proofs first,
        // a logout that drops every transaction has no reason to keep proof
        // files. ProofRepository.clearAllLocal() handles its own DAO + files.
        proofRepository.clearAllLocal()
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
            counterpartyId = counterpartyId,
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
            counterpartyId = counterpartyId,
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
            counterpartyId = counterpartyId,
            counterpartyName = counterpartyName,
            category = category,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
        )
}
