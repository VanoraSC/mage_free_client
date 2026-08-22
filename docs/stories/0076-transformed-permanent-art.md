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

**Superseded during implementation:** this section originally proposed resolving the face by
matching the live `name` against the catalog's `CardFaces.secondSideName` for the printing. Reading
further into upstream's own `PermanentView` constructor (same file) turned up a simpler, more
direct signal that makes the catalog lookup unnecessary:

```java
if (original != null && !original.getName().equals(this.getName())) {
    ...
    this.alternateName = original.getName();
}
```

`CardView.alternateName` (`getAlternateName()`) is set by upstream itself, on the object we already
map, exactly when the live name differs from the card's own original name — i.e. a transformed DFC
permanent, a name-changing copy, or a flip card in its flipped state. It is `null` in the ordinary
case. This is upstream's own "is this not the front face" signal, already computed for us — no
catalog lookup, no dependency on 0030's face-name data, and no guessing at name equality ourselves.
The fix threads this one field through all four layers (`protocol` → `bridge` → `core:network` →
`feature:game`) and uses `alternateName != null` as the sole condition for `CardArtFace.BACK`.

**Follow-up defect, found live the same session (Pete, 2026-08-17): an untransformed Kytheon in
hand showed Gideon's art.** The "hand cards are not transformable, non-issue" assumption in the
original scope section below was wrong — not because a hand card can transform, but because
`CardView.alternateName` means something different depending on *which* upstream class actually
built the object. Reading `CardView.java`'s own constructor (not just `PermanentView`'s) turned up
five more `this.alternateName = ...` assignments, all unconditional: any `card instanceof
DoubleFacedCard` (or transformable `PermanentCard`, flip card, or meld card) gets `alternateName`
set to its *other* face's name **regardless of which face is currently showing** — this is how
upstream's own GUI labels a day/night flip button, not a "which face is up" signal. `PermanentView`
extends `CardView` and its constructor calls `super()` first (running that unconditional
assignment) and then *overwrites* the field with the correct "did the name actually change" value —
but only `PermanentView` does that overwrite. A card sitting untransformed in hand is a plain
`CardView`, never a `PermanentView`, so it keeps the naive always-set value, which reads as "showing
the back face" if trusted the same way a permanent's is. Fixed by gating the bridge's read on
`card is PermanentView` — `GameCardView.alternateName` is now `null` for every zone except the
battlefield, where `PermanentView`'s own correctly-overwritten value is what's read.

