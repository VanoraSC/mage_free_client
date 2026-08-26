package magefree.bridge.xmage

/**
 * A parsed `host:port` XMage server target, used to gate and drive the live integration test from
 * the `XMAGE_SERVER` environment variable (e.g. `xmage-server:17171`).
 *
 * Defined here, with its first consumer [ConnectAuthenticateIT],
 * whose only realized artifact is the `xmage-server` reference container.
 */
public data class XMageServerTarget(
    val host: String,
    val port: Int,
) {
    public companion object {
        /** The environment variable that names the live server target, if set. */
        public const val ENV_VAR: String = "XMAGE_SERVER"

        /**
         * Parses a `host:port` string. Both parts are required and the port must be a valid TCP
         * port (1..65535).
         *
         * @throws IllegalArgumentException if [raw] is blank, not `host:port`, or the port is not a
         *   valid integer in range.
         */
        public fun parse(raw: String): XMageServerTarget {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "XMAGE_SERVER is blank; expected host:port" }

            val separator = trimmed.lastIndexOf(':')
            require(separator > 0 && separator < trimmed.length - 1) {
                "Invalid XMAGE_SERVER '$raw'; expected host:port"
            }

            val host = trimmed.substring(0, separator)
            val portText = trimmed.substring(separator + 1)
            val port =
                portText.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port '$portText' in XMAGE_SERVER '$raw'")
            require(port in 1..65535) { "Port $port out of range (1..65535) in XMAGE_SERVER '$raw'" }

            return XMageServerTarget(host, port)
        }

        /**
         * Reads and parses [ENV_VAR] from the process environment, or returns `null` when it is
         * unset or blank — the signal for [ConnectAuthenticateIT] to skip.
         */
        public fun fromEnv(): XMageServerTarget? {
            val raw = System.getenv(ENV_VAR)
            return if (raw.isNullOrBlank()) null else parse(raw)
        }
    }
}
