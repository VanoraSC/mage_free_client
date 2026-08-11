# 0051 — Game protocol & bridge relay

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0036 (table actions relay), 0039/0045 (live coverage), 0006 (callback mapper boundary)
- **Status:** ready

## 1. Objective

Carry a **live game** across the bridge. Epic 7 ends at `MatchStarting`, which today opens nothing.
This story adds the `:protocol` contract and `:bridge` relay for in-game play: the server's game
snapshots and prompts mapped to app-schema events, and the player's replies relayed back. **No app
client and no UI** — those are 0052 and the UI stories.

## 2. Context & background

- **The shape of XMage's in-game protocol** (read at the pinned ref): it is **"the server asks a typed
  question, the client answers with a primitive."**
  - **Server → client** (`ClientCallbackMethod`): `GAME_INIT`, `GAME_UPDATE`,
    `GAME_UPDATE_AND_INFORM`, `GAME_INFORM_PERSONAL`, `GAME_ERROR`, `GAME_OVER`, `GAME_REDRAW_GUI`,
    `WATCHGAME`, plus the **prompts**: `GAME_SELECT`, `GAME_TARGET`, `GAME_ASK`, `GAME_CHOOSE_ABILITY`,
    `GAME_CHOOSE_PILE`, `GAME_CHOOSE_CHOICE`, `GAME_PLAY_MANA`, `GAME_PLAY_XMANA`, `GAME_GET_AMOUNT`,
    `GAME_GET_MULTI_AMOUNT`.
  - **Client → server** (`SessionImpl`): `sendPlayerUUID/Boolean/Integer/String/ManaType(gameId, …)`,
    `sendPlayerAction(PlayerAction, gameId, data)`, `joinGame(gameId)`, `watchGame(gameId)`,
    `quitMatch(gameId)`, `stopWatching(gameId)`.
- **One payload type for everything.** Every game callback carries a `mage.view.GameClientMessage`:
  `gameView`, `cardsView1`, `cardsView2`, `message`, `flag`, `targets`, `min`, `max`, `options`,
  `choice`, `messages`. So a prompt is *the current state plus what is being asked*.
- **State is a snapshot, not a delta.** Each callback carries the full `GameView` (players, `myPlayerId`,
  `myHand`, `canPlayObjects`, stack, exiles, revealed, combat, phase, priority/buffer time). The client
  never reconciles deltas — a significant simplification.
- **The server owns the rules.** `GameView.canPlayObjects` tells us what is legally playable. We do
  **not** reimplement Magic's rules; a rules engine would dwarf everything built so far.
- **The pattern is proven.** 0036 did exactly this for tables (`TableRelay` + `CallbackMapper` cases +
  `SessionCoordinator` dispatch), and 0039/0045 verified it live. This story follows that shape.

## 3. Scope

**In scope**
- **`:protocol` `GameMessages.kt`** — app-schema, pure Kotlin, no `mage.*`:
  - a **game state** payload mapped from `GameView` (players with life/library/graveyard counts, the
    viewer's hand, battlefield per player, stack, phase/step, active + priority player, and the
    playable-object ids from `canPlayObjects`);
  - **events**: `GameStarted`/`GameStateUpdated`/`GameInformed`/`GameError`/`GameOver`;
  - **prompts** as a discriminated set carrying what the UI must render and what a valid reply is —
    select / target / ask (yes-no) / choose-ability / choose-pile / choose-choice / play-mana /
    play-x-mana / get-amount / get-multi-amount — each with its `message`, candidate ids/`targets`,
    and `min`/`max` where applicable;
  - **replies**: the five `sendPlayerX` shapes plus `PlayerActionRequest` (pass priority, concede, …),
    `JoinGame`, `WatchGame`, `QuitMatch`, `StopWatching`. `requestId`-correlated like 0036's actions.
- **`:bridge` `GameRelay`** (sibling of `TableRelay`) — dispatch each reply to the matching
  `SessionImpl` method; map results as 0036 does (typed failure, never a silent drop), including the
  `SESSION_GONE` distinction story 0050 added.
