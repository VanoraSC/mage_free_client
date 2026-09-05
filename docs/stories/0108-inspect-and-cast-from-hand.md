# 0108 — Inspecting a card, and playing it from the hand

- **Story:** #190
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0107 (the hand), 0105 (the board it opens over).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.1, §7.5 and §7.4,
  and by Pete on 2026-09-05 with the layout below.

## 1. Objective

Show a card properly when it is inspected, and give a playable card in hand the two ways of playing it
that §7.1 asks for — a tap path and a drag accelerator.

## 2. Context & background

0107 put the hand on the board and marked what the server says is playable. Neither gesture went
anywhere: a tap reported an id and a long press reported an id, and nothing was drawn. This is the
other half of that.

**The Tile's caption is dead weight in the hand.** A tile draws a card-shaped art region and then
repeats the name, cost and type line underneath it — which is right in the deck builder, where a row
may have no art at all, and wrong on the board, where the art *is* the whole card face with its name
and cost already printed on it. In a hand of twelve it cost a third of the tile's height to say what
the picture said.

**Where each field comes from, since the details column asks for several.**

| Shown | Source | Note |
|---|---|---|
| Name, mana cost, type line | `GameCard` | Cost is drawn with the shipped symbols (0103). |
| Power / toughness | `GameCard.power` / `.toughness` | Current values after effects, not the printing's. |
| Abilities | `GameCard.rules` | **Game-aware**: a creature granted flying until end of turn has it here. |
| Oracle text | `cards.sqlite` — the bundled Scryfall data | The **printed** text, which is a different thing. A local lookup, no network. |

That the two text fields differ is the point of showing both: the abilities are what the permanent can
do right now, and the oracle text is what the card says. A card with a granted ability shows the
difference immediately.

## 3. Scope

**In scope**
- A landscape card preview: the card at a share of the screen height, undistorted, with a details
  column beside it at the card's own height, scrolling when it must.
- A Play or Cast action on it when the server says the card is playable.
- Opening it from a tap or a long press on a hand card; dismissing it by pressing anywhere else.
- Dragging a playable card out of the hand as a shortcut for that action.
- The hand's tiles losing their caption.
- A mock screen that demonstrates all of it, and the catalog reaching the board without scrolling.

**Out of scope**
- **Actually casting.** The mock reports the action; wiring it to the cast flow (0102, 0103) is the
  story that mounts the board against a live session, and until then a Cast button that did something
  would be lying about what the server had been told.
- Inspecting a *permanent*, which already has its own view (0100) built around counters, badges and
  attachments. See §5.
- The board's own regions **no longer share horizontal bands** — that came in with this work rather
  than being planned for it, and is described in §5.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`, `:app`.

## 5. Design & approach

**A second inspect view, and not a mistake.** `BoardInspectView` (0100) already puts a card beside a
detail panel in landscape, and this deliberately does not reuse it: that view inspects a *permanent* —
it draws the Board tier, sizes itself against the whole attachment assembly, and lists counters and
badges. This inspects a *card*, at the Full tier §7.5 reserves for inspection, where the point is to
read the card face itself. They share a silhouette and almost nothing else, and folding them together
would mean one component with two disjoint halves.

**The card is sized by height, and the width follows.** A card has one shape; giving it a width and
letting the height fall out is how a preview ends up either cropped or stretched. So the height is the
share of the screen it is allowed, and the width is that height times the card's own ratio.

**Play or Cast is a wording choice, not a legality one.** The server has already said the card is
playable; naming the button after the card's type is only using the word a player would. Nothing about
whether the action is *allowed* is decided here.

**The drag is an accelerator with a tap path, which is §7.1's rule.** Dragging a playable card out of
the hand does exactly what the button does, and it is the same action either way — so it is offered
only for a card the server marked playable, and a drag on anything else simply returns the card.

**Dismissal is a press anywhere else.** The overlay covers the board with a scrim that takes the press,
which also stops a stray tap reaching a permanent underneath while a card is being read. The card
dismisses too — it is the biggest target on screen and putting it down is the obvious gesture — but the
**panel does not**, because that is where the buttons are and a finger reaching for Cast and missing by
a few dp must not close the card being read.

**The scrim is a sibling behind the content, not a wrapper around it.** Wrapped, its `clickable` merges
the card and the panel into one accessibility node: a screen reader announces the whole overlay as a
single button, and every press inside it dismisses — including on Cast.

**The board's regions overlay rather than sharing bands.** Not planned for this story; it came out of
looking at the last one. Cutting the board into horizontal bands — opponent, viewer, hand — held the
hand's band open across the *full width* even though a hand only ever occupies the middle of it, and
the visible cost was a land corner floating above the bottom of the screen with a rectangle of nothing
under it. The sides are anchored instead, opponents to the top and the viewer to the bottom, each
taking half the board rather than a share of what is left; the hand goes into the space the viewer's
own rows leave, beside the land corner rather than over it. A hand covering the lands would put the
cards you tap for mana under the cards you tap to spend it.

**The printed oracle text comes off the device.** `cards.sqlite` is Scryfall-derived and carries every
card's rules, so it is a lookup and not a request — no network, and it works on the first turn after an
install. Looked up by name, because a snapshot always has one and oracle text is per card rather than
per printing.

## 6. Implementation steps

1. Give the Tile tier a caption switch, and turn it off in the hand.
2. The card preview: card, details column, action.
3. Open it from the hand's tap and long press; dismiss on a press outside.
4. The drag accelerator.
5. A mock screen, and the catalog's battlefield entry moved to the top.

## 7. Testing & verification

- **Proven failing first (standard 1):** the undistorted-card test must fail against a card sized by
  width.
- **Unit:** a land offers Play and a spell offers Cast; an unplayable card offers neither.
- **Hermetic Compose:** tap and long press both open the preview; a press outside dismisses it; the
  card keeps its aspect ratio; the details column carries every field; a drag past the threshold plays
  a playable card and a drag on an unplayable one does not.
- **Eyes-on:** the new mock screen.

## 8. Acceptance criteria

- [x] Hand tiles show the card and nothing else.
- [x] Tap or long press on a hand card opens a preview; a press elsewhere closes it.
- [x] The card fills its share of the height and keeps its proportions.
- [x] The details column shows name, cost in symbols, abilities, power/toughness for a creature, and
      oracle text, scrolling when it must.
- [x] A playable card offers Play (land) or Cast (spell); an unplayable one offers neither.
- [x] Dragging a playable card out of the hand does the same thing as the button.
- [x] The catalog reaches the battlefield without scrolling.
- [x] `./gradlew check` passes and the mock screen demonstrates all of it.

## 9. References

- `docs/ui-modernization-plan.md` §7.1 (tap acts, long press inspects, drag is an accelerator with a
  tap path), §7.5 (the Full tier is for inspection), §7.4 (floating layers).
- `docs/stories/0107-the-hand.md` — the hand these gestures start from.
- `docs/stories/0100-the-board-inspect-view.md` — the *permanent* inspect view, and why this is not it.
