package com.danielanazodo.proofledger.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import com.danielanazodo.proofledger.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "proofledger.startup"

sealed interface StartupState {
    data object Loading : StartupState
    data class Resolved(val route: Route) : StartupState
}

/**
 * Runs the navigation state machine on app launch:
 *
 *   1. No Supabase session         -> SignIn
 *   2. Session but no LocalProfile -> ProfileSetup
 *   3. Profile but 0 businesses    -> refresh remote -> CreateBusiness or fall through
 *   4. >=1 business, none selected -> auto-select if 1, else BusinessSelect
 *   5. Selected business set       -> Dashboard
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val businessRepository: BusinessRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        viewModelScope.launch { resolve() }
    }

    fun rerun() {
        viewModelScope.launch {
            _state.value = StartupState.Loading
            resolve()
        }
    }

    private suspend fun resolve() {
        Log.d(TAG, "resolve: start")
        // 1.
        val session = authRepository.currentSession()
        Log.d(TAG, "resolve: session=${session != null} userId=${session?.user?.id}")
        if (session == null) {
            Log.d(TAG, "resolve: -> SignIn")
            _state.value = StartupState.Resolved(Route.SignIn)
            return
        }

        // 2. Ensure we have a local profile mirror (backend auto-creates one on first call).
        val profile = profileRepository.getCurrentProfile() ?: profileRepository.refreshFromRemote()
        Log.d(TAG, "resolve: profile=${profile?.id} fullName='${profile?.fullName}'")
        if (profile == null || profile.fullName.isNullOrBlank()) {
            Log.d(TAG, "resolve: -> ProfileSetup")
            _state.value = StartupState.Resolved(Route.ProfileSetup)
            return
        }

        // 3. Businesses.
        val businesses = businessRepository.refreshFromRemote()
        Log.d(TAG, "resolve: businesses.size=${businesses.size}")
        if (businesses.isEmpty()) {
            Log.d(TAG, "resolve: -> CreateBusiness")
            _state.value = StartupState.Resolved(Route.CreateBusiness)
            return
        }

        // 4. Selection.
        val selectedId = businessRepository.getSelectedBusinessId()?.takeIf { it.isNotBlank() }
        if (selectedId == null || businesses.none { it.id == selectedId }) {
            if (businesses.size == 1) {
                businessRepository.selectBusiness(businesses.first().id)
                Log.d(TAG, "resolve: auto-selected only business -> Dashboard")
                _state.value = StartupState.Resolved(Route.Dashboard)
            } else {
                Log.d(TAG, "resolve: -> BusinessSelect")
                _state.value = StartupState.Resolved(Route.BusinessSelect)
            }
            return
        }

        // 5.
        Log.d(TAG, "resolve: -> Dashboard")
        _state.value = StartupState.Resolved(Route.Dashboard)
    }
}
