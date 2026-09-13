# 0129 — The stack is ordered once

- **Story:** #231
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0072 (the trigger-ordering prompt is answerable at all), 0125 (Full Control).
- **Traced in:** [`docs/upstream-trigger-ordering.md`](../upstream-trigger-ordering.md), and below.
- **Specified by:** Pete, over nine life-gain triggers (four Soul Wardens, three Soul's Attendants, two
  Auriok Champions) that took two presses each —
  1. *"I just need to be able to order the triggers and I want to be able to group them by type. For
     example, each equivalent trigger should be stacked as a group."*
  2. *"I want to be able to drag the order of the triggers around such that it is clear which will resolve
     first, the top of the stack."*
  3. *"I want to be able to yield to triggers and auto-resolve them. For example, I always want to gain life
     from Auriok Champion so, I want to be able to auto select yes."*
  4. *"I need to be able to resolve the stack without having to click pass priority on each."*

## 1. Objective

A round of simultaneous triggers is one arrangement and one press. A trigger the player always answers the
same way is never asked again. A stack the player is happy to see resolve resolves with one press.

## 2. What upstream already does

Read from the local clone. Everything here is the server's behaviour; the client sends actions and renders.

**Ordering.** `HumanPlayer.chooseTriggeredAbility` (`Mage.Server.Plugins/Mage.Player.Human/.../HumanPlayer.java:1507`)
asks *"Pick triggered ability (goes to the stack first)"* while more than one trigger is left — N triggers,
N−1 questions, the pick going to the bottom. It arrives as `GAME_TARGET` with `options.queryType =
PICK_ABILITY`, which our bridge already forwards as text. Before asking, it consults four per-game sets
(`:1521`–`:1538`): an ability in *name first* is returned without a question, one in *name last* is held back
until nothing else is left. They are set by `TRIGGER_AUTO_ORDER_NAME_FIRST` / `_LAST` with the rule text as
the key — `ability.getRule(sourceName)`, and upstream throws on a key still carrying `{this}` (`:2900`). The
by-id variants need a UUID payload our wire does not carry, and are not used. `UserData.autoOrderTrigger`
defaults on (`UserData.java:112`), so a round of wholly identical triggers is already placed silently.

Upstream's desktop client offers the auto-order rules on a popup over each trigger card
(`GamePanel.java:3115`–`3147`), and after *always first* sends that trigger as the pick.

**Auto-answers.** `HumanPlayer.chooseUse` (`:427`–`:468`) builds `autoAnswerMessage` — the question with the
source's name put back to `{this}` — sends it with the question, and before asking checks two maps: by
`originalId#message`, then by message alone. An optional trigger's question is its own rule text
(`TriggeredAbilityImpl.java:268`). The maps are set by `REQUEST_AUTO_ANSWER_TEXT_YES` / `_NO` and cleared by
`_RESET_ALL`. The by-ability variant needs `ORIGINAL_ID`, which `chooseUse` never sends — the line is commented
out (`:446`) — so upstream's own client has it permanently disabled (`HelperPanel.java:532`), and so does this
one.

**Resolving the stack.** `PASS_PRIORITY_UNTIL_STACK_RESOLVED` (`PlayerImpl.java:2740`) sets the flag and calls
`skip()`, which answers the priority window that is open — so the action is the whole pass. `priority()`
keeps passing while the stack is non-empty (`HumanPlayer.java:1347`–`1368`), clears the flag when it empties,
and stops early when a new object arrives **only for the active player** and only with
`stopOnStackNewObjects`, which defaults on (`UserSkipPrioritySteps.java:18`).

## 3. Design

### 3.1 Ordering triggers — one arrangement, one press

- **Grouped.** Triggers from a card of the same name, with the same rule text and the same targets, are one
  group: one card with a count. Nine life-gain triggers from three cards are three things to order. (Targets
  count because a trigger can carry them before it is on the stack; upstream's own identical-trigger check
  compares the same three things.)
- **Left resolves first**, the stack region's reading order, labelled at both ends. A group is held a moment
  and dragged; a plain drag scrolls the row.
- **One press — *Put on the stack*.** The ViewModel holds the arrangement and answers each ordering question
  with the lowest trigger in it that the question offers. Questions in between — a trigger's own targets —
  are the player's. A question offering a trigger the arrangement does not hold (one that fired because an
  earlier one went on) is asked, with that trigger first and the rest as arranged. Priority coming back ends
  the round.

  This is what plan §7.8 set aside as "the declared-intent model rebuilt for a smaller problem". It is
  reachable because it never answers anything the arrangement does not wholly cover.
- **Always first / always last** under each group, named by resolving — upstream's *name last* and *name
  first* respectively. Setting one moves the group to that end.

### 3.2 Always yes / always no

On a plain Yes/No question carrying `autoAnswerMessage`, two more buttons: the rule is sent, then the answer.
Not on a question that names its own answers ("Mulligan" / "Keep"). The rule covers every question worded the
same for the rest of the game — for an optional trigger, every trigger with that rule text.

### 3.3 Resolve the stack

With anything on the stack, the priority controls offer *Resolve the stack*, which sends
`PASS_PRIORITY_UNTIL_STACK_RESOLVED` and nothing else.

### 3.4 Taking the rules back

The corner menu has *Forget always first / last* and *Forget always yes / no*.

## 4. Scope

**Out of scope:** remembering rules across games (the server keeps them on the player object of one game); the
by-ability auto-answer (the server never sends the id); ordering an opponent's triggers (never asked of this
client).

## 5. Acceptance criteria

- [ ] Simultaneous triggers are shown once per equivalent group, with a count.
- [ ] Dragging a group changes the order; the left end is labelled as resolving first.
- [ ] One press puts every trigger on the stack, and the stack resolves in the order arranged.
- [ ] A trigger that needs a target still asks for it, and the rest are placed after.
- [ ] Always first / always last on a group keeps that trigger in place without asking, for the rest of the game.
- [ ] Always yes on Auriok Champion's question gains the life without asking again.
- [ ] Neither Always button appears on a mulligan.
- [ ] *Resolve the stack* passes until the stack is empty.
- [ ] The corner menu forgets both kinds of rule.
