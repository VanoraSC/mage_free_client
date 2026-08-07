# 0039 — Live table-action coverage

- **Epic:** EPIC-07 — Hosting & Joining Tables (hardening)
- **Depends on:** 0036 (table actions protocol & bridge relay), 0022 (reference XMage server), 0002 (server harness)
- **Status:** ready

## 1. Objective

Give Epic 7's table actions **real-server** coverage. Today `TableRelay` (create / join / submit /
start / watch) is verified only against a fake `SessionImpl`: the mapped arguments are asserted, but
nothing proves the **real** XMage server accepts them. Add an env-gated `TableRelayIT` that drives a
genuine host → join → submit → start round trip against the pinned reference server (story 0022) and
observes the pushed table-lifecycle callbacks through to **match-start**.

## 2. Context & background

- **The gap (measured, 2026-08-07):** the bridge has five env-gated live ITs — `ConnectAuthenticateIT`,
  `SessionBridgeIT`, `SessionResumeIT` (2), `CallbackRelayIT`, `LobbyRelayIT` — all of which run and
  pass against the reference server (6 tests, `skipped=0`). **None covers table actions.** Epic 7's
  whole act-side is fake-verified only.
- Why that matters: `TableRelay` builds real `mage.*` arguments (`MatchOptions`, `DeckCardLists`,
  `PlayerType`, skill int, `password ?: ""`). A fake `SessionImpl` happily accepts a `MatchOptions`
  the real server would reject (an unknown game type, an unset field, a bad deck shape). Exactly the
  class of defect a hermetic test cannot see — and the reason 0036's own report flagged its
  `SessionImpl` subclassing as the one spot it could not exercise.
- The reference server (0022) runs anonymous-auth, registration off, and preloads game types
  (`Loaded game types: 17, tourneys: 21, players: 4, cubes: 23, decks: 52`), so a table can be created
  and started without accounts — consistent with registration being permanently deferred.
- **Pattern to follow:** the existing ITs. Env-gated via
  `@EnabledIfEnvironmentVariable(named = "XMAGE_SERVER", matches = ".+")` + `XMageServerTarget.fromEnv()`,
  so `./scripts/dev gradle :bridge:check` stays hermetic and CI-safe; the live run is opt-in.

## 3. Scope

**In scope**
- **`TableRelayIT`** (`bridge/src/test/.../mapping/`), env-gated exactly like `LobbyRelayIT`, driving a
  real `SessionImpl` against the reference server:
  1. **create** a table via `TableRelay.createTable` with a `CreateTableOptions` the server accepts —
     assert a real `TableCreated` comes back with a usable table id (not a `TableActionResult` failure).
  2. **join** it via `TableRelay.joinTable` submitting a `DeckList` — assert `ok = true`.
  3. **start** the match via `TableRelay.startMatch` once the seats are filled — assert `ok = true`.
  4. **observe** the pushed lifecycle through `CallbackMapper`: at minimum a `MatchStarting` (the
     Epic-11 boundary) — proving `START_GAME` really maps end-to-end.
  5. **tear down** (`leaveTable`/`removeTable`) so the run is repeatable and leaves no stuck table.
- **Seat strategy:** fill the second seat with an **AI** player (`PlayerType.COMPUTER_MAD`, as the
  desktop's solo-vs-AI flow does) so one client can reach match-start without a second human session.
- **A deck the server accepts:** build the `DeckList` in the test (a minimal legal-enough deck for the
  chosen game type/deck type). Document the choice; prefer the least restrictive deck type that still
  starts, and assert the *server's* verdict rather than assuming.
- **A negative case:** at least one asserting a real decline maps to a **typed failure** (e.g. joining
  a nonexistent/duplicate table → `TableActionResult(ok = false, reason = …)`), proving `false` is not
  silently dropped on the real path.
- Documented run command in the test KDoc (mirroring the siblings) and a line in
  `docs/build-environment.md`'s live-test list if one exists.

**Out of scope**
- **Gameplay** past `MatchStarting` (EPIC-11) — the test stops at the game-start signal.
- App-side (`:core:network`/`:feature:tables`) tests — 0037/0038 cover those with fakes; this is the
  bridge↔server contract.
- Tournaments/draft (`joinTournamentTable`) — EPIC-08.
- Making the live test part of the default gate: it stays **opt-in** and env-gated.

## 4. Design & approach

```
bridge/src/test/kotlin/magefree/bridge/mapping/TableRelayIT.kt   # env-gated, real SessionImpl
```

- Mirror `LobbyRelayIT`'s scaffolding: resolve `XMageServerTarget.fromEnv()`, connect+login a real
  `SessionImpl` (a unique `it_<uuid>` user, as the siblings do), resolve `getMainRoomId()`, then drive
  `TableRelay` directly — the relay is the unit under test, so no WebSocket/app layer is involved.
- Callback observation reuses the existing client callback plumbing the way `CallbackRelayIT` does,
  asserting the **mapped** `:protocol` event (not the raw `mage.*` payload).
- Clean up in a `finally`/`@AfterEach` so a failed assertion still removes the table and disconnects —
  a leaked table would poison later runs against a long-lived server.

## 5. Implementation steps

1. Scaffold `TableRelayIT` from `LobbyRelayIT` (env gate, connect/login, room id, teardown).
2. Create-table: find a `CreateTableOptions` the real server accepts; assert `TableCreated`.
3. Join with a built `DeckList` + an AI seat; assert `ok`.
4. Start the match; assert `ok` and observe the mapped `MatchStarting`.
5. Add the negative (server-decline → typed failure) case.
6. Verify: hermetic `./scripts/dev gradle :protocol:check :bridge:check` still green **and** the live
   run green with `XMAGE_SERVER` set against a running `xmage-server`.

## 6. Testing & verification

- **Hermetic (default):** `./scripts/dev gradle :bridge:check` — the new IT is **skipped** (env gate),
  all existing suites stay green.
- **Live (opt-in):** `./scripts/dev up xmage-server` then
  `XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*TableRelayIT' --rerun-tasks`
  → green, and re-runnable (teardown leaves no stuck table).
- The existing five ITs must still pass in the same live run.

## 7. Acceptance criteria

- [ ] `TableRelayIT` drives **create → join (deck submitted) → start** against the real reference
      server and asserts the server's own verdicts (`TableCreated` with a real table id; `ok = true`).
- [ ] The pushed **`MatchStarting`** event is observed through `CallbackMapper` — the `START_GAME`
      path is proven end-to-end (no gameplay beyond it).
- [ ] A real server **decline** maps to a typed `TableActionResult(ok = false, reason = …)` — proving
      failures are surfaced, not dropped.
- [ ] The test is **env-gated** (`XMAGE_SERVER`), so `:bridge:check` stays hermetic and green; it
      tears down its table so repeat runs are clean.
- [ ] The live run is documented (KDoc command, mirroring the sibling ITs); the other five ITs stay green.

## 8. References

- [`0036-table-actions-protocol-and-bridge-relay.md`](0036-table-actions-protocol-and-bridge-relay.md) — the relay + callbacks under test.
- [`0022-reference-xmage-server-container.md`](0022-reference-xmage-server-container.md) — the server this runs against.
- `bridge/src/test/kotlin/magefree/bridge/mapping/LobbyRelayIT.kt` — the env-gated live pattern to mirror; `.../CallbackRelayIT.kt` — callback observation.
- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — `createTable`/`joinTable`/`startMatch` contracts.
