# Ordering simultaneous triggers, traced from upstream

**Why this exists.** Plan §7.8 specifies trigger ordering as a result-shaped question — *"they are
shown as the stack will look"*, *"the player drags to rearrange"*, *"one confirm submits the
arrangement"* — with the component owning the translation to the process-shaped question the server
asks. That is a claim about what the server asks, and it had never been checked. §7.6's equivalent
claim turned out to be false and cost the cast flow its whole design, so this one is traced before
anything is built on it.

**Pinned ref.** Read from the local clone at `C:\Users\Pete\Documents\GitHub\mage`, commit `e0fe4b6`
(`xmage_1.4.60V3`, 2026-07-23). Every claim names its file and line.

**Related.** Story 0072 traced the same prompt to fix a live defect (unlabelled, untappable
candidates) and its findings are not repeated here. This document is about whether §7.8's *design* is
reachable.

---

## 1. What the server does

`GameImpl.checkTriggered()` (`Mage/src/main/java/mage/game/GameImpl.java:2341`) walks the players in
APNAP order — `state.getPlayerList(state.getActivePlayerId())`, CR 603.3b — and for each:

```java
while (player.canRespond()) {
    List<TriggeredAbility> abilities = state.getTriggered(player.getId());
    if (abilities.isEmpty()) break;
    // ... abilities that do not use the stack are executed first and removed ...
    if (abilities.size() == 1) {
        state.removeTriggeredAbility(abilities.get(0));
        played |= player.triggerAbility(abilities.get(0), this);
    } else {
        TriggeredAbility ability = player.chooseTriggeredAbility(abilities, this);
        if (ability != null) {
            state.removeTriggeredAbility(ability);
            played |= player.triggerAbility(ability, this);
        }
    }
}
```

Four things follow from that loop, and each of them matters to §7.8.

### 1.1 The question is "which goes on the stack **first**"

`HumanPlayer.chooseTriggeredAbility` fires the prompt with upstream's own wording
(`Mage.Server.Plugins/Mage.Player.Human/src/mage/player/human/HumanPlayer.java:1599`):

```java
game.fireSelectTargetTriggeredAbilityEvent(playerId,
    "Pick triggered ability (goes to the stack first)", abilitiesWithNoOrderSet);
```

The trigger you pick goes on the stack **first**, which puts it at the **bottom**, which means it
resolves **last**. §7.8's *"the card on top resolves first"* is correct as a statement about the
stack, and it is the exact inverse of the order the answers are given in.

### 1.2 N triggers cost N−1 questions, not N

The loop asks only while `abilities.size() > 1`. When one is left it is placed with **no prompt at
all**. So three simultaneous triggers are two questions, and the third is decided by elimination.

A translation that assumes one question per trigger is off by one in a way that is silent: the stack
still ends up ordered, just not the way the player asked.

### 1.3 The questions are **not** contiguous

`player.triggerAbility(...)` (`PlayerImpl.java:1750`) calls `ability.activate(game, false)` — the same
`AbilityImpl.activate` the cast flow goes through
([`upstream-cast-sequence.md`](upstream-cast-sequence.md) §1). So putting a trigger on the stack runs
its modes, its targets and its costs **immediately**, before the next ordering question is asked.

A player ordering three triggers, one of which targets, sees: *order* → *choose that trigger's
target* → *order* → done. The ordering prompts are separated by whatever the chosen trigger needs.

This is the finding that decides §7.8.

### 1.4 The candidate set can grow mid-round

`state.getTriggered(player.getId())` is re-read at the top of every iteration, so a trigger that fires
*because* an earlier one went on the stack joins the same ordering round. The set is not fixed when
the first question is asked.

---

## 2. What reaches a client, and what does not

`fireSelectTargetTriggeredAbilityEvent` (`GameImpl.java:3165`) raises a `PlayerQueryEvent` with
`QueryType.PICK_ABILITY` (`PlayerQueryEvent.java:193`). `GameController` routes that to an
ability-only overload of `target(...)` (`GameController.java:194`), which reaches the player as
**`ClientCallbackMethod.GAME_TARGET`** (`GameSessionPlayer.java:59`) — the same callback as ordinary
target selection — carrying the abilities as a `CardsView`.

**The only thing distinguishing it is `options["queryType"]`.** Upstream's own client reads exactly
that to decide it is looking at an ordering prompt rather than a targeting one
(`Mage.Client/src/main/java/mage/client/dialog/ShowCardsDialog.java:110`).

