# 0114 — The stack: what is happening right now

- **Story:** #201
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0112 (the board this adds a region to), 0113 (the prompt surfaces it sits beside).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.4 (board layout,
  and the stack's place in it), §7.5 (card tiers), §7.1 (a press inspects).

## 1. Objective

Draw the stack. Give it the screen while it has something on it, say what each object is doing in the
server's own words, point at what it is doing it to, and let it be read.

## 2. Context & background

**The rebuilt board does not draw the stack at all.** The portrait board had a strip for it; 0112
retired that board and did not replace the strip, so a spell being cast is currently invisible — the
only sign is that the prompt changed. A player cannot see what is resolving, what it targets, or what
they are holding priority to respond to, which is most of what priority is *for*.

**Everything it needs is already on the wire, in full.** `GameState.stack` is a list of `GameCard` in
upstream's own order; `GameCard.rules` is the **game-aware** text — the ability as it exists now,
after layers, not the printing's; and `GameCard.targets` is the list of object ids the stack object is
pointing at, which upstream fills in `addTargets`. Nothing here is derived and nothing is parsed.

**The board does not show card text by default**, which is a deliberate choice of the Board tier: a
card is its illustration, its name and its cost. That makes the glyphs over the art — counters,
badges, power and toughness — the only channel by which an ability reaches the player at a glance, and
they are currently drawn at chip size. That is the same defect class as the signal borders 0112 fixed,
one layer in.

## 3. Scope

**In scope**
- A stack region on the centre line, present only when the stack has something on it.
- Each object drawn with the board's own card, larger, with its oracle text beside it.
- Arrows from each object to each of its targets, wherever the target is drawn.
- Pressing an object opens its detail.
- The detail view enlarged, and showing a permanent's modifiers, its attachments, and the oracle text
  of each attachment.
- Counters, badges and P/T at double size, over the art.

**Out of scope**
- **The order the stack resolves in as an animation.** The region lists top-first, which is the order
  it resolves in; watching an object leave is its own piece of work.
- **Responding to a specific object.** Priority is priority — the server does not ask "respond to
  this one" — and inventing a per-object respond control would be inventing a rule.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:feature:game`.

## 5. Design & approach

**The stack goes on the centre line, because that is where it is.** The board already leaves a gap
there so the two front rows read as two armies rather than one crowd. A stack region opens in that gap
when there is something on it, and closes when there is not — which is the board's existing rule that
no empty region holds height, and the movement is honest under §7.3 because a spell arriving *is* a
game action.

It is also the only place the arrows can work. The alternative — a panel over the board, which is
what "give it the screen" first suggests — covers the permanents the arrows would point at, and a
stack that cannot show what it is targeting has lost the more useful half of what it knows.

**The arrows are drawn from measured positions, not from layout arithmetic.** Every card that can be
an anchor reports its own bounds in the board's coordinate space as it is placed; the arrows are one
overlay drawn from that map. Deriving the positions instead would mean re-deriving the whole layout —
the centre shift, the land column's width, a row's scroll offset — in a second place that would drift
from the first.

**A target that is not on screen gets no arrow, and that is not a failure.** A spell can target a card
in a graveyard or a player. An arrow to a place is worse than no arrow; the detail view names every
target in full, which is the answer for the ones the board cannot point at.

**The glyphs double and nothing shrinks.** Counters and badges are overlays inside the card's own box
— they are not part of `boardCardWidthFitting`, which accounts for attachments only — so the card
keeps its size and the marks stop being decoration.

## 6. Implementation steps

1. Counters, badges and P/T at double size.
2. The stack model, from `GameState.stack`.
3. The stack region on the centre line, with each object's oracle text.
4. The anchor map, and the arrows over it.
5. The detail view: bigger, with modifiers, attachments and their text.

## 7. Testing & verification

- **Unit:** the stack reads top-first; an object carries its own rules text and its targets.
- **Hermetic Compose:** an empty stack draws no region and takes no height; a non-empty one is drawn
  between the two sides; an object shows its oracle text; pressing one raises it; an arrow is drawn
  for a target that is on the board and none for one that is not.
- **Eyes-on:** a real game with a targeted spell in it.

## 8. Acceptance criteria

- [ ] Nothing on the stack draws no region, and the battlefield is exactly as it was.
- [ ] Something on the stack opens a band on the centre line, with the object drawn as a board card.
- [ ] Each object shows the server's own rules text for it.
- [ ] Each target that is drawn on the board has an arrow to it.
- [ ] Pressing a stack object opens its detail.
- [ ] The detail view shows a permanent's attachments and the oracle text of each.
- [ ] Counters, badges and P/T are twice their previous size, over the art, and no card is smaller.
