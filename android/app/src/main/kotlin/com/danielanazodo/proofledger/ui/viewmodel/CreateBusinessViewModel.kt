package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.AuthRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateBusinessUiState(
    val name: String = "",
    val businessType: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val createdId: String? = null,
)

@HiltViewModel
class CreateBusinessViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val businessRepository: BusinessRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateBusinessUiState())
    val state: StateFlow<CreateBusinessUiState> = _state.asStateFlow()

    val businessTypes: List<String> = listOf(
        "contractor",
        "supplier",
        "subcontractor",
        "other",
    )

    fun onNameChange(v: String) = _state.update { it.copy(name = v, error = null) }
    fun onTypeChange(v: String?) = _state.update { it.copy(businessType = v) }

    fun save() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isBlank()) {
            _state.update { it.copy(error = "Business name is required") }
            return
        }
        val ownerId = authRepository.currentUserId()
        if (ownerId == null) {
            _state.update { it.copy(error = "Not signed in. Please restart the app.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                businessRepository.createBusiness(
                    ownerId = ownerId,
                    name = name,
                    businessType = s.businessType,
                )
            }
                .onSuccess { entity ->
                    businessRepository.selectBusiness(entity.id)
                    _state.update { it.copy(loading = false, createdId = entity.id) }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(loading = false, error = t.message ?: "Could not create business")
                    }
                }
        }
    }
}
