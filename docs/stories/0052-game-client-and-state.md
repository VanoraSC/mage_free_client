# 0052 — Game client & state

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0051 (game protocol & bridge relay), 0037 (`TableClient` precedent), 0045 (live harness), 0050 (session liveness)
- **Status:** ready

## 1. Objective

The app-side, **UI-free** game layer in `:core:network`: a `GameClient` that joins/watches a game,
exposes an observable **`GameState`**, surfaces the server's **prompt** as typed app-schema state, and
sends the player's replies. Verified against a **real game** before any board is drawn. No UI — that
is the following stories.

## 2. Context & background

- **Data-first is a deliberate sequencing decision (Pete, 2026-08-11).** The layers below the UI are
  where correctness is cheap to establish; the UI is where unverified assumptions hide. Epic 7 proved
  this twice — 0036/0037 landed before 0038, and 0045 validated the whole client stack with no UI at
  all, catching defects fakes could not.
- **What 0051 delivers to consume:** app-schema game state mapped from `GameView` (players, viewer's
  hand, battlefields, stack, phase/step, active + priority player, playable object ids), lifecycle
  events (`GameStarted`/`GameStateUpdated`/`GameInformed`/`GameError`/`GameOver`), a typed prompt set,
  and the reply messages.
- **State is a snapshot.** Every game callback carries the full view, so the client **replaces** state
  rather than reconciling deltas.
- **The server owns the rules.** `canPlayObjects` says what is legally playable; the client must not
  infer legality. If a UI later needs "can I play this?", the answer comes from the server's list.
- **The precedent:** 0037's `TableClient` — erased request/response over `BridgeClient`, `:protocol`
  types confined to `internal` impls, an app-schema state folded from pushed events, re-sync across a
  0023 resume, and a public `:protocol`-free factory. Mirror it.

## 3. Scope

**In scope** (all in `:core:network`, no UI)
- **`GameClient`**: `joinGame(gameId)`, `watchGame(gameId)`, `quitMatch(gameId)`, `stopWatching(gameId)`,
  and the reply surface — one method per prompt kind, each accepting only what that prompt can validly
  answer (a chosen id, a boolean, an amount, a mana type, a string), plus `passPriority()`/`concede()`
  via player action. Results as `Result`/typed failure, honoring 0050's `SESSION_GONE` distinction.
- **`observeGame(gameId): Flow<GameState>`** — an app-schema `GameState` (players with life/zone
  counts, the viewer's hand, battlefields, stack, phase/step, whose turn, who has priority, playable
  ids, and a terminal result when the game ends) replaced on each snapshot, plus the **current prompt**
  (or none). Seeded on join; **re-syncs across a 0023/0024 resume** as 0037's table subscription does.
- **`:protocol` stays off the public ABI** — `:protocol`-typed impls `internal`, a `:protocol`-free
  public factory (0037's `TableClients.overBridge` is the pattern).
- **Reachability (standard 2):** record, for each thing a UI will gate on — "is it my turn", "do I have
  priority", "can I play this card", "is there a prompt" — exactly which server-produced field yields
  it. No UI-facing flag may be derived from something nothing produces.
- Tests over fakes: join/watch/quit; each reply mapped to the right wire message; a **pure fold** of a
  scripted event sequence into `GameState` (game start → turn/phase changes → a prompt appears and
  clears → game over); prompt typing; resume re-sync; `SESSION_GONE` surfacing.

**Out of scope**
- Any UI (the board and interaction are the following stories).
- Rules logic of any kind — legality comes from the server.
- Tournaments/draft (EPIC-08). Deck construction (Epic 9).

## 4. Design & approach

- **Mirror 0037.** Same seams, same discipline: erased request/response, `internal` `:protocol` impls,
  a pure fold that is unit-testable without the client, and a public factory for tests to drive the
  real client (0045's seam test depended on that).
- **Snapshot replace, not merge** — simpler and matches the server's model. If a field the UI needs is
  absent from a snapshot, that is a mapping gap to fix in 0051, not something to patch by remembering
  stale values.
- **One prompt at a time**: model the current prompt as state, not a queue, unless the live run shows
  the server can have several outstanding.

## 5. Implementation steps

1. `GameState` + the pure fold over 0051's events; unit tests over scripted sequences.
2. `GameClient` interface + impl over `BridgeClient`; join/watch/quit + the reply methods; mapping tests.
3. `observeGame` (seed + fold + resume re-sync); Turbine tests.
4. Additive DI + a `:protocol`-free factory; fakes for downstream stories.
5. Gates: `:core:network:check` + `:app:assembleDebug`; then the live test below.

## 6. Testing & verification

- **Hermetic:** the fold over a scripted game (start → phases → prompt → prompt cleared → over);
  reply-to-wire mapping per prompt kind; resume re-sync; `SESSION_GONE`. Each new test demonstrated
  **failing first** where it asserts new behaviour (standard 1).
- **Live (the story's real proof, via 0045's harness):** host a table against an AI seat, start the
  match, **join the game with the real `GameClient`**, and assert a real `GameState` arrives — a hand
  of 7, a turn and phase, an identified priority holder — then pass priority and observe the state
  advance. This is the first end-to-end proof that a real game reaches the app, and it must pass before
  any board is drawn.

## 7. Acceptance criteria

- [ ] `GameClient` joins/watches/quits a game and sends every reply kind, with typed failures
      (incl. `SESSION_GONE`); no `:protocol` on the public ABI.
- [ ] `observeGame` exposes an app-schema `GameState` (zones, phase, turn, priority, playable ids,
      current prompt, terminal result) replaced per snapshot, and re-syncs across a resume.
- [ ] Every UI-facing field has a recorded server-produced source; no derived flag lacks a producer.
- [ ] Legality is never inferred client-side — playable ids come from the server.
- [ ] Live: a real game against an AI reaches the app with a hand of 7 and an identified priority
      holder, and passing priority advances the state.
- [ ] `:core:network:check` + `:app:assembleDebug` green; prior suites green; no UI in this story.

## 8. References

- [`0051-game-protocol-and-bridge-relay.md`](0051-game-protocol-and-bridge-relay.md) — the contract this consumes.
- [`0037-table-client-and-session-api.md`](0037-table-client-and-session-api.md) — the client pattern to mirror.
- [`0045-app-to-bridge-live-integration.md`](0045-app-to-bridge-live-integration.md) — the live harness to extend.
