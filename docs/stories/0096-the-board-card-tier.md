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
- A Board-tier card component: name, P/T, counters on the face, tap rotation, and the signal states
  from §3.1 (playable-now, targeted, attacking/blocking, threat).
- The tap rotation as a **90° rotation of the card**, not a badge — §3.1 calls it "universal Magic
  idiom, cheaper to read than a badge", and it has layout consequences the component owns.
- A catalog entry showing the tier in each state.

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

**Counters render on the face** (§3.1) and the count is open-ended: poison, energy, experience,
+1/+1, and hundreds more, with the kind arriving as a string. So the component takes a list and shows
kinds it knows how to place, degrading legibly rather than assuming a closed set.

## 6. Implementation steps

1. Read `CardTile`, `FullCardView`, `CardDisplay` and `CardArtSlot` to match the family's existing
   shape rather than inventing a parallel one.
2. Add the Board tier against 0095's tokens.
3. Catalog entry: the tier untapped, tapped, with counters, and in each signal state.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test asserting a tapped card is rotated must fail
  against an untapped rendering, then pass.
- **Hermetic Compose (`src/testDebug`):** counters appear on the face; tap state rotates; each signal
  state is distinguishable; a card with an unknown counter kind still renders.
- **`CardTile` and `FullCardView` behave as before**, with their tests unedited.
- **Eyes-on:** the catalog at board size — is a name readable, is a counter readable, is tapped
  obvious at a glance.

## 8. Acceptance criteria

- [ ] A Board tier exists, rendering name, P/T, counters, tap state and the §3.1 signal states.
- [ ] The tier computes nothing about the game; every state is passed in.
- [ ] `CardTile`/`FullCardView` and their screens are unchanged.
- [ ] `./gradlew check` passes and the catalog shows every state.

## 9. References

- `docs/ui-modernization-plan.md` §7.5 (the tier table), §3.1 (per-card signals), §7.11 (art states).
- `core/designsystem/src/main/kotlin/magefree/designsystem/card/` — the existing family.
