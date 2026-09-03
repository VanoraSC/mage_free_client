# 0106 — Stacking identical lands: six places, and a card that turns into one

- **Epic:** EPIC-19 — Game Board Rebuild
- **Story:** #187
- **Depends on:** 0105 (the arrangement this fills), 0096 (the card tier it stacks).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.4 and
  [`0065`](0065-battlefield-stacking.md), refined by Pete on 2026-09-03 into the rule below.

## 1. Objective

Collapse identical lands into one stack, so a land corner holding twelve Plains costs about what three
cost.

## 2. Context & background

§7.4 asks for two things from the battlefield: the arrangement, and the piling. 0105 did the
arrangement and gave the lands a bounded corner; without stacking, the only way a corner holds twelve
lands is by shrinking them, which is what the corner exists to avoid.

**The rule, as specified.** A stack has **six fixed places** on one diagonal: three for upright copies,
staggered down and right as though they were attached to each other, and a turned place at each of
those, where a tapped copy lies **across** the upright one — covering its bottom half and leaving the
name, the cost and most of the art to read. The top card is the lowest and furthest right, on both
halves and for both players. Past three in a half, that half shows a floating count instead of more
faces. Tapping acts on any one copy, because they are identical and the game draws no distinction
between them, and it *animates*: the top card turns a quarter and drops onto the place it is already
in.

The worked example is the specification: **four Plains show three upright faces and a `×4`. Tap one and
a card turns off the top into the lowest, furthest-right turned place, and the count goes away — three
is countable again. Tap another and it is two upright and two turned. Tap the last and there are three
turned faces and a `×4` again.**

## 3. Scope

**In scope**
- Grouping identical lands into stacks, tapped and untapped together, with the strict key §7.4
  describes.
- Six fixed places on one diagonal, the turned half lying across the upright half, and a count per half
  rather than per stack.
- A footprint that does not change when a land taps.
- Turning and travelling a card between them when a copy is tapped, drawn in its own z-position
  throughout so it never flicks from front to back as it lands.
- Two hit regions per stack: the upright copies and the strip where the turned ones show past them.
- Reporting which half was pressed, and leaving what that means to the board.
- The land corner laid out on one line, sized so that line fits.
- A catalog board that can actually be tapped, since a transition cannot be posed.

**Out of scope**
- **Stacking creatures.** §7.4 says piles are for lands and tokens: ten Plains collapse and ten
  differently-developed creatures do not, because they differ. The one case that would fire — a row of
  identical tokens — is worth doing deliberately rather than as a side effect of a general rule.
