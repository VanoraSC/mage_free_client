# 0026 — Resilience & protocol hardening

- **Epic:** Cross-cutting hardening (post-Epic-5 audit follow-up)
- **Depends on:** 0023, 0024, 0025 (resilience), 0006 (callback relay), 0017 (server persistence)
- **Status:** ready

## 1. Objective

Close six concrete defects an independent fresh-context audit found across the resilience/protocol
stack. None is critical, but two silently undermine the "connection is the product" resilience the
design is built around, and one is an **inaccurate documentation claim** (which the project's
accuracy rule forbids). Each fix is small and must land with a test that would have caught it.

## 2. The findings & required fixes

### F1 — Protocol: honour the advertised minor-version tolerance of unknown message *types* (`:protocol`, `:bridge`, `:core:network`)
- **Problem:** `ProtocolVersion`'s KDoc promises "both sides… tolerate an unknown message `type`
  gracefully… minor differences are compatible in both directions", but `ProtocolJson` sets only
  `ignoreUnknownKeys = true` — which covers unknown *fields*, **not** an unknown sealed-subtype
  discriminator. Decoding an unknown `ServerMessage`/`ClientMessage` `type` throws
  `SerializationException`. The bridge survives via its `receiveDeserialized` try/catch, but the app
  (`KtorBridgeClient.receiveMessage` / `SessionRelay`) turns the throw into an `Error`→`RETRY`, i.e. a
  **reconnect loop** on any additive new server message — the opposite of "log and ignore".
- **Fix (make the code true to the doc — the additive-message design is intentional):** register a
  `polymorphicDefaultDeserializer` for **both** `ClientMessage` and `ServerMessage` in `ProtocolJson`
  that decodes an unknown `type` to a shared, additive sentinel (e.g. `UnknownClientMessage`/
  `UnknownServerMessage`, or one `UnknownMessage` per hierarchy). Both consumers **ignore** the
  sentinel: the bridge `SessionCoordinator` logs + drops it (no `ProtocolError`, no close); the app
  (`SessionRelay`/`SessionMapper`) maps it to `null` (log + ignore, session unaffected). Keep it
  deserialize-only (never emitted). Add round-trip/decoding tests for an unknown-`type` frame on both
  `ClientMessage` and `ServerMessage` (the existing `SerializationTest` only covers unknown *fields*).

### F2 — Reconnect back-off never resets after a successful reconnect (`:core:network`)
- **Problem:** `ReconnectingSession.events()` initialises `attempt = 0` once and only ever
  increments it; a successful `runOnce` (reached `Connected`) does not reset it. Since `runOnce`
  blocks for the whole life of a connection, every independent drop→recover cycle bumps the counter
  permanently, so the back-off climbs to `maxDelayMillis` (30s) and a later brief drop waits the full
  cap before retrying.
- **Fix:** reset the attempt counter to 0 whenever a session actually reached `Connected` (i.e. after
  a `runOnce` that produced a live session / on the `RETRY` following a previously-successful
  connection). Add a `ReconnectingSessionTest` case: two drop→recover cycles → the second recovery's
  back-off starts from the initial delay, not a grown one.

### F3 — Global status-bar "Retry" is a live no-op (`:app`)
- **Problem:** `ConnectionStatusViewModel.onRetry()` is an empty `TODO(EPIC-04)`, yet the
  `ConnectionStatusBar` renders a clickable Retry whenever `showRetry` is true (Disconnected,
  AuthFailed). A user taps a dead control on the always-visible surface.
- **Fix:** make Retry real. Preferred: add a `retry()` to the `ConnectionStatusSource` seam,
  implemented by `ConnectionStatusSourceImpl` delegating to `ConnectionRepository.retry()` (the stub
  source no-ops), and call it from `onRetry()`. (Acceptable fallback if wiring is disproportionate:
  hide the button — `showRetry = false` — so no dead control ships.) Do not change the
  `ConnectionStatusBar` layout beyond what the choice requires; keep 0010's test green.

### F4 — `SessionRegistry.resume()` doesn't enforce its documented one-consumer invariant (`:bridge`)
- **Problem:** `resume(resumeId)` returns `entry.live` without checking `entry.parked`. The
  class/`LiveSession` docs claim "exactly one socket forwarder consumes it at a time (enforced by the
  registry's state transitions)", but a `Resume` arriving on a second socket while the first is still
  **bound** (a fast reconnect before the old socket parks, or a duplicate client) hands the same
  `LiveSession.outbound` `Channel` to two forwarders — a non-broadcast `Channel` splits messages
  between them, corrupting both streams.
