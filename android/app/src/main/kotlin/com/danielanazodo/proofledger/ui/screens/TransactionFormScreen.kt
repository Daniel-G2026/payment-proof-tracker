package com.danielanazodo.proofledger.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.format.displayDate
import com.danielanazodo.proofledger.ui.viewmodel.TransactionFormViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun TransactionFormScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: TransactionFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                if (state.transactionId == null) "Add transaction" else "Edit transaction",
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.direction == "money_in",
                    onClick = { viewModel.onDirectionChange("money_in") },
                    label = { Text("Money in") },
                )
                FilterChip(
                    selected = state.direction == "money_out",
                    onClick = { viewModel.onDirectionChange("money_out") },
                    label = { Text("Money out") },
                )
            }
            OutlinedTextField(
                value = state.amount,
                onValueChange = viewModel::onAmountChange,
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    showDatePicker(context, state.transactionDate, viewModel::onDateChange)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Date: ${displayDate(state.transactionDate)}")
            }
            OutlinedTextField(
                value = state.counterpartyQuery,
                onValueChange = viewModel::onCounterpartyQueryChange,
                label = { Text(if (state.direction == "money_in") "Who paid?" else "Who you paid?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.suggestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.suggestions.forEach { counterparty ->
                        TextButton(
                            onClick = { viewModel.onCounterpartySelect(counterparty) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${counterparty.name} - ${counterpartyTypeLabel(counterparty.type)}")
                        }
                    }
                }
            }
            if (state.counterpartyQuery.isNotBlank() && state.counterpartyId == null) {
                AssistChip(
                    onClick = viewModel::onAddNewCounterparty,
                    label = { Text("Add new counterparty") },
                )
            }
            if (state.addingNewCounterparty) {
                Text("Counterparty type", style = MaterialTheme.typography.labelMedium)
                CounterpartyTypeChips(
                    selectedType = state.newCounterpartyType,
                    onTypeChange = { type -> type?.let(viewModel::onNewCounterpartyTypeChange) },
                    includeAll = false,
                )
            }
            OutlinedTextField(
                value = state.category,
                onValueChange = viewModel::onCategoryChange,
                label = { Text("Category") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.bankName,
                onValueChange = viewModel::onBankNameChange,
                label = { Text("Bank or provider") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.referenceNumber,
                onValueChange = viewModel::onReferenceNumberChange,
                label = { Text("Reference number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = { Text("Note") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.loading) "Saving..." else "Save")
            }
        }
    }
}

private fun showDatePicker(
    context: android.content.Context,
    currentIsoDate: String,
    onDateSelected: (String) -> Unit,
) {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val calendar = Calendar.getInstance()
    runCatching { format.parse(currentIsoDate) }.getOrNull()?.let { calendar.time = it }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            calendar.set(year, month, day)
            onDateSelected(format.format(calendar.time))
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    ).show()
}
