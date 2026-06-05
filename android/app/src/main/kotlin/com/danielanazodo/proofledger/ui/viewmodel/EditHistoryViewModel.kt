package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.repository.EditHistoryRepository
import com.danielanazodo.proofledger.ui.history.EditHistoryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditHistoryUiState(
    val loading: Boolean = true,
    val entries: List<EditHistoryEntry> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class EditHistoryViewModel @Inject constructor(
    private val repository: EditHistoryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val businessId: String = checkNotNull(savedStateHandle["businessId"])
    private val entityType: String = checkNotNull(savedStateHandle["entityType"])
    private val entityId: String = checkNotNull(savedStateHandle["entityId"])

    private val _state = MutableStateFlow(EditHistoryUiState())
    val state: StateFlow<EditHistoryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.load(businessId, entityType, entityId)
                .onSuccess { entries ->
                    _state.update { it.copy(loading = false, entries = entries, error = null) }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = t.message ?: "Couldn't load history. Check your connection.",
                        )
                    }
                }
        }
    }
}
