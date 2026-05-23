package com.danielanazodo.proofledger.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.danielanazodo.proofledger.ui.screens.BusinessSelectScreen
import com.danielanazodo.proofledger.ui.screens.CreateBusinessScreen
import com.danielanazodo.proofledger.ui.screens.EmptyDashboardScreen
import com.danielanazodo.proofledger.ui.screens.ProfileSetupScreen
import com.danielanazodo.proofledger.ui.screens.SignInScreen
import com.danielanazodo.proofledger.ui.viewmodel.StartupState
import com.danielanazodo.proofledger.ui.viewmodel.StartupViewModel

@Composable
fun AppNavHost(startupViewModel: StartupViewModel = hiltViewModel()) {
    val startup by startupViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    when (val s = startup) {
        is StartupState.Loading -> LoadingScreen()
        is StartupState.Resolved -> ResolvedGraph(
            navController = navController,
            startRoute = s.route.path,
            onResolveAgain = startupViewModel::rerun,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ResolvedGraph(
    navController: NavHostController,
    startRoute: String,
    onResolveAgain: () -> Unit,
) {
    NavHost(navController = navController, startDestination = startRoute) {
        composable(Route.SignIn.path) {
            SignInScreen(onSignedIn = onResolveAgain)
        }
        composable(Route.ProfileSetup.path) {
            ProfileSetupScreen(onSaved = onResolveAgain)
        }
        composable(Route.CreateBusiness.path) {
            CreateBusinessScreen(onCreated = onResolveAgain)
        }
        composable(Route.BusinessSelect.path) {
            BusinessSelectScreen(onSelected = onResolveAgain)
        }
        composable(Route.Dashboard.path) {
            EmptyDashboardScreen(onSignedOut = onResolveAgain)
        }
    }
}
