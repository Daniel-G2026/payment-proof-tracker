package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.balance.Installment
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.dao.LocalInstallmentDao
import com.danielanazodo.proofledger.data.local.dao.LocalScheduleVersionDao
import com.danielanazodo.proofledger.data.local.entity.LocalInstallmentEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScheduleVersionEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.data.remote.dto.InstallmentInputDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleDuplicateCandidateDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleDuplicateInstallmentDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleDuplicateRequestDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleVersionCreateDto
import com.danielanazodo.proofledger.data.remote.dto.ScheduleVersionDto
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import com.danielanazodo.proofledger.data.sync.toSyncErrorMessage
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** One line of a manual schedule, as the UI collects it. */
data class ScheduleLineInput(
    val sequence: Int,
    val amountMinor: Long,
    val dueDate: LocalDate,
)

/**
 * Versioned schedules for a unit (Phase 13 Cut 2). Offline-first: a new version
 * (manual entry or renegotiation) is created locally — deactivating any prior
 * active version in the same step (app-level "one active per unit") — and
 * pushed to the backend, which carries its installments inline. Installments
 * have no independent sync lifecycle; they ride with their parent version.
 *
 * Balances are NOT stored here — [com.danielanazodo.proofledger.data.balance.AllocationEngine]
 * computes fills/arrears on read from [activeInstallments] + the unit's payments
 * (payment linkage arrives in Cut 3).
 */
