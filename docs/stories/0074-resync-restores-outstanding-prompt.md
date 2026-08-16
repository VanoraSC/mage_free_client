# 0074 — A resync must be able to restore the outstanding prompt, not just the board

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0054 (bridge game-state cache), 0070 (game-state cache survives session churn),
  0071 (offer Pass when priority is held but no fresh prompt exists)

## 1. Objective

Fix a defect found live (Pete, 2026-08-16, immediately after 0071 merged): exiting during the
**mulligan decision** and rejoining leaves the player stuck — no controls, no way to proceed. This
is the same family of bug as 0070/0071, but neither of those fixes covers it, and the reason why is
itself the actual defect this story fixes.

## 2. Root cause — confirmed by reading the actual code, not assumed

Mulligan is a `GAME_ASK` → `AskPrompt` (`GamePromptMapper.kt:85`, `GamePrompt.kt:61`: *"a yes/no
question (a mulligan, an optional trigger, …)"*). Critically, **it is asked before priority
exists** — the exact same pre-priority timing already documented for "Select a starting player"
(`BoardUi.kt:382-390`, found on device previously): *"the very first thing a new game does is ask
one seat to choose who goes first, and it does that before priority exists. On that snapshot
`viewerHasPriority` is false while `prompt` is [the ask]."* Mulligan is asked the same way, the same
early.

Story 0071's fix only fires when `state.viewerHasPriority == true`. During a mulligan decision it is
`false` by construction — so 0071's fallback correctly does **not** apply here (it was never meant
to; the story explicitly scoped out every prompt type but priority). After a 0070-restored rejoin
during mulligan: `state.prompt` is `null` (a snapshot restore never carries `prompt`, unchanged since
0070/0071), `viewerHasPriority` is `false` — `controlsFor` returns `null`. No controls, exactly the
reported symptom.

**The deeper problem, found while tracing this:** the bridge already has everything needed to fix
this properly, and simply doesn't wire it through.

- `GamePrompted` (`GameMessages.kt:456-461`) — the live push a prompt actually arrives as — carries
  **both** `state: GameStateView` **and** `prompt: GamePrompt`.
- `GameStateCache.observe()` (`bridge/.../session/GameStateCache.kt:82-96`) handles `is GamePrompted
  -> put(message.gameId, message.state)` — it reads `message.state` and **discards `message.prompt`
  entirely**. The prompt that arrived alongside the cached state is thrown away at the exact moment
  it is captured.
- `GameStateSnapshot` (`GameMessages.kt:243-248`), the reply type `GetGameState` answers with, **has
  no prompt field at all** — there is nowhere for a cached prompt to go even if one were kept.
- `DefaultGameClient.readSnapshot`/`observeGame`'s `Intent.Snapshot` branch
  (`core/network/.../DefaultGameClient.kt:199-220,287-294`) calls `GameViewMapper.apply(state,
  intent.reply.state)` — passing only the state half, because that is all `GameStateSnapshot` has.

So a resync can restore the board but can **never** restore what the server is still waiting to be
told — for any prompt type, not just priority. Story 0071's fix is a correct, narrow client-side
patch for exactly one instance of this (the case where "the answer" is just Pass); this story fixes
the actual gap at its source, which also makes 0071's patch a redundant-but-harmless special case of
the general fix rather than the only coverage that exists.

**Why re-serving a stale-looking prompt is safe.** A prompt is one-shot and — per 0069/0070's own
confirmed finding — XMage never re-asks it on rejoin; nothing else can have consumed *this player's*
answer to *their own* prompt, since only their own client can send it. So the last prompt this
session was ever sent, if nothing newer has superseded it (a later `GamePrompted` with a different
prompt, a plain state push with none, or the game ending — all of which the existing cache-eviction
rules already handle), is still the live, correct, outstanding question. This is not a guess
reconstructed from state; it is the literal prompt the server sent and is still waiting on.

## 3. Scope

**In scope**
- **`:protocol`** — add `prompt: GamePrompt? = null` to `GameStateSnapshot`. `null` stays legal (a
  session's cache may have state but never a prompt — e.g. rejoining after the game moved past a
  prompt that was already answered before the disconnect).
- **`:bridge`** — `GameStateCache` caches the prompt alongside the state on every `GamePrompted`, and
  clears it whenever a subsequent cached update carries none (a plain `GameStateUpdated`/`GameOver`
  means whatever was outstanding is no longer current). `answer()` includes it in the
  `GameStateSnapshot` it builds.
- **`:core:network`** — `GameStateSnapshot.prompt` flows into `GameViewMapper.apply`, which gets a
  narrow, explicit exception to its own "prompt is event-owned" rule: a resync snapshot **may** set
  `prompt` when the reply carries one, because (per §2) it is definitionally still live truth, not an
  inference. State-owned fields are unaffected; a live `GamePrompted` push still owns `prompt` the
  same way it always has — this only adds a second, narrower producer for the resync path
  specifically.
- **`GameViewMapper`'s own doc comment** (the one 0071 quoted as the ownership split) — update it to
  state the amendment precisely, so a future reader does not rediscover this by finding stale
  guidance wrong.
- Verify 0071's fallback and this fix **compose** correctly: once this lands, the ordinary case (any
  prompt, mulligan included) is restored as a real `state.prompt`, and 0071's fallback only ever
  fires in the narrower residual case it was built for — no snapshot has ever carried *any* prompt
  for this session yet, or the cached prompt was explicitly cleared. Do not regress 0071's own tests.

