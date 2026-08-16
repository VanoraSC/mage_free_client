# 0073 — Stack fan layout, tap-to-inspect, and target visualization

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0051 (game protocol & bridge relay), 0057 (board interaction: casting, targeting,
  cancel)
- **Status:** ready — design specified directly by Pete (2026-08-16), not inferred

## 1. Objective

Requested directly by Pete: change how the stack renders, and add a way to inspect any stack
object and see what it targets.

1. **Stack layout.** Stack entries currently render side by side with a gap (`StackStrip` in
   `BoardRegions.kt`, `Arrangement.spacedBy`) — no overlap. Change this to a fanned/cascading pile,
   each entry overlapping the one below it by ~60%, so stack order is visible at a glance and each
   entry still has enough exposed area to tap.
2. **Tapping a stack entry** does nothing today (`StackCard` in `BoardCards.kt` has no
   `clickable`). Tapping one must:
   a. Reduce anything **obscuring the ability's target(s)** to 80% opacity.
   b. Draw a line from the ability to its target(s).
   c. Show a card view of the spell/ability: real card art if it is a spell being cast; the same
      card art but with the **rules text replaced by the ability's own text** if it is an ability
      (not a full spell) — e.g. a triggered ability's stack entry shows its source's art with the
      trigger's own wording in place of the source's normal rules text.

**Explicitly scoped down by Pete:** this covers targets that are **on the stack or on the
battlefield** — objects the board already renders as cards, so "an arrow to it" and "dim what's in
front of it" both make sense. Targeting something that renders nowhere as a card today (a player,
a zone, a life total) needs a different visualization and is **out of scope** — noted here as a
known follow-up, not designed in this story.

## 2. What already exists — verified by reading the code, not assumed

- **Stack rendering today:** `feature/game/src/main/kotlin/magefree/feature/game/board/BoardRegions.kt`,
  `StackStrip` (~line 317) — a plain horizontally-scrollable `Row`, `Arrangement.spacedBy(Spacing.small)`,
  fixed height `StackStripHeight = 56.dp`. Each entry is `StackCard` in `BoardCards.kt` (~line 286):
  a small `BoardCardFace` (`StackCardWidth = 44.dp`) plus a name `Text`, in a `Row` with **no tap
  handler at all** (contrast `PermanentCard`/`HandCard`, which take `onTap`).
- **No targeting-arrow or line-drawing mechanism exists anywhere in `feature/game`** — no `Canvas`,
  no `drawLine`, no "arrow" reference. Story 0057 (already built) is about *choosing* targets while
  answering a live `TargetPrompt` — highlighted candidate borders (`CardPickState.Pickable`/`Chosen`,
  `pickBorder` in `BoardCards.kt`) plus a confirm step. It has nothing to do with visualizing an
  *already-resolved* stack object's targets after the fact. This story's arrow/line requirement is
  net-new UI, not an extension of 0057's picking flow.
- **No opacity/dim mechanism exists anywhere in `feature/game`** — net-new.
- **Stack target ids are not carried anywhere in the protocol today.** `protocol/src/main/kotlin/
  magefree/protocol/GameMessages.kt`'s `GameCardView` has no target field at all (id/name/setCode/
  collectorNumber/manaCost/typeLine/power/toughness/rules/faceDown/cardTypes/creature/counters —
  nothing else). `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt`'s `mapCard()`
  (already touched twice this session, by story 0072) reads `AbilityView`/`StackAbilityView`'s
  source-card/name fields but never reads target information. This needs new plumbing the whole way
  up.
