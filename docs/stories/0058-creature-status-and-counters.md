# 0058 — Creature status and counters: render what a card *currently is*

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0051 (game protocol/relay), 0052 (`GameClient`/`GameState`), 0055 (board rendering)
- **Status:** ready

## 1. Objective

Render a permanent's **current** characteristics: whether it is a creature *right now*, its power and
toughness **only if it is one**, and the **counters** on it. Pete: *"strictly speaking, creatures have
power and toughness and non creatures don't."*

Creature-ness is not a property of the printed card — it is a property of the game state. **Earthbend
makes a land a creature. Ensoul Artifact makes an artifact a creature. Crewing a Vehicle makes it a
creature until end of turn.** A board that decides "is this a creature?" from the printing is wrong in
every one of those cases, and they are ordinary play, not corner cases.

## 2. Context & background — the server already answers this, and the bridge drops it

**Today the board renders `0/0 · Summoning sick` under a Mountain.** Observed on-device during 0057's
verification. Two separate defects behind one label:

- **`power`/`toughness` are rendered unconditionally**, so a noncreature shows `0/0`.
- **`hasSummoningSickness` is rendered unconditionally**, and upstream sets it for *any* permanent that
  came under its controller's control this turn — so the label appears on lands, artifacts and
  enchantments, where it means nothing.

**This does not require the client to interpret anything.** `mage.view.CardView` (verified by
decompiling `mage-common-1.4.60.jar`, the exact artifact `:bridge` builds against) exposes:

| Member | Type | What it gives us |
|---|---|---|
| `isCreature()` | `boolean` | **The server's own answer**, computed from the live game object |
| `getCardTypes()` | `List<CardType>` | Current types, after continuous effects |
| `getCounters()` | `List<CounterView>` | `{name: String, count: int}` |
| `getSubTypes()` / `getSuperTypes()` | | Current sub/super types |
| `getPower()` / `getToughness()` | `String` | **Current** P/T, after effects |
| `getOriginalPower()` / `getOriginalToughness()` | `MageInt` | Printed P/T, for showing a buff |

`PermanentView extends CardView`, so every one of these is already available for battlefield permanents.

**The bridge maps none of them.** `GameViewMapper.mapCard` (`bridge/.../mapping/GameViewMapper.kt:172`)
takes `typeText`, `power`, `toughness` and `rules`, and drops `cardTypes`, `isCreature` and `counters`
on the floor. So the app cannot render counters at all, and cannot tell a creature from a land except
by parsing a display string.

This is **verification standard 5** — *unexpectedly absent* — one layer further out than 0056: the
field is absent downstream because **nothing upstream writes it**.

> **Correction to an earlier judgement, recorded so it is not repeated.** During 0057 this was deferred
> on the grounds that suppressing `0/0` would mean interpreting `typeLine`, and so would be "the first
> place the client reads rules meaning out of a card". That reasoning was wrong: `isCreature()` and
> `cardTypes` are the *server's* answer and always have been. Reading them is the same act as reading
> `power` — and parsing `typeLine`, which is what a client is forced into without them, is the thing
> that would actually have put rules interpretation in the client.

## 3. Scope

**In scope**
- **`:bridge`** — map the server's answer onto the app schema: creature status (prefer the structured
  `cardTypes` so subtypes/supertypes are available later; `isCreature()` is the server's own convenience
  over the same data), and `counters`.
- **`:protocol`** — additive fields on `GameCardView`: card types and counters. Additive only; existing
  fields keep their meaning and their defaults, so an older client still parses a newer payload.
- **`:core:network`** — carry them into `GameCard`/`GamePermanent`.
- **`feature/game`** — render:
  - **P/T only when the permanent is currently a creature.** A land is a land; an Earthbent land is a
    2/2 and says so.
  - **Counters**, by name and count, on any permanent that has them — `+1/+1` and `-1/-1` above all, but
    the rendering must be **generic** (loyalty, charge, oil, stun, and hundreds more exist). Do not
    enumerate counter kinds.
  - **Summoning sickness only where it means something** — a creature that cannot yet attack or tap.
