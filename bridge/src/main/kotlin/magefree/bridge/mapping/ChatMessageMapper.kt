package magefree.bridge.mapping

import mage.view.ChatMessage
import mage.view.ChatMessage.MessageType
import magefree.protocol.ChatEvent
import magefree.protocol.ChatKind

/**
 * Maps an upstream [mage.view.ChatMessage] to the app-schema [ChatEvent]. This is the sample member
 * of the **single coupling surface**: the only place a `mage.view.*` shape is read. It is pure and
 * deterministic — same input always yields the same output, with no I/O or hidden state — so it is
 * fully exercised by the golden-file harness.
 *
 * Field mapping:
 * - [ChatEvent.text] ← [ChatMessage.getMessage].
 * - [ChatEvent.username] ← [ChatMessage.getUsername] (nullable for system messages).
 * - [ChatEvent.timestampEpochMs] ← [ChatMessage.getTime]`.time` (epoch millis; 0 if the time is null).
 * - [ChatEvent.kind] ← [ChatMessage.getMessageType] via [kindOf].
 */
public object ChatMessageMapper {
    /** Maps [message] to its app-schema [ChatEvent]. */
    public fun map(message: ChatMessage): ChatEvent =
        ChatEvent(
            text = message.message,
            username = message.username,
            timestampEpochMs = message.time?.time ?: 0L,
            kind = kindOf(message.messageType),
        )

    /**
     * Classifies an upstream [MessageType] into a [ChatKind]. An unknown/null type defensively maps to
     * [ChatKind.TALK] (the common public-chat case) rather than throwing, keeping the relay resilient
     * to upstream additions.
     */
    private fun kindOf(type: MessageType?): ChatKind =
        when (type) {
            MessageType.TALK -> ChatKind.TALK
            MessageType.WHISPER_FROM -> ChatKind.WHISPER_IN
            MessageType.WHISPER_TO -> ChatKind.WHISPER_OUT
            MessageType.GAME -> ChatKind.GAME
            MessageType.STATUS -> ChatKind.STATUS
            MessageType.USER_INFO -> ChatKind.USER
            null -> ChatKind.TALK
        }
}
