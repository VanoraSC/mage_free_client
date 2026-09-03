# 0106 — Stacking identical lands: three faces, then a count

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

**The rule, as specified.** Identical lands stack horizontally up to three faces. Past three the fan
caps and a floating count takes over, so the player can tell one, two, three and *more* apart at a
glance. Tapping a stack acts on any one member, because they are identical and the game draws no
distinction. Tapped lands form their own stack.

The worked example is the specification: **four Plains show three faces and a `×4`. Tap one and the
untapped stack is three faces with no badge — three is countable again — beside a tapped stack of one.
Tap another and the two stacks are two and two.**

## 3. Scope

**In scope**
- Grouping identical lands into stacks, with the strict key §7.4 describes.
- Drawing a stack as up to three offset faces plus a count past three.
- Acting on a stack through one of its members.
- The land corner sized over stacks rather than over lands.
- Catalog boards that walk the worked example one press at a time.

**Out of scope**
- **Stacking creatures.** §7.4 says piles are for lands and tokens: ten Plains collapse and ten
  differently-developed creatures do not, because they differ. The one case that would fire — a row of
  identical tokens — is worth doing deliberately rather than as a side effect of a general rule.
- **The movement between stacks.** A land leaving the untapped stack for the tapped one should travel,
  and travelling is the animation host's job (0098). The board is not yet driven through the host, so
  the stacks change between snapshots rather than animating; wiring the host is its own step and is
  where that belongs.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`. 0105's arrangement.

## 5. Design & approach

**A stack promises "these are interchangeable — read one and you have read them all."** That promise is
the whole value of it, and it is what makes the key strict rather than convenient. Anything that makes
one member different from another keeps it out: tap state, counters, badges, combat assignment,
playability, power and toughness, and the printing — two Forests with different art are visibly two
different things however identical the game considers them.

**An attachment is absolute, not another field.** An attachment attaches to one specific instance: the
Aura is on *that* Plains, not on the group. Two identically-enchanted permanents still do not stack.
And the rule reads what the *server* said is attached rather than what we managed to resolve, so a
snapshot that names an attachment it did not send cannot quietly merge two enchanted permanents.

**The count appears only where the picture stops answering the question.** One, two and three are
visible by looking; a badge over them would repeat what the fan already says. It appears at four. This
is also why the worked example needs no special-casing: after a tap the untapped stack is three, and
three is simply countable again.

**Three faces, not n.** A fan that grew with the pile gives back exactly the space piling exists to
save — ten Plains fanned is ten Plains of board, overlapped. Three is enough to read a stack as a
stack, and past three the number is what the player wants rather than more pictures of the same land.

**The fan claims the space it covers.** Drawn with offset modifiers a stack measures one card wide and
the next thing along the row is placed on top of it, so it is a `Layout` that measures its children and
sizes to the whole fan. Measuring is also how it handles a tapped stack without knowing about tapping:
a tapped card's footprint is landscape, and the fan finds that out rather than being told.

## 6. Implementation steps

1. Group lands by the strict key; everything else is a stack of one.
2. Draw the fan, capped, with a count past the cap.
3. Size the land corner over stacks rather than over lands.
4. Catalog boards for the worked example, one press per moment.

## 7. Testing & verification

- **Unit:** four identical lands are one stack; tapping one splits it into three and one, and another
  into two and two; a counter, a different card and a playable highlight each keep a land out; an
  attachment keeps one out absolutely, *including* when the snapshot never sent the aura; a stack acts
  through one of its own members; creatures do not stack.
- **Hermetic Compose:** a stack of four shows a count and a stack of three does not; after one tap
  there are two stacks and no count; ten of a land draw at the same size as three of it, which is the
  whole point.
- **Eyes-on:** the battlefield preview's three consecutive Plains boards.

## 8. Acceptance criteria

- [x] Identical lands draw as one stack of at most three faces.
- [x] A stack of more than three carries a count; three or fewer does not.
- [x] Tapped and untapped lands are separate stacks.
- [x] Acting on a stack names one of its own members.
- [x] Any difference in state keeps a land out of a stack; an attachment keeps it out absolutely.
- [x] Ten of a land occupy about what three of it occupy.
- [x] `./gradlew check` passes and the preview walks the worked example.

## 9. References

- `docs/ui-modernization-plan.md` §7.4 — piling, and why it is for lands.
- `docs/stories/0065-battlefield-stacking.md` — the original presentation design.
- `docs/stories/0105-the-battlefield-arrangement.md` — the corner this fills.
