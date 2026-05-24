package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
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
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            _state.update { it.copy(business = business) }
            val businessId = business?.id ?: return@launch
            transactionRepository.refreshFromRemote(businessId)
            transactionRepository.observeTransactions(businessId).collect { transactions ->
                val today = todayIsoDate()
                val month = monthPrefix(today)
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
                    )
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            // Wipe all cached user data BEFORE killing the Supabase session so
            // the next user (even if they sign in immediately) doesn't see any
            // of the previous user's profile/businesses/selected-business.
            transactionRepository.clearLocal()
            businessRepository.clearAllLocal()
            profileRepository.clearLocal()
            authRepository.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }

    private fun List<LocalTransactionEntity>.sumDirection(
        direction: String,
        include: (LocalTransactionEntity) -> Boolean,
    ): Long = filter { it.direction == direction && include(it) }.sumOf { it.amountMinor }
}
