# 0048 — On-device smoke & wiring guards

- **Epic:** Cross-cutting (foundation verification)
- **Depends on:** 0045 (live app↔bridge), 0046 (sign-out), 0047 (connect flow mounted)
- **Status:** ready

## 1. Objective

Answer, with evidence, the question the project has never actually asked: **does the app a user
installs really work?** Two complementary pieces:

1. A **scripted on-device smoke** that drives the real APK on an emulator, against the real bridge and
   XMage server, through the whole vertical — sign in → lobby → decks → host a table → match-start →
   sign out.
2. **Hermetic wiring guards** — cheap unit tests that fail the normal build when a feature is built but
   **not reachable**, so the 0047 class of defect cannot recur silently.

Deliberately **not** a full integration test in CI: instrumented tests against live servers are flaky
and expensive, and the value here is confidence now plus a cheap regression floor.

## 2. Context & background

- **Why this story exists.** Story 0047 found that `:feature:connect` — an entire epic's UI — was
  built, tested, merged, and **never mounted**: `:app` didn't depend on it, nothing called
  `connect(...)`, and an installed APK sat permanently on `Disconnected · Retry` with no way to sign
  in. Confirmed on-device before the fix.
- **Why nothing caught it.** `:app`'s instrumented tests drive the **stateless, Hilt-free** overloads
  with stub data (`:app` has Compose/Espresso/navigation test infra but **no** `hiltAndroidTest` and no
  custom Hilt runner). They assert screens render, never that the real graph wires them. Below the UI,
  0045 proves `:core:network` live — by driving it *directly*, bypassing the app. The untested seam was
  exactly the one that broke.
