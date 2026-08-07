# 0040 — Table seat state

- **Epic:** EPIC-07 — Hosting & Joining Tables (defect fix)
- **Depends on:** 0036 (table protocol/bridge relay), 0037 (`TableClient`/`TableState`), 0027 (lobby read path)
- **Status:** ready

## 1. Objective

Give the table room **real seat state**. Today `TableState.seats` is populated only by folding a
`SeatUpdated` push that **nothing ever sends**, so the room shows zero seats forever and the host's
start control — gated on `seats.isNotEmpty() && seats.all { it.isReady }` — **can never enable**.
Replace the phantom push with the seat data the server actually exposes, and gate starting on the
server's own readiness rule. Companion to 0041 (which fixes the host seating flow).

## 2. Context & background

- **The defect (audit + live, 2026-08-07, verified):**
  - `SeatUpdated` has **no producer**: `CallbackMapper` dispatches only `CHATMESSAGE`, `JOINED_TABLE`,
    `CONSTRUCT`, `SIDEBOARD`, `START_GAME`. `grep SeatUpdated` outside `:protocol` hits only
    `TableEventFold` (the consumer) and its tests.
  - `Seat.isReady` is assigned in exactly one place in the repo: a Compose **preview fixture**
    (`TableRoomScreen.kt:228`). Never in production.
  - Therefore `TableRoomUiState.canStart` (`TableRoomViewModel.kt:62`) is unreachable-true, and the room
    renders "Waiting for players to sit down…" permanently.
  - 0038's tests miss it *structurally*: `TableRoomViewModelTest` injects a fully-formed `TableState`
    through the fake, bypassing `TableEventFold` — it verifies the gate's logic, never that the gate's
    input is reachable.
