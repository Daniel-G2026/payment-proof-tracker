package com.danielanazodo.proofledger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One scheduled line of a [LocalScheduleVersionEntity] (Phase 13 Cut 2). No fill
 * columns — fills are computed by [AllocationEngine]. Installments are created
 * and synced together with their parent version (the backend create endpoint
 * takes them inline), so they have no independent sync lifecycle. `dueDate` is
 * an ISO `yyyy-MM-dd` string, matching `local_transaction.transactionDate`.
 */
@Entity(
    tableName = "local_installment",
    indices = [
        Index("businessId"),
        Index("scheduleVersionId"),
    ],
)
data class LocalInstallmentEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val scheduleVersionId: String,
    val sequence: Int,
    val amountMinor: Long,
    val dueDate: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)
