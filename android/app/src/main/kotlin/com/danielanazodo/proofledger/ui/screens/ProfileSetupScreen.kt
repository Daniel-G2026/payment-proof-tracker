package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.ProfileSetupViewModel

@Composable
fun ProfileSetupScreen(
    onSaved: () -> Unit,
    viewModel: ProfileSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save = rememberDebouncedClick(onClick = viewModel::save)
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("Your name", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Just what you want to be called. You can change this later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            ProofLedgerTextField(
                value = state.fullName,
                onValueChange = viewModel::onFullNameChange,
                label = "Full name",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            ProofLedgerTextField(
                value = state.phone,
                onValueChange = viewModel::onPhoneChange,
                label = "Phone (optional)",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            PrimaryActionButton(
                text = if (state.loading) "Saving..." else "Continue",
                onClick = save,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
