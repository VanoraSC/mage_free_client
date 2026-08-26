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
counters =
    player.counters.orEmpty().filterNotNull().map { counter ->
        GameCounterView(name = counter.name.orEmpty(), count = counter.count)
    },
monarch = player.isMonarch,
initiative = player.isInitiative,
designationNames = player.designationNames.orEmpty().filterNotNull(),
```

The accessor is `isInitiative()`, not `hasInitiative()`.

**Counter order is meaningless, and the KDoc has to say so.** `PlayerView` builds the list from
`player.getCountersAsCopy().values()`, and `mage.counters.Counters` extends `HashMap` — so unlike the
zone lists, which come from `LinkedHashMap`s, this arrives in hash order. Consumers look a counter up
by name; nothing indexes into it.

**`designationNames` can only ever carry City's Blessing** (verification standard 5). `PlayerView`
reads `player.getDesignations()`, and the only production caller of `Player.addDesignation` anywhere
in XMage is `AscendAbility`. The Monarch, Initiative and Speed designations are registered through
`GameState.addDesignation` — they are game-level, not per-player — so they never appear in this list
however the game goes. That is precisely why `monarch` and `initiative` are separate flags, and it is
worth writing down: a consumer looking for `"The Monarch"` in `designationNames` would wait forever.

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
5. Carry the same four through `:core:network`'s `GamePlayer` and its mapper.
6. No golden to regenerate: the only committed golden is `chat_talk.json`.

## 7. Testing & verification

- **Proven failing first (standard 1):** two `:bridge` mapper tests and the `:core:network` fold test
  each fail against a mapper that drops the fields. So does the live test, proven once per field:

  | Break | Live failure |
  |---|---|
  | `counters = emptyList()` | "no snapshot ever carried a poison counter" |
  | `monarch = false` | the loop exhausts its cap with `monarch=false` |
  | `initiative = false` | the loop exhausts its cap with `initiative=false` |

- **Unit (`:bridge`):** a player carries poison and energy, looked up by name; the two flags and the
  designation list are per-seat, so the opponent gets neither crown; a sparse view with `counters` and
  `designationNames` null maps to empty lists rather than throwing.
- **Unit (`:protocol`):** all four round-trip, and a frame from an older bridge decodes with no
  counters and neither flag set.
- **Unit (`:core:network`):** the same four survive the fold, and the opponent's seat carries none of
  them.
- **Live:** `GameRelayIT` plays a deck in which **every cost is generic**, so no colour of mana is
  ever required and a mis-chosen colour cannot stall a payment:
  - `Mox Poison` (`{0}`) — tapping it for mana gives its controller two poison counters. Cheapest
    poison in the game, and it needs no combat at all.
  - `Dungeoneer's Pack` (`{3}`, then `{2}`, `{T}`, sacrifice) — takes the initiative.
  - `Throne of the High City` (a land; `{4}`, `{T}`, sacrifice) — makes you the monarch.

  The opponent seat holds sixty basic lands, so it never attacks, blocks or casts anything: every
  state change in the game is one the test caused.

  Two prompts needed answers the other live loops do not: a `ChooseAbilityPrompt` (the `Throne` has a
  mana ability *and* the crown ability, so an object id alone does not say which is wanted), and
  "you still have mana in your mana pool… pass anyway?", answered **yes** — answering no sends
  `PASS_PRIORITY_CANCEL_ALL_ACTIONS` and the server asks again, which is an unbounded loop rather
  than a failure.

  **Poison arrives two at a time, and more than once.** The Mox is tapped again on any turn its mana
  is needed, so the assertion is that the count is a positive multiple of two rather than exactly two.
- **Not covered live: `designationNames`.** City's Blessing is the only value it can ever hold, and
  that needs Ascend plus ten permanents. The live test asserts the *opposite* instead — that no seat
  carries a designation even while one is the monarch and has the initiative — which is the claim a
  consumer would otherwise get wrong.
- **Eyes-on:** none. Nothing renders yet — §7.15 is EPIC-11.

## 8. Acceptance criteria

- [x] All four fields exist on `GamePlayerView`, default safely, and are documented.
- [x] Counters reuse `GameCounterView` rather than introducing a second counter type.
- [x] The mapper tests were proven failing before passing, and the live test once per field.
- [x] A live poison counter, crown and initiative all arrive end-to-end; `designationNames` is named
      as not reachable live, with the upstream reason.
- [x] The fields reach the app: `GameState`'s `GamePlayer` carries them.
- [x] `./gradlew check` and `:bridge:check` pass (the latter with `XMAGE_SERVER` set); no golden
      needed regenerating.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/PlayerView.java`, `CounterView.java`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapPlayer`.
- `docs/ui-modernization-plan.md` §7.15 — player vitals.
- `docs/stories/0058-*.md` — why a counter kind is a string.
