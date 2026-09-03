# 0105 — The battlefield: two sides, three buckets, and attachments where they belong

- **Epic:** EPIC-19 — Game Board Rebuild
- **Story:** #185
- **Depends on:** 0096 (the Board card tier), 0098 (the animation host), 0099 (what the server marks),
  0104 (what those marks look like).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.4.

## 1. Objective

Arrange a battlefield the way §7.4 describes it, and build the presentation model that feeds it from
the server's own snapshot.

## 2. Context & background

**The current board renders a flat `Row` in the server's order.** `BattlefieldBand` walks
`seat.battlefield` and draws one full-size card per permanent, with no grouping and no ordering by
type. Playing a Plains, then a Soul Warden, then a second Plains puts them on screen in exactly that
order, with the second Plains alone to the right of the creature.

**§7.4 asks for two things and this story is the first of them.** The arrangement — creatures front,
non-creature permanents behind them beside the lands, lands to the side at the back — and the piling
that collapses ten Plains into one fan. Piling is designed (0065, R§20) and is a story of its own;
without the arrangement it has nowhere to go, so the arrangement comes first.

**The new board does not reuse the old board's model.** `BoardUi`/`PermanentUi` carry counters and
combat but no card icons, no attachments and no type classification, and §11's rule is explicit that
old code is not edited to accommodate new code. The new surface builds its own model from
`:core:network`'s `GameState`, which already carries everything needed — `cardTypes`, `icons`,
`counters`, `attachments`, `attachedTo`, `isAttachedToPermanent`, `attachedControllerDiffers`, and
`playable`.

## 3. Scope

**In scope**
- A presentation model over `GameState`: one side per seat, permanents in three buckets, attachments
  folded onto their hosts, badges resolved from the server's icons.
- The arrangement itself: opponent's side mirrored above, the viewer's below, no chrome, no region
  holding height while empty.
- Card size derived from the widest populated row and floored at a legibility minimum.
- A catalog section, fed by the same mapping, so the arrangement can be judged.

**Out of scope**
- **Piling** (§7.4's other half, 0065's presentation). Every permanent renders individually here; the
  pile is the next story and slots into the same buckets.
- The hand, player vitals, the stack, and the floating layers generally.
- The live screen, its entry point and the landscape request. This story delivers the surface and its
  model; mounting it is the story after piling, so that what gets mounted is the finished thing.
- Combat arrows. The signal colours from 0096 are used; the arrows are §3.1 work.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`.

## 5. Design & approach

**Three buckets, and creature wins.** A permanent is a creature, a land, or neither, and the server
reports its *current* types after continuous effects. An animated Mutavault is a creature this turn
and belongs in front, where the things that attack and block are; that is the point of the bucket, not
what the card is printed as. So creature is checked before land.

**An attachment is not a permanent in a bucket.** An Aura or Equipment attached to a permanent renders
*on its host*, which the Board tier already knows how to draw. It leaves the buckets entirely rather
than appearing twice. A Curse — attached to a *player* — has no host on the battlefield and stays in
the non-creature bucket, which is what `isAttachedToPermanent` is for.

**Badges come from the server's icons, and the hint decides where it must.** `CardIconType` maps to
`BoardBadge` one for one, except hexproof: upstream sends shroud under the same type, told apart by a
hint of `"Shroud"`. That is the one place the mapping reads the hint, and 0104 gave shroud its own
badge for it.

**The size is derived, not chosen.** §7.4: card size comes from the widest populated row, floored at a
legibility minimum, and below the floor the row scrolls. The arrangement therefore measures before it
sizes, rather than picking a dp that is right on one device.

**No region holds height while empty.** A side with no lands has no land area, not an empty one. This
is the rule that pays for the card size, and it is easy to lose to a `Spacer` that seemed harmless.

## 6. Implementation steps

1. The model: `GameState` to sides and buckets, attachments folded, badges resolved.
2. The arrangement, measuring first and sizing from what it measured.
3. A catalog section over a fixture snapshot.

## 7. Testing & verification

- **Proven failing first (standard 1):** the arrangement test must fail against a flat row.
- **Unit:** a permanent lands in the right bucket, including an animated land and a land creature; an
  attached Aura appears on its host and *not* in a bucket; a Curse on a player stays in a bucket; a
  shroud icon becomes the shroud badge and a hexproof one does not.
- **Hermetic Compose:** an empty bucket claims no height; the viewer's side is below the opponent's;
  card size falls when a row is fuller.
- **Eyes-on:** the catalog's battlefield section.

## 8. Acceptance criteria

- [x] Permanents are grouped into creatures, lands and other, with the current types the server sent.
- [x] An attachment renders on its host and nowhere else; a player-attached permanent keeps its bucket.
- [x] Badges are resolved from the server's icons, with shroud told from hexproof by the hint.
- [x] No empty region holds height.
- [x] Card size is derived from the widest populated row and floored.
- [x] A card carrying attachments is sized as the whole assembly, not as the card.
- [x] `./gradlew check` passes and the catalog shows the arrangement.

## 9. References

- `docs/ui-modernization-plan.md` §7.4 — the specification.
- `docs/stories/0065-battlefield-stacking.md` — the piling this leaves room for.
- `docs/stories/0087-attachments-both-directions.md` — why both directions are on the wire.
