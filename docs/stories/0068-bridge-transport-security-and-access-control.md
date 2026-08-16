# 0068 — Bridge transport security and access control

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** 0001 (bridge scaffold), 0005 (session bridge connect/login)
- **Status:** planned — **parked, a future increment, not blocking current work.** Correctly deferred
  for a bridge reachable only on the developer's own trusted LAN (the setup
  `docs/verification-test-plan.md` walks through); becomes real the moment the bridge is reachable by
  anyone else. Fully scoped now so it doesn't need re-deriving when it's picked up.

## 1. Objective

The bridge has no transport security and no access control of its own, confirmed by reading the code,
not assumed: it speaks plain `ws://` unconditionally with no way to speak `wss://`, binds to all
network interfaces as a hardcoded literal, and accepts a session from anyone who can reach the port —
there is no bridge-level credential separate from whatever XMage username/password gets relayed to the
pinned upstream server. Story 0047's debug-only `usesCleartextTraffic` flag (which lets a real device
reach a dev bridge over plain `ws://`) is the **correct** scoping for that situation, not a defect —
this story is about what changes once the bridge is reachable by anyone other than the person running
it on their own network.

## 2. Context & background — traced from the actual code, not guessed

- **No TLS connector exists.** `bridge/src/main/kotlin/magefree/bridge/Application.kt`:
  `embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }` — a plain HTTP/WS Netty engine,
  no `sslConnector`, no certificate configuration anywhere in the module. There is currently no way to
  make the bridge speak `wss://` at all, embedded or otherwise.
- **The bind address is a hardcoded literal**, already flagged in `docs/review-follow-ups.md`
  ("Bridge binds to a hardcoded `0.0.0.0`," surfaced retroactively from story 0001): "correct/convenient
  for a local dev scaffold... revisit before the bridge carries real traffic — make the bind address
  configurable and make network exposure a deliberate decision." That note named this exact story's
  territory but was never given a story number until now.
- **No bridge-level authentication.** The WebSocket session route
  (`bridge/src/main/kotlin/magefree/bridge/ws/`) has no auth-related code at all — confirmed by grep.
  The only credential in the whole system is the XMage username/password the app sends through to the
  pinned upstream server (`docs/architecture.md`'s "pinned-server posture," decision #6: the app never
  names an XMage host — the bridge always knows its one upstream). Anyone who can reach the bridge's
  port can open a session and attempt to authenticate against that upstream through it; the bridge
  itself never asks "should I even talk to you."
- **On the app side, cleartext is already correctly scoped.** `app/src/debug/AndroidManifest.xml` sets
  `usesCleartextTraffic="true"` **only** in the debug variant, with its own comment explaining exactly
  why (a device otherwise refuses the connection outright) and explicitly noting release keeps the
  platform default (`wss` required). That decision is correct as-is and this story does not revisit
  it — it's the **bridge's** side of the gap, not the app's.

## 3. Scope

**In scope**
- **Configurable bind address.** Replace the hardcoded `"0.0.0.0"` literal with a config-driven value
  (env var, mirroring how `BRIDGE_PORT` already works), defaulting to whatever's appropriate for the
  container setup (likely still `0.0.0.0` inside a container, since the container's own network
  namespace is already the isolation boundary) — the point is making exposure a **deliberate,
  documented choice** per deployment, not the literal value changing by default.
- **A decision on how TLS termination happens**, then implementing it. Two real options, not yet
  chosen — this story should record which and why, not just build one blindly:
  1. **Embedded TLS in Ktor** (a `sslConnector` with a keystore) — no extra moving part, but bakes
     certificate management into the JVM process and its config/secrets.
  2. **TLS termination at a reverse proxy** (e.g. added to the existing `docker/docker-compose.yml`
     setup) in front of a bridge that stays plain HTTP behind it — a more conventional shape for a
     containerized service, keeps certs out of the bridge process, but adds a component to the compose
     stack and the local-dev story.
