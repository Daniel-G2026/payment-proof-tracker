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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.PrimaryActionButton
import com.danielanazodo.proofledger.ui.components.ProofLedgerTextField
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.SignInUiState
import com.danielanazodo.proofledger.ui.viewmodel.SignInViewModel

@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "ProofLedger",
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sign in to get started",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            when (state.stage) {
                SignInUiState.Stage.EmailEntry -> EmailEntry(
                    state = state,
                    onEmailChange = viewModel::onEmailChange,
                    onSubmit = viewModel::sendOtp,
                )
                SignInUiState.Stage.CodeEntry -> CodeEntry(
                    state = state,
                    onCodeChange = viewModel::onCodeChange,
                    onSubmit = viewModel::verifyOtp,
                    onChangeEmail = viewModel::resetToEmailStage,
                )
            }

            state.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmailEntry(
    state: SignInUiState,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val submit = rememberDebouncedClick(onClick = onSubmit)
    ProofLedgerTextField(
        value = state.email,
        onValueChange = onEmailChange,
        label = "Email",
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    PrimaryActionButton(
        text = if (state.loading) "Sending..." else "Send code",
        onClick = submit,
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeEntry(
    state: SignInUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onChangeEmail: () -> Unit,
) {
    val submit = rememberDebouncedClick(onClick = onSubmit)
    val changeEmail = rememberDebouncedClick(onClick = onChangeEmail)
    Text(
        text = "We sent a 6-digit code to ${state.email}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    ProofLedgerTextField(
        value = state.code,
        onValueChange = onCodeChange,
        label = "6-digit code",
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    PrimaryActionButton(
        text = if (state.loading) "Verifying..." else "Verify",
        onClick = submit,
        enabled = !state.loading && state.code.length == 6,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = changeEmail) { Text("Use a different email") }
}
