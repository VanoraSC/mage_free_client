# 0110 — The status rail, and the board in three columns

- **Story:** #193
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0105 (the arrangement this replaces), 0106 (land stacks), 0108 (the card preview
  this reuses), 0109 (the vitals strips this rehouses).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.4 (board
  layout), §7.13 (the zone browser), §7.15 (vitals), §7.5 (card tiers).

## 1. Objective

Rearrange the board into three columns — a status rail, a land column, and the battlefield — and put
each player's graveyard on screen where it can be opened and read.

## 2. Context & background

**This replaces 0105's arrangement rather than adjusting it.** 0105 gave each player half the screen
and a land corner inside that half, with the vitals floating at the top and bottom edges. Seen on a
real board it has two problems that are not tuning: the land corner and the creatures compete for the
same half-width, so lands push creatures around as they accumulate, and there is nowhere on screen
that says what is in a graveyard. Both are structural, so the structure changes.

**The graveyard is the zone a player reads most often after the battlefield.** It decides flashback,
delve, escape, threshold and every "return target creature card" — and today the board says only how
many cards are in it. §7.13 designs a browser for exactly this and 0109 put it out of scope as "its
own surface and its own story". This is that story, for the graveyard; exile follows.

**Everything it needs is on the wire.** `GamePlayer.graveyard` is a full `List<GameCard>`, mapped by
`GameViewMapper`, in the server's own order. §7.13's claim that "the bridge maps almost none of this"
is out of date — graveyard, exile and `commandList` are all mapped — and is corrected here.

**The Board tier's crop broke two things that assumed a portrait card.** A card cut below its art box
is wider than it is tall, and the land stack's fixed-slot geometry was derived from a card being
taller than wide. That is why lands currently render scattered and overlapping. It is fixed here
because it is the same change.

## 3. Scope

**In scope**
- Three columns: the status rail on the left, the land column beside it, the battlefield to the
  right. Opponent above, viewer below, in the two right-hand columns.
- The status rail: each seat's graveyard and its two exile piles, each drawn as the card on top of
  it, and each seat's vitals.
- An outlined placeholder the size of a card, naming the pile, when one is empty.
- Tapping a pile opens it as a floating, scrollable list of its cards; a press outside closes it;
  tapping a card in it opens the card preview.
- Tapping any card on the battlefield opens the card preview — including an attached one, from its
  own exposed band, and an enchanted permanent's preview lists what is on it.
- Non-creature permanents on their own horizontal — below the viewer's creatures, above the
  opponent's — and toward the outside of the board, so they never sit under a creature.
- Creature rows centred on the screen rather than on their column, as far as their own slack allows.
- The land stacks' geometry, re-derived for the Board tier's own card shape.

**Out of scope**
- **Revealed and looked-at zones.** Same browser, more piles, and each has a question of its own
  about when it appears and when it goes away.
- **Acting from a zone** — flashback, plot and their relatives. That is the cast flow's business
  (§7.6) and needs a live session to submit to. A card the server is offering is *marked* here.
- Sorting or grouping a pile. Each has the server's order, which is meaningful.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`, `:app`.

## 5. Design & approach

**Three columns, because the three things have different jobs.** The status rail is read
occasionally and never moves; the lands are a fixed, bounded cost; the battlefield is what changes.
Giving each a column means lands can never push creatures around, which is what the land corner did
whenever a fourth kind of land appeared.

```
 ┌────────┬─────────────┬────────────────────────────┐
 │ opp    │             │   [ other permanents ]     │  back
 │ vitals │  opponent   │   [ creatures ]            │  front
 │ grave  │   lands     ├────────────────────────────┤
 │ other  │             │                            │
 │ exile  ├─────────────┤   [ creatures ]            │  front
 │ exile  │   your      │   [ other permanents ]     │  back
 │ other  │   lands     ├────────────────────────────┤
 │ grave  │             │   phase bar                │
 │ vitals │             │   hand                     │
 └────────┴─────────────┴────────────────────────────┘
```

**The rail is a column of cards, so it is a card wide.** Its width is a board card plus the board's
margin, capped as a share of the screen — the same rule the land column follows, and the reason
nothing in it needs its own sizing pass.

**A graveyard is drawn as its top card.** That is what a graveyard looks like on a table, and it is
also the most useful single card in it: the one that just died. Empty, it is an outline the same size
saying *Graveyard*, because a zone that vanished when empty would move everything under it every time
a game's first creature died — and unlike a battlefield row, this region's whole job is to be in a
fixed place.

**The vitals move into the rail.** 0109 put both strips together in the top right as a scoreboard,
then at the top and bottom edges; either way they were floating over the battlefield. A player's
numbers belong with that player's zones, and the rail is where the zones now are. The strip wraps
rather than clipping, since the rail is a card wide and the strip was drawn for a screen-wide bar.

