# 0123 — The left rail, and the zones

- **Story:** #219
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0112 (the board), 0117 (the board holds its shape).
- **Specified by:** Pete, on an annotated screenshot, 2026-09-09.

## 1. Objective

Move the phase bar to the left edge as a vertical rail that says *whose turn* and *which phase* at
once, put each player's graveyard beside it, and make every zone with cards in it something you can
open and scroll.

## 2. What is being replaced

The phase bar runs horizontally above the hand, and whose turn it is lives in `PhaseBarState.turn` as
a colour on the current step. Zones are counts in the status rail; opening a seat gives one window with
a column per pile, and a graveyard is a column of cards with no sense of order.

## 3. The design, from the annotation

**The rail is vertical, on the left, and it is two columns side by side.**

- **Left column illuminated light grey** — it is the **opponent's** turn.
- **Right column illuminated green** — it is the **player's** turn.
- **Vertical position** along the rail is the **phase**.

So one glance answers both questions the current bar answers separately, and the answer to *whose
turn* is a side rather than a colour applied to a step. The two columns are always present; only the
illumination moves.

**The graveyards sit at the two ends of the rail**, mirrored exactly as the board is:

- **top left** — the opponent's graveyard;
- **bottom left** — the player's.

**A graveyard shows its top card**, drawn as a card rather than as a count. That is what a graveyard
looks like on a table and it is the single most useful card in it.

**Pressing it opens a scrolling view** of that graveyard, **in the server's own order** — bottom of the
list entered first. The annotation is explicit: two cards, green above orange, means orange entered
first.

**Pressing a zone count opens the same kind of view** for that zone. And:

- **only zones that have cards are rendered** — the board's own rule about regions that hold height;
- **exile is the exception**: it is drawn even when empty, so a player can always confirm there is
  nothing there rather than having to infer it from an absence.

## 4. Scope

**In scope**
- The vertical two-column rail: whose turn by side, phase by position.
- **Stops per side**, and an end-step stop on both turns by default — see §5.
- Both graveyards on the rail, each showing its top card.
- A scrolling zone view, ordered, reachable from a graveyard's top card and from a zone count.
- The render rule: zones with cards, plus exile always.

**Out of scope**
- **How a stop behaves once set.** That is 0115's and does not change: `Once` fires at the next
  occurrence and clears, `Always` fires every time, and your own main phases stop regardless. What
  changes here is *which side a mark applies to* — see §5.
- Card sizes and row layout — see #213, which has three open regressions of its own.

## 5. The stops become per-side again — and that is the rail's doing

**0115 merged the two sides, and this un-merges them.** That is a reversal, not a drift, and the reason
is geometry. 0115's own words: *"the bar has one row and a row cannot say which side a mark belongs
to"* — so a mark pressed on your turn silently set only your side, and a player had no way to see why
the opponent's upkeep went past. Merging was the only honest thing a single row could do.

**A two-column rail is a different shape, and it can say it.** Each column *is* a side. A mark in the
left column means *stop here on their turn*; a mark in the right means *stop here on mine*. The thing
that made the per-side model unreadable is exactly what the rail supplies.

This also puts the client back in step with upstream, which was never merged:
`UserSkipPrioritySteps` holds a `SkipPrioritySteps` per side natively, and 0115 has been sending the
same set to both. That extra mapping goes away.

**And the end step stops by default, on both turns.** Upstream defaults `endOfTurn` to `false` on both
sides; this client will default it to `true` on both. It is the window a player most often wants and
most often forgets to ask for, and one they lose a game to. **Disableable like any other stop** — it
is a default, not a rule, which is what separates it from the two main phases (those are a rule, see
`OWN_MAIN_PHASE_STOPS`).

## 6. The other two questions, answered

**The rail costs the board no width.** It is bounded by the width of the graveyard and the card-count
display, which are already on the left. So this is a rearrangement of a column that exists rather than
a new claim on the battlefield — which is what 0112 spent the most effort protecting.

**The graveyard's top card does not replace the card counts.** Both are shown. The counts are the
information; the top card is there so a graveyard looks like a graveyard — and so that a future story
has somewhere to animate a card *going* to it. It earns its room by being the destination of a
movement, not by carrying a number the counts already carry.