- **Fix:** in `resume()`, reject a non-parked entry (`if (entry.parked != true) return@withLock null`
  → `ResumeRejected`). Add a `SessionResumeTest` case: `resume` on a bound-but-not-parked id returns
  null (rejected).

### F5 — Callback mapping can throw on a *known* method with a malformed payload (`:bridge`)
- **Problem:** `CallbackMapper.map` does `callback.decompressData()` then `callback.data as
  ChatMessage`. For a **known** method (`CHATMESSAGE`) whose payload fails to decompress or isn't a
  `ChatMessage` (upstream shape drift), this throws; neither `CallbackRelay` nor the collector guards
  it, so the `channelFlow` producer fails → the `LiveSession` pump completes exceptionally → the
  session is evicted — contradicting the "a new upstream push cannot crash the session" comment.
- **Fix:** guard the decompress/cast so a malformed **known**-method payload is logged and dropped
  (mapped to `null`), never thrown — matching the unknown-method behaviour. Correct the KDoc if
  needed. Add a mapper test: a `CHATMESSAGE` whose data is not a `ChatMessage` → `null` (no throw).

### F6 — `ServerRepository` DataStore reads are unguarded (`:core:network`)
- **Problem:** `servers = dataStore.data.map { it.decodeServers() }` catches neither the `IOException`
  `dataStore.data` can emit on a read failure nor the `SerializationException` `decodeServers()` can
  throw on corrupt/stale JSON — so bad prefs throw into every collector (the server-list screen)
  instead of degrading gracefully.
- **Fix:** apply the canonical DataStore pattern — `catch` `IOException` on `dataStore.data` (emit
  `emptyPreferences()`), and make `decodeServers()` tolerate a corrupt/unparseable value by
  returning an empty list (log once). Add a test: a corrupt stored value → `servers` emits `[]`
  (no throw).

## 3. Scope

**In scope:** exactly the six fixes above + their tests. **Out of scope:** any feature work, the
deferred notifications track, refactors beyond what each fix needs, and re-reporting the existing
`docs/review-follow-ups.md` items.

## 4. Toolchain & verification

This story spans **both** build surfaces:
- **Container** (`:protocol`, `:bridge` — F1 protocol side, F4, F5): `./scripts/dev gradle
  :bridge:check :protocol:check` (Docker/WSL). Live ITs remain optional/hermetic here — these fixes
  are unit-testable.
- **Host** (`:core:network`, `:app` — F1 app side, F2, F3, F6): `:core:network:check`,
  `:app:testDebugUnitTest`, `:app:assembleDebug` (JDK 17 host; `local.properties` present).

Every fix ships with a test that fails before it and passes after. All prior suites must stay green.

## 5. Acceptance criteria

- [ ] **F1:** an unknown `ServerMessage`/`ClientMessage` `type` decodes to the sentinel (no throw);
      the bridge logs + drops it (no `ProtocolError`/close), the app logs + ignores it (no reconnect);
      tested on both hierarchies.
- [ ] **F2:** after two drop→recover cycles the back-off restarts from the initial delay; covered by a test.
- [ ] **F3:** the status-bar Retry either performs a real reconnect (via the seam → `ConnectionRepository.retry()`) or is not shown — no live no-op control; 0010's test stays green.
- [ ] **F4:** `resume()` rejects a non-parked id; covered by a test.
- [ ] **F5:** a malformed known-method callback payload is dropped (logged), not thrown; the session survives; covered by a mapper test.
- [ ] **F6:** corrupt/unreadable server prefs degrade `servers` to `[]` (no throw); covered by a test.
- [ ] `:bridge:check` + `:protocol:check` (container) and `:core:network:check` + `:app:testDebugUnitTest` + `:app:assembleDebug` (host) all green; all prior suites still pass.

## 6. References

- The audit that produced these findings (independent fresh-context review of stories 0001–0025).
- `docs/architecture.md` — "the connection is the product"; additive-message forward compatibility.
- Stories 0023–0025 (resilience), 0006 (callback relay), 0017 (server persistence).
