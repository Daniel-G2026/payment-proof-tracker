package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectListUiState(
    val projects: List<LocalProjectEntity> = emptyList(),
    val newProject: String = "",
)

@HiltViewModel
class ProjectListViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProjectListUiState())
    val state: StateFlow<ProjectListUiState> = _state.asStateFlow()
    private var businessId: String? = null

    init {
        viewModelScope.launch {
            val id = businessRepository.getSelectedBusinessId()
            businessId = id
            if (id != null) {
                // Local-first: show cached projects immediately and refresh in
                // the background. Awaiting the network here delayed the list
                // until the round-trip finished (or timed out).
                launch { projectRepository.refreshFromRemote(id) }
                projectRepository.observeByBusiness(id).collect { rows ->
                    _state.update { it.copy(projects = rows) }
                }
            }
        }
    }

    fun onNewProjectChange(value: String) = _state.update { it.copy(newProject = value) }

    fun onAddProject() {
        val name = _state.value.newProject.trim().ifBlank { null } ?: return
        _state.update { it.copy(newProject = "") }
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            businessId = id
            projectRepository.createProject(id, name)
        }
    }

    fun onDeleteProject(projectId: String) {
        viewModelScope.launch { projectRepository.deleteProject(projectId) }
    }
}
