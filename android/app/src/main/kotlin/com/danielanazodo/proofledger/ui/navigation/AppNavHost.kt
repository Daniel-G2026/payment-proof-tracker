package com.danielanazodo.proofledger.ui.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
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
import com.danielanazodo.proofledger.ui.screens.ProofBackupScreen
import com.danielanazodo.proofledger.ui.screens.SharedProofIntakeScreen
import com.danielanazodo.proofledger.ui.screens.SignInScreen
import com.danielanazodo.proofledger.ui.screens.SettingsScreen
import com.danielanazodo.proofledger.ui.screens.SummaryScreen
import com.danielanazodo.proofledger.ui.screens.SyncIssuesScreen
import com.danielanazodo.proofledger.ui.screens.TransactionDetailScreen
import com.danielanazodo.proofledger.ui.screens.TransactionFormScreen
import com.danielanazodo.proofledger.ui.screens.TransactionListScreen
import com.danielanazodo.proofledger.ui.viewmodel.SharedProofIntakeViewModel
import com.danielanazodo.proofledger.ui.viewmodel.StartupState
import com.danielanazodo.proofledger.ui.viewmodel.StartupViewModel

@Composable
fun AppNavHost(
    startupViewModel: StartupViewModel = hiltViewModel(),
    sharedProofIntakeViewModel: SharedProofIntakeViewModel = hiltViewModel(),
) {
    val startup by startupViewModel.state.collectAsStateWithLifecycle()
    val sharedProofState by sharedProofIntakeViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val context = LocalContext.current

    LaunchedEffect(sharedProofState.message) {
        sharedProofState.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            sharedProofIntakeViewModel.clearMessage()
        }
    }

    when (val s = startup) {
        is StartupState.Loading -> LoadingScreen()
        is StartupState.Resolved -> ResolvedGraph(
            navController = navController,
            startRoute = s.route.path,
            onResolveAgain = startupViewModel::rerun,
            pendingIntakeToken = sharedProofState.pending?.token,
            sharedProofIntakeViewModel = sharedProofIntakeViewModel,
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
    pendingIntakeToken: String?,
    sharedProofIntakeViewModel: SharedProofIntakeViewModel,
) {
    LaunchedEffect(pendingIntakeToken, startRoute) {
        if (pendingIntakeToken != null && startRoute == Route.Dashboard.path) {
            val currentEntry = navController.currentBackStackEntry
            val currentRoute = currentEntry?.destination?.route
            val alreadyHandlingThisProof =
                currentRoute == Route.SharedProofIntake.path ||
                    (
                        currentRoute == Route.AddTransaction.path &&
                            currentEntry.arguments?.getString("intakeToken") == pendingIntakeToken
                        )
            if (!alreadyHandlingThisProof) {
                navController.navigate(Route.SharedProofIntake.path(pendingIntakeToken))
            }
        }
    }

    val context = LocalContext.current
    val onTabSelected: (MainTab) -> Unit = tabHandler@ { tab ->
        val currentRoute = navController.currentDestination?.route
        val canNavigate = navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED
        if (!canNavigate) {
            return@tabHandler
        }

        if (tab == MainTab.Home) {
            if (currentRoute != Route.Dashboard.path) {
                val poppedToHome = navController.popBackStack(Route.Dashboard.path, inclusive = false)
                if (!poppedToHome) {
                    navController.navigate(Route.Dashboard.path) {
                        launchSingleTop = true
                    }
                }
            }
            return@tabHandler
        }

        val route = when (tab) {
            MainTab.Home -> Route.Dashboard.path
            MainTab.Records -> Route.TransactionList.path
            MainTab.People -> Route.CounterpartyList.path
            MainTab.Settings -> Route.Settings.path
        }
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(Route.Dashboard.path) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

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
            BusinessSelectScreen(onSelected = { onResolveAgain() })
        }
        composable(Route.BusinessSwitch.path) {
            BusinessSelectScreen(
                onSelected = { changed ->
                    if (changed) {
                        navController.navigate(Route.Dashboard.path) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.Dashboard.path) {
            EmptyDashboardScreen(
                onSignedOut = onResolveAgain,
                onAddMoneyIn = { navController.navigateOnce(Route.AddTransaction.path("money_in")) },
                onAddMoneyOut = { navController.navigateOnce(Route.AddTransaction.path("money_out")) },
                onViewTransactions = { navController.navigateOnce(Route.TransactionList.path) },
                onViewCounterparties = { navController.navigateOnce(Route.CounterpartyList.path) },
                onViewProofBackup = { navController.navigateOnce(Route.ProofBackup.path) },
                onViewReports = { navController.navigateOnce(Route.Summary.path) },
                onViewSyncIssues = { navController.navigateOnce(Route.SyncIssues.path) },
                onTransactionClick = { id -> navController.navigateOnce(Route.TransactionDetail.path(id)) },
                onTabSelected = onTabSelected,
            )
        }
        composable(Route.Summary.path) {
            SummaryScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.SyncIssues.path) {
            SyncIssuesScreen(
                onBack = { navController.popBackStack() },
                onOpenRecord = { id -> navController.navigateOnce(Route.TransactionDetail.path(id)) },
                onOpenPerson = { id -> navController.navigateOnce(Route.CounterpartyDetail.path(id)) },
            )
        }
        composable(Route.Settings.path) {
            SettingsScreen(
                onProofBackup = { navController.navigateOnce(Route.ProofBackup.path) },
                onSyncIssues = { navController.navigateOnce(Route.SyncIssues.path) },
                onSwitchBusiness = { navController.navigateOnce(Route.BusinessSwitch.path) },
                onSignedOut = onResolveAgain,
                onTabSelected = onTabSelected,
            )
        }
        composable(Route.ProofBackup.path) {
            ProofBackupScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Route.SharedProofIntake.path,
            arguments = listOf(navArgument("intakeToken") { type = NavType.StringType }),
        ) { backStackEntry ->
            val token = backStackEntry.arguments?.getString("intakeToken").orEmpty()
            val cancel = {
                sharedProofIntakeViewModel.cancel(token)
                if (!navController.popBackStack()) {
                    navController.navigate(Route.Dashboard.path)
                }
            }
            SharedProofIntakeScreen(
                item = sharedProofIntakeViewModel.get(token),
                onMoneyIn = {
                    navController.popBackStack()
                    navController.navigate(Route.AddTransaction.path("money_in", token))
                },
                onMoneyOut = {
                    navController.popBackStack()
                    navController.navigate(Route.AddTransaction.path("money_out", token))
                },
                onCancel = cancel,
            )
        }
        composable(Route.CounterpartyList.path) {
            CounterpartyListScreen(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigateOnce(Route.AddCounterparty.path) },
                onCounterpartyClick = { id -> navController.navigateOnce(Route.CounterpartyDetail.path(id)) },
                onTabSelected = onTabSelected,
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
                onEdit = { id -> navController.navigateOnce(Route.EditCounterparty.path(id)) },
                onTransactionClick = { id -> navController.navigateOnce(Route.TransactionDetail.path(id)) },
            )
        }
        composable(Route.TransactionList.path) {
            TransactionListScreen(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigateOnce(Route.AddTransaction.path("money_in")) },
                onTransactionClick = { id -> navController.navigateOnce(Route.TransactionDetail.path(id)) },
                onTabSelected = onTabSelected,
            )
        }
        composable(
            route = Route.AddTransaction.path,
            arguments = listOf(
                navArgument("direction") { type = NavType.StringType },
                navArgument("intakeToken") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
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
                onEdit = { id -> navController.navigateOnce(Route.EditTransaction.path(id)) },
            )
        }
    }

    // Single source of truth for back-button behavior. Registered AFTER the
    // NavHost composable so this callback is on top of the
    // OnBackPressedDispatcher's LIFO stack and runs FIRST on every back press.
    //
    // Important: we compare against the start route directly instead of
    // checking previousBackStackEntry. Compose Nav inserts an invisible
    // NavGraph entry under every destination, so previousBackStackEntry is
    // never actually null even on the start destination — checking it would
    // pop the start destination and leave NavHost rendering nothing.
    BackHandler {
        // Read the live destination at press time. The Compose state wrapper can
        // lag behind rapid back presses by a frame, which lets a second press
        // pop the start destination and leave NavHost blank.
        val onStartDestination = navController.currentDestination?.route == startRoute
        if (onStartDestination) {
            (context as? Activity)?.moveTaskToBack(/* nonRoot = */ true)
        } else {
            val popped = navController.popBackStack()
            if (!popped || navController.currentDestination == null) {
                navController.navigate(startRoute) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }
}
