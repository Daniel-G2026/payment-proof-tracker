package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyTypeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounterpartyTypeListUiState(
    val types: List<String> = emptyList(),
    val newType: String = "",
)

@HiltViewModel
class CounterpartyTypeListViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val counterpartyTypeRepository: CounterpartyTypeRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CounterpartyTypeListUiState())
    val state: StateFlow<CounterpartyTypeListUiState> = _state.asStateFlow()
    private var businessId: String? = null

    init {
        viewModelScope.launch {
            val id = businessRepository.getSelectedBusinessId()
            businessId = id
            if (id != null) {
                counterpartyTypeRepository.seedIfNeeded(id)
                counterpartyTypeRepository.observeTypes(id).collect { types ->
                    _state.update { it.copy(types = types) }
                }
            }
        }
    }

    fun onNewTypeChange(value: String) = _state.update { it.copy(newType = value) }

    fun onAddType() {
        val raw = _state.value.newType.trim().ifBlank { null } ?: return
        _state.update { it.copy(newType = "") }
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            businessId = id
            counterpartyTypeRepository.addType(id, raw)
        }
    }

    fun onDeleteType(type: String) {
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            counterpartyTypeRepository.removeType(id, type)
        }
    }
}
