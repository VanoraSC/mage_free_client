package magefree.feature.connect

import magefree.model.ConnectionState
import magefree.model.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the connection-state projection and the server editor's validation. */
class ConnectPhaseTest {
    @Test
    fun everyConnectionStateMapsToADistinctPhase() {
        val phases = ConnectionState.entries.map { it.toConnectPhase() }
        assertEquals("mapping must be total and injective", ConnectionState.entries.size, phases.toSet().size)
    }

    @Test
    fun authFailedAndVersionUnsupportedAreDistinctPhases() {
        assertEquals(ConnectPhase.AuthFailed, ConnectionState.AuthFailed.toConnectPhase())
        assertEquals(ConnectPhase.VersionUnsupported, ConnectionState.Unsupported.toConnectPhase())
        assertNotEquals(
            ConnectionState.AuthFailed.toConnectPhase(),
            ConnectionState.Unsupported.toConnectPhase(),
        )
    }

    @Test
    fun connectingAndReconnectingAreInProgress() {
        assertTrue(ConnectPhase.Connecting.isInProgress)
        assertTrue(ConnectPhase.Reconnecting.isInProgress)
        assertFalse(ConnectPhase.Idle.isInProgress)
        assertFalse(ConnectPhase.Connected.isInProgress)
        assertFalse(ConnectPhase.AuthFailed.isInProgress)
    }

    @Test
    fun editorRejectsBlankHostAndBadPort() {
        val blankHost = ServerEditorState(host = "", port = "9000")
        assertFalse(blankHost.canSave)
        assertNull(blankHost.portError)

        val badPort = ServerEditorState(host = "bridge.local", port = "0")
        assertFalse(badPort.canSave)

        val nonNumericPort = ServerEditorState(host = "bridge.local", port = "abc")
        assertFalse(nonNumericPort.canSave)
    }

    @Test
    fun editorAcceptsValidInputAndTrimsBlankNameToNull() {
        val editor = ServerEditorState(name = "  ", host = " bridge.local ", port = " 9000 ")
        assertTrue(editor.canSave)
        val target: ServerTarget = editor.toTarget()
        assertEquals("bridge.local", target.host)
        assertEquals(9000, target.port)
        assertNull(target.displayName)
    }
}
