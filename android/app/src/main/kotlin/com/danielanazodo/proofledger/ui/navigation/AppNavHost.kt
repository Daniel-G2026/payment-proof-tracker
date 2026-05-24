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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.danielanazodo.proofledger.ui.screens.BusinessSelectScreen
import com.danielanazodo.proofledger.ui.screens.CreateBusinessScreen
import com.danielanazodo.proofledger.ui.screens.CounterpartyDetailScreen
import com.danielanazodo.proofledger.ui.screens.CounterpartyFormScreen
import com.danielanazodo.proofledger.ui.screens.CounterpartyListScreen
import com.danielanazodo.proofledger.ui.screens.EmptyDashboardScreen
import com.danielanazodo.proofledger.ui.screens.ProfileSetupScreen
import com.danielanazodo.proofledger.ui.screens.SignInScreen
import com.danielanazodo.proofledger.ui.screens.TransactionDetailScreen
import com.danielanazodo.proofledger.ui.screens.TransactionFormScreen
import com.danielanazodo.proofledger.ui.screens.TransactionListScreen
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
            EmptyDashboardScreen(
                onSignedOut = onResolveAgain,
                onAddMoneyIn = { navController.navigate(Route.AddTransaction.path("money_in")) },
                onAddMoneyOut = { navController.navigate(Route.AddTransaction.path("money_out")) },
                onViewTransactions = { navController.navigate(Route.TransactionList.path) },
                onViewCounterparties = { navController.navigate(Route.CounterpartyList.path) },
                onTransactionClick = { id -> navController.navigate(Route.TransactionDetail.path(id)) },
            )
        }
        composable(Route.CounterpartyList.path) {
            CounterpartyListScreen(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Route.AddCounterparty.path) },
                onCounterpartyClick = { id -> navController.navigate(Route.CounterpartyDetail.path(id)) },
            )
        }
        composable(Route.AddCounterparty.path) {
            CounterpartyFormScreen(
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Route.EditCounterparty.path,
            arguments = listOf(navArgument("counterpartyId") { type = NavType.StringType }),
        ) {
            CounterpartyFormScreen(
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Route.CounterpartyDetail.path,
            arguments = listOf(navArgument("counterpartyId") { type = NavType.StringType }),
        ) {
            CounterpartyDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Route.EditCounterparty.path(id)) },
                onTransactionClick = { id -> navController.navigate(Route.TransactionDetail.path(id)) },
            )
        }
        composable(Route.TransactionList.path) {
            TransactionListScreen(
                onBack = { navController.popBackStack() },
                onTransactionClick = { id -> navController.navigate(Route.TransactionDetail.path(id)) },
            )
        }
        composable(
            route = Route.AddTransaction.path,
            arguments = listOf(navArgument("direction") { type = NavType.StringType }),
        ) {
            TransactionFormScreen(
                onSaved = {
                    navController.popBackStack(Route.Dashboard.path, inclusive = false)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Route.EditTransaction.path,
            arguments = listOf(navArgument("transactionId") { type = NavType.StringType }),
        ) {
            TransactionFormScreen(
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Route.TransactionDetail.path,
            arguments = listOf(navArgument("transactionId") { type = NavType.StringType }),
        ) {
            TransactionDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Route.EditTransaction.path(id)) },
            )
        }
    }
}
