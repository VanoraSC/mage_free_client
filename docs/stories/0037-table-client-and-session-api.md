# 0037 — Table client & session API

- **Epic:** EPIC-07 — Hosting & Joining Tables
- **Depends on:** 0036 (table protocol + bridge relay), 0028 (request/response client), 0023–0025 (session/resume), 0033 (deck model → `DeckCardLists`-equivalent)
- **Status:** ready

## 1. Objective

The app-side, **UI-free** table client in `:core:network`: a `TableClient`/repository that turns
0036's table-action messages into suspend calls (create / join / submit / update / leave / start /
watch) and exposes an observable **`TableState`** (the table's seats, players, phase, and the
match-starting signal) as a `StateFlow`. It reuses 0028's multiplexed request/response and 0023's
session/resume, and maps a 0033 `Deck` onto a join/submit deck. **No UI** — 0038 renders this.

## 2. Context & background

- 0028 gave `BridgeClient` request/response (`PendingRequests` correlation) + the server-push event
  stream; 0029's lobby repository consumes the read side. This story adds the **action** side over
  the same client, plus a **subscription** to a single table's evolving state (the join→construct→
  start lifecycle) sourced from 0036's pushed events.
- 0023–0025 gave session hold/resume + reconnect: a table subscription must survive a reconnect
  (resume re-attaches; the table state re-syncs) rather than silently dying — consistent with the
  lobby's non-destructive refresh.
- 0033 gave `Deck` + the `Deck ↔ DeckCardLists`-equivalent mapper; this client maps a device `Deck`
  onto 0036's wire `DeckList` for join/submit — **offline** deck, networked submit. No `mage.*`.
- **ABI hygiene (0028 lesson):** keep `:protocol` off the public `TableClient` ABI where practical
  (erased request/reply through the existing `request(...)` seam), and honor the
  `@JvmSuppressWildcards` covariance fix for any injected `StateFlow<…>` (the 0029/0028 bug).

## 3. Scope

**In scope** (all in `:core:network`, no UI)
- **`TableClient`** (interface + default impl over `BridgeClient`):
  - `suspend createTable(options): Result<TableRef>` (returns the new table id + seats from 0036's
    `TableCreated`), `suspend joinTable(tableId, seatName, deck, password?)`,
    `suspend submitDeck(tableId, deck)` / `updateDeck`, `suspend leaveTable(tableId)`,
    `suspend removeTable(tableId)`, `suspend startMatch(tableId)`, `suspend watchTable(tableId)` —
    each mapping 0036's `TableActionResult` to a `Result`/typed failure (reason surfaced, never
    silently dropped).
  - A domain **`TableState`** model (app-schema: table id, format/options summary, ordered **seats**
    with player name/type/ready/deck-submitted, phase = `Waiting`/`Constructing`/`Starting`/`Started`,
    plus a one-shot **`MatchStarting(gameId)`** signal) and `fun observeTable(tableId): Flow<TableState>`
    that folds 0036's `TableUpdated`/`SeatUpdated`/`Construct`/`Sideboard`/`MatchStarting` events into
    that state, seeded by the create/join result.
  - **Deck bridging:** accept a 0033 `Deck`, map to 0036's `DeckList`-equivalent internally (via the
    0033 mapper) — callers pass a domain `Deck`, never a wire type.
- **Resilience:** the table subscription re-syncs across a 0023 resume (re-attach + refetch), mirroring
  the lobby; back-pressure/lifecycle via the injected scope/dispatchers.
- **DI:** additive provider(s) in the `:core:network` module; no change to 0028/0029 wiring beyond
  adding the new binding.
- Tests (fakes, no server): each verb maps request↔result correctly (incl. a failure `reason`);
  `observeTable` folds a scripted event sequence (join → seat updates → construct → match-starting)
  into the right `TableState` transitions; deck→`DeckList` mapping; resume re-sync re-emits current
  state. Turbine for the flow.

**Out of scope**
- **UI** (create/join/seat/spectate screens; wiring lobby Join) — **0038**.
- Protocol/bridge changes — **0036** (consumed as-is).
- **In-game** state past `MatchStarting` — **EPIC-11**.
- **Tournaments** — **EPIC-08**.
- Deck construction/legality/files (0033/0034) — reused via the domain `Deck`.