- **Bridge-level access control**, independent of the per-user XMage credential relay — some proof that
  the caller is allowed to use this bridge at all before a session is even attempted upstream. Needs
  its own design pass (a shared token? per-installation credentials? something else?) rather than
  picking a shape here without checking it against how the app's connect flow (0016–0019) and the
  server-list model (`ServerTarget`) would actually carry it.
- Updating `docker/README.md`/`docs/build-environment.md`'s dev-setup instructions if the chosen shape
  changes anything about local `./scripts/dev up bridge` usage.

**Out of scope**
- Anything about the **app's** cleartext handling — `usesCleartextTraffic` (story 0047) is already
  correctly scoped to debug-only and is not revisited here.
- XMage-upstream credential handling itself (the username/password relayed through to `SessionImpl`) —
  unaffected; this is about who may use the bridge, not how the bridge talks to XMage.
- Rate limiting, DoS protection, or anything beyond "does this caller get to open a session at all" —
  a further hardening pass once basic access control exists, not this story.

## 4. Design & approach

- **This gates on a real decision, not a default.** Both the TLS-termination shape and the
  access-control shape are genuine architectural choices with real tradeoffs (§3) — resolve them
  explicitly (with Pete) before implementing, the same discipline this project has applied to every
  other cross-cutting decision (auto-pass's mechanism-vs-presentation split, the sideboard protocol
  fix, etc.).
- **Don't let this block local development.** Whatever ships must keep the existing
  `./scripts/dev up bridge` / debug-cleartext-over-LAN workflow (`docs/verification-test-plan.md`)
  working for local testing — this is about making non-local exposure a deliberate, secured choice, not
  about making local dev harder.
- **Pair with the bind-address fix.** `review-follow-ups.md`'s original note already grouped bind
  address + TLS + auth together as one "before real traffic" concern; keep them as one story rather
  than splitting, since a configurable-but-still-open bind address without TLS/auth wouldn't actually
  close the gap.

## 5. Verification

- **Hermetic:** a test confirming the bind address is read from config, not hardcoded; tests for
  whatever access-control mechanism is chosen (a caller without valid credentials is rejected before
  any upstream `SessionImpl` call is attempted).
- **Live:** confirm a client without valid access-control credentials cannot open a session; confirm
  TLS actually negotiates (a plain `ws://` connection is refused or upgraded, per whichever shape was
  chosen) against a real bridge instance.
- **Standard 5:** if TLS termination moves to a reverse proxy, confirm the bridge itself genuinely
  never receives unencrypted traffic from outside that boundary — don't assume the proxy is correctly
  in the path without checking.

## 6. Acceptance criteria

- [ ] The bridge's bind address is configurable, not a hardcoded literal, with the choice documented as
      deliberate for each deployment shape (local dev vs. anything else).
- [ ] A TLS-termination approach is decided (embedded vs. reverse proxy), recorded with its reasoning,
      and implemented — a client can reach the bridge over `wss://` in whatever environment this targets.
- [ ] Some form of bridge-level access control exists, independent of the XMage credential relay — an
      unauthorized caller cannot open a session.
- [ ] Local development (`./scripts/dev up bridge`, a debug build over LAN per
      `docs/verification-test-plan.md`) still works unchanged.
- [ ] `docs/review-follow-ups.md`'s "Bridge binds to a hardcoded `0.0.0.0`" entry is marked resolved,
      pointing at this story.

## 7. References

- `docs/review-follow-ups.md` — "Bridge binds to a hardcoded `0.0.0.0`," the original note this story
  formalizes.
- `bridge/src/main/kotlin/magefree/bridge/Application.kt` — the hardcoded bind address and the absent
  TLS connector.
- `bridge/src/main/kotlin/magefree/bridge/ws/` — confirmed absent of any auth-related code.
- `app/src/debug/AndroidManifest.xml` — the app-side cleartext decision this story does not revisit,
  and why it's correctly scoped as-is.
- `docs/architecture.md` — decision #6, the pinned-server posture this story's access-control layer
  sits alongside, not inside.
- `docs/verification-test-plan.md` — the local-dev/LAN workflow this story must not break.
