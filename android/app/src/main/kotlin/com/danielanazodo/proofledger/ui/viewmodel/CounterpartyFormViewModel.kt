package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounterpartyFormUiState(
    val counterpartyId: String? = null,
    val name: String = "",
    val type: String = "customer",
    val phone: String = "",
    val notes: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class CounterpartyFormViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val counterpartyRepository: CounterpartyRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val counterpartyId: String? = savedStateHandle["counterpartyId"]
    private val _state = MutableStateFlow(CounterpartyFormUiState(counterpartyId = counterpartyId))
    val state: StateFlow<CounterpartyFormUiState> = _state.asStateFlow()

    init {
        if (counterpartyId != null) {
            viewModelScope.launch {
                val counterparty = counterpartyRepository.observeById(counterpartyId)
                    .filterNotNull()
                    .firstOrNull()
                if (counterparty != null) {
                    _state.update {
                        it.copy(
                            name = counterparty.name,
                            type = counterparty.type,
                            phone = counterparty.phone.orEmpty(),
                            notes = counterparty.notes.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null, saved = false) }
    fun onTypeChange(value: String) = _state.update { it.copy(type = value, saved = false) }
    fun onPhoneChange(value: String) = _state.update { it.copy(phone = value, saved = false) }
    fun onNotesChange(value: String) = _state.update { it.copy(notes = value, saved = false) }

    fun save() {
        val current = _state.value
        if (current.name.trim().isBlank()) {
            _state.update { it.copy(error = "Enter a name") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, saved = false) }
            runCatching {
                if (current.counterpartyId == null) {
                    val businessId = businessRepository.getSelectedBusinessId()
                        ?: throw IllegalStateException("Select a business first.")
                    counterpartyRepository.createCounterparty(
                        businessId = businessId,
                        name = current.name,
                        type = current.type,
                        phone = current.phone.clean(),
                        notes = current.notes.clean(),
                    )
                } else {
                    counterpartyRepository.updateCounterparty(
                        counterpartyId = current.counterpartyId,
                        name = current.name,
                        type = current.type,
                        phone = current.phone.clean(),
                        notes = current.notes.clean(),
                    )
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, saved = true) }
            }.onFailure { t ->
                _state.update {
                    it.copy(loading = false, error = t.message ?: "Could not save counterparty")
                }
            }
        }
    }

    private fun String.clean(): String? = trim().ifBlank { null }
}
