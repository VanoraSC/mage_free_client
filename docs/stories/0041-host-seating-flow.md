# 0041 — Host seating flow

- **Epic:** EPIC-07 — Hosting & Joining Tables (defect fix)
- **Depends on:** 0040 (real seat state), 0037 (`TableClient`), 0033/0035 (deck library + legality)
- **Status:** ready

## 1. Objective

Make **hosting actually work**. Today the host flow calls `createTable` and stops: the host holds no
seat, submits no deck, and the AI seats they configured are never occupied — so the table can never
become ready and the match can never start. Complete the flow to match what XMage itself does: seat
the configured AI players, then seat the host with a chosen deck, and tear the table down if any of
that fails.

## 2. Context & background

- **The defect (audit + live, 2026-08-07, verified):**
  - `joinTable` is invoked from **exactly one place** in the app — `JoinTableViewModel`. The host path
    (`HostTableViewModel.create` → `TablesRoutes` → room) never calls it.
  - `HostTableScreen` has **no deck picker** — only a deck *type* chip (a label like
    "Constructed - Freeform Unlimited"), which is a table setting, not a deck.
  - In the room, `showPlayerActions` is `role == TableRole.Player`, so a **Host is never offered the
    submit-deck surface either** — a host's only controls are Start (see 0040) and Remove.
  - **Live proof:** 0039's `TableRelayIT` asserts a freshly created table has `seatsFilled = 0` — the
    server does **not** auto-seat the creator.
- **What upstream does** (`Mage.Client/.../dialog/NewTableDialog.java:511-546`, read at the pinned ref):
  ```
  table = createTable(roomId, options)                       // null → error, stop
  for each non-HUMAN seat:  joinTable(roomId, tableId, name, playerType, skill, deck, "")
                            // on failure → removeTable(...) and abort
  joinTable(roomId, tableId, playerName, HUMAN, 1, deckFromFile, password)
                            // success → close dialog; failure → removeTable(...)
  ```
  Both AI and human seats are filled with the **same** `joinTable` call, differing by `PlayerType` —
  and `TableRelay.joinTable` already accepts a `playerType`, so **no bridge change is needed**.
- **Also confirmed live (0039):** `Table.getNextAvailableSeat` matches a join to a seat **by player
  type**, so the AI join must pass its AI type and the host's must pass `HUMAN`; the "join only once"
  guard applies only to `HUMAN`, which is why one session can legitimately fill both seats.

## 3. Scope

**In scope** (`:feature:tables`; no protocol/bridge change)
- **A deck on the host form** — reuse the join flow's offline `DeckPicker` + `LegalitySummary`
  (`DeckRepository` + `DeckLegality`, both local) so the host chooses and validates a deck **before**
  creating, with the same "no decks → build one" path. Deck choice stays offline.
- **The full create sequence**, mirroring upstream: `createTable` → for each configured non-human seat
  `joinTable(..., thatSeatsPlayerType, ...)` → `joinTable(..., HUMAN, ..., password)` for the host →
  only then open the room. Any failure **removes the table** and surfaces the reason, so a half-seated
  table is never left behind on the server.
- **Host actions in the room** — a host occupies a seat, so offer the seat controls (submit/update
  deck) to Host as well as Player, keeping Remove for the host and Leave for a player.
- **AI deck** — an AI seat also submits a deck at join. Use the host's chosen deck (or a documented
  minimal one) and state the choice explicitly.
- Tests over fakes: the ordered call sequence (create → AI joins → self join), the failure paths
  (create declines; an AI join fails; the host join fails) each **removing the table** and surfacing
  the reason, the legality gate, and the password path.

**Out of scope**
- Seat display / start-gating — **0040**.
- Gameplay past match-start (EPIC-11); tournaments (EPIC-08); swapping seats or kicking players.
- Any `:protocol`/`:bridge` change (the relay already supports everything needed).

## 4. Design & approach

- **Mirror upstream's order and its cleanup.** The removeTable-on-failure is not optional politeness:
  without it a failed host flow leaves an orphan table in the lobby that nobody can start or remove.
- **One `Deck` end-to-end** — the same 0033 domain `Deck` the join flow submits; mapping to the wire
  stays inside 0037's client.
- Keep the flow in the ViewModel (testable over a fake `TableClient`), not in the screen.

## 5. Implementation steps

1. Add the deck picker + legality to the host form (reuse the join components); gate Create on a
   picked, legal deck.
2. Implement the ordered create→seat sequence in `HostTableViewModel`, with removeTable-on-failure and
   a surfaced reason.
3. Offer the seat (deck submit/update) controls to the Host role in the room.
4. Tests over fakes for the sequence and each failure path.
5. `:feature:tables:check` + `:app:testDebugUnitTest` + `:app:assembleDebug` green; prior suites green.

## 6. Testing & verification

- **Hermetic:** fake `TableClient` records the ordered calls; assert AI seats are joined before the
  host's, that the host's join carries the chosen deck and password, and that each failure path calls
  `removeTable` exactly once and reports the reason. Legality gate + "no decks" path covered.
- **Live (opt-in):** host a table from the app against the reference server, confirm the lobby shows
  `seatsFilled == seatsTotal` and the room's Start enables (with 0040), and the match starts.

## 7. Acceptance criteria

- [ ] Hosting seats the **configured AI players and the host**, in that order, each submitting a deck —
      so a hosted table reaches the server's ready state.
- [ ] The host **picks a deck** (offline, with legality feedback) before the table is created; the
      "no legal deck → build one" path is offered.
- [ ] Any failure in the sequence **removes the table** and surfaces the server's reason — no orphan
      tables.
- [ ] A host can submit/update their deck from the room (they occupy a seat).
- [ ] Tests assert the ordered sequence and every failure path over fakes; all prior suites green.

## 8. References

- `../mage/Mage.Client/src/main/java/mage/client/dialog/NewTableDialog.java:511-546` — the upstream sequence this mirrors.
- [`0040-table-seat-state.md`](0040-table-seat-state.md) — seat display + start gating (companion fix).
- [`0039-live-table-action-coverage.md`](0039-live-table-action-coverage.md) — live proof that create does not seat the creator, and that AI-then-self joining works.
- [`0038-host-and-join-tables-ui.md`](0038-host-and-join-tables-ui.md) — the flow being completed.
