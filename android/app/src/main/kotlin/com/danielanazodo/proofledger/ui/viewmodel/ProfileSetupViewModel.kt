package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalProfileEntity
import com.danielanazodo.proofledger.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileSetupUiState(
    val fullName: String = "",
    val phone: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileSetupUiState())
    val state: StateFlow<ProfileSetupUiState> = _state.asStateFlow()

    // Once the user types, loaded values must never clobber their input —
    // the remote refresh can land mid-edit.
    private var userEdited = false

    init {
        viewModelScope.launch {
            // Local-first so the form pre-fills instantly (and offline) when the
            // user steps back to fix a typo...
            profileRepository.getCurrentProfile()?.let(::applyLoaded)
            // ...then refresh to ensure the backend's auto-created profile is
            // cached and the mirror is current.
            profileRepository.refreshFromRemote()?.let(::applyLoaded)
        }
    }

    private fun applyLoaded(p: LocalProfileEntity) {
        if (userEdited) return
        _state.update {
            it.copy(fullName = p.fullName.orEmpty(), phone = p.phone.orEmpty())
        }
    }

    fun onFullNameChange(v: String) {
        userEdited = true
        _state.update { it.copy(fullName = v, error = null, saved = false) }
    }

    fun onPhoneChange(v: String) {
        userEdited = true
        _state.update { it.copy(phone = v, error = null, saved = false) }
    }

    fun save() {
        val s = _state.value
        if (s.fullName.trim().isBlank()) {
            _state.update { it.copy(error = "Your name is required") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, saved = false) }
            runCatching {
                // Blank phone is sent as-is: it means "clear" (null would mean
                // "leave unchanged" and a deleted phone would silently survive).
                profileRepository.updateProfile(
                    fullName = s.fullName.trim(),
                    phone = s.phone.trim(),
                )
            }
                .onSuccess { _state.update { it.copy(loading = false, saved = true) } }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = t.message ?: "Could not save profile",
                            saved = false,
                        )
                    }
                }
        }
    }
}
