# 0036 — Table actions: protocol & bridge relay

- **Epic:** EPIC-07 — Hosting & Joining Tables
- **Depends on:** 0027 (lobby relay & contract), 0023 (bridge session hold/resume), 0004–0006 (bridge foundation)
- **Status:** ready

## 1. Objective

Extend the wire contract and the bridge so a client can **act on tables**, not just read them:
**create** a table, **join** a table (submitting a deck), **submit/update** a deck during
construction, **leave/remove** a table, **start** the match, and **watch** (spectate). Add the
inbound **table/deck/game-start callbacks** the server pushes so the app can follow a table's state
through to the moment a match begins. **No UI and no app-side client here** — this is the `:protocol`
+ `:bridge` foundation that 0037 (core client) and 0038 (feature UI) build on.

## 2. Context & background

- 0027 delivered the bridge **read** side: `GetTables`/`TableList`/`GetRoomUsers`/`GetGameTypes`
  (fetch+map in `LobbyRelay`) over the request/response contract; 0028 gave the app-side client. 0029's
  lobby UI shows tables with a **disabled** "Join — coming soon" affordance, deferring the action
  side to this epic.
- **XMage table contract (`SessionImpl`, `javap`/source-confirmed at the pinned ref):**
  - `TableView createTable(UUID roomId, MatchOptions matchOptions)` — host creates + is seated.
  - `boolean joinTable(UUID roomId, UUID tableId, String playerName, PlayerType playerType, int skill, DeckCardLists deckList, String password)` — join a constructed table (deck submitted at join).
  - `boolean submitDeck(UUID tableId, DeckCardLists deck)` / `boolean updateDeck(UUID tableId, DeckCardLists deck)` — during the construction phase.
  - `boolean leaveTable(UUID roomId, UUID tableId)` / `boolean removeTable(UUID roomId, UUID tableId)`.
  - `boolean startMatch(UUID roomId, UUID tableId)` — host readies/starts.
  - `boolean watchTable(UUID roomId, UUID tableId)` — spectate.
  - `UUID getMainRoomId()` — the room these operate within (already used by `LobbyRelay`).
- **Callbacks:** the server pushes table/deck/game state via `ClientCallback` +
  `ClientCallbackMethod`. Today `CallbackMapper` maps only `CHATMESSAGE` but is **built to add
  more `when` cases** (its own doc says "lobby, deck, game"). This story adds the table-lifecycle
  ones needed to follow a seat through match-start — e.g. joined-table, table/seat updates, the
  **construct/sideboard** prompts, and the **game-start** signal — mapped to `:protocol` events.
- **Deck shape:** `submitDeck`/`joinTable` take `DeckCardLists` (name/author/`DeckCardInfo`
  main+sideboard) — exactly the shape 0033's `Deck ↔ DeckCardLists`-equivalent mapper produces. The
  wire contract here carries that equivalent (name/set/number/amount) so 0037 can map a `Deck`
  straight onto a join/submit without any `mage.*` on device.
- **`MatchOptions`:** game type, deck type, format name, number of seats/players, rated, free-mulligan
  policy, player/skill range, match/game timeouts, spectators-allowed, quit-ratio, etc. The wire
  contract exposes a **flat, app-schema** `CreateTableOptions` the bridge maps onto `MatchOptions`
  (the `mage.*` construction stays server-side of the boundary, in the bridge).

## 3. Scope

