# 0047 — Mount the connect flow

- **Epic:** EPIC-04 — Server Connection & Sign-In (defect fix)
- **Depends on:** 0016–0019 (`:feature:connect`), 0008–0011 (app shell & navigation)
- **Status:** ready

## 1. Objective

Make the app able to **connect to a server at all**. `:feature:connect` — the entire connect/sign-in
experience built by Epic 4 — is **not a dependency of `:app`** and is never mounted in the navigation
graph. Nothing in `:app` calls `ConnectionRepository.connect(...)`. So a running APK cannot establish
a session, and every downstream feature that *is* mounted (lobby, cards, decks, tables) can never
receive data.

## 2. Context & background

- **Measured, 2026-08-10** (found by story 0046's reachability check, then confirmed directly):
  - `app/build.gradle.kts` declares `:feature:lobby`, `:feature:cards`, `:feature:decks`,
    `:feature:tables` — and **not** `:feature:connect`.
  - `ConnectFlow` has **zero** references in `app/src/main`; there is no sign-in destination in the
    navigation graph.
  - `grep` for a session-establishing call in `app/src/main` returns **nothing**. The only API that
    starts a session is `ConnectionRepository.connect(...)`, called solely from `:feature:connect`.
  - What `:app` *does* have is `ConnectionStatusBar`/`ConnectionStatusViewModel`, which observe state
    and can `retry()` — but `retry()` re-establishes an *existing* session; it cannot create the first
    one, because it has no credentials.
- **Why it went unnoticed.** Every layer is correct and tested in isolation: `:feature:connect` has its
  own androidTests, `:core:network` is proven against a real bridge (0045), and the shell's navigation
  tests pass. The gap is the *wiring between* them — the same defect class as the rest of the 2026-08
  hardening pass, at the largest scale yet: an entire epic's UI, built and merged, never mounted.
- Story 0018 recorded mounting as out of scope at the time. That deferral was never closed, and
  nothing since has failed because of it — the app's own tests use stub/preview sources, and the live
  verification drives `:core:network` directly, bypassing the UI entirely.

## 3. Scope

**In scope**
- Add `:feature:connect` to `:app` and mount its flow (server pick → sign-in) in the navigation graph,
  following the pattern the other features already use (`MageNavHost`/`AppShell`, the nested-destination
  approach used for lobby/tables).
- **Decide and document the entry policy**: what a launch with no session does — e.g. start on the
  connect flow, or surface an explicit "Connect" action from Home and from the status bar's
  disconnected state. Whichever is chosen, a user must be able to reach sign-in **without prior state**,
  and after signing out (0046's `signOut()` now genuinely ends the session).
- Wire sign-in **success** into the shell so the app lands somewhere useful (Home/Play), and the
  status bar reflects the live session rather than a stub.
- Keep the existing shell/androidTest behaviour green: those tests use a Hilt-free stateless overload,
  so the mount must not force a Hilt dependency into them (the pattern 0035/0038 already established).

**Out of scope**
- Any change to `:feature:connect`'s screens or `:core:network`'s client (both work; 0045 proved the
  client live).
- Registration (**permanently deferred**).
- Auto-reconnect policy changes (0024) beyond making the first connect reachable.
- Gameplay (EPIC-11).

## 4. Design & approach

- **Reachability is the whole story** (verification standard 2): the acceptance test is that a user
  starting from a cold launch can reach sign-in and establish a session. State plainly, in the
  implementation, what produces the first `Connected` state in a running app.
- Mount as a nested destination consistent with the existing graph rather than restructuring the shell;
  the shell's contract (tabs, connection strip) should be unchanged.
- The status bar's disconnected/`retry()` affordance and the connect flow must not fight each other —
  decide which owns "get me connected" and document it.

## 5. Implementation steps

1. Add the `:app` → `:feature:connect` dependency; mount the flow in the navigation graph.
2. Implement the entry policy (cold launch with no session, and post-sign-out).
3. Route sign-in success into the shell; ensure the status bar observes the real session.
4. Tests: a navigation test proving sign-in is **reachable from a cold start**, and that sign-out
   returns to it. Keep existing shell/androidTests green.
5. Gates: `:app:testDebugUnitTest` + `:app:assembleDebug` + `:feature:connect:check` (host).

## 6. Testing & verification

- **Hermetic:** navigation tests proving the connect destination is reachable from a cold start and
  after sign-out; existing shell tests unchanged.
- **Independent verification (standard 3):** the check that matters is **on-device/emulator** — install
  the APK, reach sign-in, connect to the reference bridge, and confirm the lobby populates. Every
  layer below this is already proven live by 0045; this story is precisely about the layer that is
  not, so a purely hermetic pass would repeat the mistake that hid it.

## 7. Acceptance criteria

- [ ] `:app` depends on `:feature:connect` and mounts its flow; `ConnectFlow` is reachable from a cold
      launch with no prior session.
- [ ] A user can sign in from the running app and land in the shell with a live session; the status bar
      reflects it.
- [ ] Signing out (0046) returns the user to a state from which they can sign in again.
- [ ] The entry policy is documented, and the status bar's `retry()` and the connect flow have a clear
      division of responsibility.
- [ ] Existing shell/androidTests stay green; no Hilt requirement forced into the stateless overload.
- [ ] Verified **on device/emulator** against the reference bridge, not only hermetically.

## 8. References

- [`0018-connect-and-sign-in-ui.md`](0018-connect-and-sign-in-ui.md) — where mounting was deferred.
- [`0045-app-to-bridge-live-integration.md`](0045-app-to-bridge-live-integration.md) — proves everything below the UI works live.
- [`0046-sign-out-sends-logout.md`](0046-sign-out-sends-logout.md) — the reachability check that surfaced this.
- `docs/stories/README.md` § Verification standards — standard 2 (reachability) is the reason this was found.
