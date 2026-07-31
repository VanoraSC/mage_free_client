package magefree.bridge.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import magefree.protocol.ServerInfo
import magefree.protocol.ServerMessage
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tunables for session parking, read from the environment with sane defaults (story 0023).
 *
 * - [ttl] — the **grace window** a session is held after its app socket drops before it is evicted
 *   and disconnected. Env `RESUME_TTL_SECONDS` (the story's `RESUME_TTL`); default 60s.
 * - [keepaliveInterval] — how often a parked session is `ping`ed to keep the upstream link healthy
 *   (and to notice if it died). Env `RESUME_KEEPALIVE_SECONDS`; default 15s.
 *
 * Non-positive or unparsable values fall back to the defaults.
 */
public data class ResumeConfig(
    val ttl: Duration = DEFAULT_TTL,
    val keepaliveInterval: Duration = DEFAULT_KEEPALIVE,
) {
    public companion object {
        /** Env var for [ttl], in whole seconds. */
        public const val TTL_ENV: String = "RESUME_TTL_SECONDS"

        /** Env var for [keepaliveInterval], in whole seconds. */
        public const val KEEPALIVE_ENV: String = "RESUME_KEEPALIVE_SECONDS"

        /** Default grace window when [TTL_ENV] is unset/invalid. */
        public val DEFAULT_TTL: Duration = 60.seconds

        /** Default keepalive cadence when [KEEPALIVE_ENV] is unset/invalid. */
        public val DEFAULT_KEEPALIVE: Duration = 15.seconds

        private fun seconds(
            env: String,
            fallback: Duration,
        ): Duration {
            val parsed = System.getenv(env)?.trim()?.toLongOrNull()
            return if (parsed != null && parsed > 0) parsed.seconds else fallback
        }

        /** Reads [TTL_ENV]/[KEEPALIVE_ENV] from the environment, defaulting each independently. */
        public fun fromEnv(): ResumeConfig =
            ResumeConfig(
                ttl = seconds(TTL_ENV, DEFAULT_TTL),
                keepaliveInterval = seconds(KEEPALIVE_ENV, DEFAULT_KEEPALIVE),
            )
    }
}

/**
 * A single upstream session whose outbound [ServerMessage] stream is **pumped on a bridge-level
 * scope** so it outlives any one app socket (story 0023). Because the pump is not tied to a socket
 * coroutine, an app-socket drop no longer tears the session down: the underlying [UpstreamSession]
 * (its `SessionImpl`) stays connected to XMage while parked, and a reconnecting app re-binds by
 * consuming this same [messages] channel — **no second upstream connect/login**.
 *
 * The outbound channel is the durable, rebindable "sink" the story describes: exactly one socket
 * forwarder consumes it at a time (enforced by [SessionRegistry]'s state transitions). While parked
 * (no consumer) the pump keeps producing; the channel's `DROP_OLDEST` policy keeps the pump
 * non-blocking and bounds memory — stale status frames are dropped, never the live session.
 */
public class LiveSession internal constructor(
    /** The credentials the session logged in with (captured for diagnostics/parity). */
    public val credentials: Credentials,
    private val upstream: UpstreamSession,
    scope: CoroutineScope,
) {
    private val outbound =
        Channel<ServerMessage>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The durable outbound stream a socket forwarder binds to; rebindable across app-socket drops. */
    public val messages: ReceiveChannel<ServerMessage> get() = outbound

    /** Completes when the upstream link ends (clean drop, death, or [close]); watched for eviction. */
    internal val ended: CompletableDeferred<Unit> = CompletableDeferred()

    // The single collector of upstream.connect(...): started here on the bridge scope (NOT a socket
    // coroutine), so it survives socket drops. send() never suspends (DROP_OLDEST). On completion the
    // channel closes so the current forwarder's loop ends and the registry evicts a dead session.
    private val pump: Job =
        scope.launch(CoroutineName("session-pump")) {
            try {
                upstream.connect(credentials).collect { outbound.send(it) }
            } finally {
                outbound.close()
                ended.complete(Unit)
            }
        }

    /** Keepalive probe delegated to the upstream; used by the registry while parked. */
    internal suspend fun ping(): Boolean = upstream.ping()

    /** The upstream server-assigned session id (stable across resume); for the live-IT assertion. */
    public suspend fun sessionId(): String? = upstream.sessionId()

    /** The upstream server info for a `GetServerInfo` request while this session is bound. */
    internal suspend fun serverInfo(): ServerInfo? = upstream.serverInfo()

    /** Cancels the pump and disconnects the upstream. Idempotent. */
    internal suspend fun close() {
        pump.cancel()
        withContext(NonCancellable) { upstream.disconnect() }
    }
}

/**
 * Bridge-level registry of resumable sessions, keyed by a bridge-issued **resume id** (story 0023).
 * One instance is shared across every app socket, so it must be — and is — **thread-safe**: all
 * entry-map mutations happen under a single [Mutex], and every TTL/keepalive timer runs on this
 * registry's own [scope], never on a per-socket coroutine.
 *
 * Lifecycle of an entry:
 * - [createSession] builds a [LiveSession] whose pump starts immediately on [scope]; if the upstream
 *   ever ends, the entry (if any) is evicted so a later resume is rejected.
 * - [register] (on `Login`→`CONNECTED`) mints the resume id and inserts a **bound** entry (no timer).
 * - [park] (on an unexpected app-socket close) starts the grace-window TTL + keepalive ping loop.
 * - [resume] (on a `Resume` frame) cancels those timers and hands back the still-live session to
 *   re-bind to the new socket; returns `null` on an unknown/expired id.
 * - [evict] (on `Logout`, TTL expiry, or upstream death) removes the entry and `disconnect()`s it.
 *
 * The `park(session, sink) -> resumeId` shape sketched in the story is realised here as
 * [register] (mint the id at `CONNECTED`, before any drop, so the app can receive it immediately)
 * plus [park] (start the grace timer at drop). The "last-bound sink" is [LiveSession.messages] — the
 * durable outbound channel a reconnecting socket re-consumes.
 *
 * @param config TTL + keepalive tunables (defaults from the environment).
 * @param parentContext optional parent context; when it carries a [Job] (e.g. the Ktor application
 *   scope) the registry's coroutines are cancelled with it, so no shutdown hook is required. Unit
 *   tests pass none and call [shutdown] explicitly.
 */
public class SessionRegistry(
    private val config: ResumeConfig = ResumeConfig.fromEnv(),
    parentContext: CoroutineContext = EmptyCoroutineContext,
) {
    private val logger = LoggerFactory.getLogger(SessionRegistry::class.java)

    private val scope =
        CoroutineScope(
            SupervisorJob(parentContext[Job]) + Dispatchers.Default + CoroutineName("session-registry"),
        )

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()

    private class Entry(
        val resumeId: String,
        val live: LiveSession,
    ) {
        @Volatile var parked: Boolean = false

        @Volatile var lastSeen: Long = System.currentTimeMillis()

        @Volatile var ttlJob: Job? = null

        @Volatile var keepaliveJob: Job? = null
    }

    /**
     * Builds a [LiveSession] for [upstream]/[credentials], starting its outbound pump on this
     * registry's scope. Wires upstream-death to eviction so a dead session (bound or parked) can
     * never be resumed. The session is not yet registered — call [register] once it reaches
     * `CONNECTED`.
     */
    public fun createSession(
        upstream: UpstreamSession,
        credentials: Credentials,
    ): LiveSession {
        val live = LiveSession(credentials, upstream, scope)
        live.ended.invokeOnCompletion {
            scope.launch { evictByLive(live) }
        }
        return live
    }

    /** Mints a resume id and inserts a bound entry (no TTL). Returns the id to hand to the app. */
    public suspend fun register(live: LiveSession): String {
        val id = UUID.randomUUID().toString()
        mutex.withLock { entries[id] = Entry(id, live) }
        logger.info("Registered resumable session {}", id)
        return id
    }

    /**
     * Transitions the entry for [resumeId] from bound to **parked**: records the drop, starts the TTL
     * eviction timer and the keepalive ping loop. No-op if unknown or already parked. Safe to call
     * under `NonCancellable` from a socket teardown.
     */
    public suspend fun park(resumeId: String) {
        mutex.withLock {
            val entry = entries[resumeId] ?: return
            if (entry.parked) return
            entry.parked = true
            entry.lastSeen = System.currentTimeMillis()
            entry.ttlJob =
                scope.launch {
                    delay(config.ttl)
                    logger.info("Resume TTL ({}) expired for {}", config.ttl, resumeId)
                    evict(resumeId)
                }
            entry.keepaliveJob =
                scope.launch {
                    while (isActive) {
                        delay(config.keepaliveInterval)
                        val alive =
                            try {
                                entry.live.ping()
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                logger.warn("Keepalive ping failed for parked {}: {}", resumeId, failure.message)
                                false
                            }
                        if (!alive) {
                            logger.info("Parked upstream {} is no longer alive; evicting", resumeId)
                            evict(resumeId)
                            break
                        }
                    }
                }
        }
        logger.info("Parked session {} for up to {}", resumeId, config.ttl)
    }

    /**
     * Transitions the entry for [resumeId] from parked back to **bound**: cancels its TTL/keepalive
     * timers and returns the still-live session for the coordinator to re-bind. Returns `null` for an
     * unknown or already-evicted (expired) id.
     */
    public suspend fun resume(resumeId: String): LiveSession? =
        mutex.withLock {
            val entry = entries[resumeId] ?: return@withLock null
            // One-consumer invariant (story 0026 F4): only a PARKED entry may be resumed. A Resume
            // arriving while the entry is still bound (fast reconnect before the old socket parked, or a
            // duplicate client) must be rejected — handing the same non-broadcast outbound channel to two
            // forwarders would split the stream and corrupt both.
            if (!entry.parked) return@withLock null
            entry.ttlJob?.cancel()
            entry.ttlJob = null
            entry.keepaliveJob?.cancel()
            entry.keepaliveJob = null
            entry.parked = false
            entry.lastSeen = System.currentTimeMillis()
            logger.info("Resumed session {}", resumeId)
            entry.live
        }

    /**
     * Removes the entry for [resumeId], cancels its timers, and cleanly `disconnect()`s the upstream.
     * No-op if already gone. Runs the disconnect under `NonCancellable`.
     */
    public suspend fun evict(resumeId: String) {
        val entry = mutex.withLock { entries.remove(resumeId) } ?: return
        entry.ttlJob?.cancel()
        entry.keepaliveJob?.cancel()
        withContext(NonCancellable) { entry.live.close() }
        logger.info("Evicted session {}", resumeId)
    }

    /** True while [resumeId] is registered (bound or parked). For assertions/diagnostics. */
    public suspend fun isRegistered(resumeId: String): Boolean = mutex.withLock { entries.containsKey(resumeId) }

    private suspend fun evictByLive(live: LiveSession) {
        val id = mutex.withLock { entries.values.firstOrNull { it.live === live }?.resumeId } ?: return
        evict(id)
    }

    /** Cancels all timers/pumps. Called on application shutdown (or explicitly by unit tests). */
    public fun shutdown() {
        scope.coroutineContext.job.cancel()
    }
}
