# 0071 — Offer Pass when priority is held but no fresh prompt exists

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0052 (game client & state), 0057 (board interaction), 0070 (game-state cache survives
  session churn)
- **Status:** ready — **not yet implemented** (write-up only; Pete asked to stop here this pass)

## 1. Objective

Fix a defect found live (Pete, 2026-08-16, on-device, immediately after 0070 landed): after rejoining a
game via 0070's warm cache restore, the board correctly shows it is the viewer's own priority — but
**no controls render at all**, not even Pass. The player can see the game is waiting on them and has no
way to act.

## 2. Root cause — confirmed by reading the actual code, not assumed

`controlsFor` (`feature/game/.../board/BoardControls.kt:463-467`) is the single place that projects a
`GameState` onto the floating controls:

```kotlin
internal fun controlsFor(state: GameState, hasPickedTarget: Boolean = false): PromptControlsUi? {
    val prompt = state.prompt ?: return null
    ...
```

**No `state.prompt`, no controls of any kind.** The ordinary "you hold priority, nothing specific is
being asked" case is not `prompt == null` — it is itself a `GamePrompt.Select` whose options carry no
combat role (`BoardControls.kt:476-492`, `PromptControlsUi.Priority`). Priority is asked for, upstream,
the same way everything else is: a prompt.

`GameViewMapper.apply` — the function 0070's cache-restored snapshot is projected through — **never
sets `prompt`**, by explicit design, stated in its own doc comment:

> The fields *not* owned by a snapshot — `GameState.prompt`, `GameState.lastMessage`,
> `GameState.lastError`, `GameState.result`, `GameState.isWatching` — are left alone here and moved
> only by the event that produces them, in `GameEventFold`.

So after a 0070-restored rejoin, `viewerHasPriority` is correct (that defect is fixed) but `prompt`
stays whatever the fresh session's blank seed had it as — `null` — because a snapshot fundamentally
cannot carry "the current outstanding prompt": prompts are one-shot, tied to the instant they were
asked, and XMage does not re-ask on rejoin (the same constraint 0070's own root-cause section verified
against source). The server will not push a new prompt on its own either, because it is waiting on the
viewer — the exact situation the player is stuck in.

**This does not require a server round-trip to fix.** `sendPlayerAction`'s pass-priority verbs are
described as an **out-of-band player action** in the bridge's own code (`bridge/.../mapping/
GameRelay.kt:97`: *"Performs an out-of-band player action in `[gameId]`"*) — `PlayerAction.PASS_PRIORITY_*`
is sent standalone, not as a correlated answer to a specific open ask. There is nothing stopping the
client from sending it whenever it believes the viewer holds priority, snapshot-derived belief included.

## 3. Scope

**In scope**
- `controlsFor` gains a fallback path: when `state.prompt == null` but `state.viewerHasPriority == true`
  and `state.hasSnapshot`, render a minimal `PromptControlsUi.Priority` — Pass at minimum, plus whatever
  `state.playable` already offers (the snapshot restore **does** carry `playable`, confirmed in
  `GameViewMapper.apply` — only `prompt` itself is withheld), using `state.playable` directly instead of
  `prompt.options` since there is no `GamePromptOptions` to read from.
- The existing prompt-driven `Priority` case (`GamePrompt.Select` with no combat role) is unchanged —
  this is a fallback for *no prompt at all*, not a replacement for the live path.
- `message` for this fallback case: something honest, distinct from a real prompt's message (e.g. "Your
  priority" or similar) — not silently blank, and not claiming to be something the server said.

**Out of scope**
- Any other prompt type (`Target`, `Select` with a combat role, `Ask`, etc.) — none of those are
  answerable without their own live prompt data (targets, choices, options), and none of them are the
  reported symptom. Only the no-prompt-but-priority-held gap is in scope.
- Changing `GameViewMapper.apply`/`GameEventFold`'s ownership split between snapshot-owned and
  event-owned fields — that split is deliberate and correct (stated in `GameViewMapper`'s own doc
  comment); this story works within it, not around it.
- The "All attack"/combat-declaration shortcuts, or anything about combat controls specifically — combat
  prompts, when they arrive, are live prompts with real options, unaffected by this gap.

## 4. Constraints already verified — do not rediscover

- `controlsFor`'s `state.prompt ?: return null` gate, read directly at `BoardControls.kt:467`.
- `GameViewMapper.apply`'s explicit non-ownership of `prompt`, read directly in its own doc comment
  (`core/network/.../game/GameViewMapper.kt:44-51`).
- `GameStateCache`/0070's cache-restore path only ever calls `GameViewMapper.apply` (via
  `DefaultGameClient.observeGame`'s `Intent.Snapshot` branch) — never `GameEventFold` — so a
  cache-restored rejoin can never itself populate `prompt`, by construction, regardless of any future
  change to 0070's cache scope.
- `state.playable` **is** carried by a snapshot restore (`GameViewMapper.apply`, `playable = view.playable
  .map { it.toPlayable() }`) — only `prompt` is withheld, not everything a prompt would have offered.
- `sendPlayerAction`/`PASS_PRIORITY_*` is out-of-band, not prompt-correlated — read directly in
  `bridge/.../mapping/GameRelay.kt:97`.

## 5. Verification (when this is implemented)

- **Standard 1**, and the test must discriminate the *actual* bug: a `GameState` with `prompt = null`,
  `viewerHasPriority = true`, `hasSnapshot = true` must produce a non-null `PromptControlsUi.Priority`
  offering Pass — not `null`. The existing live-prompt `Priority` case must keep passing unchanged (a
  test that only exercises the prompt-driven path would pass against the unfixed code).
- **Standard 2 (reachability):** name what produces `viewerHasPriority = true, prompt = null` in
  production — a `GetGameState`-sourced snapshot restore (0070) applied to a fresh session's seed, before
  any live prompt has arrived.
- **Hermetic gate**, `BoardControlsTest`/`GameBoardViewModelTest` (Compose/unit, existing files) — extend
  with the no-prompt-but-priority case.
- **Live**, through the real client if practical: reproduce 0070's eyes-on scenario (rejoin after a
  forced bridge reconnect) and confirm Pass is now available, not just that the priority banner is
  correct.
- **Eyes-on (standard 3) — hand Pete this checklist when implemented.** Do **not** drive the UI
  programmatically.
  1. Host a table against an AI, start the match, play a turn or two.
  2. Force a bridge reconnect (restart the bridge container is the most reliable trigger).
  3. Reopen the game.
  4. Confirm that when it is genuinely your priority, a Pass control is available immediately — not only
     after the next live server push.

## 6. Acceptance criteria

- [ ] A `GameState` with no live prompt but `viewerHasPriority = true` and a real snapshot offers Pass
      (and whatever `playable` lists) instead of no controls at all.
- [ ] The existing live-prompt-driven Priority controls are unchanged.
- [ ] No change to any other prompt type's controls.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardControls.kt` — `controlsFor`,
  `PromptControlsUi.Priority`, lines 463-495.
- `core/network/src/main/kotlin/magefree/network/game/GameViewMapper.kt` — `apply`, lines 44-76 (the
  snapshot/event field-ownership split).
- `core/network/src/main/kotlin/magefree/network/game/DefaultGameClient.kt` — `observeGame`'s
  `Intent.Snapshot` branch, where a 0070-restored snapshot is applied.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameRelay.kt:97` — `sendPlayerAction`'s "out-of-band"
  characterization.
- `docs/stories/0070-game-state-cache-survives-session-churn.md` — the story whose fix surfaced this gap.
