package magefree.feature.connect

import magefree.model.ServerTarget

/** The human label for a saved server: its [ServerTarget.displayName], falling back to the host. */
val ServerTarget.title: String
    get() = displayName?.takeIf { it.isNotBlank() } ?: host

/** The endpoint line shown under a server's title, e.g. `wss://bridge.local:9000` / `ws://…`. */
val ServerTarget.endpoint: String
    get() = "${if (secure) "wss" else "ws"}://$host:$port"
