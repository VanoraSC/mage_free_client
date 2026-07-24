package magefree.bridge

import com.typesafe.config.ConfigFactory
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import magefree.bridge.routes.healthRoutes
import org.slf4j.event.Level

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
 * Wires the bridge: installs shared plugins and registers routes. Kept separate from [main] so
 * tests can boot it with Ktor's `testApplication`.
 */
fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    install(CallLogging) {
        level = Level.INFO
    }
    routing {
        healthRoutes()
    }
}
