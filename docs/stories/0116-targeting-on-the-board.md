# 0116 — Targeting on the board

- **Story:** #205
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0112 (the board), 0113 (the question takes the screen), 0114 (the stack).
- **Specified by:** a real game — Liliana of the Veil's `-6` was unanswerable.

## 1. Objective

Answer every target question on the board itself: candidates marked in the board's own *playable*
green, chosen cards tinted green across their whole face, and players pointed at by their life total.
No candidate buttons in the prompt panel.

## 2. Context & background

**The prompt that started it.** Liliana of the Veil's `-6` is *"Separate all permanents target player
controls into two piles."* It reached the board as a target prompt over an opponent's whole
battlefield, and there was no way to play it: nothing said which cards answered it, nothing said a
pick had landed, and the only other way to answer a target question — a list of names in the prompt
panel — could not name permanents at all.

**One vocabulary, not two.** Targeting had grown two: tap the thing on the board, *or* press its name
in a panel, depending on whether the board happened to draw it. The panel list was there for
candidates the board could not show, and players above all. Both are now on the board.

**The colour is the one that already exists.** A candidate is `BoardCardSignal.Playable` — the same
green as a card the server is offering to cast. "You can act on this" is one idea, and a player who
has learned the green border once should not learn a second colour for it depending on which question
is outstanding.

**Being *chosen* is a different channel.** A translucent green over the whole card face, not a
seventh border colour. Two reasons, and the second is the design system's: a set being assembled wants
the whole card rather than its edge, and `BoardColorsTest` holds every signal to a saturation floor
and a ΔE ≥ 25 spacing from every other — a "chosen" border was tried, and those tests correctly
rejected it.

**A chosen card keeps its green border, deliberately.** `HumanPlayer.choose` removes a target that is
sent a second time, and `TargetPermanent.possibleTargets` keeps a chosen permanent in the candidate
list precisely so it can be. So a card showing both is a card that can be pressed to take the choice
back, and its detail says so.

**The life total left the status rail.** In the rail it was a number in a corner with four zone counts
under it — read when you went looking for it. It is now its own element on the centre line of each
player's own edge, mirrored exactly as the battlefields are, which is where a player is *pointed at*:
by a spell on the stack, and by a player answering a target question. It is anchored like any other
target, so the arrows from the stack reach it.

**Upstream targets a player by their id like any other object**, so the id the life total is anchored
and pressed by *is* the player id the server sent. Nothing is translated.

## 3. Scope

**In scope**
- Candidates for the outstanding prompt marked `Playable` on the battlefield, in hand, and in a
  seat's open piles.
- A translucent green over a card the player has chosen.
- Life totals as their own board element, and as the way a player is targeted.
- Removing candidate buttons from the target and declaration prompts.

**Out of scope**
- **Candidate buttons on the priority and mana prompts.** Those stay: a flashback card the server is
  offering from a graveyard, or an off-board mana source, is an *offer* rather than a target, and the
  board has nowhere to put it. Different prompt, different problem.
- **Prompts that carry their own cards** — a library search, an ability picker. Those are in no zone,
  and 0113's panel already draws them.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:feature:game`. No `:protocol` or `:bridge` change: the
candidate list and `chosenTargets` were already on the wire.

## 5. Design & approach

