package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.proof.ProofRepository
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
    val monthMoneyInMinor: Long = 0,
    val monthMoneyOutMinor: Long = 0,
    val recordCount: Int = 0,
    val recordsWithProofCount: Int = 0,
    val missingProofCount: Int = 0,
    val syncing: Boolean = false,
    val signedOut: Boolean = false,
)

/**
 * Phase 2 dashboard is intentionally empty — just shows the selected business and
 * a sign-out affordance. Real totals + recent activity land in Phase 3.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val transactionRepository: TransactionRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val proofRepository: ProofRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            _state.update { it.copy(business = business) }
            val businessId = business?.id ?: return@launch
            viewModelScope.launch {
                _state.update { it.copy(syncing = true) }
                counterpartyRepository.syncPendingUploads(businessId)
                transactionRepository.syncPendingUploads(businessId)
                transactionRepository.refreshFromRemote(businessId)
                _state.update { it.copy(syncing = false) }
            }
            transactionRepository.observeTransactions(businessId).collect { transactions ->
                val today = todayIsoDate()
                val month = monthPrefix(today)
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
    }

    fun signOut() {
        viewModelScope.launch {
            // Intentionally do NOT wipe local caches here. Two reasons:
            //   1. Sign-out + sign-back-in by the SAME user is the common case;
            //      wiping forces a full re-fetch and (worse) permanently
            //      destroys proof files we have no way to re-download.
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