**In scope**
- **`:protocol` — table action messages** (sealed `ClientMessage`/`ServerMessage`, `@SerialName`,
  `ignoreUnknownKeys`, same style as `LobbyMessages`):
  - Requests: `CreateTable(roomId, CreateTableOptions)`, `JoinTable(roomId, tableId, seatName, deck, password?)`,
    `SubmitDeck(tableId, deck)` / `UpdateDeck(tableId, deck)`, `LeaveTable(roomId, tableId)`,
    `RemoveTable(roomId, tableId)`, `StartMatch(roomId, tableId)`, `WatchTable(roomId, tableId)`.
  - Results: a `TableActionResult`/`TableCreated(tableView)` (create returns the new table; the
    booleans map to an ok/failure result with a reason).
  - App-schema payloads: `CreateTableOptions` (game type, format, seats, rated, free-mulligan, range,
    timeouts, spectatorsAllowed…) and a `DeckList`-equivalent for join/submit (name/author/main/
    sideboard of name+set+number+amount) — reusing/aligning with 0033's interchange shape.
  - Events (server-pushed): `TableUpdated`/`SeatUpdated` (table + seat state as it changes),
    `ConstructPrompt`/`SideboardPrompt` (the server asks a seat to build), and `MatchStarting`
    (the game-start signal + the game id) — the **boundary to Epic 11** (in-game is out of scope).
- **`:bridge` — a `TableRelay`** (sibling of `LobbyRelay`): dispatch each request to the matching
  `SessionImpl` method within the session's room, map results/`TableView` back to `:protocol`. All
  `mage.*` (`MatchOptions`, `DeckCardLists`, `PlayerType`, `TableView`) stays **inside** the bridge
  mapping — the boundary is unchanged.
- **`:bridge` — extend `CallbackMapper`** with the table-lifecycle `when` cases (joined-table, table/
  seat updates, construct/sideboard, game-start), each via a small per-callback mapper (like
  `ChatMessageMapper`), mapping **never throws** (unknown/changed callbacks ignored, not fatal).
- **Deck mapping:** a bridge-side `DeckCardLists ↔ protocol DeckList` mapper (the reverse of 0033's
  device-side one), so join/submit carry a deck without `mage.*` crossing the wire.
- Tests: protocol round-trip (serialize/deserialize each new message + unknown-tolerance); bridge
  relay dispatch (fake `SessionImpl`/upstream: each verb calls the right method with mapped args and
  maps the result); callback mapping for each new table event (+ the never-throws guarantee).

**Out of scope**
- The **app-side client/repository** (`TableClient`, state flow) — **0037**.
- Any **UI** (create/join/seat/spectate screens, wiring the lobby Join) — **0038**.
- **In-game** play/state (the game view, priority, actions) — **EPIC-11**; this story stops at the
  `MatchStarting` signal.
- **Tournaments/draft/sealed** (`joinTournamentTable`, draft callbacks) — **EPIC-08**.
- Deck **construction logic** and legality (0033) and deck files (0034) — reused, not changed.

## 4. Design & approach

```
protocol/
├── TableMessages.kt   # CreateTable/JoinTable/SubmitDeck/UpdateDeck/Leave/Remove/StartMatch/WatchTable
│                      #  + TableCreated/TableActionResult + TableUpdated/SeatUpdated/Construct/Sideboard/MatchStarting
│                      #  + CreateTableOptions + DeckList-equivalent (aligned with 0033)
bridge/
├── mapping/TableRelay.kt        # request -> SessionImpl.{createTable,joinTable,submitDeck,...} -> protocol result
├── mapping/DeckListMapper.kt    # protocol DeckList <-> mage DeckCardLists (bridge-only; mage.* stays here)
├── mapping/table/*Mapper.kt     # per-callback mappers (joined/updated/construct/sideboard/matchStarting)
└── mapping/CallbackMapper.kt    # +when cases dispatching to the above
```

- **Symmetry with 0027:** requests ride the existing request/response contract; events ride the
  existing server-push path. No new transport — only new message types + mappers.
- **Boundary intact:** every `mage.*` type (`MatchOptions`, `DeckCardLists`, `TableView`,
  `PlayerType`, `MatchView`) is referenced **only** inside `:bridge` mapping. `:protocol` stays pure
  Kotlin/kotlinx-serialization; nothing new crosses onto the device.
