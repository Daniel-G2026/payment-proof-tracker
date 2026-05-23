package com.danielanazodo.proofledger.ui.navigation

sealed class Route(val path: String) {
    data object SignIn : Route("sign_in")
    data object ProfileSetup : Route("profile_setup")
    data object CreateBusiness : Route("create_business")
    data object BusinessSelect : Route("business_select")
    data object Dashboard : Route("dashboard")
}