**We do not map it.** `queryType` appears nowhere in `bridge/`, `protocol/` or `core/`, and
`GamePromptOptions` carries only button text, possible attackers/blockers and chosen targets. So a
trigger-ordering prompt arrives in the app as an ordinary `TargetPrompt` whose message happens to read
*"Pick triggered ability (goes to the stack first)"*.

The bridge already knows this prompt exists — `GamePromptMapper.target` documents the `PICK_ABILITY`
case and falls back to `cards.map { it.id }` because that overload never populates `targets`
(0072's fix). What is missing is any **marker** the client can branch on. Matching on the message
string would work and would be the wrong kind of correct.

**Neither the ordering prompt nor a trigger's own targets can be cancelled.**
`chooseTriggeredAbility` loops `while (canRespond())` until a valid ability id arrives
(`HumanPlayer.java:1517`, `:1604`), and `AbilityImpl`'s cancel flag is
`this instanceof ActivatedAbility && controller.isHuman()` (`AbilityImpl.java:420`) — a
`TriggeredAbility` is not an `ActivatedAbility`, which is what upstream's own comment on the next line
says: *"only activated abilities can be canceled by human user (not triggered)"*.

---

## 3. Auto-ordering already exists, and we already carry it

`HumanPlayer` keeps four per-session sets — `triggerAutoOrderAbilityFirst`, `…AbilityLast`,
`…NameFirst`, `…NameLast` — and consults them before asking anything
(`HumanPlayer.java:1521`–`1538`). An ability in the "first" set is returned immediately with no
prompt; one in a "last" set is held back and only used when nothing else is left. They are set by five
player actions (`HumanPlayer.java:2774`–`2778`): `TRIGGER_AUTO_ORDER_ABILITY_FIRST`,
`…_ABILITY_LAST`, `…_NAME_FIRST`, `…_NAME_LAST`, `…_RESET_ALL`.

There is also a user option, `isAutoOrderTrigger` (`:1516`), which silently auto-orders triggers whose
**rule text and targets** are identical — because their order cannot matter — and gives up as soon as
either differs.

**All five player actions already cross our wire** (`PlayerActionCode` in `GameMessages.kt:1506`–
`1518`). So "always put this one first" is available to the UI today with no new protocol work, and it
is the single highest-value thing here: it removes the question entirely for the repeat offenders
rather than making the question prettier.

---

## 4. What this means for §7.8

**The claim §7.8 rests on is correct.** The server really does ask a process-shaped question — "which
goes on next" — and a player really does think in result-shaped terms. Translating between them is a
legitimate change of *form*, exactly as §7.6's safety rule allows.

**But the design as written is not reachable, for the same reason the cast flow's was not.**

*"One confirm submits the arrangement"* requires collecting the whole ordering locally and replaying
it. §1.3 says the questions are separated by the chosen trigger's own target and cost prompts, and
§1.4 says the candidate set can grow between them. A local arrangement would therefore have to be
held across unrelated prompts and revalidated against a set that changed underneath it — the declared
intent model, rebuilt for a smaller problem, and dropped for the cast on 2026-09-02 for exactly this
kind of reason.

**What is reachable, and worth doing:**

1. **Mark the prompt.** Map `options["queryType"]` so the client can tell an ordering prompt from a
   targeting one without matching on English. Small, additive, and blocks everything else.
2. **Ask the question the server asks, but say what it means.** One prompt, "which of these resolves
   **last**", with the stack drawn as it currently stands and the pick shown going to the bottom. That
   is a change of form with no local state and no translation to get wrong — the reversal is in the
   wording, not in an index.
3. **Offer "always first" / "always last" on each candidate**, wired to the player actions we already
   carry. This is the real fix for the case that motivated 0072: two triggers that fire together every
   turn become a question asked once rather than every turn.
4. **Drag-to-arrange, only if it is worth it.** With N−1 questions, interleaved prompts and a growing
   candidate set, a full arrangement UI buys less than it looks like it does. It would be honest only
   over a set that cannot change — and nothing guarantees that.

**What should change in the plan:** §7.8's *"one confirm submits the arrangement"* and the drag
model, on the same grounds §7.6 lost its Confirm. The result-shaped framing survives; the batching
does not.

---

## 5. What was not traced

- Whether an opponent's simultaneous triggers ever reach the viewer's client (the loop is per player,
  so they should not, but this was not confirmed against a live game).
- `checkStateTriggers`, and triggers that do not use the stack — the loop executes those first,
  without asking anything, and their interaction with ordering was not examined.
- The reference client's own ordering dialog beyond the one line that reads `queryType`.
