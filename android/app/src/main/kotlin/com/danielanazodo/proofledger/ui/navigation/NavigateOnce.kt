package com.danielanazodo.proofledger.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController

/**
 * Navigate only if the current back stack entry is in the RESUMED state.
 *
 * Compose Nav fires `navigate()` synchronously, but the actual screen
 * transition happens asynchronously. Without this guard, double-tapping a
 * list item pushes the destination twice — and worse, if the list re-orders
 * between the two taps, the second tap can land on a different item and
 * navigate into something the user did not intend to open.
 *
 * Checking the lifecycle is the canonical fix from the official Compose Nav
 * docs: during the transition window the current entry drops from RESUMED to
 * STARTED, so the second call no-ops.
 */
fun NavController.navigateOnce(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route)
    }
}
