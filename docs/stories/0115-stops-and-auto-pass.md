# 0115 — Stops, and passing priority on their own

- **Story:** #203
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0112 (the board), 0114 (the stack).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.6 (priority and
  the cast flow), §7.4 (the phase bar).

## 1. Objective

Stop being asked about priority windows the player does not care about. With no stop set for the step
being played and nothing on the stack, priority is passed without a question; the phase bar is where a
player says which steps they *do* care about.

## 2. Context & background

**The skipping is the server's, and that is the whole design.**
`HumanPlayer.priority()` — `Mage.Server.Plugins/Mage.Player.Human/src/mage/player/human/HumanPlayer.java`
— reaches, for an empty stack, `checkPassStep(game, controllingUserData)`:

```java
if (playerId.equals(game.getActivePlayerId())) {
    return !controllingUserData.getUserSkipPrioritySteps().getYourTurn().isPhaseStepSet(game.getTurnStepType());
} else {
    return !controllingUserData.getUserSkipPrioritySteps().getOpponentTurn().isPhaseStepSet(game.getTurnStepType());
}
```

and when it answers true the server passes for the player **without sending the client a prompt at
all**. So a stop is not a decision about a question the client was asked; it decides whether the
question is asked. A client-side policy can only decline to answer, and the windows a stop is about
are precisely the ones it never hears about.

**The model is upstream's, and there are exactly seven settable steps.**
`UserSkipPrioritySteps` holds two `SkipPrioritySteps` — `getYourTurn()` and `getOpponentTurn()` — each
seven booleans over upkeep, draw, main 1, before combat, end of combat, main 2 and end of turn, where
`true` means *stop here*. `main1` and `main2` start `true`; the rest start `false`. Those seven are
exactly the seven the phase bar marks `stoppable`.

**The split by side is upstream's and it is not passed on.** A mark in the bar means the step, on both
players' turns, and both of upstream's sides are sent the same set. The bar has one row and a row
cannot say which side a mark belongs to: setting whichever side happened to be being played when the
mark was pressed made the *same* press mean different things at different times — press upkeep on your
own turn, stop on your own upkeep, and watch the opponent's go past with nothing on screen to explain
it. What the two marks distinguish is *how long* — the next occurrence, or every one — which is a
question one row can put. The only per-side thing left is a rule rather than a setting: your own main
phases (below).

**Everything else always stops.** `SkipPrioritySteps.isPhaseStepSet` ends `default: return true`, so
declare attackers, declare blockers, combat damage, untap and cleanup are not steps a stop can be
lifted from. That is where the mandatory window after blockers are declared and before combat damage
comes from — it is already unconditional server behaviour — and it is why it needs no condition on
there having been an attack: with no attackers, those steps do not happen. Upstream adds one more of
its own in `priority()`'s `quickStop`: a defender with blockers available always stops in
DECLARE_ATTACKERS (`stopOnDeclareBlockersWithAnyPermanents`, default true).

**The empty-stack rule is upstream's too.** `checkPassStep` is only consulted inside
`if (game.getStack().isEmpty())`. "Never auto-pass while something is on the stack" is not a rule this
app has to keep; it is a rule it inherits.

**There is a wire message for setting the stops mid-session.**
`SessionImpl.updatePreferencesForServer(UserData)` calls `server.connectSetUserData(...)`, which is
what the desktop client does when its preferences dialog is accepted
(`PreferencesDialog` → `SessionHandler.updatePreferencesForServer`). The server merges rather than
replaces — `Session.setUserData` → `User.setUserData` → `UserData.update`, which copies
`userSkipPrioritySteps` wholesale — and it mutates the `UserData` instance the in-game `Player` was
handed at seating (`TableController`: `seat.getPlayer().setUserData(user.getUserData())`), so a change
made during a game takes effect in that game.