**Third defect, found live a few days later (Pete, 2026-08-22): an *untransformed* battlefield
permanent (Ajani, Nacatl Pariah) showed its *back* face's art (Ajani, Nacatl Avenger's), and the
manual flip control (story 0077) never offered a Flip button until it actually transformed.** The
"gate on `card is PermanentView`" fix above was necessary but not sufficient — it assumed
`PermanentView`'s constructor always *correctly overwrites* `alternateName` before the bridge ever
reads it, and that assumption was wrong. Reading `PermanentView.java`'s constructor line by line
again: it calls `super(permanent, game, ...)` — running `CardView`'s own constructor **with the live
`Permanent` itself as the `card` parameter**. That constructor's `card instanceof PermanentCard &&
card.isTransformable()` branch (a *third* unconditional assignment, distinct from the
`DoubleFacedCard`/flip-card/meld ones the previous round found) fires for **any** permanent of a
transformable card, front or back, and sets `alternateName = ((PermanentCard)
card).getOtherFace().getName()` — the *other* face's name, unconditionally, on every ordinary
untransformed permanent too. `PermanentView`'s own body then runs its corrective reassignment — but
only `if (original != null && !original.getName().equals(this.getName()))`, i.e. only once actually
transformed. For an untransformed permanent that condition is false, the correction never fires, and
the poisoned unconditional value — the back face's name — is left in the field, indistinguishable
from "this has transformed" to anything that trusts `getAlternateName()` directly. The two cases
happen to agree only by coincidence once actually transformed (`getOtherFace()`, now relative to the
back face being active, happens to return the front card) — which is exactly why the first two
rounds of this story, both of which only tested the *transformed* case or a *plain `CardView`* case,
looked complete while an *untransformed permanent* — never covered by a test — kept leaking.

Fixed by no longer reading `getAlternateName()` at all, even off a `PermanentView`: the bridge now
reads `PermanentView.getOriginal()` directly (the field upstream itself builds from
`game.getCard(permanent.getId())` — a stable, always-front-face identity, never mutated by
transform) and compares its name against the permanent's own current name itself, replicating
upstream's own corrective comparison rather than trusting a field upstream only conditionally
corrects.

## 3. Scope

**In scope**
- Thread upstream's `CardView.alternateName` through the mapping pipeline: `GameCardView.alternateName`
  (protocol) → `GameViewMapper.mapCard()` (bridge) → `GameCard.alternateName` (core:network) →
  `toCardUi()`/`artRequestOf()` (feature:game), replacing the hardcoded `FRONT` with
  `if (alternateName != null) BACK else FRONT` — reusing 0043's existing `CardArtFace.BACK` request
  shape (already used for a card's back face elsewhere; only the *permanent-on-board* path never
  reached for it).
- Applies to every place `toCardUi()`/its art-request builder is used: battlefield permanents, the
  stack, and anywhere else a live game object's current face matters. `GameCardView.alternateName`
  is populated **only** from a `PermanentView` (battlefield); every other zone (hand, library,
  exile, stack) maps it to `null` — see the follow-up defect above for why trusting a plain
  `CardView`'s copy of the same field is wrong, not merely redundant.
- Hermetic tests at each layer: given a fixture whose `alternateName` is non-null, the value must
  survive mapping/folding unchanged; given `toCardUi()`/`artRequestOf()` a `GameCard` with a non-null
  `alternateName`, the resulting `CardArtRequest.face` must be `BACK`.

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
- `CardView.alternateName`/`getAlternateName()` is set by upstream's own `PermanentView` constructor
  exactly when the live name differs from the card's original name — confirmed by reading the
  constructor directly. This was previously completely unmapped anywhere in this codebase
  (confirmed by a repo-wide grep before adding it).
- `CardView`'s own (non-`PermanentView`) constructor sets the same field **unconditionally** for any
  transformable/double-faced/flip/meld card, to the *other* face's name, regardless of which face is
  showing — confirmed by reading `CardView.java` directly. A plain `CardView` (hand/library/exile/
  stack) carries this naive value always.
- **Gating on `card is PermanentView` alone is not enough.** `PermanentView`'s own `super()` call
  runs the very same unconditional `CardView` assignment — with the live `Permanent` as `card` — so
  even a `PermanentView`'s raw `alternateName` field is poisoned with the back face's name on every
  *untransformed* permanent of a transformable card. `PermanentView`'s own corrective reassignment
  only fires once `original.getName() != this.getName()`, i.e. only once actually transformed;
  confirmed by reading the constructor directly, not assumed from a passing test that only exercised
  the transformed case. Never read `getAlternateName()` at all, on anything — read
  `PermanentView.getOriginal()` and compare its name against the permanent's own current name
  instead (`GameViewMapper.permanentAlternateName`).

## 5. Verification

- **Standard 1**, discriminating tests at each layer: a fixture with non-null `alternateName` must
  carry it through mapping (bridge), folding (core:network), and resolve to `CardArtFace.BACK`
  (feature:game); a fixture with null `alternateName` must resolve to `FRONT`. Each proven to fail
  against the unfixed code first (bridge/core:network: compile error on the new field before it
  existed; feature:game: assertion failure against the hardcoded `FRONT`). The third round adds a
  bridge fixture that mirrors upstream's own poisoned field exactly — a `PermanentView` whose raw
  `alternateName` is set to the back face's name (as `super()` always sets it) while its `original`
  matches the current name (untransformed) — proving the mapper must derive the signal from
  `getOriginal()`, not merely gate on the object's type.
- **Standard 2 (reachability):** name what produces the face decision — `GameCard.alternateName != null`,
  itself threaded unchanged from upstream's own `CardView.getAlternateName()`.
- **Hermetic gate:** `feature/game/src/test` (or wherever `toCardUi()`'s existing coverage lives).
- **Live, if practical:** transform a permanent (Kytheon → Gideon or any other transform DFC),
  confirm the board's art switches to the back face while the name is (already, correctly) current.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. Get a transforming double-faced permanent onto the battlefield and trigger its transform.
  2. Confirm the board's art switches to match the current face, not just the name.
  3. Transform it back (if the card allows), confirm the art switches back too.

## 6. Acceptance criteria

- [ ] A transformed permanent's rendered art matches its current face, using upstream's own
      `CardView.alternateName` signal, threaded through all four layers.
- [ ] The front-face case (untransformed, or any ordinary card) is unaffected.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardUi.kt` — `toCardUi()`/
  `artRequestOf()`, where the hardcoded `CardArtFace.FRONT` lived.
- `protocol/src/main/kotlin/magefree/protocol/GameMessages.kt` — `GameCardView.alternateName`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard()` and
  `permanentAlternateName()`, which derives the signal from `PermanentView.getOriginal()` rather
  than reading `getAlternateName()` off anything.
- `core/network/src/main/kotlin/magefree/network/game/GameState.kt` — `GameCard.alternateName`.
- `Mage.Common/src/main/java/mage/view/PermanentView.java` (pinned ref `e0fe4b6f6a`) — the
  commented-out `transformed` field, the name-switching logic, and the `alternateName` assignment
  this story's fix relies on, all read directly.
- `docs/stories/0043-artwork-pipeline-fixes.md` — where `CardArtFace.BACK` requests were first
  built, for a different call site (hand-zone DFC lookup); this story is the missing sibling for
  the battlefield/live-game path.
