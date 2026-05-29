package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BusinessSelectUiState(
    val businesses: List<LocalBusinessEntity> = emptyList(),
    val loading: Boolean = true,
    val currentBusinessId: String? = null,
    val selectedId: String? = null,
    val selectedBusinessChanged: Boolean = false,
)

@HiltViewModel
class BusinessSelectViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BusinessSelectUiState())
    val state: StateFlow<BusinessSelectUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = businessRepository.getLocalBusinesses()
            val currentBusinessId = businessRepository.getSelectedBusinessId()
            _state.update {
                it.copy(
                    businesses = list,
                    currentBusinessId = currentBusinessId,
                    loading = false,
                )
            }
        }
    }

    fun select(businessId: String) {
        viewModelScope.launch {
            val changed = businessId != _state.value.currentBusinessId
            businessRepository.selectBusiness(businessId)
            _state.update {
                it.copy(
                    currentBusinessId = businessId,
                    selectedId = businessId,
                    selectedBusinessChanged = changed,
                )
            }
        }
    }
}
