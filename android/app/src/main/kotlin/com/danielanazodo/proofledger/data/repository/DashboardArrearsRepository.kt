package com.danielanazodo.proofledger.data.repository

import com.danielanazodo.proofledger.data.balance.ArrearsCalculator
import com.danielanazodo.proofledger.data.balance.ArrearsDashboard
import com.danielanazodo.proofledger.data.balance.Payment
import com.danielanazodo.proofledger.data.balance.UnitScheduleData
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The monthly arrears dashboard's data layer (Phase 13 Cut 4). Gathers the
 * pieces from Room — units, their active schedules, linked money-in payments,
 * and buyer names — and hands them to the pure [ArrearsCalculator]. Local-first
 * and offline: nothing is fetched from the network here.
 *
 * Reactive: recomputes whenever units, active schedules, linked payments, or
 * counterparty names change. The per-unit installments are read inside the
 * combine's (suspend) transform, so a schedule edit refreshes the dashboard.
 */
@Singleton
class DashboardArrearsRepository @Inject constructor(
    private val unitRepository: UnitRepository,
    private val scheduleRepository: ScheduleRepository,
    private val transactionRepository: TransactionRepository,
    private val counterpartyRepository: CounterpartyRepository,
) {
    fun observe(
        businessId: String,
        asOf: () -> LocalDate = { LocalDate.now() },
    ): Flow<ArrearsDashboard> =
        combine(
            unitRepository.observeByBusiness(businessId),
            scheduleRepository.observeActiveSchedules(businessId),
            transactionRepository.observeMoneyInLinkedForBusiness(businessId),
            counterpartyRepository.observeByBusiness(businessId),
        ) { units, _, payments, counterparties ->
            val nameById = counterparties.associate { it.id to it.name }
            val paymentsByUnit = payments.groupBy { it.unitId }

            val unitData = units.mapNotNull { unit ->
                val installments = scheduleRepository.activeInstallments(unit.id)
                if (installments.isEmpty()) return@mapNotNull null // no schedule → skip
                UnitScheduleData(
                    unitId = unit.id,
                    unitLabel = unit.label,
                    buyerId = unit.buyerCounterpartyId,
                    buyerName = unit.buyerCounterpartyId?.let { nameById[it] },
                    installments = installments,
                    payments = (paymentsByUnit[unit.id] ?: emptyList()).map { it.toPayment() },
                )
            }
            ArrearsCalculator.summarize(unitData, asOf())
        }

    private fun LocalTransactionEntity.toPayment(): Payment =
        Payment(
            id = id,
            amountMinor = amountMinor,
            date = LocalDate.parse(transactionDate.take(10)),
            earmarkedInstallmentId = earmarkedInstallmentId,
        )
}
