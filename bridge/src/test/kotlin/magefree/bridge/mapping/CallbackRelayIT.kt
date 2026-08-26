package magefree.bridge.mapping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import magefree.bridge.xmage.XMageConnection
import magefree.bridge.xmage.XMageServerTarget
import magefree.bridge.xmage.XMageSession
import magefree.protocol.ChatEvent
import magefree.protocol.ProtocolJson
import magefree.protocol.ServerMessage
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * Live end-to-end proof that the callback relay maps a **real** server-produced `CHATMESSAGE` to the
 * same golden the hermetic [ChatMessageMapperTest] asserts — the golden-file drift detector's opt-in
 * tier (`docs/architecture.md`).
 *
 * It connects a real [XMageSession] to the reference server, joins the main room chat, sends a chat
 * message, receives the resulting `CHATMESSAGE` callback (re-published off the remoting thread by
 * `BridgeMageClient`), maps it via [CallbackMapper], normalizes the non-deterministic fields (username
 * and timestamp) to the golden's fixed values, and asserts it matches `chat_talk.json`.
 *
 * **Env-gated:** enabled only when `XMAGE_SERVER` is set (mirroring the `ConnectAuthenticateIT`);
 * otherwise JUnit reports it *skipped*, keeping `./scripts/dev gradle check` hermetic.
 *
 * ```
 * ./scripts/dev up xmage-server
 * XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*CallbackRelayIT' --rerun-tasks
 * ```
 */
@EnabledIfEnvironmentVariable(named = XMageServerTarget.ENV_VAR, matches = ".+")
class CallbackRelayIT {
    private companion object {
        // Must match the committed golden (chat_talk.json) after normalization.
        const val GOLDEN_USERNAME = "golden_user"
        const val GOLDEN_TEXT = "hello from the bridge"
    }

    @Test
    fun `a real chat callback maps to the chat_talk golden`() =
        runBlocking {
            val target = requireNotNull(XMageServerTarget.fromEnv()) { "XMAGE_SERVER must be set for this test" }
            // XMage validates usernames: [a-z0-9_], length 3..14. "it_" + 8 hex = 11 chars.
            val username = "it_${UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"

            val session = XMageSession()
            val connection =
                XMageConnection.build(
                    host = target.host,
                    port = target.port,
                    username = username,
                    password = "",
                )

            check(session.connect(connection)) { "connectStart should succeed against the reference server" }
            try {
                val chatId =
                    requireNotNull(session.mainRoomChatId()) { "main room chat id should be resolvable once connected" }
                check(session.joinChat(chatId)) { "joinChat should be accepted" }

                // Subscribe to the raw callbacks, then (race-free, via onSubscription) send the chat
                // message once the collector is registered. Map each callback with the same mapper the
                // relay uses and take the first ChatEvent whose text is ours (skipping join/system chatter).
                val rawCallbacks = session.client.callbacks
                val event: ChatEvent =
                    withTimeout(30_000) {
                        rawCallbacks
                            .onSubscription {
                                withContext(Dispatchers.IO) { session.sendChatMessage(chatId, GOLDEN_TEXT) }
                            }.mapNotNull { CallbackMapper.map(it) }
                            .filterIsInstance<ChatEvent>()
                            .first { it.text == GOLDEN_TEXT }
                    }

                assertNotNull(event, "expected a mapped ChatEvent for the sent message")

                // Normalize the fields the server sets non-deterministically (sender name, wall-clock time)
                // to the golden's fixed values; the mapping itself (text, kind, structure) is asserted as-is.
                val normalized = event.copy(username = GOLDEN_USERNAME, timestampEpochMs = 0L)
                val json = ProtocolJson.json.encodeToString<ServerMessage>(normalized)
                GoldenFiles.assertMatchesGolden("chat_talk", json)
            } finally {
                session.disconnect()
            }
        }
}
