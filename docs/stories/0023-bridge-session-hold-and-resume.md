# 0023 — Bridge session hold & resume

- **Epic:** EPIC-05 — Session Resilience & Notifications
- **Depends on:** 0005 (session bridge), 0004 (`:protocol`)
- **Status:** ready

## 1. Objective

Keep a player's upstream XMage session alive when the **app's** WebSocket to the bridge drops
(network blip, backgrounding, rotation), so a reconnecting app can **resume the same session**
instead of a fresh login. The bridge parks the per-client session for a grace window, hands the app
a **resume handle**, and re-attaches the socket to the still-live session on `Resume`. This is the
bridge-side half of resilience; the app-side reconnect logic is 0024 and the UX is 0025.

## 2. Context & background

- Today (story 0005) `SessionCoordinator.run(ws)` ties the `XMageSession` (`SessionImpl`) lifecycle
  **directly to the WebSocket**: on socket close/cancel its `finally` calls
  `upstream.disconnect()` under `NonCancellable`, so the upstream session dies the instant the app
  socket drops. That is correct for `Logout`, but it means a transient app-network drop *loses the
  game*. This story decouples the two: an unexpected socket close **parks** the session rather than
  tearing it down.
- Grounding in the baked `mage-common:1.4.60` (verified via `javap`):
  - `SessionImpl.getSessionId(): String` — the server-side session id (a natural resume anchor).
  - `connectStop(boolean askForReconnect, boolean keepMySessionActive)` — the second flag keeps the
    server-side session alive for reconnection.
  - `connectReconnect(Throwable)` — `SessionImpl`'s own reconnect path; `isConnected()` and `ping()`
    for liveness.
  - `Connection.setUserIdStr(...)` — the stable per-connection identity a reconnect reuses.
- **Resumption is a bridge-level concern.** The bridge keeps its `XMageSession` object alive and
  connected to XMage (with `ping` keepalive) across app-socket drops; it issues its **own** resume
  id and re-binds a reconnecting app socket to that live session. XMage's own connect/keepalive keeps
  the bridge↔server link healthy underneath — the app never re-authenticates on a resume.
- Version-mismatch stays a hard cutover ([`../architecture.md`](../architecture.md)): resume never
  papers over a `VERSION_UNSUPPORTED` — a parked session is only resumable while it is healthy.

## 3. Scope

**In scope**
- A **`SessionRegistry`** (bridge-side, one entry per parked session) keyed by a bridge-issued
  **resume id**, holding the live `XMageSession`, the last-bound outbound sink, a `lastSeen`
  timestamp, and a TTL eviction timer.
- **Park on unexpected socket close** instead of disconnecting: `SessionCoordinator` registers the
  session on `Login` success and, when the socket closes *without* a `Logout`, parks it for a
  configurable grace window (default e.g. `RESUME_TTL=60s`, env-overridable) rather than calling
  `upstream.disconnect()`.
- **`:protocol` additions (additive, extend the 0004 sealed types):** a resume handle delivered to
  the app (e.g. `resumeId` on the `Connected`/`SessionStatus` or a dedicated `SessionResumable`
  server message), a `Resume(resumeId, protocol handshake)` **client** message, and a
  `ResumeRejected(reason)` server message for an unknown/expired/inconsistent handle.
- **Resume flow:** a fresh socket completes the 0004 handshake, then sends `Resume(resumeId)`; the
  bridge looks it up, re-binds the outbound stream to the new socket, and continues streaming the
  live session's status/callbacks — **no re-`Login`**. A miss → `ResumeRejected`, and the app is
  expected to fall back to `Login` (0024).
- **Keepalive + eviction:** parked sessions are pinged to stay alive; evicted (and cleanly
  `disconnect()`ed) on TTL expiry, on explicit `Logout`, or if the upstream link dies while parked.

**Out of scope**
- App-side automatic reconnection, back-off, and lifecycle/network awareness (**0024**).
- Reconnecting/restoring **UX** (**0025**).
- Push notifications (later EPIC-05 slice).
- Multiple concurrent sessions per app identity, and cross-process/persistent (disk) session
  survival — parking is in-memory and process-local for now.

## 4. Design & approach

```
bridge/src/main/kotlin/magefree/bridge/session/
├── SessionRegistry.kt        # resumeId -> ParkedSession(session, lastSink, ttl job); park/resume/evict
├── SessionCoordinator.kt     # (extended) register on Login; park (not disconnect) on unexpected close; handle Resume
protocol/src/main/kotlin/magefree/protocol/
└── SessionMessages.kt        # (extended) Resume (client); resumeId + ResumeRejected (server)
```