@Singleton
class ScheduleRepository @Inject constructor(
    private val api: ApiService,
    private val versionDao: LocalScheduleVersionDao,
    private val installmentDao: LocalInstallmentDao,
    private val syncEventLogger: SyncEventLogger,
) {
    fun observeVersionsByUnit(unitId: String): Flow<List<LocalScheduleVersionEntity>> =
        versionDao.observeByUnit(unitId)

    fun observeActiveVersion(unitId: String): Flow<LocalScheduleVersionEntity?> =
        versionDao.observeActiveForUnit(unitId)

    fun observeInstallments(versionId: String): Flow<List<LocalInstallmentEntity>> =
        installmentDao.observeForVersion(versionId)

    /** Active schedule versions across the business — a change-trigger for the
     * arrears dashboard (Phase 13 Cut 4). */
    fun observeActiveSchedules(businessId: String): Flow<List<LocalScheduleVersionEntity>> =
        versionDao.observeActiveForBusiness(businessId)

    /** The active version's installments as engine domain objects, ready for
     * [com.danielanazodo.proofledger.data.balance.AllocationEngine]. Empty when
     * the unit has no active schedule. */
    suspend fun activeInstallments(unitId: String): List<Installment> {
        val active = versionDao.getActiveForUnit(unitId) ?: return emptyList()
        return installmentDao.getForVersion(active.id).map { it.toDomain() }
    }

    suspend fun createScheduleVersion(
        businessId: String,
        unitId: String,
        lines: List<ScheduleLineInput>,
        source: String = "manual",
        offerLetterScreenshotId: String? = null,
    ): LocalScheduleVersionEntity {
        val now = System.currentTimeMillis()
        val versionId = UUID.randomUUID().toString()
        val nextVersion = versionDao.getMaxVersionNo(unitId) + 1
        // One active per unit (app-level enforcement, mirrors the backend).
        versionDao.deactivateActiveForUnit(unitId)
        val version = LocalScheduleVersionEntity(
            id = versionId,
            businessId = businessId,
            unitId = unitId,
            versionNo = nextVersion,
            source = source,
            isActive = true,
            offerLetterScreenshotId = offerLetterScreenshotId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastSyncAttemptAt = now,
        )
        versionDao.upsert(version)
        val installments = lines.map { line ->
            LocalInstallmentEntity(
                id = UUID.randomUUID().toString(),
                businessId = businessId,
                scheduleVersionId = versionId,
                sequence = line.sequence,
                amountMinor = line.amountMinor,
                dueDate = line.dueDate.toString(),
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        }
        installmentDao.upsertAll(installments)

        return runCatching {
            api.createScheduleVersion(businessId, unitId, version.toCreateDto(installments))
        }.fold(
            onSuccess = { remote ->
                storeRemote(remote)
                logSync(version, "create", "success")
                versionDao.getByIdIncludingDeleted(versionId) ?: version
            },
            onFailure = { t ->
                val failed = version.copy(
                    syncStatus = SyncStatus.FAILED,
                    lastSyncAttemptAt = System.currentTimeMillis(),
                    lastSyncError = t.toSyncErrorMessage(),
                )
                versionDao.upsert(failed)
                logSync(version, "create", "failure", t)
                failed
            },
        )
    }

    suspend fun syncPending(businessId: String) {
        versionDao.getRowsNeedingSync(businessId).forEach { local ->
            val installments = installmentDao.getForVersion(local.id)
            runCatching { api.createScheduleVersion(local.businessId, local.unitId, local.toCreateDto(installments)) }
                .onSuccess { remote ->
                    storeRemote(remote)
                    logSync(local, "create", "success")
                }
                .onFailure { t ->
                    versionDao.upsert(
                        local.copy(
                            syncStatus = SyncStatus.FAILED,
                            lastSyncAttemptAt = System.currentTimeMillis(),
                            lastSyncError = t.toSyncErrorMessage(),
                        )
                    )
                    logSync(local, "create", "failure", t)
                }
        }
    }

    suspend fun refreshForUnit(businessId: String, unitId: String) {
        val remote = runCatching { api.listScheduleVersions(businessId, unitId) }.getOrNull() ?: return
        remote.forEach { storeRemote(it) }
    }

    /**
     * Advisory pre-confirm check (mirrors the Phase 9 transaction duplicate
     * check): has this scanned offer letter — its buyer + installment plan, or
     * its exact proof bytes — already been entered as a schedule elsewhere in the
     * business? Best-effort and online-only: a failure (offline, server error)
     * returns empty so the confirm is never blocked — same posture as
     * [refreshForUnit]. `excludeUnitId` is the unit being confirmed, so its own
     * prior version (a renegotiation) is not surfaced as a duplicate.
     */
    suspend fun findDuplicateScheduleCandidates(
        businessId: String,
        buyerName: String?,
        lines: List<ScheduleLineInput>,
        screenshotId: String?,
        excludeUnitId: String?,
    ): List<ScheduleDuplicateCandidate> {
        val candidates = runCatching {
            api.scheduleDuplicateCandidates(
                businessId = businessId,
                body = ScheduleDuplicateRequestDto(
                    buyerName = buyerName,
                    installments = lines.map {
                        ScheduleDuplicateInstallmentDto(
                            amountMinor = it.amountMinor,
                            dueDate = it.dueDate.toString(),
                        )
                    },
                    screenshotId = screenshotId,
                    excludeUnitId = excludeUnitId,
                ),
            ).map { it.toDuplicateCandidate() }
        }.getOrDefault(emptyList())
        syncEventLogger.log(
            businessId = businessId,
            entityType = "schedule_version",
            entityId = excludeUnitId,
            action = "duplicate_check",
            status = if (candidates.isEmpty()) "no_duplicate" else "duplicate_found",
        )
        return candidates
    }

    /** Telemetry: the user saw a duplicate warning and chose to save anyway. */
    suspend fun logScheduleDuplicateWarningIgnored(businessId: String, unitId: String) {
        syncEventLogger.log(
            businessId = businessId,
            entityType = "schedule_version",
            entityId = unitId,
            action = "duplicate_warning_ignored",
            status = "success",
        )
    }

    private suspend fun storeRemote(remote: ScheduleVersionDto) {
        versionDao.upsert(remote.toEntity())
        // Replace the version's installments with the authoritative server set.
        installmentDao.deleteForVersion(remote.id)
        installmentDao.upsertAll(
            remote.installments.map { dto ->
                val now = System.currentTimeMillis()
                LocalInstallmentEntity(
                    id = dto.id,
                    businessId = dto.businessId,
                    scheduleVersionId = dto.scheduleVersionId,
                    sequence = dto.sequence,
                    amountMinor = dto.amountMinor,
                    dueDate = dto.dueDate.take(10),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                )
            }
        )
    }

    suspend fun clearLocal() {
        installmentDao.clear()
        versionDao.clear()
    }

    private fun LocalInstallmentEntity.toDomain(): Installment =
        Installment(
            id = id,
            sequence = sequence,
            amountMinor = amountMinor,
            dueDate = LocalDate.parse(dueDate),
        )

    private fun LocalScheduleVersionEntity.toCreateDto(
        installments: List<LocalInstallmentEntity>,
    ): ScheduleVersionCreateDto =
        ScheduleVersionCreateDto(
            id = id,
            source = source,
            offerLetterScreenshotId = offerLetterScreenshotId,
            installments = installments.map {
                InstallmentInputDto(
                    id = it.id,
                    sequence = it.sequence,
                    amountMinor = it.amountMinor,
                    dueDate = it.dueDate,
                )
            },
        )

    private fun ScheduleVersionDto.toEntity(): LocalScheduleVersionEntity {
        val now = System.currentTimeMillis()
        return LocalScheduleVersionEntity(
            id = id,
            businessId = businessId,
            unitId = unitId,
            versionNo = versionNo,
            source = source,
            isActive = isActive,
            offerLetterScreenshotId = offerLetterScreenshotId,
            createdAt = now,
            updatedAt = now,
            deletedAt = deletedAt?.let { now },
            syncStatus = SyncStatus.SYNCED,
            lastSyncAttemptAt = now,
            remoteUpdatedAt = updatedAt,
        )
    }

    private fun ScheduleDuplicateCandidateDto.toDuplicateCandidate(): ScheduleDuplicateCandidate =
        ScheduleDuplicateCandidate(
            unitId = unitId,
            projectId = projectId,
            unitLabel = unitLabel,
            buyerName = buyerName,
            scheduleVersionId = scheduleVersionId,
            versionNo = versionNo,
            installmentCount = installmentCount,
            totalMinor = totalMinor,
            reasons = reasons.mapNotNull { ScheduleDuplicateReason.fromWire(it) },
            score = score,
        )

    private suspend fun logSync(
        row: LocalScheduleVersionEntity,
        action: String,
        status: String,
        throwable: Throwable? = null,
    ) {
        syncEventLogger.log(
            businessId = row.businessId,
            entityType = "schedule_version",
            entityId = row.id,
            action = action,
            status = status,
            errorMessage = throwable?.toSyncErrorMessage(),
        )
    }
}

/** A prior schedule that looks like the offer letter being confirmed (Phase 13
 * advisory duplicate detection). Mirrors [DuplicateCandidate] for transactions. */
data class ScheduleDuplicateCandidate(
    val unitId: String,
    val projectId: String,
    val unitLabel: String,
    val buyerName: String?,
    val scheduleVersionId: String,
    val versionNo: Int,
    val installmentCount: Int,
    val totalMinor: Long,
    val reasons: List<ScheduleDuplicateReason>,
    val score: Int,
)

enum class ScheduleDuplicateReason(val wire: String, val label: String, val score: Int) {
    SAME_PROOF("same_proof", "same offer-letter image", 100),
    SAME_BUYER_AND_SCHEDULE("same_buyer_and_schedule", "same buyer and schedule", 75);

    companion object {
        fun fromWire(value: String): ScheduleDuplicateReason? = entries.firstOrNull { it.wire == value }
    }
}
