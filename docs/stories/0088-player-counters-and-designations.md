# 0088 — Player counters and designations

- **Epic:** EPIC-23 — Game Information We Do Not Yet Map
- **Depends on:** nothing (bridge-side + `:protocol`).

## 1. Objective

Map the per-player state that decides games without being on the battlefield: `counters`, `monarch`,
`initiative` and `designationNames`. **Poison is a win condition and the app cannot see it today.**

## 2. Context & background

**`GamePlayerView` has no counters at all.** `ui-modernization-plan.md` §7.15: *"Poison is a win
condition and we do not show it. `PlayerView.counters` is a `List<CounterView>` and `:protocol`'s
`GamePlayerView` does not have it, so no amount of rendering work reaches it today."* Confirmed
against both files.

**Upstream carries all four on `PlayerView`** (`Mage.Common/src/main/java/mage/view/PlayerView.java`):

| Field | Type | Why it matters |
|---|---|---|
| `counters` | `List<CounterView>` | poison (a loss at 10), energy, experience |
| `monarch` | `boolean` | draws a card each end step; changes on combat damage |
| `initiative` | `boolean` | Undercity venture each upkeep |
| `designationNames` | `List<String>` | City's Blessing and friends |

`CounterView` is exactly `{name, count}` — nothing richer exists upstream, which is why
`:protocol` already models a counter that way for cards (`GameCounterView`, story 0058). **Reuse
that type**; a second counter shape would be drift for no gain.

**The counter kind stays a string.** `GameCounterView`'s KDoc already argues this for cards and the
same argument holds here: poison, energy and experience are the common player counters, but the set
is open and grows with every release. Never a closed enum.

## 3. Scope

**In scope**
- `GamePlayerView.counters: List<GameCounterView>`, `monarch: Boolean`, `initiative: Boolean`,
  `designationNames: List<String>`.
- `GameViewMapper.mapPlayer` reading all four.

**Out of scope**
- The vitals overlay that renders them (§7.15, EPIC-11). This story ends at the data.
- `commandList` — emblems, commanders, dungeons and planes are story 0089. They live on the same
  view and feed the same overlay, but they need a new polymorphic protocol type and this story does
  not, so keeping them apart keeps both verifiable.
- The passed-priority flags (`passedTurn`, `passedAllTurns`, …), which belong to §7.9 priority.

## 4. Prerequisites & toolchain

Project baseline; `:bridge` in-container per `docs/build-environment.md`.

## 5. Design & approach

```kotlin
// :protocol — GamePlayerView
val counters: List<GameCounterView> = emptyList(),
val monarch: Boolean = false,
val initiative: Boolean = false,
val designationNames: List<String> = emptyList(),
```

```kotlin
// :bridge — GameViewMapper.mapPlayer
counters = player.counters.orEmpty().filterNotNull().map { GameCounterView(it.name.orEmpty(), it.count) },
monarch = player.isMonarch,
initiative = player.hasInitiative,
designationNames = player.designationNames.orEmpty().filterNotNull(),
```

Accessor names are illustrative — **read `PlayerView.java` and use what is actually there.** The
existing card-counter mapping in `GameViewMapper` is the shape to copy for the counter list.

**Reachability (standard 2).** `PlayerView`'s constructor populates `counters` from the player's own
counters, and `monarch` / `initiative` / `designationNames` from game state, on every snapshot for
every player — including the opponent, since all of it is public information. The live check is what
confirms the path rather than the fixture.

## 6. Implementation steps

1. Read `PlayerView.java` for the four accessors and `CounterView.java` to confirm `{name, count}`.
2. Add the four fields to `GamePlayerView` with KDoc — say explicitly that poison at 10 is a loss, so
   the next reader knows why this is not cosmetic.
3. Map them in `mapPlayer`.
4. Extend `GameViews.kt` so a fixture player can carry counters, the two flags and designations.
5. Regenerate goldens with `UPDATE_GOLDEN=1`; read the diff.

## 7. Testing & verification

- **Proven failing first (standard 1):** the mapper test asserting a poison counter reaches
  `GamePlayerView` must fail against a mapper that drops `counters`, then pass.
- **Unit:** `GameViewMapperTest` — a player with poison 3 and energy 2 maps both, in order; a player
  with `counters == null` maps to `emptyList()`; the two booleans and the designation list round-trip.
- **Live:** against the reference server, put a poison counter on a player and assert it arrives.
  Monarch is the cheapest of the three flags to reach live; if initiative or a designation cannot be
  produced without a contrived deck, say so in the PR rather than asserting a fixture and calling it
  live coverage.
- **Eyes-on:** none. Nothing renders yet — §7.15 is EPIC-11.

## 8. Acceptance criteria

- [ ] All four fields exist on `GamePlayerView`, default safely, and are documented.
- [ ] Counters reuse `GameCounterView` rather than introducing a second counter type.
- [ ] The mapper test was proven failing before passing.
- [ ] A live poison counter arrives end-to-end; whatever could not be reached live is named.
- [ ] `./gradlew check` passes; goldens updated deliberately.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/PlayerView.java`, `CounterView.java`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapPlayer`.
- `docs/ui-modernization-plan.md` §7.15 — player vitals.
- `docs/stories/0058-*.md` — why a counter kind is a string.
