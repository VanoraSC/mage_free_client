# 0046 — Sign-out sends Logout

- **Epic:** EPIC-04 / EPIC-05 (defect fix)
- **Depends on:** 0023 (bridge session hold/resume), 0024 (app reconnect/lifecycle), 0045 (the live run that found it)
- **Status:** ready

## 1. Objective

Make a **deliberate sign-out** tear the upstream session down immediately, instead of leaving it parked
for the resume TTL. The wire message exists, the bridge implements and tests it — **the app never sends
it**, so the bridge cannot tell an intentional sign-out from a dropped socket.

## 2. Context & background

- **Found by story 0045's live app↔bridge run (2026-08-09), then confirmed directly.** No fake could
  have caught it: both sides are individually correct and individually tested. The defect lives in the
  gap between them — a capability the bridge offers that the client never invokes.
- **The contract exists on both sides:**
  - `:protocol` defines `Logout` — "App→bridge: tear down the active upstream session."
  - `bridge/.../session/SessionCoordinator.kt` handles it, and its KDoc states the distinction
    explicitly: on socket close **without** a `Logout` a live registered session is **parked** (story
    0023's resume window, so a dropped connection doesn't lose the game); a `Logout` **disconnects
    immediately**.
- **The app never sends it.** `grep -rn Logout --include=*.kt core feature app` returns **zero** hits.
  `KtorBridgeClient.disconnect()` sets `disconnectRequested`, fails pending requests, closes the socket,
  and publishes `Disconnected` — nothing more.
- **Observed live** (bridge container logs, immediately after each intentional `disconnect()`):
  ```
  SessionRegistry - Parked session 695f95fa-… for up to 1m
  …
  SessionRegistry - Resume TTL (1m) expired for d80b7a26-…
  BridgeMageClient - XMage session disconnected (askToReconnect=false, keepMySessionActive=false)
  SessionRegistry - Evicted session d80b7a26-…
  ```
- **Why it matters.** After signing out, the upstream XMage session — and the **username** holding it —
  stays alive for the full resume TTL (~60 s). **Unverified corollary worth checking during this story:**
  XMage normally refuses a second login for a name already in use, so a sign-out followed by a sign-in
  inside that window may surface to the user as an auth failure. 0045's tests never hit it because they
  mint a unique username per run.

## 3. Scope

**In scope**
- Send `Logout` on a **deliberate** sign-out, and only then. The distinction must be preserved exactly:
  - **intentional sign-out** (the user leaves / the app tears the session down on purpose) → send
    `Logout`, then close;
  - **dropped socket / backgrounding / lifecycle pause** → close **without** `Logout`, so 0023's park +
    0024's resume keep working. Nothing about the reconnect path may regress.
- Give `BridgeClient` a way to express that intent. `disconnect()` today is one method serving both
  meanings; either add an explicit sign-out entry point or a parameter — decide and document, keeping
  `:protocol` **off** the public ABI (the established 0028 discipline: `:protocol`-typed impls stay
  `internal`).
- Wire it to wherever the app actually initiates sign-out (`ConnectionRepository` and its callers —
  find the real entry point rather than assuming).
- **Best-effort semantics:** a `Logout` that cannot be sent (socket already dead) must not throw, hang,
  or block teardown. Sign-out must always complete promptly.

**Out of scope**
- Any change to the resume/park behaviour itself (0023/0024) — this only stops mislabelling an
  intentional exit as a drop.
- Registration/account flows (registration is permanently deferred).
- The `:bridge` side, which already implements and tests `Logout` correctly.

## 4. Design & approach

- **Intent belongs to the caller.** The bridge cannot infer it from a socket close, which is exactly why
  the message exists. Model it explicitly at the client seam rather than guessing from lifecycle state.
- **Preserve the park path.** The valuable behaviour — a dropped connection parking so a reconnect
  resumes the game — must be untouched. The regression risk here is sending `Logout` on a *lifecycle*
  disconnect and destroying resume; tests must pin both directions.

## 5. Implementation steps

1. Add the explicit sign-out capability to `BridgeClient`/`KtorBridgeClient` (`Logout` sent, then close).
2. Route the app's real sign-out entry point through it; leave lifecycle/drop paths unchanged.
3. Tests (fakes): a sign-out **sends** `Logout` before closing; a dropped/lifecycle disconnect **does
   not**; a failed send still completes teardown promptly.
4. Extend 0045's live suite: after a sign-out, the bridge tears down immediately rather than parking —
   and, if the corollary holds, the **same username can sign straight back in** (today's proof that the
   defect is real and is fixed).
5. Gates: `:core:network:check` + `:app:assembleDebug` (host); `:protocol:check`/`:bridge:check`
   (in-container) if anything there is touched; the live suite with `BRIDGE_URL` set.

## 6. Testing & verification

- **Hermetic:** fake-based tests pinning **both** directions (sign-out sends `Logout`; drop/lifecycle
  does not) — the second is the regression guard for 0023/0024.
- **Live (opt-in):** via 0045's harness — sign out, then assert the bridge did **not** park the session
  (bridge-side effect, or an immediate re-login with the *same* username succeeding). This is the
  assertion that would have failed before the fix.

## 7. Acceptance criteria

- [ ] A deliberate sign-out sends `Logout`, so the upstream session is torn down immediately rather
      than parked for the resume TTL.
- [ ] A dropped socket / lifecycle pause still closes **without** `Logout`, and 0023/0024's park+resume
      behaviour is unchanged — pinned by a test.
- [ ] A `Logout` that cannot be sent never throws, hangs, or blocks teardown.
- [ ] No `:protocol` type on the public `BridgeClient` ABI.
- [ ] Live verification through 0045's harness shows the session is not parked after sign-out; prior
      suites (incl. 0045's) stay green.

## 8. References

- [`0045-app-to-bridge-live-integration.md`](0045-app-to-bridge-live-integration.md) — the live run that exposed this.
- [`0023-bridge-session-hold-and-resume.md`](0023-bridge-session-hold-and-resume.md) / [`0024-app-reconnect-and-lifecycle-session.md`](0024-app-reconnect-and-lifecycle-session.md) — the park/resume behaviour that must **not** regress.
- `bridge/src/main/kotlin/magefree/bridge/session/SessionCoordinator.kt` — the bridge side, already correct.
