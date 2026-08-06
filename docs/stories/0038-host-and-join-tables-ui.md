# 0038 — Host & join tables UI

- **Epic:** EPIC-07 — Hosting & Joining Tables
- **Depends on:** 0037 (table client + `TableState`), 0033/0035 (deck model + library to pick a deck), 0029 (lobby browser), 0018 (feature pattern), EPIC-03 (design system)
- **Status:** ready

## 1. Objective

The player-facing surface for Epic 7: **host** a table (pick format + match options + seats),
**join** an open table (choose a saved deck → submit), a **table room** (seats, ready state, start,
leave), and **spectate** — reachable by enabling the lobby's deferred **Join** (0029) and adding a
**Host** entry. Built on 0037's `TableClient`/`TableState`, reusing 0035's deck library for deck
selection. **Stops at match-start** (the `MatchStarting` signal hands off to EPIC-11's in-game view;
here it shows a "match starting" terminal state, no gameplay).

## 2. Context & background

- 0029's lobby renders tables with a **disabled** "Join — coming soon" button and detail sheet,
  explicitly deferring the action to this epic (`LobbyScreen.kt:284`). This story lights that up and
  adds hosting.
- 0037 gives, UI-free: `createTable/joinTable/submitDeck/updateDeck/leaveTable/removeTable/startMatch/
  watchTable` + `observeTable(tableId): Flow<TableState>` (seats/phase + `MatchStarting`). 0033/0035
  give the local **deck library** to choose a deck for join/submit (all offline until the actual
  submit call).
- Design system (EPIC-03): reuse `StateViews`, list/section chrome, `DecisionPrompt`, buttons; the
  lobby's `TableRow`/detail patterns; 0035's deck-picker components. UX (`../ux-principles.md`):
  phone-first, few taps to get seated, clear seat/ready state, obvious "you need a deck" path.
- **Deck submission:** joining a constructed table submits a deck at join (0037 maps a 0033 `Deck` to
  the wire); a table in the construction phase accepts submit/update. The UI picks a deck from the
  library and optionally shows its **live legality** (0033) for the table's format **before** joining.

## 3. Scope

