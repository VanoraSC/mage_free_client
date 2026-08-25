package magefree.network.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/** OkHttp — see [bridgeHttpClient]'s KDoc for why the engine is chosen per target. */
internal actual fun bridgeHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        install(WebSockets)
    }
