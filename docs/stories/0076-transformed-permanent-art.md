# 0076 — A transformed permanent shows its front-face art forever

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0055 (board rendering), 0043 (artwork pipeline fixes — the `CardArtFace.BACK`
  request shape this story reuses)

## 1. Objective

Fix a defect found live (Pete, 2026-08-17): Kytheon, Hero of Akros transformed into Gideon,
Battle-Forged (confirmed server-side — the activated-ability prompt showed Gideon's own +2/+1/0
abilities, not Kytheon's), but the board kept showing **Kytheon's art**. The name displayed
correctly; only the art stayed on the front face.

## 2. Root cause — confirmed by reading the actual code, not assumed

`toCardUi()` (`feature/game/src/main/kotlin/magefree/feature/game/board/BoardUi.kt`, the single
place every rendered permanent's `CardArtRequest` is built) hardcodes:

```kotlin
return CardArtRequest(
    setCode = setCode,
    collectorNumber = collectorNumber,
    face = CardArtFace.FRONT,   // always — every permanent, every hand card, every stack entry
    size = CardArtSize.SMALL,
)
```

There is **no signal anywhere in the mapped data** for "this permanent is currently showing its
back face." Read directly against the pinned upstream source (`Mage.Common/src/main/java/mage/
view/PermanentView.java`, ref `e0fe4b6f6a`): the one field that looks like it should carry this is
dead code —

```java
//this.transformed = permanent.isTransformed();
```

— literally commented out. `PermanentView.flipped`/`isFlipped()` (the one boolean our bridge does
map, to `GamePermanentView.flipped`, currently unused by art selection) is upstream's **old** "flip
card" mechanic (Kamigawa-style, a card that turns 180° and reads differently) — a different, older
thing from a modern transforming double-faced card. Neither upstream nor our own mapping currently
exposes "is the back face up" as a boolean at all.

What upstream's `PermanentView` constructor *does* do (same file) is switch the inherited
`CardView.name` (and the identity fields it derives from) to the current face's own values —
confirmed by its own comment: *"for fipped [sic], transformed or copied cards, switch the
names."* This is exactly why Pete saw the correct name ("Gideon, Battle-Forged") but the wrong art:
`name` already reflects the live face, but `setCode`/`collectorNumber` are a DFC printing's **one**
shared identity for both faces (Scryfall/XMage do not give the two faces of one printing separate
collector numbers), and nothing downstream ever asks for the *back* rendering of that one identity.

**The fix does not need a new upstream signal.** The bundled catalog (story 0030) already knows
both faces of a DFC printing: `CardFaces.doubleFaced`/`modalDoubleFaced` and
`CardFaces.secondSideName` (`core/cards/src/main/kotlin/magefree/cards/model/CardAttributes.kt:76-79`).
Given a live `GameCard`'s `setCode`+`collectorNumber` (the printing) and its current `name`, the
catalog can answer "is this name the *second* side's name for this printing?" — and that is a
sufficient signal for `CardArtFace.BACK` vs `FRONT`, without upstream ever telling us so directly.

## 3. Scope

**In scope**
- `toCardUi()` (or its call sites) gains a way to resolve `CardArtFace` from the live `name` +
  printing identity against the catalog, instead of the hardcoded `FRONT` — reusing 0043's existing
  `CardArtFace.BACK` request shape (already used for a card's back face elsewhere; only the
  *permanent-on-board* path never reaches for it).
- Applies to every place `toCardUi()`/its art-request builder is used: battlefield permanents, the
  stack, and anywhere else a live game object's current face matters. Hand cards are not
  transformable while in hand (transform is a battlefield-only state change for a permanent), so
  this is a non-issue there, but confirm rather than assume.
- A hermetic test: given a `GameCard` whose `name` matches a catalog entry's `secondSideName` for
  its printing, the resulting `CardArtRequest.face` must be `BACK`.

**Out of scope**
- Flip cards (the *old* Kamigawa mechanic, `GamePermanentView.flipped`) — a genuinely different
  card shape from modern transform DFCs, not confirmed to have the same defect, and not the
  reported symptom. Note as a related question worth checking later, not fixed here.
- Meld cards, split cards, or any other multi-face shape besides transform DFC/MDFC — same reason.
- Any change to how the *name* is displayed — that part is already correct.

## 4. Constraints already verified — do not rediscover

- `toCardUi()`'s hardcoded `face = CardArtFace.FRONT` — read directly, `BoardUi.kt` (line ~630).
- `PermanentView.transformed` is commented-out dead code upstream, confirmed by reading
  `PermanentView.java` directly at the pinned ref — do not assume it exists or try to read it.
- `PermanentView.flipped` is the *old* flip-card mechanic, not transform state — confirmed by
  upstream's own field name and the surrounding "for fipped, transformed or copied cards, switch
  the names" comment treating them as three separate things.
- The catalog already carries `CardFaces.secondSideName` per printing (`CardAttributes.kt:79`) —
  no new catalog data needs to be added for this fix, only a new *use* of what is already there.

## 5. Verification

- **Standard 1**, discriminating test: a `GameCard`/`GamePermanent` fixture whose `name` equals a
  catalog printing's `secondSideName` must resolve to `CardArtFace.BACK`; the same printing's
  front-face name must resolve to `FRONT`. Proven to fail against the unfixed hardcoded `FRONT`
  first.
- **Standard 2 (reachability):** name what produces the face decision — the catalog's
  `CardFaces.secondSideName` for the request's `(setCode, collectorNumber)`, compared against the
  live `GameCard.name` the current snapshot carries.
- **Hermetic gate:** `feature/game/src/test` (or wherever `toCardUi()`'s existing coverage lives).
- **Live, if practical:** transform a permanent (Kytheon → Gideon or any other transform DFC),
  confirm the board's art switches to the back face while the name is (already, correctly) current.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. Get a transforming double-faced permanent onto the battlefield and trigger its transform.
  2. Confirm the board's art switches to match the current face, not just the name.
  3. Transform it back (if the card allows), confirm the art switches back too.

## 6. Acceptance criteria

- [ ] A transformed permanent's rendered art matches its current face, using the catalog's own
      face-name data — no new upstream field required.
- [ ] The front-face case (untransformed, or any ordinary card) is unaffected.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardUi.kt` — `toCardUi()`, where the
  hardcoded `CardArtFace.FRONT` lives.
- `core/cards/src/main/kotlin/magefree/cards/model/CardAttributes.kt` — `CardFaces`,
  `secondSideName`, the existing catalog data this story reuses.
- `Mage.Common/src/main/java/mage/view/PermanentView.java` (pinned ref `e0fe4b6f6a`) — the
  commented-out `transformed` field and the name-switching logic, both read directly.
- `docs/stories/0043-artwork-pipeline-fixes.md` — where `CardArtFace.BACK` requests were first
  built, for a different call site (hand-zone DFC lookup); this story is the missing sibling for
  the battlefield/live-game path.
