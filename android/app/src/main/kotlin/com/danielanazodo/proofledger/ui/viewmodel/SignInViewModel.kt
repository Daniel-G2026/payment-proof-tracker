package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "proofledger.signin"

data class SignInUiState(
    val email: String = "",
    val code: String = "",
    val stage: Stage = Stage.EmailEntry,
    val loading: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
) {
    enum class Stage { EmailEntry, CodeEntry }
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onCodeChange(value: String) = _state.update {
        it.copy(code = value.filter(Char::isDigit).take(6), error = null)
    }

    fun sendOtp() {
        val email = _state.value.email.trim()
        if (!email.contains("@")) {
            _state.update { it.copy(error = "Enter a valid email") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.sendOtp(email) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            loading = false,
                            stage = SignInUiState.Stage.CodeEntry,
                        )
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, error = t.message ?: "Could not send code")
                    }
                }
        }
    }

    fun verifyOtp() {
        val s = _state.value
        if (s.code.length != 6) {
            _state.update { it.copy(error = "Enter the 6-digit code") }
            return
        }
        viewModelScope.launch {
            DebugLog.d(TAG) { "verifyOtp: submitting code (len=${s.code.length})" }
            _state.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.verifyOtp(s.email, s.code) }
                .onSuccess {
                    DebugLog.d(TAG) { "verifyOtp: success" }
                    _state.update { it.copy(loading = false, signedIn = true) }
                }
                .onFailure { t ->
                    DebugLog.e(TAG, { "verifyOtp: failed: ${t.javaClass.simpleName}" }, t)
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Incorrect code. Check the email and try again, " +
                                "or tap 'Use a different email' to get a new one.",
                        )
                    }
                }
        }
    }

    fun resetToEmailStage() {
        _state.update { it.copy(stage = SignInUiState.Stage.EmailEntry, code = "", error = null) }
    }
}
