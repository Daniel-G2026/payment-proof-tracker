package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransactionListUiState(
    val transactions: List<LocalTransactionEntity> = emptyList(),
    val search: String = "",
    val direction: String? = null,
    val missingProofOnly: Boolean = false,
)

@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val transactionRepository: TransactionRepository,
    private val proofRepository: ProofRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TransactionListUiState())
    val state: StateFlow<TransactionListUiState> = _state.asStateFlow()
    private var businessId: String? = null
    private var filterJob: Job? = null

    init {
        viewModelScope.launch {
            businessId = businessRepository.getSelectedBusinessId()
            observe()
        }
    }

    fun onSearchChange(value: String) {
        _state.update { it.copy(search = value) }
        observe()
    }

    fun onDirectionChange(value: String?) {
        _state.update { it.copy(direction = value) }
        observe()
    }

    fun onMissingProofOnlyChange(value: Boolean) {
        _state.update { it.copy(missingProofOnly = value) }
        observe()
    }

    private fun observe() {
        val id = businessId ?: return
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val s = _state.value
            transactionRepository.observeFiltered(
                businessId = id,
                direction = s.direction,
                search = s.search.trim(),
            ).combine(proofRepository.observeProofs(id)) { rows, proofs ->
                if (!s.missingProofOnly) {
                    rows
                } else {
                    val transactionIdsWithProof = proofs.map { it.transactionId }.toSet()
                    rows.filter { it.id !in transactionIdsWithProof }
                }
            }.collect { rows ->
                _state.update { it.copy(transactions = rows) }
            }
        }
    }
}
