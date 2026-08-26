package magefree.network.live

import magefree.model.ServerTarget

/**
 * The live bridge these tests drive, resolved from the [ENV_VAR] environment variable — the app-side
 * mirror of `:bridge`'s `XMageServerTarget`/`XMAGE_SERVER` gate.
 *
 * The whole point of the gate is that `:core:network` needs **only a URL** to reach a bridge: the module
 * speaks pure WebSocket + JSON (`:protocol`) and has no `mage.*`/`:bridge` dependency, and adding one to
 * an Android module would break the boundary in `docs/architecture.md`. So the live tests take the
 * bridge's address here and nothing else.
 *
 * Accepted forms (a bare `host:port` keeps it symmetrical with `XMAGE_SERVER`):
 * ```
 * BRIDGE_URL=localhost:8080
 * BRIDGE_URL=ws://localhost:8080
 * BRIDGE_URL=wss://bridge.example:443
 * ```
 * The path is **not** part of it — `KtorBridgeClient` builds `/<major>/session` itself from
 * [magefree.protocol.ProtocolVersion], which is one of the things these tests exist to prove.
 */
internal data class BridgeTarget(
    val host: String,
    val port: Int,
    val secure: Boolean,
) {
    /** The `:core:model` target `KtorBridgeClient.connect` takes — the only input it needs. */
    fun toServerTarget(): ServerTarget = ServerTarget(host = host, port = port, secure = secure)

    companion object {
        /** The environment variable naming the live bridge; unset ⇒ the live tests skip. */
        const val ENV_VAR: String = "BRIDGE_URL"

        /**
         * Parses `host:port`, optionally prefixed with `ws://`/`wss://` and optionally followed by a
         * trailing `/`. `wss` (only) selects [ServerTarget.secure].
         *
         * @throws IllegalArgumentException if [raw] is blank, is not `host:port`, or the port is not a
         *   valid TCP port.
         */
        fun parse(raw: String): BridgeTarget {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "$ENV_VAR is blank; expected [ws://|wss://]host:port" }

            val secure = trimmed.startsWith("wss://")
            val authority = trimmed.removePrefix("wss://").removePrefix("ws://")
            val separator = authority.lastIndexOf(':')
            require(separator > 0 && separator < authority.length - 1) {
                "Invalid $ENV_VAR '$raw'; expected [ws://|wss://]host:port"
            }

            val portText = authority.substring(separator + 1)
            val port =
                portText.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port '$portText' in $ENV_VAR '$raw'")
            require(port in 1..65535) { "Port $port out of range (1..65535) in $ENV_VAR '$raw'" }

            return BridgeTarget(host = authority.substring(0, separator), port = port, secure = secure)
        }

        /** Reads and parses [ENV_VAR], or returns `null` when it is unset or blank (the skip signal). */
        fun fromEnv(): BridgeTarget? {
            val raw = System.getenv(ENV_VAR)
            return if (raw.isNullOrBlank()) null else parse(raw)
        }
    }
}
