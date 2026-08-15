# 0065 — Battlefield stacking: conserve board space for duplicate permanents

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0055 (board rendering, `PermanentUi`/`BattlefieldBand`), 0057 (interaction,
  `CardDetailOverlay`, pick-state), 0058 (counters/creature status — part of the grouping key), 0061
  (combat fields — part of the grouping key)

## 1. Objective

Stop a battlefield with several of the same land or token from costing the same space as one with ten
different permanents. Today `BattlefieldBand` (`feature/game/.../board/BoardRegions.kt`) renders every
`PermanentUi` as its own full-size card in a single scrolling row — confirmed by reading the code. Group
duplicate, currently-fungible permanents into a compact pile; never let the grouping hide anything the
server's per-object state actually distinguishes.

## 2. Context & background

Requirements §20 has the full, resolved design — read it first; this story does not repeat the
reasoning, only the implementation shape. Summary:

- **The grouping key (§20.1) is every rendered `PermanentUi` field, not name alone**: name, `isTapped`,
  `damage`, `counters` (name+count), `showsSummoningSickness`, `isAttacking`, `isBlocking`,
  `attackingDefenderName`, `blockedByNames`/`blockingAttackerNames`, and current pick-eligibility for
  whatever prompt is outstanding. Two permanents pile together **only** when all of these match. This is
  a correctness requirement, not a preference — the pile must never merge a legal target with an
  illegal one, or hide a counter/damage difference.
- **Printing/art is deliberately excluded** from the key — mixed-art basic lands are still one pile,
  showing one representative face. This is the one deliberate exception to "match every field."
- **Combat needs no special-case logic.** The same key, applied to combat's own fields, already produces
  the behaviour Pete described: a wide token attack stays one pile until blocks are assigned, at which
  point a blocked attacker's `blockedByNames` diverges from its still-unblocked siblings and it falls
  out on its own — a consequence of the key, not a rule written for combat specifically.
- **Rendering (§20.2):** 1 member unchanged; 2–3 members fan out as that many real card faces (countable
  by inspection, no badge needed); 4+ caps the fan at 3 layered faces plus a `×N` count badge. Tapped
  state is part of the key, so a permanent moving to tapped naturally leaves its old pile and
  joins/starts a tapped one — no special transition logic, just a re-derived render on the next
  snapshot.
- **Interaction (§20.3):** tapping a pile during an active prompt sends **one pick per tap** — the
  first id in the pile the server actually offered — and the pile visually shrinks by one each time.
  Tapping a pile with nothing outstanding opens the **existing** `CardDetailOverlay` on an arbitrary
  member (all members are indistinguishable by construction of the key, so which one is sent doesn't
  matter). No new detail component.
- **Piling is derived, not persisted** — recomputed fresh every render from the current permanent list
  and live pick-state, so it never needs to be kept in sync with anything.

## 3. Scope

**In scope**
- A pure grouping function (e.g. `groupPermanents(permanents: List<PermanentUi>, picks: (String) ->
  CardPickState): List<PermanentPile>`) implementing §20.1's key exactly, unit-testable with no Compose
  dependency.
- `BattlefieldBand`'s render loop updated to render piles instead of a flat permanent list: 1-member
  piles render exactly as today; multi-member piles render the fan-and-cap treatment (§20.2).
- Tap resolution inside the pile-rendering composable: which member id a tap sends, per §20.3 — the
  `onCardTap: ((String) -> Unit)?` contract on `BattlefieldBand` itself does not change.
- Applies to both battlefield bands (viewer and opponent).
- Unit coverage proving the key: identical permanents pile; a difference in any one field (tapped,
  damage, a counter, summoning sickness, attacking/blocking state, pick-eligibility) prevents piling;
  mixed art/printing does **not** prevent piling.

**Out of scope**
- Any change to `PermanentUi`, `GameState`, or the wire protocol — this is purely a rendering
  transformation over data that already exists.
- A dedicated "pile member list" detail view — explicitly rejected (§20.3); reuse
  `CardDetailOverlay`.
