# 0109 — Player vitals: what decides a game without being on the battlefield

- **Story:** #191
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0105 (the base layer this joins), 0088 (player counters and designations), 0089
  (command objects).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.15, §7.4.

## 1. Objective

Show each player's life, zone counts, counters and designations on the board, so that "am I about to
lose" is answerable without opening anything.

## 2. Context & background

**Everything §7.15 asks for is already on the wire.** When that section was written, player counters,
monarch, initiative, designations and `commandList` were all unmapped and it said so; 0088 and 0089
closed that. The section has been corrected — this story is rendering work with nothing behind it to
unblock.

**Poison is the reason this is not cosmetic.** A player at nine poison counters is one counter from
losing, and today nothing anywhere on the board says so. The same is true of an empty library, of the
monarch, and of an emblem that has been quietly changing every combat since turn four.

**§7.15's own argument for the shape.** *"The board is short of space (§7.4) and most of this is zero
most of the time, so it earns its room by asking for almost none until there is something to say."*
Collapsed it is counts and colour; expanded it is the list — and expanding *"is a look, not a
decision"*, so it floats and never displaces the battlefield.

## 3. Scope

**In scope**
- A vitals strip per player on the base layer: life, library, hand, graveyard and exile counts, match
  wins, and any non-zero player counter.
- Poison shown the moment it is non-zero, and marked when it is close to lethal.
- Monarch and initiative shown when held.
- An expanded overlay listing every counter by name, the designations, and the command objects —
  emblems, dungeons, commanders, planes.
- The mana pool, since §7.7 fills it and it is already mapped.

**Out of scope**
- **Opening the zone browser** from the graveyard and exile counts (§7.13). The counts are shown; the
  browser is its own surface and its own story.
- **The ±N life delta animation**, which §7.15 marks P1 and which needs the animation host the board
  is not yet driven through.
- Anything that *changes* a vital. Nothing here is an action.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`, `:app`.

## 5. Design & approach

**Collapsed is counts and colour, and the colour is not a code.** §7.15 is explicit: *"The colour is a
way to tell two chips apart at a glance, not a code the player is expected to learn — the number sits
next to it, and the expanded view names it."* So life is red and always there, poison is green and
appears when it is non-zero, and every other kind takes a colour from the board's own counter palette,
which already hands out a stable colour per kind for exactly this reason.

**A counter that is zero is not shown.** The same rule the battlefield follows for empty regions, and
for the same reason: a board that reserved a chip for energy in every game would spend the space on
nothing in almost all of them.

**Poison is called out, not just counted.** Ten is a loss, and the difference between "you have poison"
and "you are one counter from losing" is the entire value of showing it. The chip carries the count and
the board marks it once it is close — which is a *presentation* of a rule the game already fixed, not a
prediction: ten is ten in every format this client plays.

**Expanded floats and never displaces.** §7.4 keeps floating layers for what is transient, and a look
at a player's emblems is exactly that. It dismisses the way the card preview does, so there is one
gesture for closing things (§7.1).

**Nothing here is derived.** Life, counts, counters and designations are all the server's. The one
judgement is which *order* to show them in, which is a question about attention rather than about
rules.

## 6. Implementation steps

1. A vitals model over `GamePlayer`.
2. The collapsed strip, with the counter chips.
3. The expanded overlay.
4. Place both on the board, and add them to the preview.

## 7. Testing & verification

- **Proven failing first (standard 1):** the zero-counters test must fail against a strip that always
  draws every kind.
- **Unit:** only non-zero counters appear; poison appears whenever it is non-zero; the near-lethal mark
  turns on at the right count; monarch and initiative appear only when held; a spectator sees both
  players.
- **Hermetic Compose:** the strip shows life and the zone counts; expanding lists the command objects
  and designations; a press outside closes it; the vitals cost the battlefield no height.
- **Eyes-on:** the battlefield preview, with a board carrying poison, an emblem and the monarch.

## 8. Acceptance criteria

- [x] Each player's life, library, hand, graveyard, exile and match wins are on the board.
- [x] A counter appears only when it is non-zero; poison appears as soon as it is.
- [x] Poison is marked when it is close to lethal.
- [x] Monarch and initiative are shown when held.
- [x] Expanding names every counter and lists designations and command objects, and floats over the
      board rather than displacing it.
- [x] `./gradlew check` passes and the preview shows all of it.

## 9. References

- `docs/ui-modernization-plan.md` §7.15 (vitals and the overlay), §7.4 (the base layer, floating
  layers).
- `docs/stories/0088-player-counters-and-designations.md` and `0089-command-objects.md` — the wire.
