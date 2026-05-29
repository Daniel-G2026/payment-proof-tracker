package com.danielanazodo.proofledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielanazodo.proofledger.ui.components.GroupedPanel
import com.danielanazodo.proofledger.ui.components.PageHeader
import com.danielanazodo.proofledger.ui.theme.NeutralInk
import com.danielanazodo.proofledger.ui.theme.NeutralMuted
import com.danielanazodo.proofledger.ui.theme.ProofLime
import com.danielanazodo.proofledger.ui.util.rememberDebouncedClick
import com.danielanazodo.proofledger.ui.viewmodel.BusinessSelectViewModel

@Composable
fun BusinessSelectScreen(
    onSelected: (changed: Boolean) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: BusinessSelectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectBusiness = rememberDebouncedClick<String> { id -> viewModel.select(id) }
    LaunchedEffect(state.selectedId) {
        if (state.selectedId != null) {
            onSelected(state.selectedBusinessChanged)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PageHeader(
                title = "Choose a business",
                subtitle = "Switch the business you're working in.",
                onBack = onBack,
            )

            LazyColumn(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                items(state.businesses, key = { it.id }) { biz ->
                    val isCurrent = biz.id == state.currentBusinessId
                    GroupedPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { selectBusiness(biz.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(biz.name, style = MaterialTheme.typography.titleLarge, color = NeutralInk)
                                biz.businessType?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NeutralMuted,
                                    )
                                }
                            }
                            if (isCurrent) {
                                Text(
                                    "Current",
                                    color = ProofLime,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
