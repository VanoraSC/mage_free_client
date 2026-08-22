# 0079 — A library search offers the whole library, not the legal fetches

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0057 (floating controls), 0072 (the `targets == null` fallback this story narrows)

## 1. Objective

Fix a defect found live (Pete, 2026-08-20): activating Marsh Flats ("Search your library for a
Plains or Island card...") let the ability go on the stack, but the resulting card-picker offered no
way to select a card to fetch.

## 2. Root cause — confirmed by reading the actual upstream source, not assumed

Fetchlands run through `PlayerImpl.searchLibrary(TargetCardInLibrary, ...)`. Read directly against
the pinned upstream source:

- `TargetCardInLibrary.choose(...)` (`Mage/.../target/common/TargetCardInLibrary.java`) builds its
  candidate set from **the player's entire remaining library** — every card, unfiltered — and calls
  `player.chooseTarget(outcome, cardsId, ...)` with that whole set.
- `HumanPlayer.chooseTarget(Outcome, Cards, TargetCard, Ability, Game)`
  (`Mage.Player.Human/.../HumanPlayer.java:946`) computes the real, narrow legal set —
  `target.possibleTargets(...)` — and puts it in `options["possibleTargets"]`, then fires the event
  with `cards` = **the whole searched zone**, not the narrowed set.
- `GameController.target(...)` (`Mage.Server/.../GameController.java:864`) turns that whole zone into
  the payload's `cardsView1`, and **`targets` stays `null`**.

So for Marsh Flats, the wire payload is: `cardsView1` = the entire remaining library (often 30+
cards), `targets` = `null`, and the real 1-4 legal picks exist **only** in
`options["possibleTargets"]`.

**This collides with story 0072's existing `targets == null` fallback.** That fallback — added for
`PICK_ABILITY` (ordering simultaneous triggers), the *other* shape that sends `targets = null` —
treats every card in `cardsView1` as pickable, correctly for `PICK_ABILITY` (there, the candidate set
*is* the answer set) but **wrong** for a library search, where `cardsView1` is a whole hidden zone and
the real answer is a small subset named separately. The bridge (`GamePromptMapper.target()`) applied
the same fallback to both shapes, so a fetch offered the entire library as if every card were a legal
target, with no way to tell the 1-4 real ones from the rest — and the UI layer
(`BoardControls.kt`'s `Target` branch) rendered every one of `prompt.cards` as a candidate button
unconditionally, compounding the problem visually.

## 3. Scope

**In scope**
- `GamePromptMapper.target()` (bridge): when `targets` is `null`, prefer `options["possibleTargets"]`
  as `targetIds` when present (even if empty — an honest "nothing to fetch" is still correct), falling
  back to `cards.map { it.id }` only when `possibleTargets` is *also* absent (the `PICK_ABILITY` case,
  unchanged).
- `BoardControls.kt`'s `GamePrompt.Target` branch (feature:game): `candidateCards` is now filtered to
  `prompt.cards.filter { it.id in pickable }` instead of showing every card the prompt carried
  verbatim — so a library search's floating panel shows only the fetchable cards, not the whole
  library.

**Out of scope**
- Any new zone-awareness in `GameCard`/`CardUi` — the fix works entirely from the existing
  `targetIds`/`options.possibleTargets` machinery already in place for other prompt kinds (combat
  declarations, scry).
- A search/filter affordance for a *large* legal candidate set (e.g. a "search for any card" effect)
  — not the reported defect, and every fetchland's real candidate set is small (1-4 cards).

## 4. Constraints already verified — do not rediscover

- `TargetCardInLibrary.choose(...)` builds its candidate set from the whole library — confirmed by
  reading the file directly.
- `HumanPlayer.chooseTarget(Outcome, Cards, TargetCard, Ability, Game)` computes
  `options["possibleTargets"]` from `target.possibleTargets(...)` and fires the event with the whole
  zone as `cards`, `targets = null` — confirmed by reading the method directly.
- `GameController.target(...)` never populates `targets` for this call path — confirmed by reading it
  directly; this is the same `targets == null` shape story 0072 already handles, but for a
  structurally different reason (a real narrowed answer exists elsewhere, vs. no narrowing at all).

## 5. Verification

- **Standard 1**, discriminating tests: a `GAME_TARGET` payload shaped like a library search (a
  3-card `cardsView1`, `targets = null`, `options["possibleTargets"]` naming 2 of the 3) must map to
  `targetIds` = exactly those 2 — not all 3 (bridge, `GameCallbackMapperTest`). The board's floating
  panel for the same shape must offer only those 2 as candidate cards, and `actionFor` on the excluded
  card's id must be `null` (feature:game, `BoardControlsTest`). Both proven to fail against the
  unfixed code first.
- **Standard 2 (reachability):** `TargetPrompt.targetIds` — `message.targets`, else
  `options["possibleTargets"]`, else every card in `cardsView1` (unchanged fallback order, new middle
  case). `Targeting.candidateCards` — `prompt.cards` narrowed to `pickableObjectIds`.
- **Hermetic gate:** `bridge/src/test/kotlin/magefree/bridge/mapping/GameCallbackMapperTest.kt`,
  `feature/game/src/test/kotlin/magefree/feature/game/board/BoardControlsTest.kt`.
- **Regression check:** the existing story 0072 test (`PICK_ABILITY`'s null-targets fallback) is
  unchanged and still passes — that shape has no `possibleTargets` key, so it still falls all the way
  through to `cards.map { it.id }`.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. Play a fetchland (Marsh Flats, Arid Mesa, Windswept Heath, Sacred Foundry's search effects, etc.)
     and activate it.
  2. Confirm the floating panel offers only the legal basics/duals to fetch — not the whole library.
  3. Confirm tapping one of them actually fetches it and the ability resolves.

## 6. Acceptance criteria

- [ ] A fetchland's search offers exactly the legal candidates, never the whole library.
- [ ] Tapping a legal candidate fetches it; the story 0072 `PICK_ABILITY` shape is unaffected.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `bridge/src/main/kotlin/magefree/bridge/mapping/game/GamePromptMapper.kt` — `target()`, the fix.
- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardControls.kt` — the `Target` branch's
  `candidateCards` filter.
- `Mage/src/main/java/mage/target/common/TargetCardInLibrary.java` (pinned ref `e0fe4b6f6a`) —
  `choose(...)`, read directly.
- `Mage.Server.Plugins/Mage.Player.Human/src/mage/player/human/HumanPlayer.java` (pinned ref
  `e0fe4b6f6a`) — `chooseTarget(Outcome, Cards, TargetCard, Ability, Game)`, read directly.
- `Mage.Server/src/main/java/mage/server/game/GameController.java` (pinned ref `e0fe4b6f6a`) —
  `target(...)`, read directly.
- `docs/stories/0072-order-simultaneous-triggers.md` — the earlier `targets == null` fallback this
  story narrows rather than replaces.
