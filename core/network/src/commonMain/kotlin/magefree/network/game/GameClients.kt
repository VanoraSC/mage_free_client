package magefree.network.game

import kotlinx.coroutines.flow.StateFlow
import magefree.model.ConnectionState
import magefree.network.BridgeClient
import magefree.network.ServerPushSource

/**
 * The one place the production [GameClient] is assembled over a [BridgeClient] — the game-side twin of
 * story 0037's `TableClients`, and for the same two reasons.
 *
 * The implementation ([DefaultGameClient]) is deliberately `internal`: it speaks `:protocol` and also
 * needs the **internal** [ServerPushSource] side-channel, neither of which may surface above
 * `:core:network`. This factory keeps that discipline — its signature is `:protocol`-free — while giving
 * two callers a way in:
 *
 * - Hilt (`NetworkModule.provideGameClient`), which passes the live `BridgeClient` singleton.
 * - A **consumer or live test** that wants the *real* client rather than a hand-written double, so it can
 *   drive a whole game through the bridge seam. Story 0045's live suite is built on exactly this property
 *   for tables; story 0052's live game test is built on it here.
 */
object GameClients {
    /**
     * The production [GameClient] over [bridgeClient], re-syncing against [connectionState].
     *
     * [bridgeClient] must also implement the internal push side-channel (both the Ktor client and the
     * test double do); a client that does not fails fast here rather than silently never folding a game
     * push — which would look exactly like a game that never starts.
     */
    fun overBridge(
        bridgeClient: BridgeClient,
        connectionState: StateFlow<@JvmSuppressWildcards ConnectionState>,
    ): GameClient {
        val pushSource =
            bridgeClient as? ServerPushSource
                ?: error("BridgeClient ${bridgeClient::class.simpleName} does not provide the server-push side-channel")
        return DefaultGameClient(
            bridgeClient = bridgeClient,
            pushSource = pushSource,
            connectionState = connectionState,
        )
    }
}
