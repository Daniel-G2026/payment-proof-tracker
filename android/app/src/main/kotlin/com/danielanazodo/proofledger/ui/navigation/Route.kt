package com.danielanazodo.proofledger.ui.navigation

import android.net.Uri

sealed class Route(val path: String) {
    data object SignIn : Route("sign_in")
    data object ProfileSetup : Route("profile_setup")
    data object CreateBusiness : Route("create_business")
    data object BusinessSelect : Route("business_select")
    data object BusinessSwitch : Route("business_switch")
    data object Dashboard : Route("dashboard")
    data object SyncIssues : Route("sync_issues")
    data object Settings : Route("settings")
    data object SharedProofIntake : Route("shared-proof/{intakeToken}") {
        fun path(intakeToken: String): String = "shared-proof/${Uri.encode(intakeToken)}"
    }
    data object ProofBackup : Route("proof_backup")
    data object CounterpartyList : Route("counterparties")
    data object AddCounterparty : Route("counterparties/add")
    data object EditCounterparty : Route("counterparties/{counterpartyId}/edit") {
        fun path(counterpartyId: String): String = "counterparties/$counterpartyId/edit"
    }
    data object CounterpartyDetail : Route("counterparties/{counterpartyId}") {
        fun path(counterpartyId: String): String = "counterparties/$counterpartyId"
    }
    data object TransactionList : Route("transactions")
    data object AddTransaction : Route("transactions/add/{direction}?intakeToken={intakeToken}") {
        fun path(direction: String, intakeToken: String? = null): String =
            if (intakeToken == null) {
                "transactions/add/$direction"
            } else {
                "transactions/add/$direction?intakeToken=${Uri.encode(intakeToken)}"
            }
    }
    data object EditTransaction : Route("transactions/{transactionId}/edit") {
        fun path(transactionId: String): String = "transactions/$transactionId/edit"
    }
    data object TransactionDetail : Route("transactions/{transactionId}") {
        fun path(transactionId: String): String = "transactions/$transactionId"
    }
}
