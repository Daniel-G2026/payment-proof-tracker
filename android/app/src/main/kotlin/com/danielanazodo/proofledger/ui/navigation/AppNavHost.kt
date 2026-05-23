package com.danielanazodo.proofledger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.danielanazodo.proofledger.ui.screens.PlaceholderHomeScreen

sealed class Route(val path: String) {
    data object Home : Route("home")
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Route.Home.path) {
        composable(Route.Home.path) { PlaceholderHomeScreen() }
    }
}
