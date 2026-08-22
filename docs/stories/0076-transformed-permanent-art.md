# 0076 — A transformed permanent shows its front-face art forever

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0055 (board rendering), 0043 (artwork pipeline fixes — the `CardArtFace.BACK`
  request shape this story reuses)

## 1. Objective

Fix a defect found live (Pete, 2026-08-17): Kytheon, Hero of Akros transformed into Gideon,
Battle-Forged (confirmed server-side — the activated-ability prompt showed Gideon's own +2/+1/0
abilities, not Kytheon's), but the board kept showing **Kytheon's art**. The name displayed
correctly; only the art stayed on the front face.

## 2. Root cause — confirmed by reading upstream's own client, not reverse-engineered

`toCardUi()` (`feature/game/src/main/kotlin/magefree/feature/game/board/BoardUi.kt`, the single
place every rendered permanent's `CardArtRequest` is built) originally hardcoded `face =
CardArtFace.FRONT` always. There is no signal in a DFC's `setCode`/`collectorNumber` for which
face is current — both faces of one printing share that one identity — so something else has to
say which face's art to request.

**This was chased through three rounds of the wrong kind of fix before Pete redirected the
investigation to upstream's own reference client, which already solves this problem correctly.**
Rounds 1–3 (kept below for the record, since they are what the intermediate commits and PR history
show) each treated `CardView.alternateName`/`getAlternateName()` as if it were the "which face is
up" signal, discovering a new way that assumption failed each time. The actual upstream desktop
client (`Mage.Client/src/main/java/org/mage/card/arcane/CardPanel.java`, pinned ref `e0fe4b6f6a`)
never does this: it reads `CardView.isTransformed()` directly for exactly this question (e.g.
`this.isTransformed()` gates the day/night button's icon and the flip animation), and treats
`alternateName`/`getSecondCardFace()` as a completely separate fact — "does this card have another
face, and what's on it" — used only to know *whether a flip control should exist* and where its
label comes from, never to decide which art is currently showing.

`CardView.isTransformed()` (`Mage.Common/src/main/java/mage/view/CardView.java`) is set correctly,
for every permanent, by `CardView`'s own constructor:

```java
if (card instanceof Permanent) {
    Permanent permanent = (Permanent) card;
    ...
    if (permanent.isTransformed()) {
        transformed = true;
    }
}
```

`PermanentView extends CardView` and its constructor calls `super(permanent, game, ...)` first —
running the exact block above with the live `Permanent` as `card` — so this field is already
correct on every `PermanentView` before `PermanentView`'s own body runs a line further down. That
line, `//this.transformed = permanent.isTransformed();`, is commented out in `PermanentView.java` —
**not because upstream never computes this field, but because `super()` already did**, making the
reassignment redundant. Earlier rounds of this story read that commented-out line and concluded the
field was dead upstream entirely; it is not — it is simply inherited, unchanged, from the
superclass constructor that always runs first. This is exactly the "solved problem" note applies
to: the reference client already renders this correctly by reading a field upstream already
computes for it, and the fix is to translate that field through the bridge, not to invent a
replacement.

`CardView.alternateName` is a *different* fact: any transformable/double-faced/flip/meld object
gets it set unconditionally, in both `CardView`'s and `PermanentView`'s constructors, to the name of
whichever face is **not** currently showing — regardless of whether the object has ever
transformed. It is upstream's own label source for a flip/day-night button (story 0077), not a
face-selection signal, and was never meant to be one.

### The three earlier rounds (superseded, kept for the record)

- **Round 1 (2026-08-17):** noticed `alternateName` is non-null on a transformed permanent and equal
  to the front name; wired `alternateName != null` through as "is this transformed." Worked for the
  one case tested (an actually-transformed permanent).
- **Round 2 (2026-08-17, same session):** an untransformed card sitting in **hand** also carried a
  non-null `alternateName` (upstream sets it unconditionally on any plain `CardView` of a
  transformable card too, to label a would-be flip button that a hand card obviously doesn't have
  yet) and was wrongly read as "showing its back face." Patched by gating the bridge's read to
  `card is PermanentView` only.
- **Round 3 (2026-08-22):** an **untransformed permanent** (Ajani, Nacatl Pariah) still showed its
  back face's art, because `PermanentView`'s own `super()` call sets the same unconditional
  "other face" value before `PermanentView`'s corrective reassignment (which only fires once the
  name has actually changed) ever runs — so an untransformed permanent's raw `alternateName` field
  is indistinguishable from a transformed one by anything reading it directly. Patched by comparing
  `PermanentView.getOriginal()` against the current name instead of trusting the field at all.

Each round "fixed" its one reported case by deriving a same-shaped boolean from `alternateName`
through narrower and narrower special-casing, without ever asking whether upstream already exposes
the actual signal directly — it does, and always did, as `isTransformed()`. Pete's correction
("read the actual code... we don't want to be reinventing the wheel for how these communications
work, we just need to translate them through the bridge") is what prompted checking the real
client's own source instead of continuing to patch the symptom.

## 3. Scope

**In scope**
- Thread upstream's `CardView.isTransformed()` through the mapping pipeline as its own field —
  `GameCardView.transformed` (protocol) → `GameViewMapper.mapCard()` (bridge, `card.isTransformed`,
  no derivation) → `GameCard.transformed` (core:network) → `toCardUi()`/`artRequestOf()`
  (feature:game) — replacing the hardcoded `FRONT` with `if (transformed) BACK else FRONT`, reusing
  0043's existing `CardArtFace.BACK` request shape.
- Keep `alternateName` threaded through too, unfiltered and ungated (a plain passthrough of
  upstream's own field, correct on any `CardView` including a plain hand card, since it was never
  wrong for its *actual* purpose) — it is what story 0077's flip control uses to know whether
  another face exists and what to call it, entirely independent of [transformed].
- Hermetic tests at each layer: a fixture with `transformed = true` must resolve to `BACK`; a
  fixture with `transformed = false` and a non-null `alternateName` (the exact shape of round 3's
  bug) must still resolve to `FRONT`.

**Out of scope**
- Flip cards (the *old* Kamigawa mechanic, `GamePermanentView.flipped`) — a genuinely different
  card shape from modern transform DFCs, not confirmed to have the same defect, and not the
  reported symptom.
- Meld cards, split cards, or any other multi-face shape besides transform DFC/MDFC — same reason.
- Any change to how the *name* is displayed — that part is already correct (upstream itself swaps
  a transformed permanent's `name` to the current face).

## 4. Constraints already verified — do not rediscover

- `CardView.isTransformed()`/the backing `transformed` field is set correctly for **any** permanent
  by `CardView`'s own constructor (`Mage.Common/.../CardView.java`, the `card instanceof Permanent`
  branch) — confirmed by reading the constructor directly. `PermanentView` inherits this unchanged
  via `super()`; its own commented-out reassignment of the same field is redundant, not evidence the
  field is unavailable.
- `CardView.alternateName`/`getAlternateName()` is set **unconditionally** on any transformable/
  double-faced/flip/meld object (confirmed in both `CardView`'s and `PermanentView`'s constructors),
  to the name of whichever face is not currently active — a catalog fact ("has another face, what's
  it called"), never a "which face is up" signal. Do not gate or filter it trying to make it answer
  that question; it isn't built to.
- `PermanentView.flipped`/`isFlipped()` is the *old* flip-card mechanic (Kamigawa-style), a
  different, older thing from a modern transforming double-faced card — confirmed by upstream's own
  field name and surrounding comments.
- The real desktop client's own card-rendering code, `Mage.Client/src/main/java/org/mage/card/
  arcane/CardPanel.java`, reads `isTransformed()` directly for exactly this purpose (e.g. the
  day/night button icon, the transform animation trigger) and never derives it from
  `alternateName`. This is the reference implementation for "how does a real client tell these two
  facts apart," and it was not consulted until round 4.

## 5. Verification

- **Standard 1**, discriminating tests at each layer: a fixture with `transformed = true` must
  resolve to `CardArtFace.BACK`; a fixture with `transformed = false` — including one whose
  `alternateName` is non-null, mirroring round 3's exact bug shape — must resolve to `FRONT`. Each
  proven to fail against the unfixed code first.
- **Standard 2 (reachability):** name what produces the face decision — `GameCard.transformed`,
  threaded unchanged from upstream's own `CardView.isTransformed()`.
- **Hermetic gate:** `bridge/src/test` (`GameViewMapperTest`), `core/network/src/test`
  (`GameEventFoldTest`), `feature/game/src/test` (`BoardUiTest`, `GameBoardViewModelTest`).
- **Live, if practical:** transform a permanent (Kytheon → Gideon or any other transform DFC),
  confirm the board's art switches to the back face while the name is (already, correctly) current;
  separately, confirm an *untransformed* DFC permanent on the battlefield shows front art and offers
  its flip control (story 0077) from the moment it enters, not only after it transforms.
- **Eyes-on (standard 3) — hand Pete this checklist. This is a bridge-only change: rebuild/restart
  the bridge container first.**
  1. Get a transforming double-faced permanent onto the battlefield **untransformed**. Confirm the
     art is the front face and the manual flip control (story 0077) is already offered.
  2. Trigger its transform. Confirm the board's art switches to match the current (back) face.
  3. Transform it back (if the card allows), confirm the art switches back too.

## 6. Acceptance criteria

- [ ] A transformed permanent's rendered art matches its current face, using upstream's own
      `CardView.isTransformed()` signal, threaded through all four layers.
- [ ] An untransformed permanent's art stays on the front face regardless of its `alternateName`.
- [ ] The front-face case (untransformed, or any ordinary card) is unaffected.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardUi.kt` — `toCardUi()`/
  `artRequestOf()`, where the hardcoded `CardArtFace.FRONT` lived.
- `protocol/src/main/kotlin/magefree/protocol/GameMessages.kt` — `GameCardView.transformed` and
  `GameCardView.alternateName`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard()`, reading
  `card.isTransformed` and `card.alternateName` straight, no derivation.
- `core/network/src/main/kotlin/magefree/network/game/GameState.kt` — `GameCard.transformed`,
  `GameCard.alternateName`.
- `Mage.Common/src/main/java/mage/view/CardView.java` (pinned ref `e0fe4b6f6a`) — the `transformed`
  field and its constructor logic, and the unconditional `alternateName` assignments.
- `Mage.Common/src/main/java/mage/view/PermanentView.java` (same ref) — the commented-out,
  redundant `transformed` reassignment, and the conditional `alternateName` correction that only
  ever partially covered the actual signal.
- `Mage.Client/src/main/java/org/mage/card/arcane/CardPanel.java` (same ref) — the reference
  desktop client's own card-face rendering logic; this is what round 4 read to find
  `isTransformed()` as the actual, already-correct signal.
- `docs/stories/0043-artwork-pipeline-fixes.md` — where `CardArtFace.BACK` requests were first
  built, for a different call site (hand-zone DFC lookup); this story is the missing sibling for
  the battlefield/live-game path.
