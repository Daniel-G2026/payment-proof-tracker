package com.danielanazodo.proofledger.ui.navigation

sealed class Route(val path: String) {
    data object SignIn : Route("sign_in")
    data object ProfileSetup : Route("profile_setup")
    data object CreateBusiness : Route("create_business")
    data object BusinessSelect : Route("business_select")
    data object Dashboard : Route("dashboard")
    data object TransactionList : Route("transactions")
    data object AddTransaction : Route("transactions/add/{direction}") {
        fun path(direction: String): String = "transactions/add/$direction"
    }
    data object EditTransaction : Route("transactions/{transactionId}/edit") {
        fun path(transactionId: String): String = "transactions/$transactionId/edit"
    }
    data object TransactionDetail : Route("transactions/{transactionId}") {
        fun path(transactionId: String): String = "transactions/$transactionId"
    }
}
