# 0100 — The board inspect view

- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0096 (the tier it zooms from), 0099 (the icons it explains).

## 1. Objective

The view a player gets when they zoom a permanent on the board: the card at full size with a side
panel carrying its attachments, its counters and its current modifications in detail.

## 2. Context & background

**The Board tier trades detail for density on purpose.** A counter is a coloured circle with a number,
an attachment is a name-and-cost band, a keyword is a small badge. That works because the player can
always ask for more — and this is where they ask.

**So the two halves are designed together.** The board can be terse precisely because the detail is
one gesture away; without this view, the Board tier's compression turns into information loss. A
counter kind whose colour a player does not recognise has to be nameable *somewhere*, and this is the
somewhere.

**It is a new view, not a change to `FullCardView`.** The existing full view serves inspection from the
deck builder and the card browser, and §11 is explicit that the old path is not edited to accommodate
the new one. The board needs different content anyway: what is attached to this permanent, what
counters it carries, and what is currently modifying it — none of which a card in a deck list has.

**Every field it needs is already on the wire.** EPIC-23 landed `attachments`, `attachedTo`,
`attachedControllerDiffers` and `counters`; `CardView.rules` carries the game-aware rules text, so a
granted ability is already in the text the server sends.

## 3. Scope

**In scope**
- The zoomed card at a size worth reading, with the side panel beside it.
- Attachments listed with what they are and who controls them — `attachedControllerDiffers` is the
  case worth surfacing, because your Aura on their creature is real and easily missed.
- Counters in full: kind name, count, and the colour the board is using for that kind, so the panel
  teaches the board's own shorthand rather than replacing it.
- The keyword badges with their names and hints, once 0099 supplies them.
- Current modifications, from the server's game-aware rules text.

**Out of scope**
- Any change to `FullCardView` or the screens that use it.
- The gesture that opens it, which belongs to the board.
- Activating abilities from the panel. Reading and acting are separate problems; this one is reading.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`.

## 5. Design & approach

**The panel is beside the card, not over it.** A zoomed card the panel covers is a zoom that hides the
thing it was opened to show. Landscape makes this the natural arrangement rather than a compromise.

**The panel explains the board's shorthand using the board's own colours.** A counter row shows the
same circle the card shows, next to its name. That is what turns the colour queue from an arbitrary
allocation into something a player learns, and it is why the panel takes the live `CounterPalette`
rather than picking its own colours.

**It renders a presentation model and computes nothing**, the same rule as every other component here.

## 6. Implementation steps

1. Define the presentation model: the card, its attachments, its counters, its badges, its
   modifications.
2. Build the view against the board tokens, panel first — it is the part with a size budget.
3. Catalog entry: a bare permanent, a heavily enchanted one, and one carrying counter kinds this build
   does not recognise.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test that an attachment controlled by the opponent is
  distinguished must fail against a view that lists attachments plainly, then pass.
- **Hermetic Compose (`src/testDebug`):** every attachment is listed; a differently-controlled
  attachment is marked; every counter appears with its name and the board's colour for it; an
  unrecognised counter kind is named rather than dropped; the panel does not cover the card.
- **`FullCardView` and its screens are unchanged**, asserted by their existing tests passing unedited.
- **Eyes-on:** the catalog in landscape — is the card readable, is the panel scannable, and does the
  counter row make the board's colours make sense.

## 8. Acceptance criteria

- [ ] A board inspect view exists, with the card and a side panel that does not cover it.
- [ ] Attachments, counters, badges and modifications are all listed.
- [ ] An attachment controlled by someone else is distinguishable.
- [ ] Counters are shown in the same colours the board is using for them.
- [ ] `FullCardView` and every screen using it are untouched.
- [ ] `./gradlew check` passes and the catalog renders the view.

## 9. References

- `docs/ui-modernization-plan.md` §7.5 (the Full tier), §7.4 (attachments).
- `core/network/src/commonMain/kotlin/magefree/network/game/GameState.kt` — the fields it reads.
