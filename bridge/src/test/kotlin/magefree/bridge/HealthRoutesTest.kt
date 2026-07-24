package magefree.bridge

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthRoutesTest {
    @Test
    fun `GET health returns 200 with the expected JSON body`() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                """{"status":"ok","service":"mage-bridge"}""",
                response.bodyAsText(),
            )
        }
}
