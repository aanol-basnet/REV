package app.rev.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface NetworkMonitor {
    /** Whether the device has any usable network (Wi-Fi, cellular, VPN such as Tailscale). */
    val isOnline: Flow<Boolean>

    /**
     * Identifies the current default network; changes when the device moves to another one (home
     * Wi-Fi → cellular) even though it stays online. Null when offline.
     */
    val currentNetwork: Flow<String?>
        get() = isOnline.map { if (it) "default" else null }
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override val currentNetwork: Flow<String?> = callbackFlow {
        fun id(network: Network?, caps: NetworkCapabilities?): String? =
            if (network != null && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                network.toString()
            } else {
                null
            }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(id(network, caps))
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        val active = connectivity.activeNetwork
        trySend(id(active, connectivity.getNetworkCapabilities(active)))
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()

    override val isOnline: Flow<Boolean> = callbackFlow {
        // Deliberately not requiring VALIDATED: a home server on a LAN without internet is fine.
        fun hasInternet(caps: NetworkCapabilities?) =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(hasInternet(caps))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(hasInternet(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()
}
