# 0121 — A card has a size

- **Story:** #213
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0117 (the board holds its shape).
- **Specified by:** Pete, from a board state he liked, 2026-09-09.

## 1. Objective

Give the two kinds of permanent their own preferred size, chosen from a board that read well, and keep
the existing rule that crowding shrinks them.

## 2. Context & background

The board sizes every non-land permanent to **one** shared width, `PreferredMainCardWidth`, capped
down by four constraints (the busiest row, the side's height, an attachment assembly, and a legible
floor). One preferred size for creatures and non-creatures alike is the part being revisited.

Pete's judgement, from a board he liked the look of:

- the **non-creature** permanents in that screenshot are the right default;
- **creatures** should be about **25% larger** by default;
- both are **maximums** — a crowded row still shrinks them to fit, exactly as it does now.

That last clause matters: this is a change to the *preferred* size, not to the constraint chain. 0117
already established that width crowding shrinks a card only to `LegibleCardWidth` and then the row
scrolls, and that a row costs its tallest entry in card-width units. None of that changes.

## 3. Scope

**In scope**
- A preferred width per role: one for `PermanentRole.Creature`, one for `PermanentRole.Other`.
- Creatures preferred ~25% wider than non-creatures.
- Both still passed through the existing caps — the busiest row, the side's height, the attachment
  assembly, the legible floor.

**Out of scope**
- Lands. They have their own column and their own sizing (`landCardWidth`), tuned to fit a line of
  stacks, and nothing here suggests it is wrong.
- The stack region and the hand, which take their sizes from elsewhere.

## 4. Prerequisites & toolchain

Project baseline; `:feature:game` only. No wire change.

## 5. Design & approach

`mainCardWidth` became `mainCardWidths`, answering a `MainCardWidths(creature, other)`. The shape the
sketch above proposed held; what it did not anticipate is which constraints turn out to bind.

**Two preferred sizes, written as a ratio.** `PreferredOtherWidth` keeps the measured 252dp — it is
the one Pete picked off a board he liked — and `PreferredCreatureWidth` is derived from it by
`CREATURE_SIZE_ADVANTAGE = 1.25`. Two independent numbers would drift apart the first time either was
adjusted; the pair is a ratio and is written as one.

**Crowding is answered per role.** The busiest row *of that role*, across both sides, so twelve
creatures no longer say anything about how wide an enchantment may be drawn. Still shared across the
two sides, which is deliberate and unchanged: the game does not say one side's creatures are nearer.

**Height is answered across both, by scaling both.** The two rows are stacked inside one side and
share its height, so when a side does not fit, both roles scale by the same factor. Scaling only the
offending row would leave the two sizes in whatever proportion the crowding happened to produce, and
the proportion is the thing this story exists to state.

**The ratio is a preference, not an invariant.** A cap that binds on one role and not the other moves
the two closer together, and that is correct — holding the ratio would mean shrinking the *uncapped*
role to match, taking room from a card that has it in order to preserve a proportion nobody asked to
be preserved at that price.

**Worth knowing for later:** on a phone in landscape the two preferred widths together are taller than
one side, so **every real board is the scaled case**. The preferred pair sets the ratio; the side's
height sets the size. That is the same as it was before this story — the single preferred was already
unreachable — but it means tuning the absolute numbers upward buys nothing without more height.

The stack takes `MainCardWidths.largest`: an object waiting to resolve has no role, may become either
kind or neither, and is the one object the game is currently waiting on.

## 6. Testing & verification

`./gradlew check -x :bridge:test -x :bridge:check`. Three tests in `BattlefieldLayoutTest`, each
confirmed to fail without the change it covers:

- **a creature is drawn a quarter larger, and stays that way once the side has to scale** — an
  uncrowded board, where both roles start at their preferred width and are then scaled together by the
  shared height budget. The ratio surviving is the proof they scaled by the same factor. Fails with
  `CREATURE_SIZE_ADVANTAGE` set to 1.
- **a crowded creature row never shrinks the permanents behind it** — twelve creatures and one
  artifact on one board; the artifact is drawn *wider than the creatures beside it*, which one shared
  width cannot produce. Measured on the same board rather than against a second one: comparing two
  boards only says the artifact did not get *smaller*, which stayed true under the old shared width.
  Fails when `busiest` is computed across roles instead of per role.
- **a crowded creature row is capped on its own, and the ratio gives way to it** — pins the paragraph
  above, so that a later attempt to "fix" the drifting ratio has to argue with a test.

The existing preferred-size assertion moved to `PreferredCreatureWidth`, since its board is creatures.

## 7. Acceptance criteria

- [ ] On an uncrowded board, creatures are noticeably larger than non-creature permanents.
- [ ] Neither ever exceeds its preferred size, however empty the board.
- [ ] A crowded row still shrinks its cards and then scrolls, as it does now.
- [ ] The non-creature rows do not shrink because the creature row is crowded, and the reverse.
- [ ] Lands are unchanged.
