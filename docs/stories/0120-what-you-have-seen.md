# 0120 — What you have seen

- **Story:** #212
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0115 (the preferences pipe), 0116 (targeting on the board).
- **Specified by:** a real game — a turn-1 Inquisition of Kozilek took a card without ever showing the hand.

## 1. Objective

See the hand a spell reveals, choose from it even when there is only one legal choice, and be able to
look again later at what you have been shown.

## 2. Context & background — what the server actually does

**The prompt never arrived, and that is a preference.** `DiscardCardYouChooseTargetEffect` reveals the
hand and then calls `controller.choose(Outcome.Benefit, revealedCards, target, source, game)`. That
overload opens with:

```java
UUID responseId = target.tryToAutoChoose(abilityControllerId, source, game, possibleTargets);
if (responseId == null) { … prepareForResponse / fireSelectTargetEvent / waitForResponse … }
```

`TargetImpl.tryToAutoChoose` picks for the player — **and never fires the event** — when all of:

- `getMinNumberOfTargets() == getMaxNumberOfTargets()`;
- `possibleTargets.size() == min - size` (exactly as many candidates as must be picked);
- `!strictChooseMode`;
- **`playerAutoTargetLevel > 0`**;
- the ability text does not contain `"search"`.

Turn 1, one nonland of mana value ≤ 3 in the target's hand: one candidate, one to pick, auto-chosen.
The server decided and the client was never asked.

`playerAutoTargetLevel` is `UserData.getAutoTargetLevel()`. Upstream's own preferences dialog offers
three values and names them **Off / Most / All** — `Constants.AUTO_TARGET_DISABLE = 0`,
`AUTO_TARGET_NON_FEEL_BAD = 1`, `AUTO_TARGET_ALL = 2` — and `UserData.getDefaultUserDataView()` passes
`1`. So the bridge connects on *Most*, and *Most* is what silently answered this.

**Reveals are transient, deliberately.** `GameState.revealed` is cleared by `GameImpl` in **two**
places — inside `fireUpdatePlayersEvent` and inside the priority `select` — so a reveal exists in
exactly the snapshot that carries it and the next update wipes it. There is no server-side history and
no verb that asks again. **Anything that lets a player revisit a reveal has to be remembered by the
client.**

**An opponent's hand is a count and nothing else.** `PlayerView.handCount` is the only hand field for
another seat; `GameView.myHand` is the viewer's own. `GameView.watchedHands` is declared and has a
getter but is **never populated anywhere in the checkout** — dead. A consent feature exists
(`PlayerAction.REQUEST_PERMISSION_TO_SEE_HAND_CARDS`, `PlayerImpl.usersAllowedToSeeHandCards`) but it
requires the opponent to agree, so it is a different feature.

**A reveal does not say whose cards it is.** `RevealedView` carries the source card's name — for
Inquisition, `sourceCard.getIdName() + " (" + zoneChangeCounter + ")"` — and the cards. No owner.

**And we already misattribute them.** `tableZones` attaches the same revealed list to *every* seat, so
a reveal of the opponent's hand also appears under the viewer's own. That is a defect in shipped code.

## 3. Scope

**In scope**
1. **Ask, always.** Send `autoTargetLevel = 0` with the user's preferences, so the server stops
   answering single-candidate choices on the player's behalf.
2. **Stop misattributing reveals.** A reveal is not per-seat information and must not be drawn as if
   it were.
3. **Remember what has been seen.** A per-opponent known-hand model, fed by reveals as they arrive and
   reconciled against `handCount`, drawn as face-up cards for what is known and face-down cards for
   the rest.

**Follow-on (4) is ruled out — see §7.** `CardView` carries no owner, so there is nothing to put on
the wire. (3) rests on inference, and the story says so.


**Out of scope**
- The consent feature. Asking an opponent to show their hand is a different thing with a different
  social meaning, and it is not what this story is about.
- Any inference about cards the player has *not* been shown. The model says what has been seen; it
  never guesses at the rest.

## 4. Prerequisites & toolchain

Project baseline. (1) touches `:protocol`, `:bridge`, `:core:network` and reuses 0115's mapper, so it
needs `:bridge:check` in the container. (2) and (3) are `:feature:game` only.

## 5. Design & approach

**(1) is one field on a message that already exists.** 0115 built `SetPriorityStops` →
`PriorityStopsMapper` → `XMageSession.updatePreferences` → `SessionImpl.updatePreferencesForServer`,
and `UserData.update()` already copies `autoTargetLevel`. The message wants renaming to something
truthful once it carries more than stops.

It is not only about Inquisition: the same auto-pick answers every single-candidate choice in the
game, silently, and turning it off is the difference between a client that shows the player the game
and one that plays parts of it for them.

**(2)**: reveals become one board-level set rather than a pile per seat. Given no owner on the wire
that is the only statement that is true.

**(3) `KnownHand`, per opponent:**

- a revealed card enters the known set;
- it leaves when it is next seen anywhere else — stack, graveyard, battlefield, exile, or the reveal
  of it being discarded;
- the component draws the known cards face-up and `handCount − known.size` face-down.

Self-correcting by construction: the count is always the server's, so a wrong memory is reconciled by
the next snapshot rather than compounding. And honest about what it is — *what you have seen*, not
*what they hold*.

## 6. Testing & verification

- **Unit (`:bridge`):** the preferences mapper carries `autoTargetLevel` alongside the stops.
- **Unit (the board):** a reveal is attributed to no seat rather than to every seat; the known hand
  admits a revealed card, drops it when it is seen elsewhere, and never claims more cards than
  `handCount`.
- **Hermetic Compose:** the opponent-hand component draws face-down cards for the unknown remainder
  and face-up for what is known.
- **Eyes-on:** a turn-1 Inquisition against a hand with exactly one legal target.

## 7. The open question, now settled — **(4) is not possible**

`CardView` exposes exactly two ids: `getId()` and `getParentId()`. There is **no owner, controller or
player id on it at all** — the only `ownerId` mentions in the file are internal, inside its own
construction, comparing a permanent's controller against its owner to decide a label. `RevealedView`
adds nothing but a name.

The bridge is a client and receives `CardView`; it cannot carry what it was never given. **So there is
no wire field to add, and (3) rests on inference.** The story says so plainly rather than implying a
certainty it does not have.

**The inference, and its limit.** A revealed card that the board cannot see in any visible zone — not
on a battlefield, not in a graveyard, not in exile, not on the stack, not in the viewer's own hand —
is in a hand or a library. With **one** opponent that is enough: it is theirs, or it is not in a hand
at all, and the `handCount` reconciliation catches the second case on the next snapshot.

With more than one opponent it is not enough, and nothing on the wire makes it so. The known-hand
component is therefore offered for a **single opponent only**, and multiplayer keeps the reveal pile
and nothing more. That is a real limit, stated rather than papered over.

## 8. Acceptance criteria

- [ ] A spell that reveals a hand shows the whole hand, and the player chooses even when one card is
      the only legal choice.
- [ ] The same is true of every other single-candidate choice the server used to answer alone.
- [ ] A reveal of an opponent's hand no longer appears under the viewer's own seat.
- [ ] An opponent's hand can be looked at again later, showing what has been seen face-up and the rest
      face-down.
- [ ] The face-up count never exceeds the server's `handCount`.
- [ ] A card seen revealed and then played, discarded or exiled stops being shown in their hand.
