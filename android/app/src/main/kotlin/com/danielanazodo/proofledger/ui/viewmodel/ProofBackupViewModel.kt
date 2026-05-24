package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.ProofBackupPreference
import com.danielanazodo.proofledger.data.proof.ManualBackupFilter
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.ui.format.parseNairaToMinor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProofBackupUiState(
    val preference: ProofBackupPreference = ProofBackupPreference.WIFI_ONLY,
    val businessId: String? = null,
    val importantOnly: Boolean = false,
    val direction: String? = null,
    val startDate: String = "",
    val endDate: String = "",
    val counterpartySearch: String = "",
    val minAmount: String = "",
    val maxAmount: String = "",
    val pendingCount: Int = 0,
    val estimatedBytes: Long = 0,
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ProofBackupViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val proofRepository: ProofRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProofBackupUiState())
    val state: StateFlow<ProofBackupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val businessId = businessRepository.getSelectedBusinessId()
            val preference = proofRepository.getBackupPreference()
            _state.update { it.copy(businessId = businessId, preference = preference) }
            refreshSummary()
        }
    }

    fun onPreferenceChange(preference: ProofBackupPreference) {
        viewModelScope.launch {
            proofRepository.setBackupPreference(preference)
            _state.update { it.copy(preference = preference, message = "Backup preference saved.") }
        }
    }

    fun onImportantOnlyChange(value: Boolean) {
        _state.update { it.copy(importantOnly = value, message = null, error = null) }
        refreshSummary()
    }

    fun onDirectionChange(value: String?) {
        _state.update { it.copy(direction = value, message = null, error = null) }
        refreshSummary()
    }

    fun onStartDateChange(value: String) {
        _state.update { it.copy(startDate = value, message = null, error = null) }
        refreshSummary()
    }

    fun onEndDateChange(value: String) {
        _state.update { it.copy(endDate = value, message = null, error = null) }
        refreshSummary()
    }

    fun onCounterpartySearchChange(value: String) {
        _state.update { it.copy(counterpartySearch = value, message = null, error = null) }
        refreshSummary()
    }

    fun onMinAmountChange(value: String) {
        _state.update { it.copy(minAmount = value, message = null, error = null) }
        refreshSummary()
    }

    fun onMaxAmountChange(value: String) {
        _state.update { it.copy(maxAmount = value, message = null, error = null) }
        refreshSummary()
    }

    fun backUpSelected() {
        viewModelScope.launch {
            val businessId = _state.value.businessId ?: return@launch
            _state.update { it.copy(loading = true, error = null, message = null) }
            runCatching { proofRepository.queueManualBackup(businessId, filter()) }
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            loading = false,
                            message = "$count proof${if (count == 1) "" else "s"} queued for backup.",
                        )
                    }
                    refreshSummary()
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, error = t.message ?: "Could not queue backup.")
                    }
                }
        }
    }

    private fun refreshSummary() {
        viewModelScope.launch {
            val businessId = _state.value.businessId ?: return@launch
            val summary = proofRepository.manualBackupSummary(businessId, filter())
            _state.update {
                it.copy(pendingCount = summary.pendingCount, estimatedBytes = summary.estimatedBytes)
            }
        }
    }

    private fun filter(): ManualBackupFilter {
        val current = _state.value
        return ManualBackupFilter(
            importantOnly = current.importantOnly,
            direction = current.direction,
            counterpartySearch = current.counterpartySearch.trim().ifBlank { null },
            startDate = current.startDate.trim().ifBlank { null },
            endDate = current.endDate.trim().ifBlank { null },
            minAmountMinor = parseNairaToMinor(current.minAmount),
            maxAmountMinor = parseNairaToMinor(current.maxAmount),
        )
    }
}
