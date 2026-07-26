package magefree.model

/**
 * A non-session-state failure surfaced to the app: transport/framing faults rather than a normal
 * connection lifecycle transition. Authentication and version-mismatch outcomes are **not** modelled
 * here — they are first-class [SessionEvent]s ([SessionEvent.AuthFailed] /
 * [SessionEvent.VersionUnsupported]) so the UI can render them distinctly.
 */
sealed interface ConnectionError {
    /** A socket/IO-level failure (connect refused, unexpected close, read/write error). */
    data class Transport(
        val message: String,
        val cause: Throwable? = null,
    ) : ConnectionError

    /** The bridge reported a protocol-envelope fault. [code] is the wire error code's name. */
    data class Protocol(
        val code: String,
        val message: String,
    ) : ConnectionError
}
