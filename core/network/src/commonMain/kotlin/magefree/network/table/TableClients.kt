package magefree.network.table

import kotlinx.coroutines.flow.StateFlow
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ServerPushSource

/**
 * The one place the production [TableClient] is assembled over a [BridgeClient].
 *
 * The implementation ([DefaultTableClient]) is deliberately `internal`: it speaks `:protocol` and also
 * needs the **internal** [ServerPushSource] side-channel, neither of which may surface above
 * `:core:network`. This factory keeps that discipline — its signature is `:protocol`-free — while giving
 * two callers a way in:
 *
 * - Hilt (`NetworkModule.provideTableClient`), which passes the live `BridgeClient` singleton.
 * - A **consumer test** that wants the *real* client rather than a hand-written double, so it can drive
 *   a room end-to-end through the bridge seam (a fake `BridgeClient` scripting `TableDetail` replies).
 *   That path is why story 0040's seat regression is now catchable: a test built on the real client
 *   fails the moment seats stop being reachable through it.
 */
object TableClients {
    /**
     * The production [TableClient] over [bridgeClient], re-syncing against [connectionState].
     *
     * [bridgeClient] must also implement the internal push side-channel (both the Ktor client and the
     * test double do); a client that does not will fail fast here rather than silently never folding a
     * table push.
     */
    fun overBridge(
        bridgeClient: BridgeClient,
        connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    ): TableClient {
        val pushSource =
            bridgeClient as? ServerPushSource
                ?: error("BridgeClient ${bridgeClient::class.simpleName} does not provide the server-push side-channel")
        return DefaultTableClient(
            bridgeClient = bridgeClient,
            pushSource = pushSource,
            connectionState = connectionState,
        )
    }
}