- **Upstream already has exactly this data, confirmed by reading the source directly** —
  `Mage.Common/src/main/java/mage/view/CardView.java` (pinned ref `e0fe4b6f6a`):
  - `protected List<UUID> targets;` (line 124), public `getTargets()` (line 1345, "Returns UUIDs for
    targets. Can be null if there is no target selected.").
  - Populated by `addTargets(Targets, Effects, Ability, Game)` (line 1152) / `overrideTargets(...)`
    — both `AbilityView` and `StackAbilityView`'s constructors already call these (confirmed reading
    `CardsView.java` and `StackAbilityView.java` earlier this session, story 0072's investigation).
  - **XMage's own desktop client already draws target arrows from this exact field for stack
    objects** — `CardView.java` line 764-795: `if (showZone.match(Zone.STACK) && this.getTargets()
    != null ...)` builds a tooltip literally reading *"Has N target(s). Move mouse over card to see
    target arrows:"*. This is not new design — it is data upstream already sends and a UX pattern
    upstream's own client already ships, that this bridge has simply never mapped through.
  - `getTargets()` is a `CardView` method, so it is available on **any** mapped card, not just stack
    entries — battlefield permanents included, which is exactly what "target(s) on the battlefield"
    needs.
- **Reusable card-detail UI:** `feature/cards/src/main/kotlin/magefree/feature/cards/
  CardInspectionScreen.kt` (`CardInspectionScreen`, wrapping the design-system `FullCardView`) is
  the existing full-bleed art + name/mana/type/oracle-text surface, driven by a stateless
  `CardDisplay`. This is the natural base for requirement 2c: reuse it, swapping `CardDisplay
  .oracleText` for the ability's own text when the stack entry is an ability rather than a spell.

## 3. Scope

**In scope**

- **`:bridge`** — map `CardView.getTargets()` into a new `targetIds: List<String>` (or similarly
  named) field on `GameCardView`, populated in `GameViewMapper.mapCard()` for every card, not just
  stack entries (battlefield permanents need it too, for the "target on the battlefield" case).
- **`:protocol`** — the new field on `GameCardView` (`GameMessages.kt`).
- **`:core:network`** — carry the new field through its own `GameCardView`/mapper equivalent into
  the app's `GameState` model, the same path every other `GameCardView` field already takes.
- **`:feature:game`, stack layout** — `StackStrip`/`StackCard` (`BoardRegions.kt`/`BoardCards.kt`):
  fan the entries with ~60% overlap instead of `spacedBy`. Order (bottom of stack vs top) must stay
  legible — confirm which visual direction (offset up-and-right, cards later in the list drawn on
  top, etc.) reads correctly against the existing top-of-stack-resolves-first convention, rather
  than assuming.
- **`:feature:game`, tap-to-inspect** — `StackCard` gets a tap handler. On tap:
  a. Every other rendered card/permanent whose id is **not** in the tapped entry's `targetIds` and
     is not the tapped entry itself needs a visual "not involved" state. Read literally, Pete's spec
     dims *what obscures the target* — in practice this likely means: dim everything **except** the
     tapped ability and its target(s) (the arrow's two endpoints), since "obscuring" implies
     everything else recedes so the endpoints stand out. Confirm this reading with Pete before
     building if there's any ambiguity once a mock is in front of him — this is exactly the kind of
     board-visual-language call that should not be guessed silently.
  b. Draw a line/arrow (`Canvas`/`drawLine` or a Compose path) from the tapped stack entry's
     position to each target's position, for targets that are on the stack or the battlefield
     (`targetIds` entries not found among currently-rendered stack/battlefield cards are the
     off-stack/off-battlefield case — see §1's scope note; skip drawing an arrow for those, do not
     silently draw nothing where a real arrow should appear, and do not crash).
  c. Show the card view (§1.2c) — reuse `CardInspectionScreen`/`FullCardView`, art via the existing
     `CardArtRenderer`/`CardArtRequest` plumbing, oracle text swapped to the ability's own `rules`
     text when the entry is an ability (already correctly mapped by story 0072) rather than a full
     spell.
- Tapping again / tapping elsewhere to dismiss the dim+arrow+card-view state — define the exact
  dismiss gesture (tap the same card again? tap the arrow? a close button on the card view?) as part
  of implementation, matching this app's existing "nothing is modal, float over the board" pattern
  (§16.2, referenced in `BoardControls.kt`) rather than introducing a first modal dialog.

**Out of scope**

- Targeting a player, a zone, a life total, or anything else that does not render as a card
  anywhere on the board today. Needs its own, separately designed visualization — noted here as a
  known follow-up, not specified.
- Any change to how targets are *chosen* while casting/answering a live `TargetPrompt` (story 0057's
  territory) — this story is about visualizing an *already-resolved* stack object's targets, purely
  read-only/inspection, no new answers sent to the server.
- Redesigning the stack's scroll/overflow behavior beyond the fan layout itself.

## 4. Constraints already verified — do not rediscover

- No arrow-drawing, opacity/dim, or stack-tap mechanism exists anywhere in `feature/game` today —
  confirmed by search, not assumed absent.
- `CardView.getTargets()` is real, already-sent-upstream data (`Mage.Common/src/main/java/mage/
  view/CardView.java:124,1152,1345`), not something that needs inventing — the bridge has simply
  never read it. XMage's own desktop client draws target arrows from this exact field
  (`CardView.java:764-795`), which is strong precedent for the visual language this story asks for.
- `CardInspectionScreen`/`FullCardView` (`feature/cards`) already exists and is the right base for
  the ability card view — do not build a second card-detail surface from scratch.

## 5. Verification

- **Standard 1**, discriminating tests for: the new `targetIds` field surviving bridge → protocol →
  core:network → app `GameState` (a hermetic mapper test per layer, matching this repo's existing
  per-layer test pattern); the fan-layout math (given N stack entries, each overlaps the previous by
  the specified fraction); the "which cards get dimmed" predicate: given a tapped entry with known
  `targetIds`, prove which rendered cards are/aren't dimmed against both the endpoints-only reading
  and the everything-except reading, whichever is confirmed correct.
- **Standard 2 (reachability):** name what produces `targetIds` at each layer — `CardView.getTargets()`
  → `GameViewMapper.mapCard()` → protocol `GameCardView.targetIds` → core:network's equivalent →
  app `GameState`.
- **Hermetic gate:** `bridge/src/test`, `core/network/src/test`, `feature/game/src/test` — one test
  per layer, no live server needed for the plumbing; a Compose UI test or preview for the fan
  layout/dim/arrow rendering.
- **Live, if practical:** get a real stack with 2+ objects (e.g. two triggers, or a spell with a
  target already on the stack/battlefield), confirm the fan layout, tap an entry, confirm dim +
  arrow + card view all appear correctly and match the target(s) the server actually resolved.
- **Eyes-on (standard 3) — hand Pete this checklist once implemented.** Do **not** drive the UI
  programmatically.
  1. Get 2+ objects on the stack (e.g. Guide of Souls's trigger + another spell/trigger).
  2. Confirm the fan layout: ~60% overlap, stack order visually legible.
  3. Tap a stack entry that has a target on the battlefield or stack. Confirm: the target and the
     tapped entry are visually distinguished from the rest of the board (per whichever dim reading
     was confirmed), an arrow/line connects them, and a card view appears showing real art plus
     either the spell's normal text or the ability's own text as appropriate.
  4. Tap a stack entry with **no** targets (or only off-stack/off-battlefield targets). Confirm it
     does not crash and does something sensible (card view with no arrow, at minimum).
  5. Confirm dismissing the inspection view returns the board to normal.

## 6. Acceptance criteria

- [ ] Stack entries render as a fanned/overlapping pile (~60% overlap), stack order legible.
- [ ] Tapping a stack entry shows a card view (real art; ability text substituted for rules text
      when the entry is an ability) via the reused `CardInspectionScreen`/`FullCardView` surface.
- [ ] Tapping a stack entry with a target on the stack/battlefield draws an arrow to it and applies
      the confirmed dim treatment to the rest of the board.
- [ ] A stack entry with no targets, or only off-stack/off-battlefield targets, does not crash and
      degrades sensibly (documented explicitly, not left as an implicit edge case).
- [ ] `targetIds` (or equivalent) is confirmed reaching the app from `CardView.getTargets()` through
      every layer, with a test per layer.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardRegions.kt` — `StackStrip`.
- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardCards.kt` — `StackCard`,
  `pickBorder`/`CardPickState` (0057's existing highlight pattern, for visual-language consistency).
- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardUi.kt` — `StackEntryUi`, `StackUi`,
  `CardUi`.
- `feature/cards/src/main/kotlin/magefree/feature/cards/CardInspectionScreen.kt` — the card-detail
  surface to reuse for the ability card view.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard()`, where
  `targetIds` needs to be added (already touched by story 0072 for `displayName`/`setCode`/
  `collectorNumber` — same function, same pattern).
- `protocol/src/main/kotlin/magefree/protocol/GameMessages.kt` — `GameCardView`.
- `Mage.Common/src/main/java/mage/view/CardView.java` (pinned ref `e0fe4b6f6a`) — `targets`,
  `getTargets()`, `addTargets()`/`overrideTargets()`, and the desktop client's own target-arrow
  tooltip (lines 124, 764-795, 1152, 1345) confirming this is upstream's own established data/UX,
  not new design.
- `docs/stories/0057-board-interaction-casting-targeting-cancel.md` — the existing (different)
  targeting mechanism, for contrast/consistency.
- `docs/stories/0072-order-simultaneous-triggers.md` — the prior work on `mapCard()`'s
  `AbilityView`/`StackAbilityView` handling this story's ability-text substitution builds on.
