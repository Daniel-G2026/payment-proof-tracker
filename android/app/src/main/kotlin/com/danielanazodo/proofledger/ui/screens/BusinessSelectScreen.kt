package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.BusinessSelectViewModel

@Composable
fun BusinessSelectScreen(
    onSelected: () -> Unit,
    viewModel: BusinessSelectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectBusiness = rememberDebouncedClick<String> { id -> viewModel.select(id) }
    LaunchedEffect(state.selectedId) { if (state.selectedId != null) onSelected() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Choose a business", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "You have more than one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            LazyColumn {
                items(state.businesses, key = { it.id }) { biz ->
                    GroupedPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { selectBusiness(biz.id) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(biz.name, style = MaterialTheme.typography.titleLarge, color = NeutralInk)
                            biz.businessType?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeutralMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