## 4. Design & approach

```
core/network/
├── table/TableClient.kt          # interface: create/join/submit/update/leave/remove/start/watch + observeTable
├── table/DefaultTableClient.kt   # over BridgeClient (0028 request/response) + 0036 messages
├── table/TableState.kt           # app-schema table/seat/phase model + MatchStarting signal
├── table/TableEventFold.kt       # folds 0036 events -> TableState (pure, unit-tested)
└── di/…                          # additive providers
```

- **Requests** go through 0028's existing `request(...)` seam (erased generic), so `:protocol` stays
  off the `TableClient` ABI. **Events** are filtered from 0028's push stream by table id and folded
  by a **pure** `TableEventFold` (unit-testable without the client).
- **`observeTable`** = seed (from create/join `TableRef` or an initial fetch) + fold of the filtered
  event stream, shared while subscribed; on a 0023 resume it re-seeds (re-attach/refetch) so a
  reconnect doesn't strand the seat.
- **One representation:** `TableState`/seat/phase is app-schema; the `Deck` in/out is 0033's domain
  model. `:protocol`/`mage.*` never surface to callers.

## 5. Implementation steps

1. `TableState` + `TableEventFold` (pure): fold each 0036 event kind into seats/phase/signal; unit
   tests over scripted sequences.
2. `TableClient` interface + `DefaultTableClient` over `BridgeClient`: the eight verbs mapping
   result→`Result`; deck→`DeckList` via the 0033 mapper; request-mapping tests.
3. `observeTable`: seed + folded event stream filtered by table id; Turbine tests incl. the
   match-starting signal.
4. Resume re-sync: re-seed on a 0023 resume; test that current state re-emits after a simulated
   reconnect.
5. Additive DI; `:core:network:check` + `:app:assembleDebug` green; prior suites green.

## 6. Testing & verification

- **Hermetic (fakes):** verb→request/result mapping (each verb, incl. a typed failure); `observeTable`
  folds a scripted join→construct→match-starting sequence into the expected `TableState`s (Turbine);
  `Deck`→`DeckList` mapping round-trips 0033's shape; resume re-emits state. No server, no device.
- **Live (opt-in, 0022 server):** create+join+submit+start against the reference server; observe
  `TableState` reach `Starting`/`MatchStarting` — documented manual smoke (no gameplay).

## 7. Acceptance criteria

- [ ] `TableClient` exposes create/join/submit/update/leave/remove/start/watch as suspend calls over
      0028's request/response, each mapping 0036's result to a `Result`/typed failure (reason surfaced).
- [ ] `observeTable(tableId)` exposes an app-schema `TableState` (seats/players/phase + a one-shot
      `MatchStarting(gameId)`) folded from 0036's pushed events, seeded from create/join.
- [ ] Callers pass a **0033 `Deck`** for join/submit (mapped internally to the wire `DeckList`); no
      `:protocol`/`mage.*` on the `TableClient` ABI; the 0028 covariance fix honored.
- [ ] The table subscription **re-syncs across a 0023 resume** (reconnect doesn't strand the seat).
- [ ] Fake-based tests cover every verb, the event fold (incl. match-starting), deck mapping, and
      resume re-sync; `:core:network:check` + `:app:assembleDebug` green; prior suites green.
- [ ] No UI; no protocol/bridge changes; stops at `MatchStarting` (Epic 11 owns in-game).

## 8. References

- [`0036-table-actions-protocol-and-bridge-relay.md`](0036-table-actions-protocol-and-bridge-relay.md) — the messages/events this consumes.
- [`0028-app-lobby-model-and-data.md`](0028-app-lobby-model-and-data.md) — the app-side request/response + push client reused; the `@JvmSuppressWildcards` fix.
- [`0023-bridge-session-hold-and-resume.md`](0023-bridge-session-hold-and-resume.md) — resume the subscription re-syncs across.
- [`0033-deck-model-storage-and-legality.md`](0033-deck-model-storage-and-legality.md) — the `Deck` + `DeckCardLists`-equivalent mapper.
