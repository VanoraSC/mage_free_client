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
import kotlinx.coroutines.flow.StateFlow
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ConnectionRepository
import magefree.network.LobbyClient
import magefree.network.LobbyClientImpl
import magefree.network.game.GameClient
import magefree.network.game.GameClients
import magefree.network.ktor.KtorBridgeClient
import magefree.network.reconnect.AndroidConnectivityObserver
import magefree.network.reconnect.AppLifecycleObserver
import magefree.network.reconnect.ConnectivityObserver
import magefree.network.reconnect.ProcessAppLifecycleObserver
import magefree.network.table.TableClient
import magefree.network.table.TableClients
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

    /**
     * The production [LobbyClient] (story 0028), riding the same [BridgeClient] singleton (the live
     * session) via its request/response primitive. `LobbyClientImpl` is `@Inject`-constructed with the
     * `:protocol`-free [BridgeClient], so nothing in the Hilt graph above `:core:network` sees a wire type.
     */
    @Provides
    @Singleton
    fun provideLobbyClient(impl: LobbyClientImpl): LobbyClient = impl

    /**
     * The production [TableClient] (story 0037), riding the same [BridgeClient] singleton as the lobby.
     * Assembled by [TableClients] (rather than `@Inject`) because the implementation also needs the
     * **internal** server-push side-channel — the same singleton, cast — which stays off the
     * cross-module Hilt graph so `:protocol` never surfaces above `:core:network`. The [ConnectionState]
     * flow it re-syncs against carries the same `@JvmSuppressWildcards` fix as the lobby's.
     */
    @Provides
    @Singleton
    fun provideTableClient(
        bridgeClient: BridgeClient,
        connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    ): TableClient = TableClients.overBridge(bridgeClient, connectionState)

    /**
     * The production [GameClient] (story 0052), riding the same [BridgeClient] singleton as the lobby and
     * table clients — one socket, three feature clients. Assembled by [GameClients] for the same reason
     * the table client is: the implementation also needs the **internal** server-push side-channel (the
     * same singleton, cast), which stays off the cross-module Hilt graph so `:protocol` never surfaces
     * above `:core:network`.
     */
    @Provides
    @Singleton
    fun provideGameClient(
        bridgeClient: BridgeClient,
        connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    ): GameClient = GameClients.overBridge(bridgeClient, connectionState)

    /**
     * The app-level connection state as a plain [StateFlow], sourced from the single-source-of-truth
     * [ConnectionRepository] (story 0017). The [magefree.network.LobbyRepository] observes it to gate the
     * lobby on an active connection without owning the session.
     */
    @Provides
    fun provideConnectionState(connectionRepository: ConnectionRepository): StateFlow<@JvmSuppressWildcards ConnectionState> =
        connectionRepository.connectionState

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
