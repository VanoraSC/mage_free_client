# 0107 — The hand: always visible, always readable, never a gesture away

- **Epic:** EPIC-19 — Game Board Rebuild
- **Story:** #188
- **Depends on:** 0096 (the Tile tier), 0105 (the board it sits under), 0106 (the stacks beside it).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.4, §7.5, §7.1
  and §3.1.

## 1. Objective

Put the player's hand on the board's base layer, at the Tile tier, with the cards the server says are
playable marked as such.

## 2. Context & background

**§7.4 is unusually specific about the hand, and it is a rule about interaction rather than about
layout.** *"The hand never collapses. The player reads their hand constantly to make decisions, so
hiding it behind a peek edge and an expand gesture takes the most-consulted information on screen and
puts it a gesture away. This also removes a class of interaction — no peek edge, no expand gesture, no
collapse-on-back, and no question about what state the hand was in when a prompt arrived."*

§3.1 makes the **playable-now highlight on hand and permanents** P0, for the reason the epic gives:
*"can I do this?" is never a question I have to ask*. The board has that highlight already; the Tile
tier does not, because until now nothing rendered a tile in a game.

**Everything it needs is already on the wire.** `GameState.hand` is the viewer's own cards and
`GameState.playable` is what upstream says can be acted on right now. Neither is derived here.

## 3. Scope

**In scope**
- The hand as a region of the base layer, under the viewer's own half.
- The Tile tier, with the printing the server named.
- A playable highlight on the tiles the server marked, using the board's own signal colours.
- Tap to act, long-press to inspect — §7.1's vocabulary, unchanged.
- Never collapsing: every card visible at all times, overlapping when the hand outgrows the width.

**Out of scope**
- **What a tap on a hand card *does*.** That is the cast flow, which exists (0102, 0103) and is
  driven by the server's prompts; connecting the two is the story that mounts the board against a
  live session.
- The opponent's hand *count*, which is player vitals and belongs with life and library.
- Cards animating between hand and battlefield. That is the animation host, and the board is not
  driven through it yet.
- Dragging a card to play it. §7.1 makes drag an accelerator that always has a tap path; the tap path
  comes first.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`.

## 5. Design & approach

**The hand overlaps rather than scrolls.** A scrolling hand is a collapsing hand by another name: the
cards past the edge are a gesture away, which is the thing §7.4 rules out. So when the hand outgrows
its width the tiles overlap, and every card keeps a visible edge. The overlap is only as much as it
has to be — a seven-card hand at a comfortable size does not overlap at all.

**What the overlap leaves showing is the card's own left edge**, so the tile has to carry its name
where a strip of it can still be read. That is what the Tile tier already does.

**The playable highlight belongs in the design system, not the hand.** The board draws its signals
with `BoardSignal`'s colours and the Tile tier should draw the same signal the same way, or a player
learns two vocabularies for one fact. So `CardTile` gains a signal, defaulting to none — the deck
builder and the card browser have no signals to show and are unaffected.

**Nothing about the hand is derived.** `GameState.playable` is upstream's own list of what this player
may act on. A client that decided for itself which cards were castable would be answering a rules
question — the same one §7.6 refuses to answer during a cast.

## 6. Implementation steps

1. Give the Tile tier a signal, drawn the way the Board tier draws one.
2. A hand model over `GameState.hand` and `GameState.playable`.
3. The hand region, overlapping when it must, under the viewer's half.
4. The preview shows a hand, including one large enough to overlap.

## 7. Testing & verification

- **Proven failing first (standard 1):** the overlap test must fail against a plain row.
- **Unit:** the hand carries the server's cards in order; the playable ones and only those are marked;
  a spectator has no hand.
- **Hermetic Compose:** every card is on screen however many there are; a comfortable hand does not
  overlap and a large one does; tap and long-press report the right card.
- **Eyes-on:** the battlefield preview, which now includes a hand.

## 8. Acceptance criteria

- [x] The hand is always present under the viewer's half, with no peek edge and no expand gesture.
- [x] Every card in hand is visible at once, whatever the hand size.
- [x] Cards the server marked playable are highlighted, in the board's own signal colour.
- [x] Tap and long-press follow §7.1, and report which card.
- [x] Nothing about playability is derived from the card.
- [x] `./gradlew check` passes and the preview shows a hand.

## 9. References

- `docs/ui-modernization-plan.md` §7.4 (the hand never collapses), §7.5 (tiers), §7.1 (gestures),
  §3.1 (the playable highlight).
- `docs/stories/0105-the-battlefield-arrangement.md` — the base layer this joins.
