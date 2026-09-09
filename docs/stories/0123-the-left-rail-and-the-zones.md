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

## 7. Acceptance criteria

- [ ] The phase rail is vertical, on the left, and has two columns.
- [ ] The left column lights when it is an opponent's turn; the right when it is the player's.
- [ ] Vertical position along the rail says which phase the game is in.
- [ ] The opponent's graveyard is at the top of the rail and the player's at the bottom.
- [ ] Each graveyard shows its top card as a card.
- [ ] Pressing a graveyard opens a vertical scroll of its cards in the server's order, oldest last.
- [ ] Pressing a zone count opens the same kind of view for that zone.
- [ ] A zone with no cards is not drawn — except exile, which is always drawn.
- [ ] A stop is set per side: the left column stops on an opponent's turn, the right on the player's.
- [ ] Both sides stop at the end step by default, and either can be turned off.
- [ ] Your own M1 and M2 still stop whatever the marks say — that is a rule, not a default.
- [ ] The rail takes no width from the battlefield.
- [ ] The zone counts are still shown, alongside the graveyard's top card.
