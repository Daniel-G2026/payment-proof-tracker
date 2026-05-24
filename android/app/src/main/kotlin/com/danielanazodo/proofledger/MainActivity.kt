package com.danielanazodo.proofledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.danielanazodo.proofledger.data.proof.SharedProofIntakeRepository
import com.danielanazodo.proofledger.ui.navigation.AppNavHost
import com.danielanazodo.proofledger.ui.theme.ProofLedgerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sharedProofIntakeRepository: SharedProofIntakeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureShareIntent(intent)
        enableEdgeToEdge()
        setContent {
            ProofLedgerTheme {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureShareIntent(intent)
    }

    private fun captureShareIntent(incoming: Intent?) {
        sharedProofIntakeRepository.capture(incoming)
        setIntent(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
    }
}
