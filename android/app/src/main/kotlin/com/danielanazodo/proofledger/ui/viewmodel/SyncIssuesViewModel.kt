package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.sync.SyncIssue
import com.danielanazodo.proofledger.data.sync.SyncIssueRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncIssuesUiState(
    val businessId: String? = null,
    val issues: List<SyncIssue> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SyncIssuesViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val syncIssueRepository: SyncIssueRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SyncIssuesUiState())
    val state: StateFlow<SyncIssuesUiState> = _state.asStateFlow()
    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            val businessId = businessRepository.getSelectedBusinessId()
            _state.update { it.copy(businessId = businessId) }
            if (businessId != null) {
                observeJob?.cancel()
                observeJob = launch {
                    syncIssueRepository.observeIssues(businessId).collect { issues ->
                        _state.update { it.copy(issues = issues) }
                    }
                }
            }
        }
    }

    fun retrySync() {
        val businessId = _state.value.businessId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            syncIssueRepository.retrySync(businessId)
            _state.update { it.copy(loading = false, message = "Tried syncing again.") }
        }
    }

    fun retryProof(proofId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            runCatching { syncIssueRepository.retryProof(proofId) }
            _state.update { it.copy(loading = false, message = "Tried backing up proof again.") }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
