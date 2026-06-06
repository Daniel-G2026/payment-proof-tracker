package com.danielanazodo.proofledger.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cheap synchronous connectivity check so offline writes don't pay a network
 * timeout.
 *
 * Offline-first saves should be instant — a local Room write — but if the
 * create/duplicate-check code awaits a backend call, an offline save blocks
 * until the OkHttp connect timeout fires (~10s each) before falling back to
 * local. When there's no validated internet, we skip those best-effort calls
 * entirely; the row stays locally pending and the SyncWorker pushes it when
 * connectivity returns.
 *
 * Defaults to "online" when connectivity can't be determined, preserving the
 * prior try-the-network behaviour (e.g. on odd devices/emulators).
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
