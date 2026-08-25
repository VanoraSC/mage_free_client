package magefree.network.reconnect

import magefree.model.SessionEvent

/**
 * The bridge-issued resume handle (story 0023) carried across reconnect attempts.
 *
 * A fresh [connect][magefree.network.BridgeClient.connect] starts with an empty handle and sends
 * `Login`; when the bridge delivers a `SessionResumable`, [resumeId] is captured here so the **next**
 * attempt sends `Resume(resumeId)` instead of re-authenticating. A `ResumeRejected` clears it back to
 * `null`, so the loop falls back to a fresh `Login`. `@Volatile` because it is written on the session
 * coroutine and read when the next attempt begins.
 */
class ResumeHandle {
    @Volatile
    var resumeId: String? = null
}

/** How a single socket session ended, deciding whether the reconnect loop retries. */
enum class SessionOutcome {
    /** A terminal status (auth/version failure, relayed disconnect, protocol error) — do not retry. */
    TERMINAL,

    /** The socket closed because the app requested disconnect — surface a clean `Disconnected`. */
    CLEAN_CLOSE,

    /** The socket dropped unexpectedly — the loop should reconnect (and `Resume` if a handle is held). */
    RETRY,
}

/**
 * Runs exactly one socket session — open, handshake, `Resume`/`Login`, relay — and reports how it
 * ended, updating the shared [ResumeHandle]. `KtorBridgeClient` supplies the real Ktor implementation;
 * `ReconnectingSession` (and its tests, via a fake runner) drives it in a back-off/connectivity/
 * lifecycle-aware loop.
 */
fun interface SessionRunner {
    suspend fun runOnce(
        handle: ResumeHandle,
        emit: suspend (SessionEvent) -> Unit,
    ): SessionOutcome
}
