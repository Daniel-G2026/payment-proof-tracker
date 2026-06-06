package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import com.danielanazodo.proofledger.data.local.entity.LocalUnitEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.ProjectRepository
import com.danielanazodo.proofledger.data.repository.UnitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectDetailUiState(
    val project: LocalProjectEntity? = null,
    val units: List<LocalUnitEntity> = emptyList(),
    val buyers: List<LocalCounterpartyEntity> = emptyList(),
    val newUnitLabel: String = "",
)

/**
 * One project and its units (Phase 13 Cut 1). Lets the user add units, assign a
 * buyer (a counterparty) to each, and remove units. The buyer list is the
 * business's counterparties — a buyer is just a counterparty, so any can be
 * picked.
 */
@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val businessRepository: BusinessRepository,
    private val projectRepository: ProjectRepository,
    private val unitRepository: UnitRepository,
    private val counterpartyRepository: CounterpartyRepository,
) : ViewModel() {
    private val projectId: String = checkNotNull(savedStateHandle["projectId"])
    private val _state = MutableStateFlow(ProjectDetailUiState())
    val state: StateFlow<ProjectDetailUiState> = _state.asStateFlow()
    private var businessId: String? = null

    init {
        viewModelScope.launch {
            businessId = businessRepository.getSelectedBusinessId()
            _state.update { it.copy(project = projectRepository.getById(projectId)) }
        }
        viewModelScope.launch {
            unitRepository.observeByProject(projectId).collect { rows ->
                _state.update { it.copy(units = rows) }
            }
        }
        viewModelScope.launch {
            val id = businessRepository.getSelectedBusinessId() ?: return@launch
            counterpartyRepository.observeByBusiness(id).collect { rows ->
                _state.update { it.copy(buyers = rows) }
            }
        }
    }

    fun onNewUnitLabelChange(value: String) = _state.update { it.copy(newUnitLabel = value) }

    fun onAddUnit() {
        val label = _state.value.newUnitLabel.trim().ifBlank { null } ?: return
        _state.update { it.copy(newUnitLabel = "") }
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            businessId = id
            unitRepository.createUnit(businessId = id, projectId = projectId, label = label)
        }
    }

    fun onAssignBuyer(unitId: String, buyerCounterpartyId: String?) {
        viewModelScope.launch {
            // Pass-through null leaves the buyer unchanged in updateUnit, so to
            // truly clear a buyer here we re-create the row with null buyer.
            // The picker only assigns/reassigns to a real buyer.
            unitRepository.updateUnit(unitId = unitId, buyerCounterpartyId = buyerCounterpartyId)
        }
    }

    /** Create a new person from the buyer picker and assign them in one step —
     * so the user never has to leave the unit to add a missing buyer. */
    fun onCreateAndAssignBuyer(unitId: String, name: String, type: String = "customer") {
        val clean = name.trim().ifBlank { null } ?: return
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            businessId = id
            val created = counterpartyRepository.createCounterparty(
                businessId = id,
                name = clean,
                type = type,
                phone = null,
                notes = null,
            )
            unitRepository.updateUnit(unitId = unitId, buyerCounterpartyId = created.id)
        }
    }

    fun onDeleteUnit(unitId: String) {
        viewModelScope.launch { unitRepository.deleteUnit(unitId) }
    }
}
