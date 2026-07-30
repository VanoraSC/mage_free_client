package magefree.network.mapper

import magefree.model.ConnectionError
import magefree.model.ConnectionState
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.SessionEvent
import magefree.protocol.ChatEvent
import magefree.protocol.ChatKind
import magefree.protocol.Pong
import magefree.protocol.ProtocolError
import magefree.protocol.ProtocolErrorCode
import magefree.protocol.ProtocolVersion
import magefree.protocol.ResumeRejected
import magefree.protocol.ServerHello
import magefree.protocol.SessionResumable
import magefree.protocol.SessionStateCode
import magefree.protocol.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit coverage for the `:protocol` -> `:core:model` mapping — the single coupling point. Proves
 * every session state code, the local handshake check, and the protocol-error path translate to the
 * intended domain [SessionEvent], and that off-lifecycle messages are ignored.
 */
class SessionMapperTest {
    private val target = ServerTarget(host = "bridge.local", port = 9000)
    private val credentials = Credentials(username = "pete", password = "secret")

    @Test
    fun everySessionStateCodeMapsToTheAlignedConnectionState() =
        with(SessionMapper) {
            assertEquals(ConnectionState.Connecting, SessionStateCode.CONNECTING.toConnectionState())
            assertEquals(ConnectionState.Connected, SessionStateCode.CONNECTED.toConnectionState())
            assertEquals(ConnectionState.AuthFailed, SessionStateCode.AUTH_FAILED.toConnectionState())
            assertEquals(ConnectionState.Unsupported, SessionStateCode.VERSION_UNSUPPORTED.toConnectionState())
            assertEquals(ConnectionState.Disconnected, SessionStateCode.DISCONNECTED.toConnectionState())
            assertEquals(ConnectionState.Reconnecting, SessionStateCode.RECONNECTING.toConnectionState())
        }

    @Test
    fun handshakeAcceptsMatchingMajor() {
        val hello = ServerHello(ProtocolVersion.MAJOR, ProtocolVersion.MINOR, "bridge-1.4.60")
        assertNull(SessionMapper.handshakeResult(hello))
    }

    @Test
    fun handshakeRejectsMismatchedMajorAsVersionUnsupported() {
        val hello = ServerHello(ProtocolVersion.MAJOR + 1, 0, "bridge-9.9.9")
        val result = SessionMapper.handshakeResult(hello)
        assertTrue(result is SessionEvent.VersionUnsupported)
        assertEquals(ConnectionState.Unsupported, result!!.connectionState)
    }

    @Test
    fun connectedStatusCarriesASessionHandle() {
        val event =
            SessionMapper.toSessionEvent(
                message = SessionStatus(SessionStateCode.CONNECTED),
                target = target,
                username = credentials.username,
                bridgeVersion = "bridge-1.4.60",
            )
        assertTrue(event is SessionEvent.Connected)
        val connected = event as SessionEvent.Connected
        assertEquals(target, connected.session.target)
        assertEquals("pete", connected.session.username)
        assertEquals("bridge-1.4.60", connected.session.bridgeVersion)
    }

    @Test
    fun authFailedStatusPreservesTheMessage() {
        val event =
            SessionMapper.toSessionEvent(
                message = SessionStatus(SessionStateCode.AUTH_FAILED, message = "bad credentials"),
                target = target,
                username = credentials.username,
                bridgeVersion = null,
            )
        assertEquals(SessionEvent.AuthFailed("bad credentials"), event)
    }

    @Test
    fun versionUnsupportedStatusIsFirstClass() {
        val event =
            SessionMapper.toSessionEvent(
                message = SessionStatus(SessionStateCode.VERSION_UNSUPPORTED, message = "server=1.4.61 bridge=1.4.60"),
                target = target,
                username = credentials.username,
                bridgeVersion = null,
            )
        assertEquals(SessionEvent.VersionUnsupported("server=1.4.61 bridge=1.4.60"), event)
    }

    @Test
    fun disconnectedAndReconnectingStatusesMap() {
        assertEquals(
            SessionEvent.Disconnected("dropped"),
            SessionMapper.toSessionEvent(
                SessionStatus(SessionStateCode.DISCONNECTED, message = "dropped"),
                target,
                credentials.username,
                null,
            ),
        )
        assertEquals(
            SessionEvent.Reconnecting,
            SessionMapper.toSessionEvent(
                SessionStatus(SessionStateCode.RECONNECTING),
                target,
                credentials.username,
                null,
            ),
        )
    }

    @Test
    fun protocolVersionErrorMapsToVersionUnsupportedNotGenericError() {
        val event =
            SessionMapper.toSessionEvent(
                message = ProtocolError(ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED, "unsupported major"),
                target = target,
                username = credentials.username,
                bridgeVersion = null,
            )
        assertEquals(SessionEvent.VersionUnsupported("unsupported major"), event)
    }

    @Test
    fun otherProtocolErrorsMapToAProtocolError() {
        val event =
            SessionMapper.toSessionEvent(
                message = ProtocolError(ProtocolErrorCode.MALFORMED_MESSAGE, "bad frame"),
                target = target,
                username = credentials.username,
                bridgeVersion = null,
            )
        assertTrue(event is SessionEvent.Error)
        val error = (event as SessionEvent.Error).error
        assertEquals(ConnectionError.Protocol("MALFORMED_MESSAGE", "bad frame"), error)
    }

    @Test
    fun resumeIdIsExtractedFromSessionResumableOnly() {
        assertEquals("resume-123", SessionMapper.resumeIdOrNull(SessionResumable(resumeId = "resume-123")))
        assertNull(SessionMapper.resumeIdOrNull(SessionStatus(SessionStateCode.CONNECTED)))
        assertNull(SessionMapper.resumeIdOrNull(ResumeRejected(reason = "expired")))
    }

    @Test
    fun resumeRejectedIsDetected() {
        assertTrue(SessionMapper.isResumeRejected(ResumeRejected(reason = "expired")))
        assertFalse(SessionMapper.isResumeRejected(SessionResumable(resumeId = "r1")))
        assertFalse(SessionMapper.isResumeRejected(SessionStatus(SessionStateCode.CONNECTED)))
    }

    @Test
    fun resumeFlowControlFramesAreNotLifecycleEvents() {
        // SessionResumable/ResumeRejected are handled out-of-band by the relay, never as SessionEvents.
        assertNull(SessionMapper.toSessionEvent(SessionResumable(resumeId = "r1"), target, credentials.username, null))
        assertNull(SessionMapper.toSessionEvent(ResumeRejected(reason = "expired"), target, credentials.username, null))
    }

    @Test
    fun offLifecycleMessagesAreIgnored() {
        assertNull(
            SessionMapper.toSessionEvent(Pong(nonce = "n"), target, credentials.username, null),
        )
        assertNull(
            SessionMapper.toSessionEvent(
                ChatEvent(text = "hi", username = "someone", timestampEpochMs = 0, kind = ChatKind.TALK),
                target,
                credentials.username,
                null,
            ),
        )
    }
}
