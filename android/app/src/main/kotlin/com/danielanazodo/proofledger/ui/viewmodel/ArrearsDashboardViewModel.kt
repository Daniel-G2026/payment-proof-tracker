package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.balance.ArrearsDashboard
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.DashboardArrearsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ArrearsDashboardUiState(
    val dashboard: ArrearsDashboard? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class ArrearsDashboardViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val dashboardArrearsRepository: DashboardArrearsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ArrearsDashboardUiState())
    val state: StateFlow<ArrearsDashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val businessId = businessRepository.getSelectedBusinessId() ?: run {
                _state.value = ArrearsDashboardUiState(dashboard = null, loading = false)
                return@launch
            }
            dashboardArrearsRepository.observe(businessId).collect { dashboard ->
                _state.value = ArrearsDashboardUiState(dashboard = dashboard, loading = false)
            }
        }
    }
}
