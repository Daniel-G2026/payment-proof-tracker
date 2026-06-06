package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.balance.ArrearsDashboard
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CategoryRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.DashboardArrearsRepository
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.sync.SyncIssueRepository
import com.danielanazodo.proofledger.ui.format.currentWeekRange
import com.danielanazodo.proofledger.ui.format.monthPrefix
import com.danielanazodo.proofledger.ui.format.todayIsoDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val business: LocalBusinessEntity? = null,
    val recentTransactions: List<LocalTransactionEntity> = emptyList(),
    val todayMoneyInMinor: Long = 0,
    val todayMoneyOutMinor: Long = 0,
    val weekMoneyInMinor: Long = 0,
    val weekMoneyOutMinor: Long = 0,
    val monthMoneyInMinor: Long = 0,
    val monthMoneyOutMinor: Long = 0,
    val recordCount: Int = 0,
    val recordsWithProofCount: Int = 0,
    val missingProofCount: Int = 0,
    val syncIssueCount: Int = 0,
    val syncing: Boolean = false,
    val signedOut: Boolean = false,
    /** Proofs backed up to this account whose images aren't on this device yet
     *  and are awaiting a download decision. Non-zero only just after a
     *  fresh-device restore; drives the "download your proofs?" prompt. */
    val pendingProofDownloadCount: Int = 0,
    /** True while a "Download now" pass is running, so the prompt can show a
     *  busy state instead of letting the user tap repeatedly. */
    val downloadingProofs: Boolean = false,
    /** Phase 13 Cut 4: monthly arrears summary. Null until computed; the Home
     *  hero card shows only when there are buyers due or behind. */
    val arrears: ArrearsDashboard? = null,
)

/**
 * Phase 2 dashboard is intentionally empty — just shows the selected business and
 * a sign-out affordance. Real totals + recent activity land in Phase 3.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val proofRepository: ProofRepository,
    private val syncIssueRepository: SyncIssueRepository,
    private val dashboardArrearsRepository: DashboardArrearsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            _state.update { it.copy(business = business) }
            val businessId = business?.id ?: return@launch
            viewModelScope.launch {
                dashboardArrearsRepository.observe(businessId).collect { arrears ->
                    _state.update { it.copy(arrears = arrears) }
                }
            }
            viewModelScope.launch {
                _state.update { it.copy(syncing = true) }
                try {
                    categoryRepository.syncPending(businessId)
                    counterpartyRepository.syncPendingUploads(businessId)
                    transactionRepository.syncPendingUploads(businessId)
                    categoryRepository.refreshFromRemote(businessId)
                    transactionRepository.refreshFromRemote(businessId)
                    // Restore proof metadata (badges + the download prompt) on
                    // every dashboard open. Metadata only — image bytes are
                    // pulled on the user's terms, never silently on mobile data.
                    proofRepository.refreshFromRemote(businessId)
                } finally {
                    _state.update { it.copy(syncing = false) }
                }
            }
            transactionRepository.observeTransactions(businessId).collect { transactions ->
                val today = todayIsoDate()
                val month = monthPrefix(today)
                val (weekStart, weekEnd) = currentWeekRange()
                val currentProofCount = _state.value.recordsWithProofCount
                _state.update {
                    it.copy(
                        recentTransactions = transactions.take(5),
                        todayMoneyInMinor = transactions.sumDirection("money_in") { row ->
                            row.transactionDate == today
                        },
                        todayMoneyOutMinor = transactions.sumDirection("money_out") { row ->
                            row.transactionDate == today
                        },
                        weekMoneyInMinor = transactions.sumDirection("money_in") { row ->
                            row.transactionDate in weekStart..weekEnd
                        },
                        weekMoneyOutMinor = transactions.sumDirection("money_out") { row ->
                            row.transactionDate in weekStart..weekEnd
                        },
                        monthMoneyInMinor = transactions.sumDirection("money_in") { row ->
                            row.transactionDate.startsWith(month)
                        },
                        monthMoneyOutMinor = transactions.sumDirection("money_out") { row ->
                            row.transactionDate.startsWith(month)
                        },
                        recordCount = transactions.size,
                        missingProofCount = (transactions.size - currentProofCount).coerceAtLeast(0),
                    )
                }
            }
        }
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            val businessId = business?.id ?: return@launch
            proofRepository.observeProofs(businessId).collect { proofs ->
                val recordsWithProof = proofs.map { it.transactionId }.toSet().size
                _state.update {
                    it.copy(
                        recordsWithProofCount = recordsWithProof,
                        missingProofCount = (it.recordCount - recordsWithProof).coerceAtLeast(0),
                    )
                }
            }
        }
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            val businessId = business?.id ?: return@launch
            syncIssueRepository.observeIssueCount(businessId).collect { count ->
                _state.update { it.copy(syncIssueCount = count) }
            }
        }
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            val businessId = business?.id ?: return@launch
            proofRepository.observePendingDownloadCount(businessId).collect { count ->
                _state.update { it.copy(pendingProofDownloadCount = count) }
            }
        }
    }

    /** "Download now": pull every not-yet-downloaded proof onto this device,
     *  regardless of network. The pending count falls to zero as rows download,
     *  which dismisses the prompt. */
    fun downloadProofsNow() {
        val businessId = _state.value.business?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(downloadingProofs = true) }
            try {
                proofRepository.downloadAllProofsNow(businessId)
            } finally {
                _state.update { it.copy(downloadingProofs = false) }
            }
        }
    }

    /** "Wait for Wi-Fi": defer proof downloads to a WiFi-only background pass.
     *  Pending rows leave the awaiting-decision state, so the prompt clears. */
    fun waitForWifiProofDownload() {
        val businessId = _state.value.business?.id ?: return
        viewModelScope.launch {
            proofRepository.scheduleProofDownloadsOnWifi(businessId)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            // Intentionally do NOT wipe local caches here. Two reasons:
            //   1. Sign-out + sign-back-in by the SAME user is the common case;
            //      wiping forces a full re-fetch (and a re-download of every
            //      proof, even though restore now makes that possible) for no
            //      benefit.
            //   2. Sign-in by a DIFFERENT user on this device is handled by
            //      StartupViewModel's user-mismatch detector, which wipes
            //      everything (including cascading proof cleanup) at that
            //      point — which is the only point where it's actually unsafe
            //      to keep the previous user's data around.
            authRepository.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }

    private fun List<LocalTransactionEntity>.sumDirection(
        direction: String,
        include: (LocalTransactionEntity) -> Boolean,
    ): Long = filter { it.direction == direction && include(it) }.sumOf { it.amountMinor }
}
