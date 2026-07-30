package magefree.network.reconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The Android [ConnectivityObserver], backed by [ConnectivityManager.NetworkCallback].
 *
 * [isOnline] is a cold `callbackFlow` that, on collection, registers a callback for networks with the
 * `INTERNET` capability and emits `true`/`false` as networks appear and disappear (seeding the current
 * state so a late collector is not left waiting for the next transition). It is `distinctUntilChanged`
 * + `conflate`d so only real edges reach the reconnect loop. The callback is unregistered on
 * cancellation via `awaitClose`, so nothing leaks when no one observes (`WhileSubscribed`).
 */
class AndroidConnectivityObserver(
    context: Context,
) : ConnectivityObserver {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val isOnline: Flow<Boolean> =
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(true)
                    }

                    override fun onLost(network: Network) {
                        trySend(hasValidatedNetwork())
                    }
                }
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            // Seed the current state before the first transition arrives.
            trySend(hasValidatedNetwork())
            connectivityManager.registerNetworkCallback(request, callback)
            awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged().conflate()

    private fun hasValidatedNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