**The three-state stop is ours.** Upstream's stop is a boolean. A one-shot — *stop the next time this
comes round, then forget it* — is a thing a player wants constantly ("let me see their end step **this**
turn") and has no upstream equivalent, so it is sent as an ordinary stop and taken back once the window
it asked for has arrived.

> **Correction of record.** This story was first built as a client-side `PassPolicy` that declined to
> answer prompts, on the written claim that "there is no wire message for setting that user data". Both
> halves were wrong and neither had been checked against the clone: the consumer
> (`HumanPlayer.priority()`) was never opened, and `updatePreferencesForServer` was never looked for.
> It shipped and did nothing. `StopPassPolicy` is deleted and `BoardModule` binds `ManualPassPolicy`
> again.

## 3. Scope

**In scope**
- Stops over the seven steps upstream accepts, sent to the server for both sides of the turn.
- Pressing a step cycles none → once → always → none, with a distinct mark for each.
- The one-shot: sent as a stop, cleared once the window it asked for arrives.
- Your own M1 and M2, forced on the wire so they cannot be lost.
- Stops surviving the game they were set in.
- A new client→bridge message, and the bridge mapping onto upstream's own type.

**Out of scope**
- **Standing passes** — *pass until end of turn*, *pass until something happens*. They are player
  actions rather than settings, and they need their own controls.
- **Upstream's other stop flags** — `stopOnAllMainPhases`, `stopOnAllEndPhases`,
  `stopOnStackNewObjects`, `stopOnDeclareAttackers`, the two `stopOnDeclareBlockersWith…`. They govern
  the standing passes above and the combat quick-stop, so there is nothing here to set them from. The
  connection's defaults stand.
- **Persisting stops across a session.** They live for as long as the app is running.

## 4. Prerequisites & toolchain

Project baseline; `:protocol`, `:bridge`, `:core:network`, `:core:designsystem`, `:feature:game`.
`:bridge` verifies in the container only — `wsl.exe … ./scripts/dev gradle :bridge:check`.

## 5. Design & approach

**One object, published twice.** `StopStore` is a `single` in the DI graph, because stops belong to
the player and a match plays several games through several boards. The ViewModel collects it and sends
every value two places: to the UI state, so the bar marks the press without waiting for a snapshot,
and to `GameClient.setPriorityStops`, which is where the stop actually takes effect. The current value
is emitted on collection, so opening a board sends what the player set last rather than leaving the
server on its own defaults.

**The wire is a mirror, and the bridge does not interpret.** `SetPriorityStops` carries two
`PriorityStops`, each a field-for-field copy of `SkipPrioritySteps` including its defaults.
`PriorityStopsMapper` writes them onto the session's own login profile — in place, because
`UserSkipPrioritySteps` holds its two `SkipPrioritySteps` in final fields — and
`XMageSession.updatePreferences` pushes that profile up. Carrying the rest of the profile across
matters: the server merges, so a preference dropped on the way would be merged back as a default.

**Your own main phases are forced on the wire, not just locked in the bar.**
`TurnStops.asSteps(forced = OWN_MAIN_PHASE_STOPS)` ORs them in for your own side. A lock that existed
only in the bar would send `main1 = false` for a player who had never pressed M1, and the server would
skip the one window the turn is for. Upstream defaults them to `true` for the same reason.

**The one-shot is spent by the next window, whoever's turn it is.** When a priority prompt arrives in
the step a `Once` was set for, the stop is cleared — which republishes, which sends the server the new
set, which is what stops it firing again. *Next* means next: an opponent's upkeep is an occurrence of
upkeep, and a blue mark that skipped it would be answering a question the player did not ask. It is
consumed once per prompt *instance* (`GameBoardViewModel.policyAskedFor`), so a snapshot re-pushed for
an unrelated reason cannot spend it twice.

**The bar still says whose turn it is; the marks do not.** `PhaseBarState.turn` colours the current
step, which is about where the game *is*. The stop marks are about what the player asked for, and that
is one set drawn identically on every turn.

**`lockedStops` states the server's rules for the bar.** The three combat steps, always, because
`isPhaseStepSet` answers `default: return true` for them; plus your own two main phases. A locked step
draws a standing mark and takes no press, so what the bar says and what the server does cannot
disagree.

**`PassPolicy` stays, and stays manual.** The seam was written expecting auto-pass to arrive as a
policy; it arrived somewhere else. Every prompt that now reaches the board is one the player asked
for, so the shipped policy answers *ask the player* to all of them.

## 6. Implementation steps

1. `PhaseStop`, three states, and the bar's mark for each.
2. `TurnStops` / `BoardStops` / `StopStore`, and its place in the DI graph.
3. `SetPriorityStops` and `PriorityStops` in `:protocol`.
4. `UpstreamSession.setPriorityStops`, `PriorityStopsMapper`, `XMageSession.updatePreferences`, and the
   coordinator route.
5. `BridgeClient.send` (fire-and-forget) and `GameClient.setPriorityStops`.
6. The board: collect the store, publish to the bar and upstream, route the press, consume the one-shot.

## 7. Testing & verification

- **Unit (the mapper, `:bridge`):** each side lands on its own `SkipPrioritySteps`; a cleared stop is
  written as `false` rather than left at upstream's `true` default; the rest of the profile is carried
  across.
- **Integration (the coordinator, `:bridge`):** `SetPriorityStops` reaches the bound session with both
  sides intact; on an unbound socket it is dropped and the socket stays usable.
- **Unit (the board):** opening a board sends the stops before any snapshot; your own main phases are
  sent whether or not the player asked; one press sends the step on **both** sides, and the turn being
  played when it is pressed makes no difference; a one-shot is cleared when a prompt arrives in its
  step — including on an opponent's turn — and the clearing is sent; a standing stop is not; a one-shot
  in a step the prompt did not arrive in is left alone; the same prompt instance cannot spend two.
- **Unit (the bar's rules):** the three combat steps are locked on either side; your main phases are
  locked and an opponent's are not; a locked step draws a standing mark and takes no press; the mark a
  step draws is the same whoever's turn it is.
- **Hermetic Compose:** the bar marks a once-stop differently from an always-stop; pressing cycles the
  mark; a step the bar accepts no stop for does not change.
- **Eyes-on:** a real game, playing a full turn cycle with a mark set on each kind of step.

## 8. Acceptance criteria

- [ ] With no stops set, a turn plays without asking about steps nothing is happening in.
- [ ] Pressing a step in the bar cycles blue → red → none, and the mark says which.
- [ ] A blue stop fires at the **next** occurrence of that step, whoever's turn it is, then clears.
- [ ] A red stop fires every time, on both players' turns.
- [ ] Where a mark was pressed in the turn cycle makes no difference to what it does.
- [ ] Your own M1 and M2 always stop, and cannot be turned off.
- [ ] After blockers are declared, the game stops before combat damage.
- [ ] Nothing is passed on the player's behalf while anything is on the stack.
- [ ] Stops set in one game are still set in the next.

## 9. Known gaps

- **A reconnect resets the server's copy.** `XMageUpstreamSession` captures the profile it logged in
  with, and a fresh login builds a fresh `UserData.getDefaultUserDataView()`. The stops are re-sent
  when a board opens, so a reconnect between games recovers on its own; a reconnect *during* a game
  leaves the server on its defaults until the next board.
- **Stops do not persist across an app restart.** `StopStore` is memory only.