- **What is already proven** (so this story doesn't re-prove it): the bridge against real XMage (0039),
  and the app's whole networking stack against a real bridge (0045) — connect/login, lobby read,
  create → seat AI → seat self → ready → start → `MatchStarting`, and sign-out semantics (0046).
  **Everything below the UI works.** This story covers the UI layer and the wiring.

## 3. Scope

**In scope — part 1: the scripted smoke**
- A checked-in script (under `scripts/`) that, given a running emulator + `bridge` + `xmage-server`,
  installs the debug APK and drives the app through:
  1. **Cold launch → sign in** against the bridge (the emulator reaches the host bridge at
     `10.0.2.2:8080`), reaching a connected state.
  2. **Lobby** — populates (host a table from the app, or via the bridge, so there is something to see).
  3. **Decks** — create a deck, search the catalog, add cards, see legality. **Offline**: this must work
     with the network unavailable, which is the deck feature's core promise.
  4. **Host a table** — options → deck pick → create → seats fill (AI) → ready → start → the
     match-starting hand-off.
  5. **Sign out** → returns to a state from which sign-in is reachable again (0046 + 0047).
- Drives via `adb` + `uiautomator` dumps, asserting on on-screen content at each step, and captures a
  screenshot per step into an output directory for evidence.
- **Fails loudly and specifically**: each step names what it expected and what was on screen. A smoke
  that passes vacuously is worse than none (verification standard 1 in spirit).
- Documented in `docs/build-environment.md`: prerequisites, how to run, what a pass looks like.

**In scope — part 2: hermetic wiring guards**
- Unit tests (normal `check`, no device, no network) that fail when a feature module is **built but not
  reachable**. At minimum: for every `:feature:*` module the app is supposed to expose, assert the app
  can actually reach it — e.g. its route/entry type resolves on `:app`'s runtime classpath, so removing
  the Gradle dependency or dropping the destination fails the build.
- A guard that the **cold-start entry policy** holds: with no session, the destination a user lands on
  is one from which sign-in is reachable (whatever policy 0047 chose).
- These must be **cheap and hermetic** — they run in the normal `:app:testDebugUnitTest`.

**Out of scope**
- A full instrumented end-to-end test in CI (explicitly rejected: flaky, expensive, low marginal value
  over the smoke).
- Adding `hiltAndroidTest`/a custom Hilt runner (only if a guard genuinely cannot be written without
  it — prefer the cheap route, and say so if you hit the wall).
- Gameplay past match-start (EPIC-11); tournaments (EPIC-08).
- Any production behaviour change. **If the smoke finds a defect, report it — do not fix it here.**

## 3a. Practical constraints (learned on-device, 2026-08-10)

Both were hit during the manual run that verified 0047; the script must handle them or it will report
false failures.

- **Dismiss the soft keyboard before tapping bottom-anchored buttons.** The sign-in layout does **not**
  resize for the IME, so a bottom button's reported bounds stay where they are while the keyboard
  covers them — a tap silently hits the IME. This produced a convincing false "dead Connect button"
  (no UI change, no logs, no bridge contact) until `dumpsys input_method` showed `mInputShown=true`.
  Dismiss the IME, then tap. Where a step's expectation fails, re-check IME state before concluding.
- **Do not locate controls by content description.** Material3 `NavigationBarItem` (and the lobby's
  icon-only actions) expose `Role=Tab` and text but **no `ContentDescription`** in the merged semantics
  tree, so `onNodeWithContentDescription`-style lookups match nothing. Locate by visible text, role, or
  bounds instead. **This is a testing-correctness concern, not an accessibility one** — accessibility
  work is deferred for this effort; fix the locator, never the semantics.
  (Note in passing: `:app`'s existing androidTests locate tabs by content description and therefore
  appear unable to find them. Those tests do not run pre-merge. Out of scope here — flagged only so a
  future reader does not trust them.)

## 4. Design & approach

- **The smoke is verification, not a test suite.** It should be readable, linear, and obvious about
  what it asserts, so a human can follow a failure without decoding a framework.
- **Offline deck step matters.** Toggle the device offline (`adb shell svc data disable` / airplane
  mode, or stop the bridge) and confirm deck building still works — that is the one promise the app
  makes that no live test has ever checked.
- **The wiring guards are the durable part.** The smoke proves today; the guards prevent tomorrow. If
  the guard for a feature cannot be written cheaply, say so rather than writing one that passes
  regardless.

## 5. Implementation steps

1. Wiring guards first (they're cheap, and a failure immediately tells us something is unmounted).
2. The smoke script: sign-in step, then each subsequent step, committing incrementally.
3. Screenshot/evidence capture + a clear pass/fail summary.
4. Document in `docs/build-environment.md`.
5. Run the smoke end to end; record the result and **every** defect found, fixed by nobody.

## 6. Testing & verification

- **Hermetic:** `:app:testDebugUnitTest` green including the new wiring guards; each guard demonstrated
  **failing** when its dependency/destination is removed (standard 1) — capture the output.
- **On-device:** the smoke run itself, with per-step evidence. Repeat it once to confirm it is not
  order- or state-dependent.

## 7. Acceptance criteria

- [ ] A checked-in, documented script drives the installed app on an emulator through sign-in → lobby →
      decks (incl. **offline**) → host → match-start → sign-out, asserting on-screen state at each step
      and capturing evidence.
- [ ] The smoke fails loudly and specifically when a step's expectation is not met.
- [ ] Hermetic wiring guards fail the normal build if a feature module is built but unreachable, and
      each is demonstrated failing when its wiring is removed.
- [ ] The cold-start entry policy is guarded hermetically.
- [ ] Every defect the smoke finds is **reported, not fixed**, with enough detail to become a story.
- [ ] No production behaviour change in this story.

## 8. References

- [`0047-mount-the-connect-flow.md`](0047-mount-the-connect-flow.md) — the defect that motivated this, and the entry policy the smoke drives.
- [`0045-app-to-bridge-live-integration.md`](0045-app-to-bridge-live-integration.md) — everything below the UI is already proven live; don't re-prove it.
- `docs/stories/README.md` § Verification standards — standard 2 (reachability) is what the wiring guards mechanise.
