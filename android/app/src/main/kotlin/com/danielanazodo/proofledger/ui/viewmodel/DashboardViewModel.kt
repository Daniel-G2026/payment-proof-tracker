package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalBusinessEntity
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val business: LocalBusinessEntity? = null,
    val signedOut: Boolean = false,
)

/**
 * Phase 2 dashboard is intentionally empty — just shows the selected business and
 * a sign-out affordance. Real totals + recent activity land in Phase 3.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(business = businessRepository.getSelectedBusiness()) }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            // Wipe all cached user data BEFORE killing the Supabase session so
            // the next user (even if they sign in immediately) doesn't see any
            // of the previous user's profile/businesses/selected-business.
            businessRepository.clearAllLocal()
            profileRepository.clearLocal()
            authRepository.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }
}
