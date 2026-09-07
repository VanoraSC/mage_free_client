# 0115 — Stops, and passing priority on their own

- **Story:** #203
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0112 (the board), 0114 (the stack, which the rule reads).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.6 (priority and
  the cast flow), §7.4 (the phase bar).

## 1. Objective

Stop asking the player about priority windows they do not care about. With no stop set for the step
being played and nothing on the stack, the client passes on its own; the phase bar is where a player
says which steps they *do* care about.

## 2. Context & background

**The seam has been waiting since it was written.** `PassPolicy` exists so that "when stops arrive,
they change *when the app answers a priority prompt*, and nothing else should have to change" — one
interface, one caller, one `passPriority` call behind it. `ManualPassPolicy` answers *ask the player*
to everything. This replaces the bound implementation and nothing else moves.

**Upstream already models the stops, and its model is the one to follow.**
`UserSkipPrioritySteps` holds two `SkipPrioritySteps` — `getYourTurn()` and `getOpponentTurn()` — each
a set of seven booleans: upkeep, draw, main1, before combat, end of combat, main2, end of turn. Those
are exactly the seven steps the phase bar already marks `stoppable`, and the split by side is exactly
what "set stops for the opponent's phases, the player's phases, or both" needs.

**Upstream's skipping is done by the server and ours is not.** `HumanPlayer` reads
`getControllingPlayersUserData(game).getUserSkipPrioritySteps()` and skips before it ever asks. There
is no wire message for setting that user data, so this app decides for itself and sends the pass —
which is what `PassPolicy` was built to do. The *semantics* are upstream's; only the place the
decision is taken is ours.

**The three-state stop is ours.** Upstream's stop is a boolean. A one-shot stop — *stop the next time
this comes round, then forget it* — is a thing a player wants constantly ("I want to see their end
step this turn") and has no upstream equivalent, so it is a client-side convenience and is written
down as one.

## 3. Scope

**In scope**
- Auto-pass: no stop for this side and step, and nothing on the stack.
- Stops per side, over the seven steps the bar marks stoppable.
- Pressing a step cycles none → once → always → none, with a distinct mark for each.
- The two stops that cannot be turned off.
- Stops surviving the game they were set in.

**Out of scope**
- **Standing passes** — *pass until end of turn*, *pass until something happens*. They are player
  actions rather than answers, `PassPolicy`'s own KDoc says so, and they need their own controls.
- **Stopping on anything but a step** — upstream also has "stop when something new goes on the stack",
  which this covers from the other side by never auto-passing with a non-empty stack.
- **Sending the stops to the server.** There is no message for it, and the client passing is
  indistinguishable from the server skipping as far as the game is concerned.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:feature:game`, `:app`.

## 5. Design & approach

**The stops are one object, and the policy and the bar read the same one.** A holder scoped to the
session, so stops survive a game ending — you set them once and play a match with them — injected into
both the policy that reads them and the ViewModel that publishes them to the bar.

**The policy may consume, because it is asked once per question.** A one-shot stop has to clear when
it fires, which makes `decide` more than a pure function. That is safe here and only here: the
ViewModel asks the policy exactly once per prompt *instance* (`policyAskedFor`), so a re-emission of
the same question cannot consume a stop twice.

**Two stops are rules rather than settings.**

*Your own main phases.* A turn you cannot act in is not a turn you are playing. Upstream defaults both
to true and lets them be turned off; this does not, because the failure is silent and total.

*After blockers, before damage — when there was an attack.* This is the combat-trick window, and it is
the one priority window whose absence loses games rather than wasting time. It is conditional on
there having *been* an attack, because with no combat there is nothing to respond to and stopping
there would be exactly the kind of pointless question this story exists to remove. "Was there an
attack" is `GameState.combat` being non-empty — the server's own assignment, never a guess.

**The bar shows the side whose turn it is.** `PhaseBarState.turn` already carries that. Pressing a
step sets the stop for the turn being played, so both sides' stops are reachable across one turn
cycle without a second control for choosing a side.

**A step nobody receives priority in is not a step this decides about.** Untap and cleanup give no
priority, and the declaration steps are answered by a declaration rather than by a pass — the policy
sees a `Select` carrying `possibleAttackers`/`possibleBlockers` and never passes it, because
`FinishTargeting` is how a declaration closes.

## 6. Implementation steps

1. The stop model: three states, seven steps, two sides.
2. The holder, and its place in the DI graph.
3. The policy: the auto-pass rule and the two mandatory stops.
4. The phase bar: three marks, and the press that cycles them.
5. The board: publish the stops, and route the press.

## 7. Testing & verification

- **Unit (the policy):** passes with no stop and an empty stack; asks with a stop set; asks with
  anything on the stack; asks in your own main phases whatever the stops say; asks after blockers when
  there was an attack and passes when there was not; never passes a declaration; a one-shot fires once
  and then stops firing.
- **Unit (the model):** the press cycles none → once → always → none; a stop set on one side does not
  appear on the other.
- **Hermetic Compose:** the bar marks a once-stop differently from an always-stop; pressing a step
  cycles the mark; a step the bar does not accept a stop for does not change.
- **Eyes-on:** a real game, playing a turn cycle with stops set on each side.

## 8. Acceptance criteria

- [ ] With no stops set, a turn plays without asking about steps nothing is happening in.
- [ ] Pressing a step in the bar cycles blue → red → none, and the mark says which.
- [ ] A blue stop fires once and clears itself.
- [ ] A red stop fires every time.
- [ ] Your own M1 and M2 always stop, and cannot be turned off.
- [ ] After blockers are declared, with an attack on the board, the game stops.
- [ ] With no attack, it does not.
- [ ] Nothing is auto-passed while anything is on the stack.
- [ ] Stops set in one game are still set in the next.