**Opening a graveyard is a look, not a decision** (§7.4's floating layers, §7.1's gesture rules), so
it floats over the board, dismisses on a press outside, and takes nothing from the battlefield. It
opens the same card preview 0108 built, from the same tap, so there is one way to read a card
wherever it is.

**Non-creature permanents get their own horizontal.** They were sharing the creature row's centre
line and ending up drawn behind creatures. They now sit on their own row, on the far side of the
creatures from the centre line, aligned toward the outside — where there is room, and where they are
not in the way of the row that changes every combat.

**Exile is two piles, and the second one is the only judgement in this story.** *Other* is exile a
card is coming back from or can be cast from — plot, suspend, rebound, adventure, foretell, airbend.
§7.13 establishes that there is no single upstream flag for it and that the reference client has none
either; two signals together cover it and both are already on the wire — an effect-created zone's
*name*, and `canPlayObjects`. Neither alone is enough: a plotted card is named always and playable
only on the turn it can be cast, and an airbent card is nameless and marked only while it is
castable. The board reads both, and the pile is called *Other* rather than something more confident.

**The creature rows centre on the screen, not on their column.** The battlefield is the third column,
so centring inside it puts the creatures well right of the middle with a hole where the player is
looking. Each row slides back toward the screen's own centre — but only as far as its own unused
width allows, so a row busy enough to need its whole column stays in it and never slides under the
lands.

**A card's face is drawn width-first and anchored to the top.** That is what makes the tier's crop
work: the image fills the card's width, keeps its proportions from that alone, and whatever runs past
the bottom of the face is clipped. `ContentScale.Crop` is the wrong tool for it — it scales an image
to *cover* the box it is given, so in a box shorter than a card it takes the top and the bottom in
equal measure and the card loses its title bar; and where the box is not the height the layout
intended, it silently squashes the card instead. Both of those shipped, one after the other.

**The rail's piles are sized by its height, not its width.** A seat has three of them plus its own
numbers in half a rail; at the rail's own width they would want three times the height there is.

**The vitals read down, not across.** Life on its own line at the top, the zone counts under it, then
one counter per line below that, scrolling past what the rail can show. Across a column a card wide,
a row of chips set one letter per line — which is how *Monarch* came out as a vertical stack of seven
letters.

**The land stack geometry follows the card, not a card.** Every distance in it is now a fraction of
the Board tier's own shape rather than of a portrait card, so the crop cannot silently invert it
again. The same is true of the attachment stack in `BoardCard`.

## 6. Implementation steps

1. The graveyard model, over `GamePlayer.graveyard`.
2. The status rail: graveyard face or placeholder, and the vitals strip, per seat.
3. The graveyard browser, floating, and its card preview.
4. The three-column layout, replacing 0105's halves.
5. Re-derive the land stack geometry for the Board tier's shape.
6. Wire battlefield taps to the card preview in the preview screen.

## 7. Testing & verification

- **Proven failing first (standard 1):** the empty-graveyard placeholder test must fail against a
  rail that draws nothing when the zone is empty.
- **Unit:** the top card is the one the server lists last; an empty graveyard yields no card; a
  spectator sees every seat's rail.
- **Hermetic Compose:** the rail shows both seats; an empty graveyard shows the placeholder; tapping
  a graveyard lists its cards; a press outside closes it; tapping a card in it opens the preview;
  tapping a battlefield card opens the preview; the land column never overlaps the battlefield.
- **Eyes-on:** the battlefield preview, on the developed board.

## 8. Acceptance criteria

- [x] The board reads as three columns, with the opponent above and the viewer below in the two
      right-hand ones.
- [x] Each seat's graveyard and its two exile piles are on screen, each as its top card or as a
      placeholder the same size.
- [x] Each seat's vitals are in the rail, against that seat's own edge of the screen, reading down.
- [x] Tapping a pile opens a scrollable list of it; a press outside closes it; tapping a card in it
      opens the card preview.
- [x] Tapping any battlefield card opens the card preview, including an attached one, and an
      enchanted permanent's preview lists what is on it.
- [x] Creature rows are centred on the screen wherever they have the width to be.
- [x] Non-creature permanents sit on their own horizontal and never under a creature.
- [x] Land stacks render correctly at the Board tier's card shape.
- [x] `./gradlew check` passes and the preview shows all of it.

## 9. References

- `docs/ui-modernization-plan.md` §7.4 (board layout, floating layers), §7.13 (the zone browser),
  §7.15 (vitals), §7.5 (card tiers), §7.1 (tap acts, long press inspects).
- `docs/stories/0105-the-battlefield-arrangement.md` — the arrangement this replaces.
- `docs/stories/0108-inspect-and-cast-from-hand.md` — the card preview this reuses.
