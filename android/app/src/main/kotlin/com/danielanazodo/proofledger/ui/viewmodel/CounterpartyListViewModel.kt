package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyTypeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounterpartyListUiState(
    val counterparties: List<LocalCounterpartyEntity> = emptyList(),
    val search: String = "",
    val type: String? = null,
    val types: List<String> = emptyList(),
)

@HiltViewModel
class CounterpartyListViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val counterpartyTypeRepository: CounterpartyTypeRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CounterpartyListUiState())
    val state: StateFlow<CounterpartyListUiState> = _state.asStateFlow()
    private var businessId: String? = null
    private var filterJob: Job? = null

    init {
        viewModelScope.launch {
            businessId = businessRepository.getSelectedBusinessId()
            observe()
            businessId?.let { counterpartyRepository.refreshFromRemote(it) }
        }
        viewModelScope.launch {
            val id = businessRepository.getSelectedBusinessId() ?: return@launch
            counterpartyTypeRepository.seedIfNeeded(id)
            counterpartyTypeRepository.observeTypes(id).collect { types ->
                _state.update { it.copy(types = types) }
            }
        }
    }

    fun onSearchChange(value: String) {
        _state.update { it.copy(search = value) }
        observe()
    }

    fun onTypeChange(value: String?) {
        _state.update { it.copy(type = value) }
        observe()
    }

    private fun observe() {
        val id = businessId ?: return
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val s = _state.value
            counterpartyRepository.observeFiltered(
                businessId = id,
                search = s.search,
                type = s.type,
            ).collect { rows ->
                _state.update { it.copy(counterparties = rows) }
            }
        }
    }
}