- **Upstream reality (confirmed against the pinned ref + the live reference server):**
  - `mage.view.TableView` **already carries** `List<SeatView>`; `SeatView` has `playerId`,
    `playerName`, `playerType`, `flagName`, ratings. There is **no** per-seat push before match-start
    and **no** `SessionImpl.getTable(...)` — the desktop's waiting room *polls*.
  - Our `TableMapper` already reads `view.seats` but **reduces it to counts** (`seatsFilled`/
    `seatsTotal`), discarding who is seated (`TableMapper.kt:43-44, 81-82`).
  - There is no per-seat "ready" flag anywhere upstream. Readiness is a **table-level** property:
    `TableStateCode.READY_TO_START` once every seat is filled — which is exactly the gate the server
    enforces (0039's live IT: `startMatch` is accepted only once both seats are filled).

## 3. Scope

**In scope**
- **`:protocol`** — carry per-seat detail: a `TableSeatSummary` (seat index, `playerName?`,
  `playerType`, `occupied`) and a targeted **`GetTable(tableId)` → `TableDetail`** request/reply
  (additive; `requestId`-correlated like the other reads).
- **`:bridge`** — `TableMapper` maps `SeatView` → `TableSeatSummary` (additive; existing
  `seatsFilled`/`seatsTotal` behavior unchanged). A `GetTable` handler resolves the table from
  `getTables(roomId)` filtered by id (upstream exposes no single-table read) and replies `TableDetail`,
  or a typed not-found. `mage.*` stays inside the bridge.
- **`:core:network`** — `TableClient.refreshTable(tableId)`; `observeTable` seeds/refreshes
  `TableState.seats` from `TableDetail` and **re-refreshes on the table-lifecycle pushes it already
  receives** (`TableUpdated`/`ConstructPrompt`/`MatchStarting`) plus on a 0023 resume, so a seat change
  driven by another player becomes visible without a phantom push. Poll only while the room is on
  screen; no background polling.
- **Readiness on server truth** — replace per-seat `isReady` with the table's own state:
  `TableState.phase`/a `canStart` derived from `TableStateCode.READY_TO_START` (all seats filled).
  Remove or repurpose `Seat.isReady` so no field is gated on something nothing populates.
- **`TableEventFold` hardening** — a `SeatUpdated` with a null `playerId` currently **appends a new
  phantom seat on every push** (`upsertSeat`, `TableEventFold.kt:82-90`); key seats by index/slot (or
  ignore null-id updates) so the list cannot grow unbounded.
- **Tests that could actually catch this class of bug:** at least one test that drives the room from
  the **client seam** (fake `BridgeClient`/push source + `TableDetail` replies) rather than injecting a
  finished `TableState`, asserting seats appear and `canStart` becomes true. Plus bridge mapper tests
  for `SeatView` → `TableSeatSummary` and the `GetTable` handler.

**Out of scope**
- The **host seating flow** (deck picker, joining AI + self) — **0041**.
- Gameplay past `MatchStarting` (EPIC-11); tournaments (EPIC-08).
- Any change to the lobby browser's own list behavior (0029).

## 4. Design & approach

- **Poll, don't invent a push.** Upstream has no pre-match seat push, so the room refreshes a targeted
  `GetTable` on open, on each table-lifecycle push it already gets, and on resume. This mirrors the
  desktop's polling waiting room and keeps the bridge honest — we do not fabricate an event the server
  never sends.
- **One source of truth for "can start":** the server's `READY_TO_START`. The client should not invent
  a readiness rule the server does not share, and 0039 proved the server rejects an early start anyway.
- Existing `TableUpdated`/`ConstructPrompt`/`SideboardPrompt`/`MatchStarting` folding is unchanged.

## 5. Implementation steps

1. `:protocol` `TableSeatSummary` + `GetTable`/`TableDetail`; round-trip + unknown-tolerance tests.
2. `:bridge` `TableMapper` seat mapping + the `GetTable` handler (resolve via `getTables`, typed
   not-found); mapper + relay tests.
3. `:core:network` `refreshTable` + seats into `TableState`; readiness from table state; fold hardening.
4. Room UI reads the real seats; `canStart` from server truth.
5. Tests **through the client seam** (not injected state) proving seats appear and start enables.
6. `:protocol:check`/`:bridge:check` in-container; `:core:network:check` + `:feature:tables:check` +
   `:app:assembleDebug` on host. Extend 0039's `TableRelayIT` to assert `GetTable` reflects a filled seat.

## 6. Testing & verification

- **Hermetic:** protocol round-trip; `SeatView`→summary mapping; `GetTable` handler (found + not-found);
  client seam test (fake replies/pushes → seats populate → `canStart` true); fold no longer appends
  phantom seats for null-`playerId`.
- **Live (opt-in):** extend `TableRelayIT` — after seating the AI, a `GetTable` shows `seatsFilled = 1`
  with the AI's name; after the host joins, `READY_TO_START`. This is the regression guard that would
  have caught the original defect.

## 7. Acceptance criteria

- [ ] The room shows the table's **actual seats** (who is seated, player type, empty slots) sourced
      from `SeatView` via a targeted `GetTable` — no reliance on a push the server never sends.
- [ ] The host's start control enables exactly when the **server** says the table is ready
      (`READY_TO_START` / all seats filled); no field is gated on something nothing populates.
- [ ] Seats refresh on open, on the existing table-lifecycle pushes, and across a 0023 resume; no
      background polling when the room is off screen.
- [ ] `TableEventFold` cannot grow an unbounded phantom-seat list from null-`playerId` updates.
- [ ] A test drives the room **through the client seam** (fake bridge + replies), not by injecting a
      finished `TableState`, and fails if seats become unreachable again.
- [ ] All prior suites green; the live `TableRelayIT` (extended) green against the reference server.

## 8. References

- [`0037-table-client-and-session-api.md`](0037-table-client-and-session-api.md) / [`0038-host-and-join-tables-ui.md`](0038-host-and-join-tables-ui.md) — the surfaces being corrected.
- [`0039-live-table-action-coverage.md`](0039-live-table-action-coverage.md) — the live harness to extend; proved the server's own readiness gate.
- `../mage/Mage.Common/src/main/java/mage/view/{TableView,SeatView}.java` — the seat data upstream already exposes.
