package com.danielanazodo.proofledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.danielanazodo.proofledger.ui.navigation.AppNavHost
import com.danielanazodo.proofledger.ui.theme.ProofLedgerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProofLedgerTheme {
                AppNavHost()
            }
        }
    }
}
