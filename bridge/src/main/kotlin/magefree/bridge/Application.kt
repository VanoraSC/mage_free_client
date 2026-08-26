package magefree.bridge

import com.typesafe.config.ConfigFactory
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import magefree.bridge.routes.healthRoutes
import magefree.bridge.session.SessionRegistry
import magefree.bridge.ws.sessionWebSocket
import magefree.protocol.ProtocolJson
import org.slf4j.event.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Process entry point. Reads the deployment port from `application.conf` (overridable via the
 * `BRIDGE_PORT` environment variable) and starts a Netty engine pointed at [Application.module].
 */
fun main() {
    val config = HoconApplicationConfig(ConfigFactory.load())
    val port = config.property("ktor.deployment.port").getString().toInt()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

/**
 * How often the bridge pings an app socket, and how long it then waits for the pong (
 * defect B).
 *
 * Ktor disables the server pinger by default (`pingPeriodMillis = PINGER_DISABLED`), which meant a
 * half-open app socket — a phone whose radio dies, so no FIN is ever delivered — was never noticed:
 * the coordinator's read simply never returned, the session stayed **bound** rather than parked, its
 * resume TTL never started, and it was therefore never evicted. One such session sat bound for 7.9
 * hours in the smoke's bridge log, and while it did a returning app could not resume it (the registry
 * only resumes a *parked* entry) so it opened a **second** XMage login for the same username.
 *
 * 15s + 15s bounds that at ~30s to detect, plus the 60s resume TTL to reclaim. Well inside the
 * upstream's own ~3-minute idle expiry, and far above any plausible round trip.
 */
private val APP_SOCKET_PING_PERIOD: Duration = 15.seconds

/**
 * Wires the bridge: installs shared plugins and registers routes. Kept separate from [main] so
 * tests can boot it with Ktor's `testApplication`.
 *
 * @param wsPingPeriod the app-socket keepalive cadence (and pong timeout); see
 *   [APP_SOCKET_PING_PERIOD]. Parameterised only so a test can prove the reaping happens without
 *   sleeping out the production interval.
 */
fun Application.module(wsPingPeriod: Duration = APP_SOCKET_PING_PERIOD) {
    install(ContentNegotiation) {
        json()
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(ProtocolJson.json)
        // A peer that stops answering is a peer that is gone: hang up, so the coordinator's teardown
        // runs and the session is parked (and then TTL-evicted) instead of leaking bound forever.
        pingPeriod = wsPingPeriod
        timeout = wsPingPeriod
    }
    // One registry per application: it holds parked sessions across app-socket drops.
    // Parented to the application scope so its TTL/keepalive coroutines are cancelled on shutdown.
    val registry = SessionRegistry(parentContext = coroutineContext)
    routing {
        healthRoutes()
        sessionWebSocket(registry = registry)
    }
}