**In scope** (a `:feature:tables` module — or a scoped extension of `:feature:lobby`; MVVM,
immutable `StateFlow<UiState>`)
- **Host a table** — a create-table screen: format + match options (rated, free-mulligan, seats/
  players, skill range, spectators, timeouts as 0037's `CreateTableOptions`) → `createTable` → land
  in the table room as host. Sensible defaults; validation.
- **Join a table** — from the lobby detail (enable the deferred button): pick a saved deck (0035
  library; show format-legality for the table's format), then `joinTable` (submit deck) → table room.
  Password prompt when the table is protected. Clear "no legal deck / build one" path.
- **Table room** — over `observeTable(tableId)`: the table's seats (player name/type, **ready** /
  deck-submitted), format/options summary, and actions by role — host: **start** (enabled when
  seats ready), remove; player: submit/update deck, **leave**; everyone: live seat updates. On
  `MatchStarting` show a terminal "match starting…" state (hand-off marker to EPIC-11).
- **Spectate** — a **watch** action (`watchTable`) from the lobby for in-progress/open tables → a
  read-only room view.
- Wire the shell: enable the lobby **Join**/detail actions + a **Host** affordance (minimal nav,
  shell/0029 behavior otherwise intact). Stateless, previewable Composables (light + dark).
- ViewModel tests (fakes over 0037's `TableClient` + 0033/0035 deck library): host (options→create),
  join (deck pick→submit, legality gate, password), room state (seat/ready/phase transitions from a
  scripted `TableState`, start enabled/disabled, leave), spectate, and the match-starting hand-off.

**Out of scope**
- **In-game** play — the game board, priority, actions, results (**EPIC-11**); this story ends at
  `MatchStarting`.
- **Tournaments / draft / sealed** hosting+joining and their lobbies (**EPIC-08**).
- New **protocol/bridge** or **client** logic (**0036/0037**); deck **construction**/legality/files
  (**0033–0035**) — all reused.
- Invites to **specific** players beyond what `SessionImpl`/0037 expose (best-effort; advanced
  invite management is a later refinement).

## 4. Design & approach

```
feature/tables/
├── host/    HostTableViewModel + HostTableScreen (options -> createTable)
├── join/    JoinTableViewModel + JoinTableScreen (deck pick + legality + submit)
├── room/    TableRoomViewModel + TableRoomScreen (observeTable: seats/ready/start/leave/spectate)
└── di/
```

- Screens stateless/previewable; ViewModels over 0037's `TableClient` + a fake deck library. Reuse
  0029 lobby row/detail, 0035 deck-picker + legality panel, design-system chrome.
- **Deck choice is offline** (0035 library + 0033 legality); only the **submit/create/start/watch**
  calls touch the network (via 0037). One `Deck` representation end-to-end.
- **Hand-off boundary:** `MatchStarting(gameId)` renders a terminal state + a marker EPIC-11 will
  replace with navigation into the game — no gameplay wired here.

## 5. Implementation steps

1. Create `:feature:tables` (conventions; deps `:core:network` (0037), `:core:decks`, `:feature:decks`
   or shared deck-picker, `:core:designsystem`); register in `settings.gradle.kts` (Android guard).
2. Host screen + VM: `CreateTableOptions` form (defaults/validation) → `createTable` → room.
3. Join flow + VM: deck pick (0035) + format legality (0033) + password → `joinTable` → room.
4. Table room + VM over `observeTable`: seats/ready/phase, host start (gated), submit/update, leave,
   spectate; `MatchStarting` terminal state.
5. Wire lobby Join/detail + a Host entry (enable 0029's deferred affordance; keep shell intact).
6. ViewModel tests (host/join/room/spectate/hand-off) via fakes; light+dark previews.
   `:feature:tables:check` + `:app:testDebugUnitTest` + `:app:assembleDebug` green; prior suites green.

## 6. Testing & verification

- **Hermetic gate (fakes, offline except the mocked client):** host options→`createTable`; join deck-
  pick→`joinTable` incl. a legality-gated and a password case; room VM folds a scripted `TableState`
  (seat joins, ready toggles, host-start enabled/disabled, leave) and reaches the `MatchStarting`
  terminal state; spectate→`watchTable`. Compose previews compile (light+dark). No device, no server.
- **Live (opt-in, 0022 server):** host a table, join from a second session with a saved deck, ready,
  start — the room reaches match-starting; spectate an open table. Documented manual smoke (no
  gameplay).

## 7. Acceptance criteria

- [ ] A player can **host** (format + options + seats → create → table room) and **join** (pick a
      saved deck, see its legality for the format, submit → table room), with a password prompt for
      protected tables and a clear "build a legal deck" path.
- [ ] A **table room** over `observeTable` shows seats + ready/deck-submitted + format/options, offers
      role-appropriate actions (host **start** when ready / remove; player submit/update / **leave**),
      and reaches a **"match starting"** terminal state on `MatchStarting` (EPIC-11 hand-off) — no
      gameplay here.
- [ ] **Spectate** works via `watchTable` (read-only room). The lobby's deferred **Join** is enabled
      and a **Host** entry added; shell/0029 behavior otherwise unchanged (their tests green).
- [ ] Deck selection/legality is **offline** (0033/0035); only create/join/submit/start/watch touch
      the network (via 0037). One `Deck` representation; no `:protocol`/`mage.*` in the feature.
- [ ] ViewModel tests cover host/join(+legality+password)/room(seat/ready/start/leave)/spectate/hand-
      off via fakes; previews compile; `:feature:tables:check` + `:app:testDebugUnitTest` +
      `:app:assembleDebug` green; prior suites green.

## 8. References

- [`0037-table-client-and-session-api.md`](0037-table-client-and-session-api.md) — the `TableClient` + `TableState` this renders.
- [`0029-lobby-browser-ui.md`](0029-lobby-browser-ui.md) — the lobby whose deferred Join this enables.
- [`0035-deck-library-and-builder-ui.md`](0035-deck-library-and-builder-ui.md) — the deck library/picker + legality reused for join/submit.
- [`../ux-principles.md`](../ux-principles.md) — phone-first hosting/joining. [`../architecture.md`](../architecture.md) — offline deck, networked table actions.
