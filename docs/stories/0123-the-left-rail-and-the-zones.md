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
- Both graveyards on the rail, each showing its top card.
- A scrolling zone view, ordered, reachable from a graveyard's top card and from a zone count.
- The render rule: zones with cards, plus exile always.

**Out of scope**
- The stops on the phase bar. They are 0115's and they move with the bar; how a stop is *marked* on a
  vertical rail is a question this story has to answer, but what a stop **means** does not change.
- Card sizes and row layout — see #213, which has three open regressions of its own.

## 5. Open questions to settle before building

- **Where do the stop marks go?** The horizontal bar puts a dot under a step. A vertical rail with two
  columns has a different geometry, and the stop is per-step rather than per-side — so it cannot
  simply live in one of the two columns without implying it applies to that side only, which 0115
  established is not true.
- **What does the rail do to the board's width?** The status rail already occupies the left. Two rails
  side by side would take width from the battlefield, which is the thing 0112 spent the most effort
  protecting. Whether the phase rail *replaces* the status rail's position, merges with it, or sits
  outside it is a layout decision this story owns.
- **Does the top card of a graveyard replace that zone's count in the status rail**, or sit beside it?

## 6. Acceptance criteria

- [ ] The phase rail is vertical, on the left, and has two columns.
- [ ] The left column lights when it is an opponent's turn; the right when it is the player's.
- [ ] Vertical position along the rail says which phase the game is in.
- [ ] The opponent's graveyard is at the top of the rail and the player's at the bottom.
- [ ] Each graveyard shows its top card as a card.
- [ ] Pressing a graveyard opens a vertical scroll of its cards in the server's order, oldest last.
- [ ] Pressing a zone count opens the same kind of view for that zone.
- [ ] A zone with no cards is not drawn — except exile, which is always drawn.
- [ ] Stops still work, and still read as belonging to a step rather than to a side.
