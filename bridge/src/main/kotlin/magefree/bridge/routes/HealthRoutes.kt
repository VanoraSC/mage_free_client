package magefree.bridge.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** Response body for the `/health` endpoint. Serialized to JSON via ContentNegotiation. */
@Serializable
data class HealthStatus(
    val status: String,
    val service: String,
)

/** Registers the `/health` liveness endpoint. */
fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthStatus(status = "ok", service = "mage-bridge"))
    }
}
