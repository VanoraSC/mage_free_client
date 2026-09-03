# The cast sequence, traced from upstream

**Phase 2 step 2a.** The plan is explicit that no design work on the cast flow happens until the real
prompt sequence is written down: *"2a — trace upstream. The real prompt sequence for a cast carrying
additional costs, read from `HumanPlayer` in `../mage`. No design work until this is written down."*
This is that document.

**Pinned ref.** Everything below was read from the local clone at
`C:\Users\Pete\Documents\GitHub\mage`, commit `e0fe4b6` (`xmage_1.4.60V3`, 2026-07-23). Every claim
names the file and line it came from so the next reader can check it rather than trust it.

---

## 1. The canonical order

`AbilityImpl.activate(Game, Set<MageIdentifier>, boolean noMana)`
(`Mage/src/main/java/mage/abilities/AbilityImpl.java:271`) is the whole cast, and it follows CR 601.2
step by step with the rule numbers in its own comments. In order:

| # | Step | Rule | Where |
|---|---|---|---|
| 1 | Alternative and additional costs announced | 601.2b | `activateAlternateOrAdditionalCosts`, called at `:316` |
| 2 | Unpayable-cost check | 117.6 | `:331` |
| 3 | Targets for costs that must be chosen early | 601.2b | `handleChooseCostTargets`, `:338` |
| 4 | Dynamic costs prepared | — | `adjustX`, `:342` |
| 5 | **X announced** | 601.2b | `handleManaXCosts`, `:348`; non-mana X at `handleOtherXCosts`, `:349` |
| 6 | Phyrexian mana: 2 life or the coloured cost, per symbol | 601.2b | `handlePhyrexianCosts`, `:354` |
| 7 | Early cost targets again | 601.2b | `handleChooseCostTargets`, `:359` — the same call as step 3, run a second time |
| 8 | **Modes chosen** (and kicker declared with them) | 601.2b / 700.2 | `getModes().choose(...)`, `:368` |
| 9 | Mode costs applied | — | `:373` |
| 10 | **Targets chosen** | 601.2c | `getTargets().chooseTargets(...)`, `:421` |
| 11 | Legality check | 601.2e | `:428` |
| 12 | Cost modification | 601.2e | `:449` |
| 13 | **Mana costs paid** | 601.2f | `getManaCostsToPay().pay(...)`, `:453` |
| 14 | **Other costs paid** (sacrifice, discard, tap) | 601.2g | `getCosts().pay(...)`, `:458` |

Step 7 is not a typo: `handleChooseCostTargets` is called twice, at `:338` and again at `:359`, with
the same comment block above each. Whether that is deliberate or a duplication was not established —
it is recorded here because anything replaying a cast will see the effect of both.

Two orderings in that table are worth stating out loud because they are easy to assume backwards:

- **X is announced before targets are chosen** (step 5 before step 10). A UI that asks "what are you
  targeting?" first and "how big is X?" second is asking in the opposite order to the engine.
- **Mana is paid before the non-mana additional costs** (step 13 before step 14). Sacrificing a
  creature to a kicker happens *after* the lands are tapped, not before.

