# 0096 — The Board card tier, and one coherent card family

- **Epic:** EPIC-03 — Design System & Theming (Phase 1)
- **Depends on:** 0095 (the board tokens it renders with).

## 1. Objective

Add the **Board** rendering tier — the card as it appears on a battlefield or in a stack pile — and
make the three tiers §7.5 defines one coherent family.

## 2. Context & background

**Two of the three tiers already exist; the one the board needs does not.** `:core:designsystem`
carries `CardTile` (the Tile tier: hand, zone browsers, deck lists) and `FullCardView` (the Full
tier: inspection, mulligan, sideboard), both built on the presentation-only `CardDisplay` model and
the abstract `CardArtSlot`. There is no Board tier, which is the one every permanent on the new board
will be drawn with.

**§7.5's table is the contract:**

| Tier | Where | Shows | Art |
|---|---|---|---|
| **Board** | Battlefield, stack piles | Name, P/T, counters, tap state, status | Downsampled, cropped to the art box |
| **Tile** | Hand, zone browsers, deck lists | Name, cost, type line, P/T | Downsampled full card |
| **Full** | Inspection, mulligan, sideboard | Oracle text, current modifications, activatable abilities, flip control | Full resolution |

Art resolution differs per tier, as the table says, and each tier asks its slot for what it needs.
That is a property of the component rather than an optimisation exercise: build the tiers, and tune
resolution later if it ever turns out to matter.

**The Board tier is where the P0 signals land.** Counters rendered on the card face, tapped as a 90°
rotation, the playable-now highlight, and the status a permanent carries — all of §3.1's per-card
signals are properties of this component. The data for every one of them now exists on the wire:
counters, tap state, attachments, token identity and targets all arrive on `GameCardView` /
`GamePermanentView`.

## 3. Scope

**In scope**
- A Board-tier card component rendering the **whole card face**, so the card's own printed name and
  mana cost do the work an overlay would otherwise cover. Power and toughness stay overlaid, because
  they change during a game and the printed pair goes stale.
- P/T, counters, keyword badges, attachments, tap state, and the §3.1 signals.
- **Counters as a filled circle carrying only the count.** `+1/+1`, `-1/-1` and loyalty have fixed
  colours; every other kind takes the next colour from a queue on first sight and keeps it for the
  game. An alternating black-and-white ring makes a circle read on any background, and the digit
  flips by the fill's lightness so an allocated colour can never produce an unreadable number.
- **Emphasis follows a `BoardFocus`** rather than a fixed precedence: the signal the current moment is
  about takes a strong border, and the most immediate remaining one a thin muted edge. A card carries
  several signals at once and has only one border, and which of them matters is a property of the
  moment rather than of the card.
- **Attachments as whole cards behind the host**, each leaving the band that carries its name and cost
  uncovered. An attachment carries its own tap state, because it is its own permanent — improvise and
  convoke tap artifacts that are still attached.
- The tap rotation as a **90° rotation of the card**, not a badge — §3.1 calls it "universal Magic
  idiom, cheaper to read than a badge", and it has layout consequences the component owns.
- A catalog entry showing the tier in each state, rendered with **real card art** supplied by the host
  through the existing `CardArtSlot`.

**Deliberately not settled here**
- Badges are placeholders. The data behind them is `CardView.cardIcons`, which the server already
  computes from game-aware abilities and the bridge drops — story 0099.
- Attachments have tap state but not their own signals. An Equipment about to be tapped for a cost is
  a pending-cost target, which belongs with the board's wiring rather than this component.

**Out of scope**
- The battlefield layout that arranges these (§7.4's front-to-back rows, the land pile). That is the
  board itself, Phase 3.
- Animating between slots — that is the animation host, 0098. This story renders a card in a state;
  it does not move it.
- Targeting arrows and combat arrows, which are drawn *between* cards and belong to the board.
- Changing `CardTile` or `FullCardView` beyond what making the family coherent requires. They are on
  the old path and every edit there is a regression risk for a working screen.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`.

## 5. Design & approach

**State is passed in, never derived.** Whether a permanent is playable, targeted, attacking or tapped
is server-supplied state that already crosses the wire. The component renders what it is handed and
computes nothing about the game — the same rule the whole client runs on.

**The card face carries what it already prints.** A real card puts its name and mana cost where a
player looks for them, so overlaying our own covers the art for no gain. Only what the printing gets
*wrong* during a game is overlaid: current power and toughness, counters, badges, and the signals.

**Counter kinds are an open set** — poison, energy, experience and hundreds more, arriving as
strings — so a fixed table cannot cover them. What a player needs is narrower than global
consistency: on one board there are rarely several kinds on a single card, but often many cards each
carrying a different kind. The colour only has to say that one kind differs from another; the number
carries the precision, and the inspect view (0100) carries the name.

**A stack steps perpendicular to the band it exposes.** An upright attachment carries its name across
its top edge, so the stack steps upward. A quarter turn moves that band to the right edge, so a
turned attachment steps sideways instead. Stepping the wrong way covers the very thing the offset
exists to reveal, and this one rule covers both cases.

**A rotation moves a card, it does not resize it.** A rotated card sits in a box shorter than the card
is tall, so a size that respects the parent's constraints is clamped — measuring the card as a square
and cropping its art before the rotation turns it.

## 6. Implementation steps

1. Read `CardTile`, `FullCardView`, `CardDisplay` and `CardArtSlot` to match the family's existing
   shape rather than inventing a parallel one.
2. Add the Board tier against 0095's tokens.
3. Catalog entry against real card art, since a card component is judged on whether its overlays stay
   readable over an illustration — and a flat placeholder is the one background that flatters
   everything.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test asserting a tapped card takes a landscape footprint
  must fail against a rendering that only rotates pixels, then pass.
- **Hermetic Compose (`src/testDebug`):** counters carry their count; an unknown counter kind still
  gets a circle; badges render; an attachment shows its name and cost; the tier overlays no name of
  its own; a tapped card and a turned attachment keep card proportions rather than being squashed.
- **Footprint is asserted, not assumed:** an upright attachment claims height, a turned one claims
  width, and a second turned one claims more width and no more height.
- **`CardTile` and `FullCardView` behave as before**, with their tests unedited.
- **Eyes-on:** the catalog at board size, over real art — is a counter readable, is tapped obvious at
  a glance, and can an attached card be named without tapping it.

## 8. Acceptance criteria

- [ ] A Board tier exists, rendering the card face plus P/T, counters, badges, attachments, tap state
      and the §3.1 signals.
- [ ] Emphasis follows the board's focus rather than a fixed precedence.
- [ ] An attachment carries its own tap state and is legible without being opened.
- [ ] The tier computes nothing about the game; every state is passed in.
- [ ] `CardTile`/`FullCardView` and their screens are unchanged.
- [ ] `./gradlew check` passes and the catalog shows every state over real art.

## 9. References

- `docs/ui-modernization-plan.md` §7.5 (the tier table), §3.1 (per-card signals), §7.11 (art states).
- `core/designsystem/src/main/kotlin/magefree/designsystem/card/` — the existing family.
