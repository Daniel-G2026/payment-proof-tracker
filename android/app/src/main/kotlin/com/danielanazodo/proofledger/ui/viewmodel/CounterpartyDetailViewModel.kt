package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounterpartyDetailUiState(
    val counterparty: LocalCounterpartyEntity? = null,
    val transactions: List<LocalTransactionEntity> = emptyList(),
    val totalInMinor: Long = 0,
    val totalOutMinor: Long = 0,
    val lastTransactionDate: String? = null,
    val transactionCount: Int = 0,
    val deleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class CounterpartyDetailViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val transactionRepository: TransactionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val counterpartyId: String = checkNotNull(savedStateHandle["counterpartyId"])
    private val _state = MutableStateFlow(CounterpartyDetailUiState())
    val state: StateFlow<CounterpartyDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            counterpartyRepository.observeById(counterpartyId).collect { counterparty ->
                _state.update { it.copy(counterparty = counterparty) }
            }
        }
        viewModelScope.launch {
            val businessId = businessRepository.getSelectedBusinessId() ?: return@launch
            transactionRepository.observeByCounterparty(businessId, counterpartyId)
                .collect { transactions ->
                    _state.update {
                        it.copy(
                            transactions = transactions,
                            totalInMinor = transactions.sumDirection("money_in"),
                            totalOutMinor = transactions.sumDirection("money_out"),
                            lastTransactionDate = transactions.maxOfOrNull { row ->
                                row.transactionDate
                            },
                            transactionCount = transactions.size,
                        )
                    }
                }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { counterpartyRepository.deleteCounterparty(counterpartyId) }
                .onSuccess { _state.update { it.copy(deleted = true) } }
                .onFailure { t ->
                    _state.update { it.copy(error = t.message ?: "Could not delete counterparty") }
                }
        }
    }

    private fun List<LocalTransactionEntity>.sumDirection(direction: String): Long =
        filter { it.direction == direction }.sumOf { it.amountMinor }
}