- Stacking anywhere other than the battlefield (hand, graveyard, exile, stack) — not asked for, and
  each of those has its own existing rendering with its own reasons; out of scope unless a future
  requirement names them.
- Animation/transition polish for permanents moving between piles (e.g. tap causing a piece to visibly
  slide from one pile to another) — a real render-correctness feature first; motion design is a later
  pass if it's worth it.

## 4. Design & approach

- **Keep the grouping function pure and separate from Compose.** It takes the same inputs
  `BattlefieldBand` already receives (`seat.battlefield`, the `picks` lookup) and returns a list of
  piles; `BattlefieldBand` only renders what it's given. This keeps the correctness-critical logic
  (the grouping key) testable without a Compose test harness.
- **The key is exact-match, not "close enough."** Do not attempt fuzzy grouping (e.g. ignoring small
  damage differences) — any drift from exact-match risks hiding real state, which is the one thing this
  feature must never do.
- **Reuse existing visual language.** The tapped-rotation (`TAPPED_ROTATION`) and pick-state border
  treatment (`pickBorder`, `CardPickState`) already exist per-card; the fan/cap treatment wraps them,
  it doesn't replace them — a pile of pickable permanents should still read as pickable at a glance.

## 5. Verification

- **Hermetic:** the grouping function is the centre of gravity here — exhaustive unit tests over
  `groupPermanents` covering every field in the key individually (change one field, confirm the
  permanents no longer pile), the mixed-art exception, and the 1 / 2–3 / 4+ rendering thresholds.
  Compose tests confirming a pile tap sends the expected id and shrinks correctly across successive
  taps (mana-payment-style), and that an idle tap opens `CardDetailOverlay`.
- **Live:** a deck with enough basic lands to reach a 4+ pile (any of the working decks in
  `docs/live-test-decklists.md` already has 20+ of one basic), confirm the pile renders capped-at-3
  with a correct count, and that tapping it to pay mana consumes members one at a time and matches the
  server's actual tap state (`viewer.battlefield[].isTapped`) after each tap.
- **On-device (standard 3):** eyes-on — the pile is legible at a glance (count is readable, tapped
  members are visibly separated from untapped ones), and a wide combat (several same-name
  attackers/blockers) behaves the way §20.1 describes as blocks are assigned.

## 6. Acceptance criteria

- [ ] Same-name permanents that are identical across every field in §20.1's key render as one pile;
      any single differing field (tapped, damage, a counter, summoning sickness, attacking/blocking
      state, pick-eligibility) keeps them separate.
- [ ] Mixed printings/art of the same card still pile together.
- [ ] 1 member renders unchanged; 2–3 render as a fan of that many faces; 4+ render as a 3-face fan
      with a correct `×N` count badge.
- [ ] Tapping a pile during an active prompt sends one pick per tap, using only ids the server offered,
      and the pile's visible count decreases by one each time.
- [ ] Tapping a pile with no prompt outstanding opens the existing `CardDetailOverlay`.
- [ ] A blocked attacker (or an assigned blocker) separates from its still-unpaired, same-name siblings
      automatically, with no combat-specific code path — verified by the same grouping-key tests, not a
      separate combat test suite.
- [ ] Applies identically to both battlefield bands.
- [ ] Live-verified against a real pile of 4+ (e.g. basic lands) being tapped down to zero.

## 7. References

- `docs/game-board-requirements.md` — §20 (the full design, resolved).
- `feature/game/.../board/BoardRegions.kt` — `BattlefieldBand`, the render loop this changes.
- `feature/game/.../board/BoardUi.kt` — `PermanentUi`, the fields the grouping key reads.
- `feature/game/.../board/BoardCards.kt` — `PermanentCard`, `TAPPED_ROTATION`, `CardPickState` — the
  existing per-card visual language a pile wraps rather than replaces.
- [`0055-board-rendering.md`](0055-board-rendering.md), [`0057-board-interaction.md`](0057-board-interaction.md),
  [`0058-creature-status-and-counters.md`](0058-creature-status-and-counters.md),
  [`0061-combat-declaration.md`](0061-combat-declaration.md) — the fields and machinery this reuses.
