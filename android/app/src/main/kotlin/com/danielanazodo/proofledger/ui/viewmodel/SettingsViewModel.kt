package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.ProofBackupPreference
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity
import com.danielanazodo.proofledger.data.proof.ManualBackupFilter
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import com.danielanazodo.proofledger.data.sync.SyncIssueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val business: LocalBusinessEntity? = null,
    val profile: LocalProfileEntity? = null,
    val backupPreference: ProofBackupPreference = ProofBackupPreference.WIFI_ONLY,
    val pendingProofCount: Int = 0,
    val estimatedProofBytes: Long = 0,
    val syncIssueCount: Int = 0,
    val signedOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val profileRepository: ProfileRepository,
    private val proofRepository: ProofRepository,
    private val syncIssueRepository: SyncIssueRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val business = businessRepository.getSelectedBusiness()
            val profile = profileRepository.getCurrentProfile()
            val preference = proofRepository.getBackupPreference()
            _state.update {
                it.copy(
                    business = business,
                    profile = profile,
                    backupPreference = preference,
                )
            }
            val businessId = business?.id ?: return@launch
            val summary = proofRepository.manualBackupSummary(businessId, ManualBackupFilter())
            _state.update {
                it.copy(
                    pendingProofCount = summary.pendingCount,
                    estimatedProofBytes = summary.estimatedBytes,
                )
            }
            syncIssueRepository.observeIssueCount(businessId).collect { count ->
                _state.update { it.copy(syncIssueCount = count) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }
}
