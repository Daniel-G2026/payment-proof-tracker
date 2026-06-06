package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.analytics.Analytics
import com.danielanazodo.proofledger.data.analytics.AnalyticsEvents
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalCounterpartyDao
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.dao.LocalTransactionDao
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.local.escapeLikeTerm
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.DuplicateCandidateDto
import com.danielanazodo.proofledger.data.remote.dto.DuplicateCandidateRequestDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionCreateDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto
import com.danielanazodo.proofledger.data.sync.AUTH_EXPIRED_SYNC_MESSAGE
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import com.danielanazodo.proofledger.data.sync.isSyncConflictFailure
import com.danielanazodo.proofledger.data.sync.toSyncErrorMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

@Singleton
class TransactionRepository @Inject constructor(
    private val api: ApiService,
    private val localDao: LocalTransactionDao,
    private val counterpartyDao: LocalCounterpartyDao,
    private val screenshotDao: LocalScreenshotDao,
    private val proofRepository: ProofRepository,
    private val syncEventLogger: SyncEventLogger,
    private val analytics: Analytics,
) {
    fun observeTransactions(businessId: String): Flow<List<LocalTransactionEntity>> =
        localDao.observeByBusiness(businessId)

    fun observeFiltered(
        businessId: String,
        direction: String?,
        search: String?,
    ): Flow<List<LocalTransactionEntity>> =
        // Pre-escape so a literal % / _ in the term isn't treated as a wildcard
        // (the DAO LIKE clauses declare ESCAPE '\').
        localDao.observeFiltered(businessId, direction, search?.let { escapeLikeTerm(it) })

    fun observeTransaction(transactionId: String): Flow<LocalTransactionEntity?> =
        localDao.observeById(transactionId)

    fun observeByCounterparty(
        businessId: String,
        counterpartyId: String,
    ): Flow<List<LocalTransactionEntity>> =
        localDao.observeByCounterparty(businessId, counterpartyId)

    /** Money-in transactions linked to a unit, oldest-first — the payments the
     * [com.danielanazodo.proofledger.data.balance.AllocationEngine] pours into
     * the unit's active schedule (Phase 13 Cut 3). */
    fun observeMoneyInForUnit(unitId: String): Flow<List<LocalTransactionEntity>> =
        localDao.observeMoneyInForUnit(unitId)

    /** All unit-linked money-in across the business (Phase 13 Cut 4 dashboard). */
    fun observeMoneyInLinkedForBusiness(businessId: String): Flow<List<LocalTransactionEntity>> =
        localDao.observeMoneyInLinkedForBusiness(businessId)

    /** Money-out tagged to a project (Phase 13 Cut 5 per-project spend). */
    fun observeMoneyOutForProject(projectId: String): Flow<List<LocalTransactionEntity>> =
        localDao.observeMoneyOutForProject(projectId)

    suspend fun findDuplicateCandidates(
        businessId: String,
        amountMinor: Long,
        direction: String,
        transactionDate: String,
        counterpartyId: String?,
        counterpartyName: String?,
        referenceNumber: String?,
        screenshotId: String?,
        imageSha256: String?,
        excludeTransactionId: String?,
    ): List<DuplicateCandidate> {
        val local = findLocalDuplicateCandidates(
            businessId = businessId,
            amountMinor = amountMinor,
            direction = direction,
            transactionDate = transactionDate,
            counterpartyId = counterpartyId,
            counterpartyName = counterpartyName,
            referenceNumber = referenceNumber,
            imageSha256 = imageSha256,
            excludeTransactionId = excludeTransactionId,
        )
        val remote = runCatching {
            api.duplicateCandidates(
                businessId = businessId,
                body = DuplicateCandidateRequestDto(
                    amountMinor = amountMinor,
                    direction = direction,
                    transactionDate = transactionDate,
                    counterpartyId = counterpartyId,
                    counterpartyName = counterpartyName,
                    referenceNumber = referenceNumber,
                    screenshotId = screenshotId,
                    excludeTransactionId = excludeTransactionId,
                ),
            ).map { it.toDuplicateCandidate() }
        }.getOrDefault(emptyList())

        val merged = (local + remote)
            .groupBy { it.transaction.id }
            .map { (_, candidates) ->
                val best = candidates.maxBy { it.score }
                best.copy(
                    reasons = candidates.flatMap { it.reasons }.distinct().sortedBy { it.name },
                    score = candidates.maxOf { it.score },
                )
            }
            .sortedByDescending { it.score }
            .take(5)
        syncEventLogger.log(
            businessId = businessId,
            entityType = "transaction",
            entityId = excludeTransactionId,
            action = "duplicate_check",
            status = if (merged.isEmpty()) "no_duplicate" else "duplicate_found",
        )
        return merged
    }

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
    suspend fun syncPending(
        businessId: String,
        includeAuthExpiredFailures: Boolean = false,
    ) {
        val rows = localDao.getRowsNeedingSync(businessId).filterForRetry(includeAuthExpiredFailures)
        rows.forEach { local ->
            when {
                local.deletedAt != null || local.syncStatus == SyncStatus.PENDING_DELETE ->
                    syncDelete(local)
                shouldWaitForCounterparty(local) -> markWaitingForCounterparty(local)
                local.syncStatus == SyncStatus.PENDING_UPDATE -> syncUpdate(local)
                local.syncStatus == SyncStatus.FAILED -> syncCreateOrUpdate(businessId, local)
                else -> syncCreate(businessId, local)
            }
        }
    }

    private suspend fun syncCreate(businessId: String, local: LocalTransactionEntity) {
        runCatching { api.createTransaction(businessId, local.toCreateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "create", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "create", "failure", t)
            }
    }

    private suspend fun syncUpdate(local: LocalTransactionEntity) {
        runCatching { api.updateTransaction(local.businessId, local.id, local.toUpdateDto()) }
            .onSuccess { remote ->
                localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                logSync(local, "update", "success")
            }
            .onFailure { t ->
                localDao.upsert(local.toFailed(t))
                logSync(local, "update", "failure", t)
            }
    }

    private suspend fun syncCreateOrUpdate(businessId: String, local: LocalTransactionEntity) {
        if (local.updatedAt > local.createdAt) {
            val update = runCatching { api.updateTransaction(local.businessId, local.id, local.toUpdateDto()) }
            update
                .onSuccess { remote ->
                    localDao.upsert(remote.toEntity(SyncStatus.SYNCED))
                    logSync(local, "update", "success")
                    return
                }
                .onFailure { t ->
                    if ((t as? HttpException)?.code() != 404) {
                        localDao.upsert(local.toFailed(t))
                        logSync(local, "update", "failure", t)
                        return
                    }
                }
        }
        syncCreate(businessId, local)
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
                logSync(local, "delete", "success")
            }
            .onFailure { t ->
                if ((t as? HttpException)?.code() == 404) {
                    localDao.upsert(
                        local.copy(
                            syncStatus = SyncStatus.SYNCED,
                            lastSyncAttemptAt = System.currentTimeMillis(),
                            lastSyncError = null,
                        )
                    )
                    logSync(local, "delete", "success")
                } else {
                    localDao.upsert(local.toFailed(t))
                    logSync(local, "delete", "failure", t)
                }
            }
    }

    private fun LocalTransactionEntity.toFailed(t: Throwable): LocalTransactionEntity =
        copy(
            syncStatus = if (t.isSyncConflictFailure()) SyncStatus.CONFLICT else SyncStatus.FAILED,
            lastSyncAttemptAt = System.currentTimeMillis(),
            lastSyncError = t.toSyncErrorMessage(),
        )

    private fun List<LocalTransactionEntity>.filterForRetry(
        includeAuthExpiredFailures: Boolean,
    ): List<LocalTransactionEntity> =
        if (includeAuthExpiredFailures) {
            this
        } else {
            filterNot {
                it.syncStatus == SyncStatus.FAILED &&
                    it.lastSyncError == AUTH_EXPIRED_SYNC_MESSAGE
            }
        }

    private suspend fun shouldWaitForCounterparty(local: LocalTransactionEntity): Boolean {
        val counterpartyId = local.counterpartyId ?: return false
        val counterparty = counterpartyDao.getByIdIncludingDeleted(counterpartyId) ?: return false
        return counterparty.syncStatus != SyncStatus.SYNCED
    }

    private suspend fun markWaitingForCounterparty(local: LocalTransactionEntity) {
        localDao.upsert(
            local.copy(
                lastSyncAttemptAt = System.currentTimeMillis(),
                lastSyncError = "Waiting for this person to sync first.",
            )
        )
    }

    private suspend fun findLocalDuplicateCandidates(
        businessId: String,
        amountMinor: Long,
        direction: String,
        transactionDate: String,
        counterpartyId: String?,
        counterpartyName: String?,
        referenceNumber: String?,
        imageSha256: String?,
        excludeTransactionId: String?,
    ): List<DuplicateCandidate> {
        val normalizedReference = referenceNumber.normalizeReference()
        val normalizedName = counterpartyName.normalizeName()
        if (!imageSha256.isNullOrBlank()) {
            proofRepository.backfillMissingImageHashes(businessId)
        }
        val baseRows = localDao.findDuplicateCandidates(
            businessId = businessId,
            amountMinor = amountMinor,
            direction = direction,
            transactionDate = transactionDate,
            referenceNumber = normalizedReference,
            excludeTransactionId = excludeTransactionId,
        )
        val proofRows = imageSha256?.takeIf { it.isNotBlank() }?.let {
            screenshotDao.findByImageSha256(businessId, it)
        }.orEmpty()
        val proofTransactionIds = proofRows.map { it.transactionId }.toSet() - setOfNotNull(excludeTransactionId)
        val proofTransactions = proofTransactionIds.mapNotNull { localDao.getById(it) }

        return (baseRows + proofTransactions)
            .distinctBy { it.id }
            .mapNotNull { row ->
                val reasons = buildList {
                    if (row.id in proofTransactionIds) add(DuplicateReason.SAME_PROOF)
                    if (
                        normalizedReference != null &&
                        row.referenceNumber.normalizeReference() == normalizedReference
                    ) add(DuplicateReason.SAME_REFERENCE)
                    val sameBase = row.amountMinor == amountMinor &&
                        row.direction == direction &&
                        row.transactionDate == transactionDate
                    if (sameBase && counterpartyId != null && row.counterpartyId == counterpartyId) {
                        add(DuplicateReason.SAME_AMOUNT_DATE_COUNTERPARTY)
                    } else if (
                        sameBase &&
                        normalizedName != null &&
                        row.counterpartyName.normalizeName() == normalizedName
                    ) {
                        add(DuplicateReason.SAME_AMOUNT_DATE_NAME)
                    }
                }
                if (reasons.isEmpty()) {
                    null
                } else {
                    DuplicateCandidate(
                        transaction = row,
                        reasons = reasons.distinct().sortedBy { it.name },
                        score = reasons.sumOf { it.score },
                    )
                }
            }
            .sortedByDescending { it.score }
            .take(5)
    }

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
        // Phase 13: money-in unit link + earmark (Cut 3); money-out project (Cut 5).
        projectId: String? = null,
        unitId: String? = null,
        earmarkedInstallmentId: String? = null,
        // Phase 8.1: orphan screenshot id to link atomically on the server.
        // Only flows through on the first (online) API call — if that call
        // fails, the orphan stays on the server until cron cleanup. We don't
        // persist the link intent on the local row, so sync retries won't
        // re-attempt the link. Documented limitation.
        screenshotId: String? = null,
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
            projectId = projectId,
            unitId = unitId,
            earmarkedInstallmentId = earmarkedInstallmentId,
        )
        localDao.upsert(local)
        analytics.track(
            AnalyticsEvents.TRANSACTION_SAVED,
            mapOf("direction" to direction, "has_proof" to (screenshotId != null)),
        )
        return runCatching { api.createTransaction(businessId, local.toCreateDto(screenshotId)) }
            .fold(
                onSuccess = { remote ->
                    val synced = remote.toEntity(SyncStatus.SYNCED)
                    localDao.upsert(synced)
                    logSync(local, "create", "success")
                    synced
                },
                onFailure = { t ->
                    val failed = local.toFailed(t)
                    localDao.upsert(failed)
                    logSync(local, "create", "failure", t)
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
        // Phase 13: money-in unit link + earmark (Cut 3); money-out project (Cut 5).
        projectId: String? = null,
        unitId: String? = null,
        earmarkedInstallmentId: String? = null,
        screenshotId: String? = null,  // Phase 8.1 — see createTransaction
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
            projectId = projectId,
            unitId = unitId,
            earmarkedInstallmentId = earmarkedInstallmentId,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_UPDATE,
            lastSyncAttemptAt = now,
        )
        localDao.upsert(updated)
        return runCatching {
            api.updateTransaction(current.businessId, current.id, updated.toUpdateDto(screenshotId))
        }.fold(
            onSuccess = { remote ->
                val synced = remote.toEntity(SyncStatus.SYNCED)
                localDao.upsert(synced)
                logSync(updated, "update", "success")
                synced
            },
            onFailure = { t ->
                val failed = updated.toFailed(t)
                localDao.upsert(failed)
                logSync(updated, "update", "failure", t)
                failed
            },
        )
    }

    suspend fun deleteTransaction(transactionId: String) {
        val current = localDao.getById(transactionId)
            ?: throw IllegalStateException("Transaction not found.")
        if (current.syncStatus == SyncStatus.PENDING_UPLOAD) {
            proofRepository.deleteForTransaction(transactionId)
            localDao.deleteById(transactionId)
            return
        }
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
                logSync(pending, "delete", "success")
            }
            .onFailure { t ->
                localDao.upsert(pending.toFailed(t))
                logSync(pending, "delete", "failure", t)
            }
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
            projectId = projectId,
            unitId = unitId,
            earmarkedInstallmentId = earmarkedInstallmentId,
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
            remoteUpdatedAt = updatedAt,
        )
    }

    private fun DuplicateCandidateDto.toDuplicateCandidate(): DuplicateCandidate =
        DuplicateCandidate(
            transaction = transaction.toEntity(SyncStatus.SYNCED),
            reasons = reasons.mapNotNull { DuplicateReason.fromWire(it) },
            score = score,
        )

    private fun String.toEpochMillisOrNull(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()

    private fun LocalTransactionEntity.toCreateDto(
        screenshotId: String? = null,
    ): TransactionCreateDto =
        TransactionCreateDto(
            id = id,
            amountMinor = amountMinor,
            currency = currency,
            direction = direction,
            transactionDate = transactionDate,
            counterpartyId = counterpartyId,
            counterpartyName = counterpartyName,
            category = category,
            projectId = projectId,
            unitId = unitId,
            earmarkedInstallmentId = earmarkedInstallmentId,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
            screenshotId = screenshotId,
        )

    private fun LocalTransactionEntity.toUpdateDto(
        screenshotId: String? = null,
    ): TransactionUpdateDto =
        TransactionUpdateDto(
            amountMinor = amountMinor,
            currency = currency,
            direction = direction,
            transactionDate = transactionDate,
            counterpartyId = counterpartyId,
            counterpartyName = counterpartyName,
            category = category,
            projectId = projectId,
            unitId = unitId,
            earmarkedInstallmentId = earmarkedInstallmentId,
            note = note,
            bankName = bankName,
            referenceNumber = referenceNumber,
            screenshotId = screenshotId,
            expectedUpdatedAt = remoteUpdatedAt,
        )

    suspend fun logDuplicateWarningIgnored(
        businessId: String,
        transactionId: String?,
    ) {
        syncEventLogger.log(
            businessId = businessId,
            entityType = "transaction",
            entityId = transactionId,
            action = "duplicate_warning_ignored",
            status = "success",
        )
    }

    private suspend fun logSync(
        row: LocalTransactionEntity,
        action: String,
        status: String,
        throwable: Throwable? = null,
    ) {
        syncEventLogger.log(
            businessId = row.businessId,
            entityType = "transaction",
            entityId = row.id,
            action = action,
            status = status,
            errorMessage = throwable?.toSyncErrorMessage(),
        )
    }
}

data class DuplicateCandidate(
    val transaction: LocalTransactionEntity,
    val reasons: List<DuplicateReason>,
    val score: Int,
)

enum class DuplicateReason(val wire: String, val label: String, val score: Int) {
    SAME_PROOF("same_proof", "same proof", 100),
    SAME_REFERENCE("same_reference", "same reference", 80),
    SAME_AMOUNT_DATE_COUNTERPARTY(
        "same_amount_date_counterparty",
        "same amount, date, and person",
        70,
    ),
    SAME_AMOUNT_DATE_NAME("same_amount_date_name", "same amount, date, and name", 50);

    companion object {
        fun fromWire(value: String): DuplicateReason? = entries.firstOrNull { it.wire == value }
    }
}

private fun String?.normalizeReference(): String? =
    this?.filterNot(Char::isWhitespace)?.lowercase()?.takeIf { it.isNotBlank() }

private fun String?.normalizeName(): String? =
    this?.trim()?.replace(Regex("\\s+"), " ")?.lowercase()?.takeIf { it.isNotBlank() }
