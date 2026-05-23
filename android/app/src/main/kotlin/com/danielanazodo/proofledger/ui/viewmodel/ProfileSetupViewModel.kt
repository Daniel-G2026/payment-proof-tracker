package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    init {
        // Ensure backend has auto-created our profile + local mirror is populated.
        viewModelScope.launch {
            profileRepository.refreshFromRemote()?.let { p ->
                _state.update {
                    it.copy(fullName = p.fullName.orEmpty(), phone = p.phone.orEmpty())
                }
            }
        }
    }

    fun onFullNameChange(v: String) = _state.update { it.copy(fullName = v, error = null) }
    fun onPhoneChange(v: String) = _state.update { it.copy(phone = v, error = null) }

    fun save() {
        val s = _state.value
        if (s.fullName.trim().isBlank()) {
            _state.update { it.copy(error = "Your name is required") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                profileRepository.updateProfile(
                    fullName = s.fullName.trim(),
                    phone = s.phone.trim().ifBlank { null },
                )
            }
                .onSuccess { _state.update { it.copy(loading = false, saved = true) } }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, error = t.message ?: "Could not save profile")
                    }
                }
        }
    }
}