- Counters are **not** battlefield-only: `CardView` carries them, so a card in another zone can have
  them. Render where the data is present rather than assuming a zone.

**Out of scope**
- Combat declaration (attackers/blockers) — its own story.
- Showing *why* a permanent is a creature (which effect animated it), and printed-vs-current P/T
  comparison via `originalPower`/`originalToughness`. Both are additive once the data is carried; keep
  the fields in mind but do not build the UI here.
- Any rules computation in the client. If a question needs an answer the server did not send, the answer
  is to carry more of the server's data, never to derive it.
- Card art, layout changes beyond what P/T and counters need.

## 4. Constraints already verified — do not rediscover

- **`PermanentView extends CardView`** — permanents inherit types, P/T and counters; no separate path.
- **`CounterView` is `{name, count}`** — nothing richer to model.
- **`power`/`toughness` are `String`**, not ints (`*` is a real value — Tarmogoyf, Mortivore). Do not
  parse them into numbers to decide anything.
- **`manaCost` is null for lands** (0055) — absence of a cost is not absence of a card.
- **Deck/card resolution is by `(setCode, collectorNumber)`** — irrelevant here, but the same
  `GameCardView` carries it; do not disturb those fields.
- The board must keep rendering correctly from the **first snapshot**, where battlefields are empty.

## 5. Verification

- **Standard 1** — demonstrate each behavioural test failing first. The sharpest one: a **land that is
  currently a creature** must show its P/T, and a **land that is not** must show none. A test that only
  covers "lands have no P/T" would be passed by hardcoding lands to hide it, which is the bug in
  mirror image.
- **Standard 5 / reachability (standard 2)** — for every new field, name what *writes* it: the bridge
  mapper's read of `CardView`. A protocol field with no mapper behind it is exactly this defect again.
- **Bridge tests** for the mapping, including a permanent with several counters and one with none.
- **Hermetic** ViewModel/Compose tests over fakes: creature, noncreature, animated noncreature,
  counters present/absent, `*` power.
- **Live** — the honest proof needs a permanent whose creature-ness *changes*. Two candidates worth
  costing before writing the test: **crewing a Vehicle** (a creature only while crewed) and
  **Ensoul Artifact**. Record the exact printings in `docs/live-test-decklists.md` as §5, per the
  standing instruction that working decklists are written down rather than relearned. A `+1/+1` counter
  source in the same deck covers counters in the same run.
- **On device** — a land and a creature side by side, and a permanent carrying counters.

## 6. Acceptance criteria

- [ ] A noncreature permanent shows **no power/toughness** and no summoning-sickness label.
- [ ] A creature shows its **current** power/toughness.
- [ ] A permanent that is a creature **only because of an effect** (animated land, ensouled artifact,
      crewed Vehicle) renders as a creature, with P/T, and stops doing so when the effect ends.
- [ ] Counters render generically by name and count, with no enumeration of counter kinds.
- [ ] The bridge carries card types and counters; each new protocol field has a mapper that writes it.
- [ ] `*` power/toughness renders as sent, never parsed.
- [ ] Verified live against a permanent whose creature status changes, and on device.

## 7. References

- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard`, where the data is dropped.
- `protocol/src/main/kotlin/magefree/protocol/GameMessages.kt` — `GameCardView`, `GamePermanentView`.
- `core/network/src/main/kotlin/magefree/network/game/GameState.kt` — `GameCard`, `GamePermanent`.
- [`0055-board-rendering.md`](0055-board-rendering.md) — the rendering this corrects; [`0057-board-interaction.md`](0057-board-interaction.md) — where `0/0 · Summoning sick` was observed.
- `docs/stories/README.md` § Verification standards — standards 1, 2 and 5.
