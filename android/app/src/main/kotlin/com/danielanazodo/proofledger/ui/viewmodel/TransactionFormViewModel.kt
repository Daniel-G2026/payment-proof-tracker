package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.ui.format.parseNairaToMinor
import com.danielanazodo.proofledger.ui.format.todayIsoDate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransactionFormUiState(
    val transactionId: String? = null,
    val direction: String = "money_in",
    val amount: String = "",
    val transactionDate: String = todayIsoDate(),
    val counterpartyName: String = "",
    val category: String = "",
    val note: String = "",
    val bankName: String = "",
    val referenceNumber: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val transactionRepository: TransactionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val transactionId: String? = savedStateHandle["transactionId"]
    private val initialDirection: String = savedStateHandle["direction"] ?: "money_in"

    private val _state = MutableStateFlow(
        TransactionFormUiState(transactionId = transactionId, direction = initialDirection)
    )
    val state: StateFlow<TransactionFormUiState> = _state.asStateFlow()

    init {
        if (transactionId != null) {
            viewModelScope.launch {
                transactionRepository.observeTransaction(transactionId).collect { transaction ->
                    if (transaction != null && !_state.value.loading) {
                        _state.update {
                            it.copy(
                                direction = transaction.direction,
                                amount = formatAmountForInput(transaction.amountMinor),
                                transactionDate = transaction.transactionDate,
                                counterpartyName = transaction.counterpartyName.orEmpty(),
                                category = transaction.category.orEmpty(),
                                note = transaction.note.orEmpty(),
                                bankName = transaction.bankName.orEmpty(),
                                referenceNumber = transaction.referenceNumber.orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun onDirectionChange(value: String) = _state.update { it.copy(direction = value, saved = false) }
    fun onAmountChange(value: String) = _state.update { it.copy(amount = value, error = null, saved = false) }
    fun onDateChange(value: String) = _state.update { it.copy(transactionDate = value, saved = false) }
    fun onCounterpartyNameChange(value: String) = _state.update {
        it.copy(counterpartyName = value, saved = false)
    }
    fun onCategoryChange(value: String) = _state.update { it.copy(category = value, saved = false) }
    fun onNoteChange(value: String) = _state.update { it.copy(note = value, saved = false) }
    fun onBankNameChange(value: String) = _state.update { it.copy(bankName = value, saved = false) }
    fun onReferenceNumberChange(value: String) = _state.update {
        it.copy(referenceNumber = value, saved = false)
    }

    fun save() {
        val current = _state.value
        val amountMinor = parseNairaToMinor(current.amount)
        if (amountMinor == null || amountMinor <= 0) {
            _state.update { it.copy(error = "Enter a valid amount") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, saved = false) }
            runCatching {
                val transactionId = current.transactionId
                if (transactionId == null) {
                    val businessId = businessRepository.getSelectedBusinessId()
                        ?: throw IllegalStateException("Select a business first.")
                    transactionRepository.createTransaction(
                        businessId = businessId,
                        amountMinor = amountMinor,
                        direction = current.direction,
                        transactionDate = current.transactionDate,
                        counterpartyName = current.counterpartyName.clean(),
                        category = current.category.clean(),
                        note = current.note.clean(),
                        bankName = current.bankName.clean(),
                        referenceNumber = current.referenceNumber.clean(),
                    )
                } else {
                    transactionRepository.updateTransaction(
                        transactionId = transactionId,
                        amountMinor = amountMinor,
                        direction = current.direction,
                        transactionDate = current.transactionDate,
                        counterpartyName = current.counterpartyName.clean(),
                        category = current.category.clean(),
                        note = current.note.clean(),
                        bankName = current.bankName.clean(),
                        referenceNumber = current.referenceNumber.clean(),
                    )
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, saved = true) }
            }.onFailure { t ->
                _state.update {
                    it.copy(loading = false, error = t.message ?: "Could not save transaction")
                }
            }
        }
    }

    private fun String.clean(): String? = trim().ifBlank { null }

    private fun formatAmountForInput(amountMinor: Long): String {
        val whole = amountMinor / 100
        val fraction = amountMinor % 100
        return if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
    }
}