## 7. How it was built

**The rail is a new component, not the bar turned sideways.** `PhaseRail` in the design system draws a
row per step with a cell per side and the step's name **between** the two columns. The label went in
the gutter rather than inside a cell for a reason worth keeping: inside, it would be dark-on-light in
whichever cell happens to be lit and light-on-dark in the other, which is one word rendered two ways
on a single row.

**The stops model gained a side and lost a mapping.** `BoardStops` holds a map per `TurnSide`, and
`asSteps(side)` reads that side's own marks — 0115 sent the same set to both because the bar could not
tell them apart. `BoardStops.Default` marks the end step `Always` on both sides.

**The locks got simpler, which was not expected.** `lockedStops(state)` had to read the snapshot to
know whether your-main-phase lock applied *right now*, because one row stood for whichever turn was
being played. A column **is** a side, so the lock belongs to the column and the turn in progress has
nothing to do with it. `TablePhases.kt` is gone; `TableRail.kt` replaces it and is shorter.

**The turn leaving the bottom gave height back.** The horizontal bar and its gap were about forty dp of
the board's height, and `bottomStackHeight` no longer reserves them. So §6's promise that the rail
costs the board no width arrived as *height returned* on top of it.

**The counts are one door, not four.** A press on any of a seat's counts opens *everything behind
them at once* — exile always, plus any other pile with cards in it — rather than the pile that was
pressed. Not the graveyard, which has its own card on the rail and its own press; not the hand, which
the board already draws along the player's own edge. "What has this player got that is not on the
board" is one question, and answering it a pile at a time makes the player ask it four times to find
out three of the answers were empty. `ZoneViewer` therefore takes a *list* and draws a column per
pile.

**Cards in a pile ask for `boardArt`.** `TableCard.art` is the request as the server named it, at
whatever size; a Board-tier card draws the illustration alone inside its own frame, so a full card
scan handed to one draws a whole printing — borders, text box and all — shrunk inside a name plate.
The hand and the battlefield already ask for the crop; the rail and the viewer now do too.

**Where the seat window still lives.** `PlayerOverlay` — all of a seat's piles side by side — is
unchanged and still opens by pressing the strip itself. `ZoneViewer` is the new single-pile view, and
it is what a graveyard or a count opens. Two surfaces, because they answer different questions.

## 8. Verification

`./gradlew check -x :bridge:test -x :bridge:check`. New tests:

- `TableRailTest` — the projection: every server step maps onto the rail or onto nothing, which column
  is lit, which locks sit in which column, and that a mark lands on one side only.
- `PhaseRailBoardTest` — the rail on a real board: pressing each column reports its own side, a rule
  refuses the press, the rail sits between the two graveyards, a graveyard and a count each open their
  pile, an empty graveyard still holds its place, and the rail takes no width from the battlefield.
- `ZoneViewerTest` — order above all: the card on top of the pile is the card at the top of the list.

Rewritten rather than patched, because 0123 reverses what they asserted: the `GameBoardViewModel`
stops tests said *one press stops the step on both turns*, and the two that mattered most now say the
opposite — a press sets one side, and an opponent's window does not spend a one-shot set on your own.

## 9. Acceptance criteria

- [x] The phase rail is vertical, on the left, and has two columns.
- [x] The left column lights when it is an opponent's turn; the right when it is the player's.
- [x] Vertical position along the rail says which phase the game is in.
- [x] The opponent's graveyard is at the top of the rail and the player's at the bottom.
- [x] Each graveyard shows its top card as a card.
- [x] Pressing a graveyard opens a vertical scroll of its cards in the server's order, oldest last.
- [x] Pressing a zone count opens the same kind of view for that zone.
- [x] A zone with no cards is not drawn — except exile, which is always drawn.
- [x] A stop is set per side: the left column stops on an opponent's turn, the right on the player's.
- [x] Both sides stop at the end step by default, and either can be turned off.
- [x] Your own M1 and M2 still stop whatever the marks say — that is a rule, not a default.
- [x] The rail takes no width from the battlefield.
- [x] The zone counts are still shown, alongside the graveyard's top card.