**Out of scope**
- Any change to `GameStateCache`'s per-game/per-username ownership or eviction rules — unchanged from
  0054/0070.
- Any change to how prompts are answered — `sendPlayerBoolean`/etc. are unaffected; this only changes
  whether the client is *shown* the question after a resync.
- A dedicated "is this prompt possibly stale" heuristic beyond what §2 already establishes — the
  existing cache-eviction rules (new update without a prompt clears it; `GameOver` clears everything)
  are the only staleness signal needed, and are already correct.

## 4. Constraints already verified — do not rediscover

- `GamePrompted` already carries `prompt: GamePrompt`, read directly (`GameMessages.kt:456-461`) —
  the data this fix needs is already on the wire the bridge relays; nothing new must be requested
  from upstream.
- `GameStateCache.observe()`'s `GamePrompted` branch currently reads only `.state`, read directly
  (`GameStateCache.kt:88`).
- `GameStateSnapshot` currently has no prompt field, read directly (`GameMessages.kt:243-248`).
- Mulligan and "select a starting player" are both asked before `viewerHasPriority` becomes
  meaningful, confirmed against this repo's own prior on-device finding
  (`feature/game/.../BoardUi.kt:382-390`) — not re-derived here, cited.
- 0069/0070's confirmed finding that XMage never re-asks a one-shot prompt on rejoin
  (`GameController.join`/`watch`, read from source in 0070) is what makes re-serving the last cached
  prompt correct rather than merely convenient — the server genuinely has no other way to tell the
  client what it is still waiting for.

## 5. Verification

- **Standard 1**, discriminating tests:
  - `bridge/src/test` — `GameStateCache`: a `GamePrompted` observation followed by an `answer()` call
    must return a `GameStateSnapshot` whose `prompt` matches what was observed, not `null`. A
    subsequent plain `GameStateUpdated` (no prompt) must clear it — the next `answer()` carries
    `prompt = null`. A `GameOver` still drops the whole entry (existing behaviour, must not regress).
  - `core/network/src/test` — `DefaultGameClient`/`GameViewMapper.apply`: a `GameStateSnapshot` with a
    non-null `prompt` must produce a `GameState` whose `prompt` is that value, not the pre-resync
    state's. A `GameStateSnapshot` with `prompt = null` must leave the existing `prompt` alone (a
    resync must never *clear* a live prompt a push already set — only *restore* one).
  - `feature/game/src/test` — confirm `controlsFor` renders the restored mulligan/`AskPrompt`
    normally once `state.prompt` is real again (this needs no new `controlsFor` code — it is proof
    that the plumbing above is sufficient, not a third fix).
- **Standard 2 (reachability):** name what produces a non-null `GameStateSnapshot.prompt` in
  production — `GameStateCache.observe()`'s `GamePrompted` branch, itself fed by the same
  `LiveSession` pump 0054/0070 already established.
- **Hermetic gate:** the three test locations above, no live server needed for the plumbing.
- **Live, if practical:** reproduce the exact reported scenario — leave during the mulligan
  decision, force/allow a bridge reconnect or session churn, reopen, confirm the mulligan question
  itself reappears and is answerable (not just that *some* control eventually shows up).
- **Eyes-on (standard 3) — hand Pete this checklist.** Do **not** drive the UI programmatically.
  1. Start a new match, and at the mulligan decision, leave the table/app.
  2. Force a bridge reconnect (restarting the bridge container is the most reliable trigger) or wait
     for a natural session churn.
  3. Reopen the game.
  4. Confirm the mulligan question itself is shown again (not just a generic priority fallback), and
     that answering it (Mulligan/Keep) proceeds the game normally.
  5. Repeat with any other pre-priority prompt reachable on demand, if convenient, to confirm this is
     not mulligan-specific.

## 6. Acceptance criteria

- [ ] `GameStateSnapshot` carries the last outstanding prompt for the session's game, when one
      exists.
- [ ] A resync restores a real `state.prompt` for **any** prompt type the session was actually shown,
      not only the priority case 0071 patched.
- [ ] A resync never clears a `prompt` a live push already set (only ever restores one that would
      otherwise be missing).
- [ ] 0071's fallback and its existing tests are unaffected — it remains correct for the narrower
      case where no prompt was ever cached at all.
- [ ] Pete has completed the eyes-on checklist, specifically re-testing the mulligan scenario that
      surfaced this.

## 7. References

- `protocol/src/main/kotlin/magefree/protocol/GameMessages.kt` — `GamePrompted` (456-461),
  `GameStateSnapshot` (243-248), where the new field goes.
- `bridge/src/main/kotlin/magefree/bridge/session/GameStateCache.kt` — `observe()`/`answer()`,
  where the prompt half needs to be captured and served alongside the state it already handles.
- `core/network/src/main/kotlin/magefree/network/game/DefaultGameClient.kt` — `readSnapshot`,
  `observeGame`'s `Intent.Snapshot` branch (199-220, 287-294).
- `core/network/src/main/kotlin/magefree/network/game/GameViewMapper.kt` — `apply`, whose
  snapshot/event field-ownership split (quoted by story 0071) needs the narrow, explicit amendment
  this story makes.
- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardUi.kt:382-390` — the prior
  on-device finding (pre-priority asks) this story's root cause directly extends.
- `docs/stories/0070-game-state-cache-survives-session-churn.md`,
  `docs/stories/0071-priority-controls-without-a-fresh-prompt.md` — the two prior stories in this
  family; read both before touching `GameStateCache`/`controlsFor` again.
