package magefree.network.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ConnectionRepository
import magefree.network.LobbyClient
import magefree.network.LobbyClientImpl
import magefree.network.LobbyRepository
import magefree.network.ServerRepository
import magefree.network.game.GameClient
import magefree.network.game.GameClients
import magefree.network.ktor.KtorBridgeClient
import magefree.network.reconnect.AppLifecycleObserver
import magefree.network.reconnect.ConnectivityObserver
import magefree.network.table.TableClient
import magefree.network.table.TableClients
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Koin provisioning for `:core:network`'s session/persistence layer, in common (story 0084; was
 * Hilt's `NetworkModule`, then story 0081's Koin module).
 *
 * - Binds the production [BridgeClient] to the real Ktor WebSocket implementation; tests construct a
 *   [magefree.network.fake.FakeBridgeClient] directly and never touch Koin.
 * - Supplies the injected IO dispatcher + application scope the [ConnectionRepository] needs (no
 *   hard-coded `Dispatchers.*`, per `AGENTS.md`).
 * - Provides the single Preferences [DataStore] backing [ServerRepository].
 *
 * Every binding here is a `single` — the Hilt graph made all of these `@Singleton`, and one live
 * session shared by the lobby, table and game clients is load-bearing rather than incidental.
 *
 * **Four things are platform-shaped and arrive as parameters.** The two reconnect observers have
 * Android implementations backed by `ConnectivityManager` and `ProcessLifecycleOwner`; the
 * Preferences [DataStore] is built from a `Context` on Android; and `Dispatchers.IO` does not exist
 * in a common source set. Everything else — every client, every repository, and the wiring between
 * them — is identical on every target and lives here. Each is a `Scope.() ->` because on Android
 * they need `androidContext()`, which is only reachable while Koin is resolving a definition.
 */
fun networkDefinitions(
    connectivityObserver: Scope.() -> ConnectivityObserver,
    lifecycleObserver: Scope.() -> AppLifecycleObserver,
    serverDataStore: Scope.() -> DataStore<Preferences>,
    ioDispatcher: CoroutineDispatcher,
): Module =
    module {
        /**
         * The device-connectivity observer (story 0024) that lets the reconnect loop wake a waiting
         * back-off the instant the network returns. Android-backed; a JVM/unit context uses the
         * always-on default inside [KtorBridgeClient].
         */
        single<ConnectivityObserver> { connectivityObserver() }

        /**
         * The whole-app foreground/background observer (story 0024). On Android it is backed by
         * `ProcessLifecycleOwner`: it nudges a reconnect on foreground and relaxes retries while
         * backgrounded (the bridge holds the session per story 0023). Bound here (not in `:app`) so
         * `:app` needs no lifecycle wiring.
         */
        single<AppLifecycleObserver> { lifecycleObserver() }

        single<BridgeClient> { KtorBridgeClient(connectivity = get(), lifecycle = get()) }

        /**
         * The production [LobbyClient] (story 0028), riding the same [BridgeClient] singleton (the
         * live session) via its request/response primitive. Constructed with the `:protocol`-free
         * [BridgeClient], so nothing in the graph above `:core:network` sees a wire type.
         */
        single<LobbyClient> { LobbyClientImpl(bridgeClient = get()) }

        /**
         * The production [TableClient] (story 0037), riding the same [BridgeClient] singleton as the
         * lobby. Assembled by [TableClients] because the implementation also needs the **internal**
         * server-push side-channel — the same singleton, cast — which stays off the cross-module
         * graph so `:protocol` never surfaces above `:core:network`.
         */
        single<TableClient> { TableClients.overBridge(get(), get()) }

        /**
         * The production [GameClient] (story 0052), riding the same [BridgeClient] singleton as the
         * lobby and table clients — one socket, three feature clients. Assembled by [GameClients]
         * for the same reason the table client is.
         */
        single<GameClient> { GameClients.overBridge(get(), get()) }

        single { ConnectionRepository(bridgeClient = get(), dispatcher = get(IoDispatcher), scope = get(ApplicationScope)) }

        /**
         * The app-level connection state as a plain [StateFlow], sourced from the
         * single-source-of-truth [ConnectionRepository] (story 0017). [LobbyRepository] observes it
         * to gate the lobby on an active connection without owning the session.
         */
        single<StateFlow<ConnectionState>> { get<ConnectionRepository>().connectionState }

        single {
            LobbyRepository(
                lobbyClient = get(),
                connectionState = get(),
                dispatcher = get(IoDispatcher),
                scope = get(ApplicationScope),
            )
        }

        single<CoroutineDispatcher>(IoDispatcher) { ioDispatcher }

        single(ApplicationScope) { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(IoDispatcher)) }

        single<DataStore<Preferences>> { serverDataStore() }

        single { ServerRepository(dataStore = get()) }
    }
