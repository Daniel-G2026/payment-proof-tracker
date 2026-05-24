package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransactionDetailUiState(
    val transaction: LocalTransactionEntity? = null,
    val proof: LocalScreenshotEntity? = null,
    val deleted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val proofRepository: ProofRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val transactionId: String = checkNotNull(savedStateHandle["transactionId"])
    private val _state = MutableStateFlow(TransactionDetailUiState())
    val state: StateFlow<TransactionDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                transactionRepository.observeTransaction(transactionId),
                proofRepository.observeProofForTransaction(transactionId),
            ) { transaction, proof -> transaction to proof }.collect { (transaction, proof) ->
                _state.update { it.copy(transaction = transaction, proof = proof) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { transactionRepository.deleteTransaction(transactionId) }
                .onSuccess { _state.update { it.copy(deleted = true) } }
                .onFailure { t ->
                    _state.update { it.copy(error = t.message ?: "Could not delete transaction") }
                }
        }
    }

    fun setProofImportant(important: Boolean) {
        val proofId = _state.value.proof?.id ?: return
        viewModelScope.launch {
            proofRepository.setImportant(proofId, important)
        }
    }

    fun backUpProofNow() {
        val proofId = _state.value.proof?.id ?: return
        viewModelScope.launch {
            proofRepository.backupNow(proofId)
        }
    }
}
