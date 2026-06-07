package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.balance.ArrearsDashboard
import com.danielanazodo.proofledger.data.local.entity.LocalProjectEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.DashboardArrearsRepository
import com.danielanazodo.proofledger.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectListUiState(
    // All projects (filtered by the query when searching). The screen renders these
    // in a fixed-height scroll window, so the full list never grows the page.
    val projects: List<LocalProjectEntity> = emptyList(),
    val query: String = "",
    val total: Int = 0,
    val newProject: String = "",
    // Phase 13 Cut 4 summary, surfaced on the Projects tab. Null until computed.
    val arrears: ArrearsDashboard? = null,
)

@HiltViewModel
class ProjectListViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val projectRepository: ProjectRepository,
    private val dashboardArrearsRepository: DashboardArrearsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProjectListUiState())
    val state: StateFlow<ProjectListUiState> = _state.asStateFlow()
    private var businessId: String? = null
    // Full project list; the visible slice in state is derived from this + query.
    private var allProjects: List<LocalProjectEntity> = emptyList()

    init {
        viewModelScope.launch {
            val id = businessRepository.getSelectedBusinessId()
            businessId = id
            if (id != null) {
                // Local-first: show cached projects immediately and refresh in
                // the background. Awaiting the network here delayed the list
                // until the round-trip finished (or timed out).
                launch { projectRepository.refreshFromRemote(id) }
                launch {
                    dashboardArrearsRepository.observe(id).collect { arrears ->
                        _state.update { it.copy(arrears = arrears) }
                    }
                }
                projectRepository.observeByBusiness(id).collect { rows ->
                    allProjects = rows
                    applyFilter()
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        applyFilter()
    }

    private fun applyFilter() {
        // No recent cap: the screen shows every project inside a scroll window, so
        // we hand it the full list (newest first), filtered when the user searches.
        val slice = browseSlice(
            all = allProjects,
            query = _state.value.query,
            recencyKey = { it.createdAt },
            matches = { project, q -> project.name.contains(q, ignoreCase = true) },
            recentCap = Int.MAX_VALUE,
        )
        _state.update { it.copy(projects = slice.visible, total = slice.total) }
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
