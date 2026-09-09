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

*(to be filled in when it is built)*

The obvious shape is that `mainCardWidth` stops answering one number and answers one **per role**,
with the height budget summing each row at its own role's width. The thing to be careful about is that
the two rows share a side's height: two independently-capped widths can add up to more than the side
has, so the height constraint has to be solved across both rather than applied to each.

## 6. Testing & verification

*(to be filled in when it is built)*

At minimum: creatures are drawn wider than non-creatures on an uncrowded board; both shrink when a row
is crowded; neither exceeds its own preferred size on an empty board.

## 7. Acceptance criteria

- [ ] On an uncrowded board, creatures are noticeably larger than non-creature permanents.
- [ ] Neither ever exceeds its preferred size, however empty the board.
- [ ] A crowded row still shrinks its cards and then scrolls, as it does now.
- [ ] The non-creature rows do not shrink because the creature row is crowded, and the reverse.
- [ ] Lands are unchanged.