- **`SessionRegistry`** (a `@Singleton`-style bridge component): `park(session, sink): resumeId`
  starts a TTL timer + keepalive; `resume(resumeId): ParkedSession?` cancels the timer and returns
  the live session for re-binding; `evict(resumeId)` cancels and `disconnect()`s. Thread-safe
  (concurrent app sockets); the TTL and keepalive run on the bridge's scope, not a socket coroutine.
- **`SessionCoordinator`** changes:
  - On `Login` → `CONNECTED`, register the session and emit the `resumeId` to the app (on/with the
    first `Connected`).
  - The post-handshake loop additionally accepts `Resume(resumeId)`: validate, re-bind the outbound
    flow to this socket, resume forwarding; on miss send `ResumeRejected`.
  - Replace the unconditional teardown: on socket close, **if** a `Logout` was seen or the session
    is unrecoverable → `disconnect()`; **otherwise** → `registry.park(...)` and return (the session
    lives on). `NonCancellable` still guards the chosen path.
- **Config:** `RESUME_TTL` (grace window) and keepalive interval from env, with sensible defaults.
- **Consistency with 0004/0005:** all new frames extend the sealed `ClientMessage`/`ServerMessage`
  with unique `@SerialName`s; `ignoreUnknownKeys` keeps older peers tolerant.

## 5. Implementation steps

1. Add `Resume`/`ResumeRejected` + the `resumeId` handle to `:protocol`; extend the round-trip tests.
2. Implement `SessionRegistry` (park/resume/evict + TTL + keepalive), thread-safe.
3. Extend `SessionCoordinator`: register + emit `resumeId` on connect; park on unexpected close;
   handle `Resume` (re-bind) and `ResumeRejected`; keep `Logout` → immediate `disconnect()`.
4. Hermetic tests (fake upstream): login → capture `resumeId` → drop socket (no logout) → new
   socket + handshake + `Resume` → same session continues, **no second `connect`**; expired/unknown
   `resumeId` → `ResumeRejected`; `Logout` → evicted, later `Resume` rejected.
5. `./scripts/dev gradle :bridge:check :protocol:check` green.
6. **Live:** against the reference server, confirm a parked session survives an app-socket drop and a
   `Resume` re-attaches without re-authenticating.

## 6. Testing & verification

- **Hermetic (default gate):** `SessionRegistry` + coordinator paths with a `FakeUpstreamSession` —
  every branch (park→resume, TTL eviction→reject, logout→evict) through the real WebSocket plumbing.
  `./scripts/dev gradle check` stays hermetic.
- **Live (opt-in, `XMAGE_SERVER`):** an IT that logs in, drops the client socket, reconnects, and
  `Resume`s the **same** upstream session (asserting the upstream `SessionImpl` was never
  reconnected/re-authed and `getSessionId()` is unchanged), then a TTL-expiry case that rejects.

## 7. Acceptance criteria

- [ ] An unexpected app-socket close **parks** the upstream session (kept alive) instead of
      disconnecting it; an explicit `Logout` still disconnects immediately.
- [ ] The app receives a `resumeId` on connect; a `Resume(resumeId)` on a fresh, handshaken socket
      re-attaches to the **same** live session with **no** re-`Login`/re-auth.
- [ ] An unknown/expired/inconsistent `resumeId` yields `ResumeRejected` (no crash); TTL expiry and
      `Logout` evict and cleanly `disconnect()` the parked session.
- [ ] `:protocol` gains `Resume`/`ResumeRejected` + the handle by **extending** the sealed types
      (round-trips pass); `ignoreUnknownKeys` preserves compatibility.
- [ ] `./scripts/dev gradle :bridge:check :protocol:check` green and hermetic; the live IT is
      env-gated and skipped by default.
- [ ] No app-side reconnect logic, UX, or notifications introduced here.

## 8. References

- [`0005-session-bridge-connect-login-reconnect.md`](0005-session-bridge-connect-login-reconnect.md) — the `SessionCoordinator`/`XMageUpstreamSession` this extends.
- [`0004-protocol-contract-v1-and-schema-versioning.md`](0004-protocol-contract-v1-and-schema-versioning.md) — the envelope + additive-extension rule.
- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — `getSessionId`, `connectStop(_, keepMySessionActive)`, `connectReconnect`, `ping`, `isConnected`.
- [`../architecture.md`](../architecture.md) — "the connection is the product"; version mismatch is a hard cutover, not smoothed by reconnect.
