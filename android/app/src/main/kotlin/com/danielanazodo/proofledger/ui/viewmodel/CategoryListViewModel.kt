package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.entity.LocalCategoryEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryListUiState(
    val categories: List<LocalCategoryEntity> = emptyList(),
    val newCategory: String = "",
)

@HiltViewModel
class CategoryListViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CategoryListUiState())
    val state: StateFlow<CategoryListUiState> = _state.asStateFlow()
    private var businessId: String? = null

    init {
        viewModelScope.launch {
            val id = businessRepository.getSelectedBusinessId()
            businessId = id
            if (id != null) {
                categoryRepository.refreshFromRemote(id)
                categoryRepository.observeByBusiness(id).collect { rows ->
                    _state.update { it.copy(categories = rows) }
                }
            }
        }
    }

    fun onNewCategoryChange(value: String) = _state.update { it.copy(newCategory = value) }

    fun onAddCategory() {
        val name = _state.value.newCategory.trim().ifBlank { null } ?: return
        val normalized = name.lowercase().replace(Regex("\\s+"), " ")
        // Don't create a duplicate of an existing (normalized) category.
        val exists = _state.value.categories.any { it.normalizedName == normalized }
        _state.update { it.copy(newCategory = "") }
        if (exists) return
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            businessId = id
            categoryRepository.createCategory(id, name)
        }
    }

    fun onDeleteCategory(categoryId: String) {
        viewModelScope.launch {
            categoryRepository.deleteCategory(categoryId)
        }
    }
}