- **Driving the movement from the animation host** (0098). The turn-and-travel is animated here by the
  stack itself, from its own counts, because it is a transition *within* one component and the host
  exists to sequence transitions *between* snapshots. When the board is wired to the host the two will
  need to agree on who owns the timing; that is a step of its own.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`. 0105's arrangement.

## 5. Design & approach

**A stack promises "these are interchangeable — read one and you have read them all."** That promise is
the whole value of it, and it is what makes the key strict rather than convenient. Anything that makes
one member different from another keeps it out: tap state, counters, badges, combat assignment,
playability, power and toughness, and the printing — two Forests with different art are visibly two
different things however identical the game considers them.

**Tap state is the one difference that does not split a stack.** Four Plains are one thing a player
controls whether two of them are tapped or none are, and two unrelated piles drifting apart across the
turn say otherwise. One stack with two halves keeps the count in one place — and gives a tapping card
somewhere to travel *to*, which is what makes the movement mean anything.

**A count per half, not per stack.** With three upright and one turned there are four copies, and a
badge saying four would be counting a card the player can already see. Each half answers only for
itself, which is why tapping one of four makes the count vanish rather than persist.

**The travelling card is drawn, not moved.** The copies are identical, so tracking which server id sits
in which place would be work in service of a distinction nobody can see — and it would be fragile, since
the snapshot has no idea which copy the player pressed. The places are drawn from counts instead, and
when the turned count rises a single card is animated from the upright top place to the one it is
arriving at. The stack behind it never re-flows, which is exactly what fixed places bought.

**An attachment is absolute, not another field.** An attachment attaches to one specific instance: the
Aura is on *that* Plains, not on the group. Two identically-enchanted permanents still do not stack.
And the rule reads what the *server* said is attached rather than what we managed to resolve, so a
snapshot that names an attachment it did not send cannot quietly merge two enchanted permanents.

**The count appears only where the picture stops answering the question.** One, two and three are
visible by looking; a badge over them would repeat what the fan already says. It appears at four. This
is also why the worked example needs no special-casing: after a tap the untapped stack is three, and
three is simply countable again.

**The two halves share one diagonal.** Side by side they cost the width of two stacks, and they made
the corner wrap — which is what put a Swamp on its own line under the Islands. Laid across each other
they cost the overhang of a card on its side, and they read the way a tapped land does on a table.

**The footprint never changes when a land taps.** It always allows for the turned half, occupied or
not. A stack that grew as its first land tapped would resize the corner, which resizes every card on
the board — and §7.3 is clear that movement means a game action happened. One land turning must not
make the opponent's creatures jump.

**Three places, not n.** A stagger that grew with the pile gives back exactly the space stacking exists
to save — ten Plains fanned is ten Plains of board, overlapped. Three is enough to read a stack as a
stack, and past three the number is what the player wants rather than more pictures of the same land.

**Both halves stagger the same way, front card on top.** Arrivals fill the turned half from its front
place backwards, but they are *drawn* back to front, so the lowest and furthest right is on top in both
halves. Drawing them in arrival order instead makes the two halves mirror images, which reads as a
mistake rather than as a rule.

**The card is rotated here, not handed `tapped = true`.** The stack needs *partial* turns — a card
halfway through a tap is at forty-five degrees — and the card tier flips its own footprint from portrait
to landscape when told it is tapped, which would jump. That logic is right everywhere it is used and
wrong inside a layout that has already decided where everything goes.

## 6. Implementation steps

1. Group lands by the strict key; everything else is a stack of one.
2. Draw the fan, capped, with a count past the cap.
3. Size the land corner over stacks rather than over lands.
4. Catalog boards for the worked example, one press per moment.

## 7. Testing & verification

- **Unit:** four identical lands are one stack; tapping moves a copy between its halves rather than
  splitting it; a counter, a different card and a playable highlight each keep a land out; an
  attachment keeps one out absolutely, *including* when the snapshot never sent the aura; a tap names
  a copy that is actually untapped; a fully tapped stack offers nothing to tap; creatures do not stack.
- **Hermetic Compose:** four upright shows a count and three does not; after one tap there is still one
  stack and no count; a half counts only itself; a fully turned stack counts on its turned half; ten of
  a land occupy what three of it occupy.
- **Eyes-on:** the battlefield preview, whose Plains board is tapped rather than stepped through — the
  turn-and-travel only exists while it is running.

## 8. Acceptance criteria

- [x] Identical lands draw as one stack with an upright half and a turned half, three places each.
- [x] Tap state moves a copy between the halves rather than splitting the stack.
- [x] Each half carries its own count, shown only past three.
- [x] Tapping turns a card a quarter and drops it onto the place it is already in.
- [x] The travelling card keeps its z-position for the whole flight.
- [x] A press reports which half it landed on, so the board can act differently on each.
- [x] Any other difference in state keeps a land out; an attachment keeps it out absolutely.
- [x] The land corner lays its stacks on one line, and lands of one player never fall onto separate
      lines while there is room.
- [x] Ten of a land occupy about what three of it occupy.
- [x] `./gradlew check` passes and the preview can be tapped.

## 9. References

- `docs/ui-modernization-plan.md` §7.4 — piling, and why it is for lands.
- `docs/stories/0065-battlefield-stacking.md` — the original presentation design.
- `docs/stories/0105-the-battlefield-arrangement.md` — the corner this fills.
