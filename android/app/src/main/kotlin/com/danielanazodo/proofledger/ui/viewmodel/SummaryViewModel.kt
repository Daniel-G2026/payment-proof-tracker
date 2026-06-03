package com.danielanazodo.proofledger.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.ReportRepository
import com.danielanazodo.proofledger.data.repository.ReportSummary
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.sync.SyncEventLogger
import com.danielanazodo.proofledger.ui.format.PeriodUnit
import com.danielanazodo.proofledger.ui.format.ReportPeriod
import com.danielanazodo.proofledger.ui.format.currentPeriod
import com.danielanazodo.proofledger.ui.format.periodsCovering
import com.danielanazodo.proofledger.ui.format.todayIsoDate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SummaryUiState(
    val businessName: String = "",
    val unit: PeriodUnit = PeriodUnit.MONTH,
    val period: ReportPeriod = currentPeriod(PeriodUnit.MONTH),
    val summary: ReportSummary? = null,
    val availablePeriods: List<ReportPeriod> = emptyList(),
    val canGoNext: Boolean = false,
    val exporting: Boolean = false,
    val shareUri: Uri? = null,
    val message: String? = null,
)

/**
 * Drives the Reports/Summary page. Totals and CSV are computed locally from
 * Room via [ReportRepository], so the whole screen works offline. The screen
 * only reads this state and sends events; it never touches DAOs, file IO, or
 * the share intent's plumbing directly (the intent itself is fired by the
 * screen from the [shareUri] one-shot, which is an Android UI concern).
 */
@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val transactionRepository: TransactionRepository,
    private val reportRepository: ReportRepository,
    private val syncEventLogger: SyncEventLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(SummaryUiState())
    val state: StateFlow<SummaryUiState> = _state.asStateFlow()

    private var allTransactions: List<LocalTransactionEntity> = emptyList()
    private var businessId: String? = null
    private var computeJob: Job? = null

    init {
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            businessId = business?.id
            _state.update { it.copy(businessName = business?.name.orEmpty()) }
            val id = business?.id ?: return@launch
            transactionRepository.observeTransactions(id).collect { transactions ->
                allTransactions = transactions
                recompute(includePeriods = true)
            }
        }
    }

    fun onUnitChange(unit: PeriodUnit) {
        if (unit == _state.value.unit) return
        _state.update { it.copy(unit = unit, period = currentPeriod(unit)) }
        // Unit change alters which periods exist in the jump list.
        recompute(includePeriods = true)
    }

    fun onPrevious() {
        _state.update { it.copy(period = it.period.previous()) }
        recompute(includePeriods = false)
    }

    fun onNext() {
        if (!_state.value.canGoNext) return
        _state.update { it.copy(period = it.period.next()) }
        recompute(includePeriods = false)
    }

    fun onPickPeriod(period: ReportPeriod) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period) }
        recompute(includePeriods = false)
    }

    fun onExportCsv() {
        val id = businessId ?: return
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            try {
                val uri = reportRepository.exportCsv(allTransactions, _state.value.period)
                syncEventLogger.log(
                    businessId = id,
                    entityType = "export",
                    entityId = null,
                    action = "export_created",
                    status = "success",
                )
                _state.update { it.copy(shareUri = uri) }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn't create the file. Try again.") }
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun onShareHandled() = _state.update { it.copy(shareUri = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Recompute the summary off the main thread so a large ledger never janks
     * the UI (aggregation + building a Calendar per record in [periodsCovering]
     * is O(n) with allocations). The job is cancellable so rapid period
     * stepping doesn't pile up stale computations — only the latest wins.
     *
     * [includePeriods] is false on plain period changes (prev/next/pick): the
     * jump list only depends on the record dates + unit, not the selected
     * period, so there's no need to rebuild it on every step.
     */
    private fun recompute(includePeriods: Boolean) {
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            val transactions = allTransactions
            val unit = _state.value.unit
            val period = _state.value.period
            val today = todayIsoDate()
            val (summary, periods) = withContext(Dispatchers.Default) {
                val computedSummary = reportRepository.summarize(transactions, period)
                val computedPeriods = if (includePeriods) {
                    periodsCovering(unit, transactions.map { it.transactionDate }, today)
                } else {
                    null
                }
                computedSummary to computedPeriods
            }
            _state.update {
                it.copy(
                    summary = summary,
                    availablePeriods = periods ?: it.availablePeriods,
                    // Block stepping into a period that hasn't finished/started
                    // yet: if the window ends before today, a later one is safe.
                    canGoNext = period.endIso < today,
                )
            }
        }
    }
}