- **`:bridge` `CallbackMapper` extension** — a `when` case per game callback, each via a small
  per-callback mapper over `GameClientMessage` (mirroring `mapping/table/*`), and a
  `GameView → protocol` mapper. **Mapping must never throw** (the 0006/0026-F5 invariant).
- **Reachability (standard 2):** for every field the app will gate on — whose turn it is, whether the
  viewer has priority, what is playable — state which callback/`GameView` field produces it.
- Tests mirroring 0036: protocol round-trip for every message + unknown-type tolerance; relay dispatch
  over a fake `SessionImpl`; callback mapping per game event incl. never-throws; `GameView` mapping.

**Out of scope**
- The app-side `GameClient`/`GameState` (**0052**) and any UI.
- Tournaments/draft (`DRAFT_*`, `TOURNAMENT_*` callbacks) — EPIC-08.
- Reimplementing any rules logic. Spectating beyond relaying `WATCHGAME`/`watchGame`.

## 4. Design & approach

- **Mirror 0036 exactly** — it is the working precedent and keeps the `mage.*` boundary intact: every
  `mage.view.*` read happens inside `magefree.bridge.mapping`, nothing upstream crosses the wire.
- **Map the whole `GameView` once**; prompts reference it rather than duplicating state.
- **Prefer a closed prompt set** over a generic "server asked something" blob: the UI must know what a
  valid reply looks like, and a typed set is what makes 0052's client and the UI honest.
- `GAME_REDRAW_GUI` is a desktop-client concern; map it to nothing (documented) unless it proves to
  carry state we need.

## 5. Implementation steps

1. `:protocol` game state + events + prompts + replies; register in `ProtocolJson`; round-trip tests.
2. `:bridge` `GameView` → protocol mapper (+ per-callback mappers); mapping tests incl. never-throws.
3. `:bridge` `GameRelay` + dispatch wiring through `SessionCoordinator` (as 0036's actions are wired).
4. In-container `:protocol:check` + `:bridge:check` green.
5. Extend the live `TableRelayIT` (or a sibling `GameRelayIT`): after `startMatch`, `joinGame` and
   assert a real `GAME_INIT`/state arrives with the viewer's hand populated — the first proof a real
   game crosses the bridge.

## 6. Testing & verification

- **Hermetic:** protocol round-trip + unknown tolerance; relay dispatch per verb over a fake session;
  a mapper test per game callback; `GameView` → protocol mapping over a crafted view; mapping never
  throws on a malformed/unknown payload.
- **Live (opt-in, the reference server):** start a match against an AI seat, `joinGame`, and assert a
  real game state arrives (hand of 7, a turn/phase, a priority holder). This is the story's real proof;
  a hermetic pass alone would repeat the mistake that hid earlier defects.

## 7. Acceptance criteria

- [ ] `:protocol` carries app-schema game **state**, **events**, a typed **prompt** set, and the
      **replies** (the five `sendPlayerX` shapes + player action + join/watch/quit/stop) — all
      round-tripping, unknown-tolerant, no `mage.*`.
- [ ] `:bridge` maps every in-scope `GAME_*` callback via per-callback mappers that **never throw**,
      and `GameRelay` dispatches each reply to the right `SessionImpl` method with typed results.
- [ ] `mage.*` stays inside `:bridge`; nothing upstream crosses the wire.
- [ ] The reachability answer is recorded for turn/priority/playability.
- [ ] Live: after a real match starts, `joinGame` yields a real game state with the viewer's hand.
- [ ] `:protocol:check` + `:bridge:check` green in-container; prior suites green; no app or UI change.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/interfaces/callback/ClientCallbackMethod.java` — the callback set.
- `../mage/Mage.Common/src/main/java/mage/view/{GameClientMessage,GameView,PlayerView,CardsView}.java` — the payloads to map.
- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — `sendPlayerX`, `sendPlayerAction`, `joinGame`, `watchGame`, `quitMatch`, `stopWatching`.
- [`0036-table-actions-protocol-and-bridge-relay.md`](0036-table-actions-protocol-and-bridge-relay.md) — the precedent this mirrors.
