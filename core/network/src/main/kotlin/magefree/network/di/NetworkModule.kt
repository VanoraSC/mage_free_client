package magefree.network.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import magefree.network.BridgeClient
import magefree.network.ktor.KtorBridgeClient
import magefree.network.reconnect.AndroidConnectivityObserver
import magefree.network.reconnect.AppLifecycleObserver
import magefree.network.reconnect.ConnectivityObserver
import magefree.network.reconnect.ProcessAppLifecycleObserver
import javax.inject.Singleton

/**
 * Hilt provisioning for `:core:network`'s session/persistence layer.
 *
 * - Binds the production [BridgeClient] to the real Ktor WebSocket implementation; tests construct a
 *   [magefree.network.fake.FakeBridgeClient] directly and never touch Hilt.
 * - Supplies the injected IO dispatcher + application scope the [magefree.network.ConnectionRepository]
 *   needs (no hard-coded `Dispatchers.*`, per `AGENTS.md`).
 * - Provides the single Preferences [DataStore] backing [magefree.network.ServerRepository].
 *
 * `ConnectionRepository` / `ServerRepository` are `@Inject`-constructed `@Singleton`s, so they need
 * no explicit `@Provides` here.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** The user's saved server list lives in this Preferences DataStore file. */
    private val Context.serverDataStore by preferencesDataStore(name = "server_targets")

    /**
     * The device-connectivity observer (story 0024) that lets the reconnect loop wake a waiting
     * back-off the instant the network returns. Android-backed; a JVM/unit context uses the always-on
     * default inside [KtorBridgeClient].
     */
    @Provides
    @Singleton
    fun provideConnectivityObserver(
        @ApplicationContext context: Context,
    ): ConnectivityObserver = AndroidConnectivityObserver(context)

    /**
     * The whole-app foreground/background observer (story 0024). Backed by `ProcessLifecycleOwner`, it
     * nudges a reconnect on foreground and relaxes retries while backgrounded (the bridge holds the
     * session per story 0023). Lives here (not `:app`) so `:app` needs no lifecycle wiring.
     */
    @Provides
    @Singleton
    fun provideAppLifecycleObserver(): AppLifecycleObserver = ProcessAppLifecycleObserver()

    @Provides
    @Singleton
    fun provideBridgeClient(
        connectivity: ConnectivityObserver,
        lifecycle: AppLifecycleObserver,
    ): BridgeClient = KtorBridgeClient(connectivity = connectivity, lifecycle = lifecycle)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideServerDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.serverDataStore
}
