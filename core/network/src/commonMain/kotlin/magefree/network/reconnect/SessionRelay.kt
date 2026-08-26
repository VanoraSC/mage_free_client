package magefree.network.reconnect

import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.model.Session
import magefree.model.SessionEvent
import magefree.network.mapper.SessionMapper
import magefree.protocol.ClientMessage
import magefree.protocol.Login
import magefree.protocol.Resume
import magefree.protocol.ServerMessage

/**
 * The frame-level relay for one authenticated socket session, factored out of the Ktor
 * transport so the **`Resume` vs `Login`** decision and the `ResumeRejected` → `Login` fallback are
 * unit-testable without a live socket: it speaks in `:protocol` [ServerMessage]/[ClientMessage] over two
 * injected suspend I/O lambdas ([receive]/[send]) and emits `:core:model` [SessionEvent]s via the
 * production [SessionMapper].
 *
 * Post-handshake behaviour:
 * - **Open:** if [handle] holds a `resumeId`, send `Resume(resumeId)` (a reconnect resuming the parked
 *   session) and emit [SessionEvent.Restoring] while awaiting its ack; otherwise send
 *   `Login` with the retained [credentials].
 * - **`SessionResumable`:** capture the handle; when it is the ack of a `Resume` we just sent, surface
 *   [SessionEvent.Connected] (the resume completed — recovery is over).
 * - **`ResumeRejected`:** the parked session is gone/expired — clear the handle and fall back to a fresh
 *   `Login` on the same socket.
 * - **A correlated reply** (a lobby list reply matching an outstanding [pending] request) is
 *   routed to the waiting requester and consumed here — it is *not* a lifecycle frame, so the
 *   session-event stream is untouched.
 * - **An uncorrelated, non-lifecycle push** (a 0036 table *event* the [SessionMapper] does not map —
 *   the seam (b)) is forwarded to [featurePush] so a feature layer (the table client) can fold
 *   it, instead of being silently dropped. It is *not* a `SessionEvent`, so the session-event stream is
 *   again untouched; a caller that supplies no [featurePush] (the default no-op) is unaffected.
 * - Every other server frame maps through [SessionMapper]; a terminal event ends the session.
 */
internal object SessionRelay {
    suspend fun run(
        server: ServerTarget,
        credentials: Credentials,
        bridgeVersion: String?,
        handle: ResumeHandle,
        receive: suspend () -> ServerMessage?,
        send: suspend (ClientMessage) -> Unit,
        emit: suspend (SessionEvent) -> Unit,
        isDisconnectRequested: () -> Boolean,
        pending: PendingRequests = PendingRequests(),
        featurePush: suspend (ServerMessage) -> Unit = {},
    ): SessionOutcome {
        var awaitingResumeAck = false
        val held = handle.resumeId
        if (held != null) {
            // Socket is back: ask the bridge to re-attach the parked session and surface Restoring
            // (distinct from Reconnecting) for the window until the ack turns this into Connected.
            send(Resume(held))
            awaitingResumeAck = true
            emit(SessionEvent.Restoring)
        } else {
            send(Login(credentials.username, credentials.password))
        }

        while (true) {
            val message = receive() ?: break

            if (pending.tryComplete(message)) {
                // A correlated request/response reply: handed to the waiting requester,
                // never mapped to a SessionEvent. The live session keeps relaying as before.
                continue
            }

            if (SessionMapper.isUnknown(message)) {
                // Additive forward-compat: an unknown server `type` is ignored — no
                // lifecycle change and, critically, no reconnect. The session keeps relaying.
                continue
            }

            val resumeId = SessionMapper.resumeIdOrNull(message)
            if (resumeId != null) {
                handle.resumeId = resumeId
                if (awaitingResumeAck) {
                    // The bridge acked our Resume: the parked session is live again.
                    awaitingResumeAck = false
                    emit(SessionEvent.Connected(Session(server, credentials.username, bridgeVersion)))
                }
                continue
            }

            if (SessionMapper.isResumeRejected(message)) {
                // Handle unknown/expired: drop it and re-authenticate on this same socket.
                handle.resumeId = null
                awaitingResumeAck = false
                send(Login(credentials.username, credentials.password))
                continue
            }

            val event =
                SessionMapper.toSessionEvent(message, server, credentials.username, bridgeVersion)
            if (event == null) {
                // Not a lifecycle frame and not a correlated reply: a spontaneous server push (a 0036
                // table event, or a chat/ping/server-info the app ignores). Offer it to the feature
                // side-channel — the table client folds the ones for its table; the rest
                // are harmlessly ignored downstream. The session keeps relaying either way.
                featurePush(message)
                continue
            }
            emit(event)
            when (event) {
                is SessionEvent.AuthFailed,
                is SessionEvent.VersionUnsupported,
                is SessionEvent.Disconnected,
                is SessionEvent.Error,
                -> return SessionOutcome.TERMINAL

                else -> Unit // Connecting / Connected / Reconnecting: keep relaying.
            }
        }
        // Incoming closed without a terminal status.
        return if (isDisconnectRequested()) SessionOutcome.CLEAN_CLOSE else SessionOutcome.RETRY
    }
}
