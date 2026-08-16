# 0069 — Rejoin a table whose game already started

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0055 (board rendering), 0057 (board interaction)
- **Status:** ready

## 1. Objective

Fix a defect found live (Pete, 2026-08-16, on-device): leave a table's room screen after its match has
started — back to the lobby, or the app is relaunched — and there is **no way back into your own active
game**. The lobby still lists the table as full (2/2) and lets you tap it, but tapping leads nowhere
playable.

## 2. Root cause

The only path from a table into its game board is a `LaunchedEffect` in `MageNavHost` keyed on
`TableRoomUiState.matchStarting?.gameId` (`app/.../navigation/MageNavHost.kt:217-224`). That field is
folded from `MatchStarting`, which `TableState.kt:30` and `TableEventFold.kt:39` both document as a
**one-shot** push: it fires once, at the instant XMage's `START_GAME` event crosses the wire, and is
never resent.

So the hand-off works exactly once, for whichever client happens to be sitting in the table room at that
instant. A client that opens (or re-opens) the room *after* that moment — because it navigated away and
came back, or the app restarted — gets a **fresh** `observeTable` subscription. That subscription's
`TableEventFold` state starts with `matchStarting = null`, and nothing ever sets it again: the effect
never fires, and the room has no other way to learn the game exists.

**The fix has a cheap source.** `mage.view.TableView` (`e0fe4b6f6a`, the pinned server ref) already
carries every game the table's match has played, in order:

```java
private List<UUID> games = new ArrayList<>();
...
for (Game game : table.getMatch().getGames()) {
    games.add(game.getId());
}
...
public List<UUID> getGames() { return games; }
```

`games.last()` is the current (or most recently started) game, for a single game or a best-of-N match
alike. This is carried on **every** `GetTable` reply, not just the one that happens to catch the
transition — and `DefaultTableClient.observeTable` already performs one such read unconditionally when a
room opens (`DefaultTableClient.kt:219-220`, `"Seed the seats: read the table once as the room opens"`),
**regardless of `TableState.isPastSeating`**. So the data needed to fix this is already arriving; nothing
reads it.

## 3. Scope

**In scope**
- **Bridge:** `TableMapper.mapDetail` reads `TableView.getGames()` and carries its last element (the
  current game id, or `null` if the match has not produced a game yet) into `TableDetail`.
- **`:protocol`:** `TableDetail` gains an `activeGameId: String? = null` field.
- **`:core:network`:** `OptionsMapper.toDetails()` carries it through; `TableDetails` gains
  `activeGameId: String?`; `TableState` gains the same, set by `withDetails()`.
- **`:app` navigation:** the `LaunchedEffect` in `MageNavHost` that opens `GameBoardNavRoute` triggers on
  **either** `matchStarting?.gameId` (the live push, unchanged) **or** `table.activeGameId` (the read) —
  whichever is known. Both name the same thing: the game to open.
- The room still shows the "match starting…" terminal view (`TableRoomScreen.kt`) as it does today; the
  fix is only in *what makes the effect fire*, not the room's own UI.

**Out of scope**
- The lobby's `TableDetailDialog` (`feature/lobby/.../LobbyScreen.kt:290,299,318-326`) offers only
  **Watch** for a full table, even when it is the viewer's own seat — mislabeled, but not blocking:
  `GameBoardViewModel` always calls `gameClient.joinGame(gameId)` regardless of which label got you to
  the room (`GameBoardViewModel.kt:203`), never `watchGame`, so tapping the mislabeled "Watch" on your
  own table still rejoins you as a player once this story's fix lands. Relabeling it is a separate,
  smaller UX story if Pete wants it.
- Any change to how `MatchStarting`/the live push itself works.
- Sideboarding, multi-game matches beyond "which game is current" (already handled for free by taking
  `games.last()`), match end.

## 4. Constraints already verified — do not rediscover

- `TableView.getGames()` — read directly from `mage.view.TableView` source at the pinned ref
  (`e0fe4b6f6a`), not decompiled or inferred. It is populated from `table.getMatch().getGames()` inside
  the constructor, so it reflects whatever the match holds at read time.
- `DefaultTableClient.observeTable` already reads the table once, unconditionally, when a room opens —
  confirmed by reading the implementation, not assumed. No new read/poll is needed; only a new field on
  the reply already fetched.
- `GameBoardViewModel` always joins (`joinGame`), never watches, regardless of table role — confirmed by
  reading `GameBoardViewModel.kt:203`. The room's `TableRole` (Player/Spectator) does not gate what the
  game board does once it has a `gameId`.

## 5. Verification

- **Standard 1:** a test proving the actual bug — a `TableState` fold that never receives `MatchStarting`
  but does receive a `TableDetails` with `activeGameId` set must still resolve to a navigable game id.
  A test that only exercises the `matchStarting` path would pass against the unfixed code.
- **Standard 2 (reachability):** name what produces `activeGameId` end to end — `TableView.getGames()` →
  `TableMapper.mapDetail` → `TableDetail.activeGameId` → `OptionsMapper.toDetails()` →
  `TableDetails.activeGameId` → `TableState.withDetails()`.
- **Standard 5:** confirm live that a `GetTable` read against an **already-Dueling** table (not one
  caught mid-transition) actually returns a non-empty `games` list — the source reads clearly, but this
  crosses the bridge/wire boundary and must be proven, not assumed.
- **Hermetic gate**, unit tests for each mapping layer (bridge `TableMapperTest`, `:core:network`
  `OptionsMapper`/`TableState` fold tests) plus a `MageNavHost`-level or `TableRoomViewModel`-level test
  proving the effect fires from a read alone.
- **Live**, through the real client: start a table + AI match, let it begin, back out to the lobby,
  re-open the table, confirm the game board opens with a playable state (not spectator-only, not stuck
  on the room).
- **Eyes-on (standard 3) — hand Pete this checklist.** Do **not** drive the UI programmatically.
  1. Host a table against an AI, start the match, confirm the board opens as usual (the existing
     one-shot path — must still work, unchanged).
  2. From the live game, navigate back to the lobby (or kill and relaunch the app).
  3. Find the same table in the lobby (still shown 2/2 or however "in progress" renders) and open it.
  4. Confirm you land back in a **playable** game board — your hand, your priority when it is yours —
     not a spectator view, not a stuck "match starting…" room.

## 6. Acceptance criteria

- [ ] A table already in progress carries its current `gameId` on a table read, not only on the one-shot
      live push.
- [ ] Re-opening a table whose match has already started (from the lobby, or after an app relaunch)
      lands the player back in their own playable game.
- [ ] The original live-push path (in the room when the match starts) is unchanged and still works.
- [ ] No regression to `MatchStarting`'s existing one-shot behaviour.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `bridge/.../mapping/TableMapper.kt` — `mapDetail`, where `activeGameId` is added.
- `protocol/.../TableMessages.kt` — `TableDetail`.
- `core/network/.../table/OptionsMapper.kt` — `toDetails()`.
- `core/network/.../table/TableState.kt` — `TableDetails`, `TableState`, `withDetails()`.
- `core/network/.../table/DefaultTableClient.kt` — `observeTable`'s unconditional open-time read.
- `app/.../navigation/MageNavHost.kt:204-246` — the `LaunchedEffect` this story extends.
- `feature/game/.../board/GameBoardViewModel.kt:203` — confirms `joinGame` is unconditional.
- `mage.view.TableView` at `e0fe4b6f6a` (`Mage.Common/src/main/java/mage/view/TableView.java`) —
  `getGames()`, read directly from source.
