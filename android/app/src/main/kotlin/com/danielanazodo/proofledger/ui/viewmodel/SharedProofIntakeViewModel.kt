package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.danielanazodo.proofledger.data.proof.SharedProofIntakeItem
import com.danielanazodo.proofledger.data.proof.SharedProofIntakeRepository
import com.danielanazodo.proofledger.data.proof.SharedProofIntakeState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SharedProofIntakeViewModel @Inject constructor(
    private val repository: SharedProofIntakeRepository,
) : ViewModel() {
    val state: StateFlow<SharedProofIntakeState> = repository.state

    fun get(token: String): SharedProofIntakeItem? = repository.get(token)

    fun cancel(token: String) = repository.cancel(token)

    fun clearMessage() = repository.clearMessage()
}
