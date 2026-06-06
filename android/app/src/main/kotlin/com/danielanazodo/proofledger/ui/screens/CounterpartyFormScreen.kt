package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerScreen
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.CounterpartyFormViewModel

@Composable
fun CounterpartyFormScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: CounterpartyFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = rememberDebouncedClick(onClick = onBack)
    val save = rememberDebouncedClick(onClick = viewModel::save)
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    ProofLedgerScreen(
        title = if (state.counterpartyId == null) "Add person" else "Edit person",
        onBack = back,
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProofLedgerTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = "Name",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CounterpartyTypeChips(
                selectedType = state.type,
                onTypeChange = { type -> type?.let(viewModel::onTypeChange) },
                includeAll = false,
                types = state.types,
            )
            ProofLedgerTextField(
                value = state.phone,
                onValueChange = viewModel::onPhoneChange,
                label = "Phone",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ProofLedgerTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = "Notes",
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(
                text = if (state.loading) "Saving..." else "Save person",
                onClick = save,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Check,
            )
        }
    }
}
