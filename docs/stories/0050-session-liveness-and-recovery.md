# 0050 — Session liveness & recovery

- **Epic:** EPIC-05 — Session Resilience (defect fixes)
- **Depends on:** 0023/0024 (hold, resume, reconnect), 0046 (sign-out), 0048 (the smoke that found these)
- **Status:** ready

## 1. Objective

Make the app's idea of "connected" match reality. Story 0048's on-device smoke found four
session-lifecycle defects that share one cause: **app-side connection state is never reconciled with
server-side truth.** The app shows `Connected` while the server has already dropped the user, leaks a
session on every network excursion, and reports a generic failure instead of prompting re-auth.

## 2. Context & background

All four observed on-device (emulator + live bridge + live XMage), 2026-08-10.

- **A. An idle session dies server-side after ~3 minutes and nothing notices.** Bridge log:
  `17:42:28 smoke13414004 connected` → `17:45:32 … disconnected due connection problems`
  (XMage `UserManagerImpl.checkExpired`) **while the app still displayed `Connected`**. The next server
  action failed with the generic *"the server declined to create the table"* — no re-auth prompt, no
  indication the session was gone. The bridge itself only noticed at `17:59:49` (`XMage server error:
  Ping failed`). **A user who spends three minutes deck-building and then tries to host hits this.**
- **B. Every offline excursion leaks a bridge/XMage session.** When the radio dies no FIN reaches the
  bridge, so the pre-offline session is never `Parked` or `Evicted`; on return the app registers a
  *second* XMage login for the same username. Observed both runs (`ce6bdb28` never torn down while
  `9e0abf56` was created; likewise `9de64e4f` / `790de28f`).
- **C. `Connected` while the lobby says signed out** (intermittent). The strip read `Connected` on the
  same screen as the lobby's *"Connect to browse — Sign in to a server to see open tables"*. The bridge
  showed a **fresh** login had just registered, so a session existed but the lobby's room subscription
  was never re-established after the offline excursion. One run recovered; the other did not.
- **D. The bridge WebSocket closes ~33 s after connecting, every time** — `101 Switching Protocols … in
  33135ms / 33433ms / 33319ms / 33099ms / 33516ms` — then immediately `Resumed session`. Functionally
  survivable (resume works), but too regular to be coincidence.
- **Why the existing tests miss all of this:** 0023/0024's resilience tests drive *deliberate*
  transitions through fakes, and 0045's live tests run for seconds against a healthy server. Nothing
  exercises a session that the **server** kills while the app believes it is fine — the failure mode is
  time-based and one-sided.

## 3. Scope

**In scope**
- **D first — diagnose before changing anything.** Establish why the socket closes at ~33 s: an idle
  timeout (Ktor/OkHttp/proxy), a ping/pong failure, or churn. Whatever the cause, **record it**; if it
  is an intended timeout, say so in KDoc and move on. Fixing A–C on top of a misunderstood transport
  would be guesswork.
- **A — the app must learn its session is gone.** Reconcile app-side state with the server: react to
  the bridge's knowledge that the upstream is dead (XMage's disconnect / `Ping failed`) rather than
  waiting for the next user action to fail. If the bridge does not relay that today, this is where it
  is added (a `:protocol` event + bridge relay is acceptable). A dead session must surface as a
  **disconnected/re-authenticate** state, not as `Connected`.
- **A (second half) — failures must be honest.** A server action that fails *because the session is
  gone* must not report the generic "the server declined…". Distinguish "the server refused this
  action" from "you are no longer signed in", and offer the re-auth path.
- **B — a network excursion must not leak a session.** When connectivity drops, the app should mark
  itself disconnected and ensure the upstream session is reclaimed — either by tearing it down on
  return, or by resuming the existing one rather than opening a second login for the same username.
  0024's `ConnectivityObserver` already exists; use it.
- **C — a re-established session must restore its subscriptions.** After a reconnect/resume, the lobby
  (and any table room) must re-subscribe, so the UI cannot show `Connected` beside "sign in to browse".

**Out of scope**
- Gameplay (EPIC-11); tournaments (EPIC-08).
- Card search text entry (**0049**).
- Re-designing 0023's park/resume model — this makes the app *use* it correctly.
- Accessibility (deferred).

## 4. Design & approach

- **Reachability (standard 2):** for the connection indicator, answer in writing *what produces
  `Connected` in production, and what can invalidate it.* Today only app-side events can, which is the
  defect: the server's opinion never reaches the state.
- Prefer **reacting to a real signal** (a relayed upstream-dead event) over polling. If a keepalive is
  genuinely needed to detect death promptly, size it against XMage's ~3-minute expiry and say why.
- Keep 0023/0024's park/resume intact — a *transport* drop must still park and resume; only a genuinely
  dead upstream should force re-auth. Pin both directions with tests, as 0046 did.

## 5. Implementation steps

1. Diagnose D; record the finding (and fix only if it is not intended).
2. Relay upstream-dead to the app; reduce it into connection state.
3. Distinguish session-gone from action-refused in the failure surfaced to the user; offer re-auth.
4. Handle connectivity loss without leaking a session (resume, or tear down on return).
5. Re-subscribe the lobby/room after a re-established session.
6. Gates below; then re-run 0048's smoke and confirm the affected steps.

## 6. Testing & verification

- **Hermetic:** a server-killed-session test (fake bridge relays upstream-dead → state becomes
  disconnected/re-auth, **not** `Connected`); an action-fails-because-session-gone test asserting the
  honest message; a connectivity-drop test asserting no second login is opened; a re-subscribe test.
  **Standard 1:** each must be demonstrated failing against today's code.
- **Live (opt-in, via 0045's harness):** hold a session idle past XMage's expiry and assert the app
  reaches a disconnected/re-auth state rather than `Connected`. This is the assertion that fails today.
- **On-device (standard 3):** re-run `scripts/smoke-on-device.sh`; its defect-2 recovery step and the
  offline excursion should stop leaking sessions.

## 7. Acceptance criteria

- [ ] The ~33 s socket close is **explained** and recorded; fixed if unintended.
- [ ] When the server drops the session, the app stops showing `Connected` without needing a user
      action to discover it.
- [ ] An action that fails because the session is gone says so and offers re-auth — not the generic
      "the server declined…".
- [ ] A connectivity excursion does not leave an orphaned upstream session or open a second login for
      the same username.
- [ ] After a re-established session, the lobby/room re-subscribe — `Connected` and "sign in to browse"
      can never appear together.
- [ ] 0023/0024 park+resume behaviour is unchanged, pinned in both directions.
- [ ] Each new test demonstrated failing pre-fix; prior suites green; 0048's smoke re-run.

## 8. References

- [`0048-on-device-smoke-and-wiring-guards.md`](0048-on-device-smoke-and-wiring-guards.md) — where A–D were observed, with bridge log evidence.
- [`0023-bridge-session-hold-and-resume.md`](0023-bridge-session-hold-and-resume.md) / [`0024-app-reconnect-and-lifecycle-session.md`](0024-app-reconnect-and-lifecycle-session.md) — the park/resume model to use correctly, not change.
- [`0046-sign-out-sends-logout.md`](0046-sign-out-sends-logout.md) — the both-directions test pattern to mirror.
