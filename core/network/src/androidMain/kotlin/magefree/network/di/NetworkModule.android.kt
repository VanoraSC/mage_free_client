package magefree.network.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import magefree.network.reconnect.AndroidConnectivityObserver
import magefree.network.reconnect.ProcessAppLifecycleObserver
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module

/** The user's saved server list lives in this Preferences DataStore file. */
private val Context.serverDataStore by preferencesDataStore(name = "server_targets")

/**
 * The Android edge of [networkDefinitions]: the two `Context`-backed reconnect
 * observers, the Preferences DataStore, and the IO dispatcher. This is the `networkModule` `:app`
 * lists in `appModules`.
 *
 * **The DataStore is still built by the `preferencesDataStore` delegate, deliberately.** The saved
 * server list is real user data that exists only on the device, and the delegate resolves its file
 * to `filesDir/datastore/server_targets.preferences_pb`. Constructing the store from a path spelled
 * out here instead would put that path's correctness in this file rather than in DataStore's own
 * code — and getting it wrong loses every saved server silently, with no error and no crash. The
 * type the rest of the module sees is `DataStore<Preferences>`, which is already multiplatform, so
 * nothing above this line is Android-shaped; a JVM host would supply the same binding through
 * `PreferenceDataStoreFactory.createWithPath`.
 */
val networkModule: Module =
    networkDefinitions(
        connectivityObserver = { AndroidConnectivityObserver(androidContext()) },
        lifecycleObserver = { ProcessAppLifecycleObserver() },
        serverDataStore = { androidContext().serverDataStore },
        ioDispatcher = Dispatchers.IO,
    )
