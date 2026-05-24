package com.danielanazodo.proofledger.ui.viewmodel

import com.danielanazodo.proofledger.util.DebugLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
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
 *   3. Profile but 0 cached businesses -> refresh remote -> CreateBusiness or fall through
 *   4. >=1 business, none selected -> auto-select if 1, else BusinessSelect
 *   5. Selected business set       -> Dashboard
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val businessRepository: BusinessRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val transactionRepository: TransactionRepository,
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
        DebugLog.d(TAG) { "resolve: start" }
        // 1.
        val session = authRepository.currentSession()
        val sessionUserId = session?.user?.id
        DebugLog.d(TAG) { "resolve: session=${session != null} userId=$sessionUserId" }
        if (session == null) {
            DebugLog.d(TAG) { "resolve: -> SignIn" }
            _state.value = StartupState.Resolved(Route.SignIn)
            return
        }

        // 1b. Defensive: if local data belongs to a different user (e.g. someone
        // signed out badly or installed an old build), wipe the cache so we
        // don't show the previous user's businesses to the new one.
        val cachedProfile = profileRepository.getCurrentProfile()
        if (cachedProfile != null && sessionUserId != null && cachedProfile.id != sessionUserId) {
            DebugLog.w(TAG, { "resolve: user mismatch, wiping local" })
            // Order matters: transactions first triggers proof-file cleanup,
            // then counterparties, then businesses (which clears the selected-
            // business pointer), then profile. The previous version skipped
            // transactions + counterparties, leaving the new user's dashboard
            // showing the old user's rows.
            transactionRepository.clearLocal()
            counterpartyRepository.clearLocal()
            businessRepository.clearAllLocal()
            profileRepository.clearLocal()
        }

        // 2. Ensure we have a local profile mirror (backend auto-creates one on first call).
        val profile = profileRepository.getCurrentProfile() ?: profileRepository.refreshFromRemote()
        DebugLog.d(TAG) { "resolve: profile=${profile?.id} fullName='${profile?.fullName}'" }
        if (profile == null || profile.fullName.isNullOrBlank()) {
            DebugLog.d(TAG) { "resolve: -> ProfileSetup" }
            _state.value = StartupState.Resolved(Route.ProfileSetup)
            return
        }

        // 3. Businesses. Prefer the local cache so startup does not block on
        // the network every time the app opens. Only hit the backend before
        // routing if there is no cached business to make a safe decision from.
        val cachedBusinesses = businessRepository.getLocalBusinesses()
        val businesses = cachedBusinesses.ifEmpty { businessRepository.refreshFromRemote() }
        DebugLog.d(TAG) { "resolve: businesses.size=${businesses.size}" }
        if (businesses.isEmpty()) {
            DebugLog.d(TAG) { "resolve: -> CreateBusiness" }
            _state.value = StartupState.Resolved(Route.CreateBusiness)
            return
        }

        // 4. Selection.
        val selectedId = businessRepository.getSelectedBusinessId()?.takeIf { it.isNotBlank() }
        if (selectedId == null || businesses.none { it.id == selectedId }) {
            if (businesses.size == 1) {
                businessRepository.selectBusiness(businesses.first().id)
                DebugLog.d(TAG) { "resolve: auto-selected only business -> Dashboard" }
                _state.value = StartupState.Resolved(Route.Dashboard)
                refreshBusinessesInBackground()
            } else {
                DebugLog.d(TAG) { "resolve: -> BusinessSelect" }
                _state.value = StartupState.Resolved(Route.BusinessSelect)
                refreshBusinessesInBackground()
            }
            return
        }

        // 5.
        DebugLog.d(TAG) { "resolve: -> Dashboard" }
        _state.value = StartupState.Resolved(Route.Dashboard)
        refreshBusinessesInBackground()
    }

    private fun refreshBusinessesInBackground() {
        viewModelScope.launch {
            runCatching { businessRepository.refreshFromRemote() }
        }
    }
}
