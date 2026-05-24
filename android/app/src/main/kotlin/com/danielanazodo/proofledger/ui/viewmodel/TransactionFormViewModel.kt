package com.danielanazodo.proofledger.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.proof.SharedProofIntakeRepository
import com.danielanazodo.proofledger.ui.format.parseNairaToMinor
import com.danielanazodo.proofledger.ui.format.todayIsoDate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransactionFormUiState(
    val transactionId: String? = null,
    val direction: String = "money_in",
    val amount: String = "",
    val transactionDate: String = todayIsoDate(),
    val counterpartyId: String? = null,
    val counterpartyQuery: String = "",
    val suggestions: List<LocalCounterpartyEntity> = emptyList(),
    val addingNewCounterparty: Boolean = false,
    val newCounterpartyType: String = defaultCounterpartyType(direction),
    val counterpartyName: String = "",
    val category: String = "",
    val note: String = "",
    val bankName: String = "",
    val referenceNumber: String = "",
    val proofUri: String? = null,
    val proofOriginalSizeBytes: Long? = null,
    /** Id of the proof row already attached to this transaction when the form
     *  opened. Used so we can delete the old proof when the user replaces it
     *  and avoid creating a duplicate row on every save. */
    val existingProofId: String? = null,
    /** True once the user has touched the proof field on this form session
     *  (picked, replaced, or removed). Until then, save() leaves the existing
     *  proof alone. */
    val proofDirty: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val transactionRepository: TransactionRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val proofRepository: ProofRepository,
    private val sharedProofIntakeRepository: SharedProofIntakeRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val transactionId: String? = savedStateHandle["transactionId"]
    private val initialDirection: String = savedStateHandle["direction"] ?: "money_in"
    private val initialIntakeToken: String? = savedStateHandle["intakeToken"]

    private val _state = MutableStateFlow(
        TransactionFormUiState(transactionId = transactionId, direction = initialDirection)
    )
    val state: StateFlow<TransactionFormUiState> = _state.asStateFlow()
    private var businessId: String? = null
    private var suggestionsJob: Job? = null
    private var activeIntakeToken: String? = null

    init {
        viewModelScope.launch {
            businessId = businessRepository.getSelectedBusinessId()
        }
        initialIntakeToken?.let { token ->
            val item = sharedProofIntakeRepository.get(token)
            if (item == null) {
                _state.update { it.copy(error = "Shared proof is no longer available.") }
            } else {
                activeIntakeToken = token
                _state.update {
                    it.copy(
                        proofUri = item.localUri,
                        proofOriginalSizeBytes = item.sizeBytes,
                        // Share-sheet intake is always a fresh proof to attach.
                        proofDirty = true,
                    )
                }
            }
        }
        if (transactionId != null) {
            // Seed the form once from the local row, then stop collecting.
            // Continuing to collect would let a background dashboard refresh
            // (or any other write to this row) overwrite the user's in-progress
            // edits while they're typing.
            viewModelScope.launch {
                val transaction = transactionRepository.observeTransaction(transactionId)
                    .filterNotNull()
                    .firstOrNull()
                if (transaction != null) {
                    val existingProof = proofRepository
                        .observeProofForTransaction(transactionId)
                        .firstOrNull()
                    _state.update {
                        it.copy(
                            direction = transaction.direction,
                            amount = formatAmountForInput(transaction.amountMinor),
                            transactionDate = transaction.transactionDate,
                            counterpartyId = transaction.counterpartyId,
                            counterpartyQuery = transaction.counterpartyName.orEmpty(),
                            counterpartyName = transaction.counterpartyName.orEmpty(),
                            category = transaction.category.orEmpty(),
                            note = transaction.note.orEmpty(),
                            bankName = transaction.bankName.orEmpty(),
                            referenceNumber = transaction.referenceNumber.orEmpty(),
                            // Surface the existing proof in the UI without
                            // marking dirty, so leaving it untouched on save
                            // doesn't re-attach a duplicate.
                            proofUri = existingProof?.localOriginalPath,
                            proofOriginalSizeBytes = existingProof?.originalFileSizeBytes,
                            existingProofId = existingProof?.id,
                            proofDirty = false,
                        )
                    }
                }
            }
        }
    }

    fun onDirectionChange(value: String) = _state.update {
        val previousDefault = defaultCounterpartyType(it.direction)
        it.copy(
            direction = value,
            newCounterpartyType = if (it.newCounterpartyType == previousDefault) {
                defaultCounterpartyType(value)
            } else {
                it.newCounterpartyType
            },
            saved = false,
        )
    }
    fun onAmountChange(value: String) = _state.update { it.copy(amount = value, error = null, saved = false) }
    fun onDateChange(value: String) = _state.update { it.copy(transactionDate = value, saved = false) }
    fun onCounterpartyQueryChange(value: String) {
        _state.update {
            it.copy(
                counterpartyId = null,
                counterpartyQuery = value,
                counterpartyName = value,
                suggestions = emptyList(),
                addingNewCounterparty = false,
                saved = false,
            )
        }
        observeSuggestions(value)
    }

    fun onCounterpartySelect(counterparty: LocalCounterpartyEntity) {
        suggestionsJob?.cancel()
        _state.update {
            it.copy(
                counterpartyId = counterparty.id,
                counterpartyQuery = counterparty.name,
                counterpartyName = counterparty.name,
                suggestions = emptyList(),
                addingNewCounterparty = false,
                newCounterpartyType = counterparty.type,
                saved = false,
            )
        }
    }

    fun onAddNewCounterparty() {
        suggestionsJob?.cancel()
        _state.update {
            it.copy(
                addingNewCounterparty = true,
                suggestions = emptyList(),
                newCounterpartyType = defaultCounterpartyType(it.direction),
                saved = false,
            )
        }
    }

    fun onNewCounterpartyTypeChange(value: String) = _state.update {
        it.copy(newCounterpartyType = value, saved = false)
    }
    fun onCategoryChange(value: String) = _state.update { it.copy(category = value, saved = false) }
    fun onNoteChange(value: String) = _state.update { it.copy(note = value, saved = false) }
    fun onBankNameChange(value: String) = _state.update { it.copy(bankName = value, saved = false) }
    fun onReferenceNumberChange(value: String) = _state.update {
        it.copy(referenceNumber = value, saved = false)
    }
    fun onProofSelected(uri: String, sizeBytes: Long?) {
        clearActiveIntake()
        _state.update {
            it.copy(
                proofUri = uri,
                proofOriginalSizeBytes = sizeBytes,
                proofDirty = true,
                error = null,
                saved = false,
            )
        }
    }
    fun onProofRemoved() {
        clearActiveIntake()
        _state.update {
            it.copy(
                proofUri = null,
                proofOriginalSizeBytes = null,
                proofDirty = true,
                saved = false,
            )
        }
    }

    fun discardIntakeProof() {
        clearActiveIntake()
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
                val selectedBusinessId = businessId ?: businessRepository.getSelectedBusinessId()
                    ?: throw IllegalStateException("Select a business first.")
                businessId = selectedBusinessId
                val linkedCounterparty = createInlineCounterpartyIfNeeded(
                    current = current,
                    businessId = selectedBusinessId,
                )
                val counterpartyId = linkedCounterparty?.id ?: current.counterpartyId
                val counterpartyName = linkedCounterparty?.name ?: current.counterpartyName.clean()
                val savedTransaction = if (transactionId == null) {
                    transactionRepository.createTransaction(
                        businessId = selectedBusinessId,
                        amountMinor = amountMinor,
                        direction = current.direction,
                        transactionDate = current.transactionDate,
                        counterpartyId = counterpartyId,
                        counterpartyName = counterpartyName,
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
                        counterpartyId = counterpartyId,
                        counterpartyName = counterpartyName,
                        category = current.category.clean(),
                        note = current.note.clean(),
                        bankName = current.bankName.clean(),
                        referenceNumber = current.referenceNumber.clean(),
                    )
                }
                // Only touch proof storage when the user actually changed
                // something on this form session. Otherwise we'd re-attach the
                // same proof every save and accumulate duplicate rows + files.
                if (current.proofDirty) {
                    current.existingProofId?.let { proofRepository.deleteProof(it) }
                    current.proofUri?.let { uri ->
                        proofRepository.attachProof(Uri.parse(uri), savedTransaction)
                    }
                }
                activeIntakeToken?.let { token ->
                    sharedProofIntakeRepository.complete(token)
                    activeIntakeToken = null
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

    private fun observeSuggestions(query: String) {
        suggestionsJob?.cancel()
        if (query.trim().isBlank()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionsJob = viewModelScope.launch {
            delay(250)
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            businessId = id
            counterpartyRepository.observeFiltered(
                businessId = id,
                search = query,
                type = null,
            ).collect { rows ->
                _state.update { it.copy(suggestions = rows.take(5)) }
            }
        }
    }

    private suspend fun createInlineCounterpartyIfNeeded(
        current: TransactionFormUiState,
        businessId: String,
    ): LocalCounterpartyEntity? {
        if (!current.addingNewCounterparty) return null
        val name = current.counterpartyQuery.clean()
            ?: throw IllegalStateException("Enter who paid or who you paid.")
        return counterpartyRepository.createCounterparty(
            businessId = businessId,
            name = name,
            type = current.newCounterpartyType,
            phone = null,
            notes = null,
        )
    }

    private fun String.clean(): String? = trim().ifBlank { null }

    private fun clearActiveIntake() {
        activeIntakeToken?.let { token ->
            sharedProofIntakeRepository.cancel(token)
            activeIntakeToken = null
        }
    }

    private fun formatAmountForInput(amountMinor: Long): String {
        val whole = amountMinor / 100
        val fraction = amountMinor % 100
        return if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

private fun defaultCounterpartyType(direction: String): String =
    if (direction == "money_out") "supplier" else "customer"