- **Result semantics:** the `boolean` verbs map to a structured `TableActionResult{ ok, reason? }`
  (a `false` from XMage becomes a typed failure, not a silent drop); `createTable` returns the
  mapped `TableView` (reusing 0027's `TableMapper`) so the caller gets the new table id + seats.

## 5. Implementation steps

1. `:protocol` `TableMessages.kt`: the action requests, results, `CreateTableOptions`, the join/submit
   `DeckList`-equivalent, and the pushed table/game-start events; register in `ProtocolJson`;
   round-trip + unknown-tolerance tests.
2. `:bridge` `DeckListMapper` (protocol `DeckList` ↔ `DeckCardLists`) + `CreateTableOptions →
   MatchOptions`; unit tests over crafted decks/options.
3. `:bridge` `TableRelay`: dispatch each verb to `SessionImpl` within the resolved room; map
   results/`TableView`; fake-upstream dispatch tests.
4. `:bridge` `CallbackMapper` `when` cases + per-callback table mappers; mapping-never-throws tests
   for each new event (and an unknown/changed callback → ignored).
5. `:bridge:test` + `:protocol:test` green in-container; `:app:assembleDebug` unaffected (no app
   change yet). Boundary check: no new `mage.*` outside `:bridge`.

## 6. Testing & verification

- **Hermetic (no server):** protocol serialize/deserialize round-trip for every new message + an
  unknown-`type` tolerance case; `TableRelay` dispatch over a fake `SessionImpl`/upstream (each verb
  → right method + mapped args → mapped result, including a `false`→failure and a `createTable`→
  `TableView` case); `DeckListMapper`/options mapper round-trip; `CallbackMapper` maps each new table
  event and **never throws** on a malformed/unknown callback.
- **Live (opt-in, against the 0022 reference server):** create a table, join it with a small deck,
  submit, start — observe the `MatchStarting` event; documented as a manual smoke (no gameplay).
- **Boundary:** a scan confirming `mage.*` appears only under `:bridge`.

## 7. Acceptance criteria

- [ ] `:protocol` carries table **actions** (create/join/submit/update/leave/remove/start/watch) with
      app-schema `CreateTableOptions` + a `DeckList`-equivalent, and the server-pushed table/seat/
      construct/sideboard/**match-starting** events — all round-tripping, unknown-tolerant, no `mage.*`.
- [ ] `:bridge` `TableRelay` dispatches each action to the correct `SessionImpl` method within the
      session's room and maps the result (`TableView` for create; typed ok/failure for the booleans).
- [ ] `CallbackMapper` maps the new table-lifecycle callbacks (through match-start) via per-callback
      mappers and **never throws** on unknown/changed payloads.
- [ ] `mage.*` (`MatchOptions`/`DeckCardLists`/`TableView`/`PlayerType`) stays **inside `:bridge`**;
      nothing new crosses onto the device.
- [ ] `:protocol:test` + `:bridge:test` green (in-container); `:app:assembleDebug` still green; no UI
      or app-client changes here. Prior suites green.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — `createTable`/`joinTable`/
  `submitDeck`/`updateDeck`/`leaveTable`/`removeTable`/`startMatch`/`watchTable`/`getMainRoomId`.
- `../mage/Mage.Common/.../mage/game/match/MatchOptions.java`, `.../mage/cards/decks/DeckCardLists.java`,
  `.../mage/view/TableView.java`, `.../mage/interfaces/callback/ClientCallbackMethod.java` — the shapes to map.
- [`0027-lobby-data-relay-and-contract.md`](0027-lobby-data-relay-and-contract.md) — the read side + request/response contract this extends.
- [`0033-deck-model-storage-and-legality.md`](0033-deck-model-storage-and-legality.md) — the `Deck ↔ DeckCardLists`-equivalent the join/submit deck reuses.
- [`../architecture.md`](../architecture.md) — the bridge as the sole `mage.*` boundary.
