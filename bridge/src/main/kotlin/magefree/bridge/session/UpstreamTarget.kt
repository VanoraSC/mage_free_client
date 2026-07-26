package magefree.bridge.session

/**
 * The bridge's **pinned** upstream XMage server, as a parsed `host:port`.
 *
 * Per the pinned-server posture ([`docs/architecture.md`](../../../../../../../docs/architecture.md),
 * Decision #6) the app never sends a host/port; the bridge reads its own target from the
 * `XMAGE_UPSTREAM` environment variable, defaulting to `xmage-server:17171` (the compose service in
 * [`docs/build-environment.md`](../../../../../../../docs/build-environment.md)).
 */
public data class UpstreamTarget(
    val host: String,
    val port: Int,
) {
    public companion object {
        /** Environment variable naming the pinned upstream server target. */
        public const val ENV_VAR: String = "XMAGE_UPSTREAM"

        /** The compose-network default used when [ENV_VAR] is unset. */
        public const val DEFAULT: String = "xmage-server:17171"

        /**
         * Parses a `host:port` string. Both parts are required and the port must be in `1..65535`.
         *
         * @throws IllegalArgumentException if [raw] is blank, not `host:port`, or the port is invalid.
         */
        public fun parse(raw: String): UpstreamTarget {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "$ENV_VAR is blank; expected host:port" }

            val separator = trimmed.lastIndexOf(':')
            require(separator > 0 && separator < trimmed.length - 1) {
                "Invalid $ENV_VAR '$raw'; expected host:port"
            }

            val host = trimmed.substring(0, separator)
            val portText = trimmed.substring(separator + 1)
            val port =
                portText.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port '$portText' in $ENV_VAR '$raw'")
            require(port in 1..65535) { "Port $port out of range (1..65535) in $ENV_VAR '$raw'" }

            return UpstreamTarget(host, port)
        }

        /** Reads [ENV_VAR] from the environment, falling back to [DEFAULT] when unset or blank. */
        public fun fromEnv(): UpstreamTarget {
            val raw = System.getenv(ENV_VAR)
            return parse(if (raw.isNullOrBlank()) DEFAULT else raw)
        }
    }
}
