# 0054 — Bridge game-state cache & query

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0051 (game protocol & bridge relay), 0052 (game client & state), 0023 (session hold/resume)
- **Status:** ready — **required for the initial release, before the board UI**

## 1. Objective

Let a reconnecting client **ask for the current board state** instead of waiting for the server to push
one. XMage offers no way to read game state, so today a client that drops is blind until something
happens in the game — which may be minutes, or never if the opponent is thinking. The bridge already
sees every snapshot, so it can hold the latest and answer the question.

## 2. Context & background

- **The constraint (verified, stories 0051/0052).** Upstream has **no "get game" verb**, and
  **re-joining a running game does not resync** — `GameController.join` on an already-running game only
  logs "rejoined" (`gameFuture != null`, so `startGame()`/`init()` never re-fire). The board is
  therefore **push-only**: `observeGame` has no read-on-open, no post-resume read, and no poll, and
  `observeGameNeverIssuesARequestOfItsOwn` pins that so nobody adds a poll against a request the bridge
  cannot answer.
- **Why the bridge can fix it.** Two facts make this cheap and safe:
  1. **The bridge sees every game callback** — it is the mapper boundary, so every snapshot passes
     through it.
  2. **State is a full snapshot, not a delta** (0051) — so *keeping the most recent one* is sufficient.
     There is no log to replay or deltas to reconcile.
- **It already stays fresh while the app is away.** Story 0023 parks a session and keeps pumping
  upstream events into a durable buffer; story 0050 made the keepalive run for a session's whole life.
  So the cached snapshot continues to advance during a disconnection rather than freezing at the moment
  the socket died.
- **Design decision (Pete, board design session, §10.1 of `docs/game-board-requirements.md`):**
  *"The bridge needs to support the client reconnecting and the bridge should act as a proxy for the
  board state making it queryable."* Confirmed as **initial-release scope**, because the alternative is
  a board that must either show stale data or show nothing.

## 3. Scope

**In scope**
- **`:protocol`** — an additive request/reply for the current game state
  (`GetGameState(gameId)` → the same app-schema game state 0051 already defines, or a typed
  not-found/not-in-game). `requestId`-correlated like every other read.
- **`:bridge`** — cache the most recent mapped game state **per session, per game**, updated as
  callbacks pass through the existing mapper, and answer the new request from it.
  **Per session is not optional:** `GameView` is built *for a specific player* (`myPlayerId`, the
  viewer's own hand, and `canPlayObjects` only for the priority holder), so one shared cache would hand
  a player another player's view. Cache what *that* session was sent.
- **`:core:network`** — a `GameClient` read (e.g. `refreshGame(gameId)`), and `observeGame` using it at
  the points it currently cannot: **on open** and **after a resume/reconnect**. This is the seam
  `observeGameNeverIssuesARequestOfItsOwn` deliberately closed — that test must be **updated, not
  deleted**, to assert the new, intentional read rather than "never reads".
- **Lifecycle:** the cache is dropped when the game ends or the session is evicted — it must not
  outlive the thing it describes.

**Out of scope**
- **Any inference or accumulation of knowledge over time** — that is story **0053** (post-release).
  This story caches **the latest snapshot, verbatim**; it never remembers what a card *was*, never
  merges older snapshots, and never reconstructs history.
- Board UI (a later story consumes this).
- Changing XMage or the pinned ref.

## 4. Design & approach

- **Replay, never fabricate.** The reply is the server's own most recent snapshot for that session,
  unchanged. If nothing has been received yet, the honest answer is a typed "no state yet", not an
  empty board — an empty board is indistinguishable from a real one and would be read as truth.
- **Staleness is bounded and knowable.** The cached snapshot is current as of the last push. Consider
  carrying *when* it was captured so the client can reason about it rather than guess.
- **Reachability (standard 2):** state plainly what produces the cached value (the mapped snapshot from
  the game callback path) and what invalidates it (game over, session eviction).
- **Unexpectedly absent (standard 5):** confirm the cache is genuinely populated in a live game before
  building the client on it — a cache that is always empty would present exactly like this feature
  working, right up until a real reconnect.

## 5. Implementation steps

1. `:protocol` request/reply + correlation; round-trip and unknown-tolerance tests.
2. `:bridge` per-session cache fed from the existing game mapper path; handler for the request;
   invalidation on game end and eviction.
3. `:core:network` read verb; `observeGame` reads on open and after resume; update the
   never-reads test to assert the new intent.
4. In-container `:protocol:check` + `:bridge:check`; host `:core:network:check`.
5. Live proof (below).

## 6. Testing & verification

- **Hermetic:** protocol round-trip; the bridge answers from the cache after a snapshot has passed
  through, and returns the typed not-found before any has; the cache is per-session (two sessions in
  one game get *their own* views, not a shared one); invalidation on game end/eviction; the client
  reads on open and after a resume.
- **Live — the proof that matters.** With a game in progress: **drop the client's socket without
  signing out** (`LiveBridge.dropWithoutSigningOut()` already exists for exactly this), reconnect, and
  assert the board is populated **without waiting for the opponent to act**. That is the scenario the
  feature exists for and the one that fails today.
- Each new test demonstrated **failing first** (standard 1).

## 7. Acceptance criteria

- [ ] A reconnecting client can obtain the current game state **on demand**, without waiting for a push.
- [ ] The cache is **per session, per game** — a session is never served another player's view.
- [ ] Before any snapshot exists the reply is a **typed "no state"**, never an empty board.
- [ ] The cache is dropped on game end and session eviction.
- [ ] `observeGame` reads on open and after a resume; the previous "never reads" test is updated to
      assert the new intent rather than removed.
- [ ] Live: a client dropped mid-game and reconnected shows the board **before** the opponent acts.
- [ ] No knowledge accumulation or inference (that is 0053); the cached state is the server's own
      snapshot, verbatim.

## 8. References

- `docs/game-board-requirements.md` §10.1 — the design decision, and §0 for the push-only constraint.
- [`0051-game-protocol-and-bridge-relay.md`](0051-game-protocol-and-bridge-relay.md) / [`0052-game-client-and-state.md`](0052-game-client-and-state.md) — the snapshot contract and the client this extends.
- [`0023-bridge-session-hold-and-resume.md`](0023-bridge-session-hold-and-resume.md) — why the cache keeps advancing while the app is away.
- [`0053-bridge-known-information-tracking.md`](0053-bridge-known-information-tracking.md) — the **post-release** successor; deliberately not this story.