The one exception is a mana ability, which pays its non-mana costs first to avoid an endless loop
(`AbilityImpl.java:441`, with upstream's own comment calling it a hack).

---

## 2. What actually reaches a client

The engine talks to a human player only through `HumanPlayer`
(`Mage.Server.Plugins/Mage.Player.Human/src/mage/player/human/HumanPlayer.java`), and `HumanPlayer`
talks to a client only by firing an event that becomes a `ClientCallback`. For one cast, these are the
callbacks that can arrive, in the order section 1 produces them:

| Step | `HumanPlayer` method | Fires | Our prompt type |
|---|---|---|---|
| Additional/alternative costs | `chooseUse` (`:422`, `:427`) | `fireAskPlayerEvent` (`:477`) | `AskPrompt` |
| Choosing among an object's abilities | `activateAbility(Map, …)` (`:2326`) | `fireGetChoiceEvent` (`:2365`) | `ChooseAbilityPrompt` |
| **X** | `announceX` (`:1709`) | **`fireGetAmountEvent`** (`:1723`) | **`GetAmountPrompt`** |
| Targets | `choose`/`chooseTarget` | `fireSelectTargetEvent` (`:734`) | `TargetPrompt` |
| **Mana** | `playMana` (`:1627`) | `firePlayManaEvent` (`:1647`) | `PlayManaPrompt` |

### 2.1 `GAME_PLAY_XMANA` is dead code upstream

`Game.firePlayXManaEvent` is declared (`Mage/src/main/java/mage/game/Game.java:370`), implemented
(`GameImpl.java:3100`), and wired to the `GAME_PLAY_XMANA` callback
(`Mage.Server/src/main/java/mage/server/game/GameSessionPlayer.java:105`) — and **nothing anywhere in
the repository calls it.** A `grep -rn "firePlayXManaEvent" --include=*.java .` at this ref returns
exactly the declaration and the implementation.

X announcement really arrives as `GAME_GET_AMOUNT`, because `announceX` fires
`fireGetAmountEvent` regardless of its `isManaPay` flag (`HumanPlayer.java:1723`).

**Consequence for us:** `:protocol`'s `PlayXManaPrompt` models a callback the server does not send. A
cast UI that waited for it before showing an X spinner would wait forever. The X prompt to handle is
`GetAmountPrompt`, and it carries `min`/`max` from `VariableManaCost.getMinX()`/`getMaxX()`
(`AbilityImpl.java:827`). Note also that `announceX` short-circuits and prompts **nothing** when
`min >= max` (`HumanPlayer.java:1715`), so an X with only one legal value never reaches the player.

### 2.2 Mana payment is one prompt per source, not one prompt per cost

`ManaCostsImpl.pay` loops (`Mage/src/main/java/mage/abilities/costs/mana/ManaCostsImpl.java:147`):

```java
while (player.canRespond() && !isPaid()) {
    ManaCost unpaid = this.getUnpaid();
    String promptText = ManaUtil.addSpecialManaPayAbilities(ability, game, unpaid);
    if (player.playMana(ability, unpaid, promptText, game)) {
        assignPayment(...);
    } else {
        return false;
    }
}
```

So paying `{2}{R}` from three lands produces **three separate `GAME_PLAY_MANA` callbacks**, each
whose message is `"Pay "` plus the *remaining* unpaid cost. Floating mana already in the pool is
spent by `assignPayment` before the loop begins (`:144`).

Each `playMana` accepts exactly one of four answers (`HumanPlayer.java:1653`–`1667`):

- a **UUID** — the permanent to tap, which then has its own mana abilities narrowed and activated;
- a **mana type** — unlock that type from the player's own pool;
- the string **`"special"`** — a special mana action (convoke, improvise, delve);
- a **boolean** — **cancel**, which returns `false` and aborts the whole payment loop.

### 2.3 The server never proposes a payment

This is the finding that matters most for §7.6's *"server-proposed mana as the editable default"*.

`ManaUtil.tryToAutoPay` (`Mage/src/main/java/mage/util/ManaUtil.java:65`) exists, and `HumanPlayer`
does call it (`:1781`) — but only **after the player has already named a permanent to tap**. It takes
that one permanent's usable mana abilities and eliminates the others when one fits the unpaid cost
perfectly, so the ability picker can be suppressed. It never looks at the battlefield and never
suggests which permanents to use.

It is also skipped entirely when the spell cares about the colour of mana spent
(`caresAboutManaColor`, `:1774`, referencing magefree/mage#9070).

**Consequence for us:** there is **no proposed payment on the wire and no upstream code that computes
one.** This is the finding that ended the declared-intent model: offering an editable default meant
computing a payment ourselves, which is client-side rules work. Plan §7.6 was rewritten on 2026-09-02
to follow the server's own flow instead, and no default is proposed — the player taps what they want
to spend, one prompt at a time, exactly as the server asks.

### 2.4 Special mana payment is one-way within a cast

`HumanPlayer.playManaAbilities` checks the spell's mana-abilities step (`:1755`):

```java
case AFTER:
    game.informPlayer(this, "You can no longer use activated mana abilities to pay for the
        current spell (special mana pay already used). Cancel and recast the spell to
        activate mana abilities first.");
    return;
```

Once convoke or improvise has been used, **normal mana abilities are locked out for the rest of that
cast**, and upstream's own remedy is to cancel and start again.

**Consequence for us:** a declared intent that lets the player assemble tapped lands and convoked
creatures in any order cannot be played back in any order. Lands first, special payment second, and
an intent that violates it is unplayable — which is exactly the class of bug the intent contract
exists to catch before it reaches a live game.

### 2.5 What cancelling costs

Cancelling is offered at target selection (`canCancel`, `AbilityImpl.java:421`) and at every mana
prompt (§2.2). Either one makes `activate` return `false`, and `PlayerImpl.cast` then rolls the game
back to a bookmark taken before the cast (`PlayerImpl.java:1364`, restored at `:1421`).

**In the ordinary case the rollback works and cancelling is clean.** The bookmark is taken before
`card.cast(...)`, and every land tapped during payment happens after it, so `restoreState` puts those
taps and that mana back. A player who taps two lands and then cancels gets both lands untapped. This
is the case to design for.

There are two established exceptions, and they are different from each other.

**Mana floated *before* the cast started is outside the window.** The line that takes the bookmark
also moves the player's *undo* bookmark forward to it (`PlayerImpl.java:1365`), with upstream's own
comment:

> move global bookmark to current state (if you activated mana before then you can't rollback it)

So mana the player put in their pool before clicking the spell is not returned by cancelling the
spell. That mana stays floating.

**A mana source that cannot be undone removes the cast's own bookmark.** `playManaAbility`
(`PlayerImpl.java:1565`) calls `resetStoredBookmark(game)` when the ability reports
`isUndoPossible() == false` (`:1578`), and `resetStoredBookmark` calls `game.removeBookmark` on the
stored bookmark — which during a cast *is* the cast's bookmark (`:4879`). `GameImpl.restoreState` then
has an explicit failure path that logs *"It was not possible to do the requested undo operation"* and
changes nothing (`GameImpl.java:1004`).

`undoPossible` defaults to true and is set false by only a handful of cards, all of which reveal
information — `Astrolabe`, `Barbed Sextant`, `Brass Infiniscope`, `Charmed Pendant`, and Drain Power's
effect. So the case is rare, but it is reachable with a real card.

**What was not established:** whether that failure path is actually reached in practice.
`restoreState` tests `savedStates.contains(bookmark - 1)` — a *value* check on a `Stack<Integer>` of
state indices — and then reads `savedStates.get(bookmark - 1)`, an *index* lookup, on the next line
(`GameImpl.java:1002` and `:1008`). Those two lines do not agree with each other, and reasoning
further from them would be guessing. **This is a thing to test, not to conclude.**

**What this means:** a bail-out is not automatically dirty, as an earlier draft of this document
claimed. It is clean for ordinary lands, it leaves pre-floated mana behind, and it has a rare path
where the rollback may not happen at all. 0102's bail-out test should pin all three rather than assume
any of them.

### 2.6 The one relevant user option

`userData.isUseFirstManaAbility()` (`HumanPlayer.java:2348`) skips the ability picker when the object
is a land and its first ability is a mana ability. It is the closest thing upstream has to §7.7's
low-friction land tapping, and it is a blunt per-user toggle rather than a per-decision judgement.

---

## 3. What the server lets you cancel

Since the design is now to follow the server's own flow (plan §7.6, decided 2026-09-02), this is the
feature rather than a detail. Each prompt in a cast differs, and the differences are not guessable.

| Prompt | Cancellable? | Mechanism |
|---|---|---|
| Optional/additional cost (`AskPrompt`) | **Not a cancel** | It is a yes/no. Declining continues the cast without the cost. |
| Modes (`ChooseChoicePrompt`) | **Yes** | `Modes.choose` treats a null choice as *"user press cancel/stop in choose dialog"* and returns `isSelectedValid(...)`, which is false when too few modes were taken (`Mage/src/main/java/mage/abilities/Modes.java:406`–`409`). |
| **X** (`GetAmountPrompt`) | **No** | `announceX` loops `while (canRespond())`, `continue`s on a null or out-of-range integer, and breaks only on a valid one (`HumanPlayer.java:1720`–`1736`). Once asked, it must be answered. |
| Targets (`TargetPrompt`) | **Usually** | `canCancel` is `this instanceof ActivatedAbility && controller.isHuman()` (`AbilityImpl.java:420`), which sets `target.setRequired(false)` (`Targets.java:115`) — so it arrives as `required = false`. **But** a free cast sets `setRequired(true)` first (`Targets.java:112`), so Suspend and similar cannot be cancelled at targeting. |
| Mana (`PlayManaPrompt`) | **Always** | A boolean response cancels, returning `false` and aborting the payment loop (`HumanPlayer.java:1653`). |
| Ability picker (`ChooseAbilityPrompt`) | **Effectively** | A response naming no ability activates nothing (`HumanPlayer.java:2369`–`2373`). |

**The UI consequence.** A cancel affordance belongs on exactly the prompts above that accept one.
Offering it on an X prompt would be a control the server discards — the same defect as the table
room's deck picker — and the player would be stuck in a loop they were told they could leave.

**The non-obvious one is X.** It is the only step of a cast that, once reached, cannot be backed out
of. A player who announces a spell with X and changes their mind must pick a value, let the cast
continue to the mana prompts, and cancel there instead.

## 4. What this means for the cast flow

**The design changed because of §2.3.** Plan §7.6 originally called for a declared intent assembled
locally and played back by the bridge, whose step 3 showed *"the server's proposed solution for the
remainder"* as the default payment. There is no such proposal and no upstream code that computes one,
so that model required us to compute a payment ourselves — client-side rules work of exactly the kind
this project refuses elsewhere. **On 2026-09-02 the model was replaced: the client follows the
server's own sequence and offers cancellation exactly where the server accepts it.**

What follows from the trace, under that decision:

1. **The prompt types already exist.** `AskPrompt`, `ChooseAbilityPrompt`, `GetAmountPrompt`,
   `TargetPrompt` and `PlayManaPrompt` all cross `:protocol` today and the reply path is built. The
   remaining work is a surface, not a wire format — which is why the phase is much smaller than it
   was.
2. **`PlayXManaPrompt` is dead.** Whatever handles X handles `GetAmountPrompt`. Worth deciding
   separately whether to keep the type on the wire as documentation of a callback upstream still
   declares, or drop it.
3. **Cancellation is per-prompt and not uniform** (§3). Mana always; targets usually; modes yes; X
   never. The affordance has to follow the table, not a general rule.
4. **X is the one point of no return.** Once asked it must be answered, so a player changing their
   mind about an X spell has to continue to the mana prompts and cancel there. Worth saying in the UI
   rather than discovering.
5. **Special mana payment is one-way** (§2.4), and upstream's own remedy is "cancel and recast". The
   UI should keep convoke and improvise available only while they are still usable.
6. **Cancelling is mostly clean** (§2.5), with two rare exceptions that are worth a test rather than a
   design.
7. **Everything here is observable from a live game**, so the flow can be verified against the
   reference server the same way every other correctness result in this project was.

---

## 5. What was not traced

Named so nobody assumes it was covered:

- Split, fused and spliced spells, beyond noting that `activate` applies optional costs and cost
  modification only to the main part (`AbilityImpl.java:296`, with upstream's own TODO warning that
  the multi-ability path may be buggy).
- Alternative casting methods that replace the whole cost (flashback, foretell, adventure) — the
  announcement step is the same, but which alternatives the server offers was not enumerated.
- Delve and other special actions beyond the fact that they arrive through the `"special"` answer.
- The AI player, which does not use these prompts at all.
