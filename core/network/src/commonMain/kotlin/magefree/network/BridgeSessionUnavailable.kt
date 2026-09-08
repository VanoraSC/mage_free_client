package magefree.network

/**
 * The app had no socket to the bridge, so the message never left the device.
 *
 * **The distinction this carries is "who said no".** A [magefree.network.game.GameActionFailure] means
 * the server considered the action and refused it; this means nothing was asked, because the transport
 * was not there. Presenting the second as the first tells a player their move was illegal when the
 * truth is that the connection blinked — and the connection blinking is the case that fixes itself.
 *
 * Raised for both halves of that: no session when the message was handed over, and a session that ended
 * with the reply still outstanding. An [IllegalStateException] so that existing `catch` sites — and the
 * `Result`-returning clients that wrap every throw — keep behaving exactly as they did.
 */
class BridgeSessionUnavailable(
    message: String,
) : IllegalStateException(message)
