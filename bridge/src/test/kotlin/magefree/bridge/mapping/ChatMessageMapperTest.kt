package magefree.bridge.mapping

import kotlinx.serialization.encodeToString
import mage.interfaces.callback.ClientCallback
import mage.interfaces.callback.ClientCallbackMethod
import mage.view.ChatMessage
import magefree.protocol.ChatKind
import magefree.protocol.ProtocolJson
import magefree.protocol.ServerMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Date
import java.util.UUID

/**
 * Hermetic golden test for the mapper boundary: builds a `mage.view.ChatMessage` in memory (we depend
 * on `mage-common`), maps it, serializes with `ProtocolJson.json`, and asserts it matches the committed
 * `chat_talk.json`. The same golden is asserted by the live `CallbackRelayIT` against a *real*
 * server-produced callback, proving these in-memory fixtures match reality.
 *
 * The fixture uses fixed values — username `golden_user`, text `hello from the bridge`, time `Date(0)`
 * → `timestampEpochMs = 0` — so the golden is deterministic; the live test normalizes the same fields.
 */
class ChatMessageMapperTest {
    private companion object {
        const val GOLDEN_USERNAME = "golden_user"
        const val GOLDEN_TEXT = "hello from the bridge"
        val GOLDEN_TIME: Date = Date(0)
    }

    @Test
    fun `maps a TALK ChatMessage to the chat_talk golden`() {
        // ChatMessage(username, message, time, game, color, messageType, soundToPlay)
        val message =
            ChatMessage(
                GOLDEN_USERNAME,
                GOLDEN_TEXT,
                GOLDEN_TIME,
                null,
                ChatMessage.MessageColor.BLUE,
                ChatMessage.MessageType.TALK,
                null,
            )

        val event = ChatMessageMapper.map(message)

        assertEquals(GOLDEN_TEXT, event.text)
        assertEquals(GOLDEN_USERNAME, event.username)
        assertEquals(0L, event.timestampEpochMs)
        assertEquals(ChatKind.TALK, event.kind)

        val json = ProtocolJson.json.encodeToString<ServerMessage>(event)
        GoldenFiles.assertMatchesGolden("chat_talk", json)
    }

    @Test
    fun `maps whisper message types to WHISPER_IN and WHISPER_OUT`() {
        fun kindFor(type: ChatMessage.MessageType): ChatKind =
            ChatMessageMapper
                .map(ChatMessage("u", "m", Date(0), null, ChatMessage.MessageColor.BLUE, type, null))
                .kind

        assertEquals(ChatKind.WHISPER_IN, kindFor(ChatMessage.MessageType.WHISPER_FROM))
        assertEquals(ChatKind.WHISPER_OUT, kindFor(ChatMessage.MessageType.WHISPER_TO))
        assertEquals(ChatKind.GAME, kindFor(ChatMessage.MessageType.GAME))
        assertEquals(ChatKind.STATUS, kindFor(ChatMessage.MessageType.STATUS))
        assertEquals(ChatKind.USER, kindFor(ChatMessage.MessageType.USER_INFO))
    }

    @Test
    fun `a known-method callback with a malformed payload maps to null instead of throwing (F5)`() {
        // F5: a CHATMESSAGE whose decompressed payload is not a ChatMessage (upstream shape drift) must
        // be logged and dropped (mapped to null), never thrown — a bad push cannot crash the session.
        val malformed =
            ClientCallback(ClientCallbackMethod.CHATMESSAGE, UUID.randomUUID(), "definitely not a ChatMessage")

        assertNull(CallbackMapper.map(malformed))
    }
}