**`PromptPicks` is the whole seam.** Two sets — `pickable` and `picked` — projected from the
outstanding `PromptControlsUi` by `boardPicks()`, and handed to every projection that draws a card:
`battlefieldModel`, `handCards`, `tableZones`. `PromptControlsUi.marksCandidatesOnBoard` gates it, and
is false for the two prompts that already have a signal of their own (a priority window's candidates
are `Playable` for their own reason; a mana payment's are the cost being assembled).

**`BoardCardState.isSelected` is the fill.** A boolean rather than a signal, because it is a different
visual channel and a different kind of fact: every `BoardCardSignal` says what the *game* is doing to
a card, this says what the *player* has done.

**`LifeTotal` is a small component with its own state type.** `lifeTotals(vitals, picks)` builds it
from the same vitals the rail draws. `BattlefieldLayout` draws the totals in a `Box` **over** the
board rows — aligned top-centre and bottom-centre — rather than in them, so no height is reserved for
them in the games where nothing is ever targeted. Each is `anchors.anchorModifier(playerId)`, which is
what puts them on the end of an arrow.

**Removing the buttons made one gap load-bearing**, and it is closed here: `tableZones` now takes the
picks too, so a candidate in a graveyard is marked in the seat window it is already visible in. That
case — a card in a pile, which upstream sends as a count and the panel could not name — was the whole
argument for the buttons.

## 6. Testing & verification

- **Unit (`BattlefieldModelTest`):** a candidate is `Playable`, in the same green as a castable card;
  a chosen permanent is tinted *and* keeps its border; a priority prompt is not painted over; a
  targeting prompt hands the board both sets.
- **Unit (`BoardControlsTest`):** a target prompt offers **no** candidate buttons; every candidate is
  in `pickableObjectIds` whether the board draws it or not; an id the panel cannot name is still
  pickable. The priority and mana prompts keep theirs.
- **Hermetic Compose (`LifeTotalTest`):** pressing a targetable player sends their own server id; a
  player the question is not about cannot be pressed; the total is drawn whether or not anything is
  being targeted; each seat's life carries what the prompt says about that player.
- **Eyes-on:** Liliana of the Veil's `-6`, and any spell that targets a player.

## 7. Acceptance criteria

- [ ] Liliana's `-6` is playable: candidates marked, chosen cards visibly chosen, the split sent.
- [ ] A chosen card can be pressed again to take the choice back, and its detail says so.
- [ ] A spell that targets a player is answered by pressing a life total.
- [ ] An arrow from the stack to a targeted player reaches their life total.
- [ ] No candidate buttons appear in the prompt panel for a target prompt or a declaration.
- [ ] A candidate in a graveyard is marked in the seat window and can be answered from there.
- [ ] Life is still readable at a glance, in its new place.

## 8. Known issues

**There is no way to untap a land tapped for mana, and there must not be one.** Upstream has a
`PlayerAction.UNDO` that does exactly what a player wants — `PlayerImpl.playManaAbility` stores a
bookmark when the ability `isUndoPossible()`, and `GameImpl.undo` restores it, land untapped and mana
gone. It was wired up here and it **soft-locked the game**: the board redrew with the land untapped
and the outstanding prompt gone, with nothing left to press.

Upstream says why, at the call site, and it was read too late:

```java
public void sendPlayerAction(PlayerAction playerAction, UUID userId, Object data) {
    // TODO: critical bug, must be enabled and research/rework due:
    // * game change commands must be executed by game thread (example: undo)
    //SystemUtil.ensureRunInGameThread();
    switch (playerAction) {
        case UNDO:
            game.undo(getPlayerId(userId));
```

`GameImpl.restoreState` mutates the game state in place from the **network** thread while the game
thread is parked inside `HumanPlayer.priority()`'s `waitForResponse`. Nothing wakes that thread, and
the question it is waiting on no longer exists. The `fireUpdatePlayersEvent` that follows pushes a
fresh `GameView`, which is why the board looks updated and is in fact dead.

The reference client has the same always-visible Undo button, and **that is not evidence it works** —
it is the same bug, which is what the TODO is about. Do not re-add this without fixing the threading
upstream first.

What actually recovers a mis-tap: mid-cast, *Cancel this cast*, which the server rewinds and which
leaves the mana unspent. Tapped during a priority window with no cast in flight, nothing recovers it
— the mana empties when the step ends, which is the rule.

## 9. History

The first build of this story was a two-column overlay — piles dragged between columns — and it was
rejected on sight: it moved targeting off the board instead of fixing it there. What survives from it
is the research in the commit history: the wire carries no min/max and no notion of a pile split
(`HumanPlayer.getOptions` sends `UI.right.btn.text`, `targetZone` and `chosenTargets`; the cardinality
exists only in the prose of `TargetImpl.getMessage`), so no bridge signal was added and none is needed.
