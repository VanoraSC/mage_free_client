# 0090 — Zone contents: graveyard and exile cards, not just counts

- **Epic:** EPIC-23 — Game Information We Do Not Yet Map
- **Depends on:** nothing technically; land it after 0088/0089 to keep `GamePlayerView` edits serial.

## 1. Objective

Stop discarding the cards in a player's graveyard and exile. The bridge reduces both to an integer
today, which makes the zone browser (§7.13) unimplementable at any layer above it.

## 2. Context & background

**The bridge takes the size and throws the cards away.** From `GameViewMapper.mapPlayer`:

```kotlin
graveyardCount = player.graveyard?.size ?: 0,
exileCount = player.exile?.size ?: 0,
```

`PlayerView.graveyard` and `PlayerView.exile` are both `CardsView` — a map of `CardView` by id. The
count is derived from data we already have in hand and then drop.

**Two exile views exist and both are needed.** §7.13, confirmed against `GameView.java` and
`PlayerView.java`:

- `PlayerView.exile` answers *"what of mine is exiled"*.
- `GameView.exiles: List<ExileView>` answers *"which pile is it in"* — each `ExileView` is an
  `ExileZone`'s cards with its **name** and id.

`GameView.exiles` is **already mapped**, as `GameStateView.exile: List<GameZoneView>` — and never
rendered. So this story adds the player-owned half, and the browser joins them.

**Telling special exiles apart needs no new data.** §7.13 records two signals, both already
available: the zone **name** (`"Plots of <player> - Exile"`, `"Rebound"`), which arrives as
`GameZoneView.name` today; and `canPlayObjects`, already mapped as `GameStateView.playable`. Do not
add a third mechanism.

**Payload size is the one real design question here.** `architecture.md` open question #7 — how much
of `GameView` a phone needs per frame, and whether to delta it — is still open, and a late-game
graveyard is the largest single thing this adds to a snapshot sent on every state change. §10 says to
**measure real payloads before deciding anything**, so this story measures rather than guesses.

## 3. Scope

**In scope**
- `GamePlayerView.graveyard: List<GameCardView>` and `exile: List<GameCardView>`.
- `GameViewMapper.mapPlayer` mapping both through the existing `mapCard`.
- Keeping `graveyardCount` / `exileCount` as they are, and a **measurement** of the snapshot size
  before and after, recorded in the PR.

**Out of scope**
- The zone browser itself (§7.13, EPIC-11).
- Library and sideboard contents. The library is hidden information; the sideboard belongs to
  sideboarding (§7.16, EPIC-14).
- Any delta or compression scheme for snapshots. If the measurement says one is needed, that is its
  own story with its own numbers — not a decision smuggled into a mapping change.

## 4. Prerequisites & toolchain

Project baseline; `:bridge` in-container per `docs/build-environment.md`.

## 5. Design & approach

```kotlin
// :protocol — GamePlayerView
val graveyard: List<GameCardView> = emptyList(),
val exile: List<GameCardView> = emptyList(),
```

```kotlin
// :bridge — GameViewMapper.mapPlayer
graveyard = player.graveyard.orEmpty().values.map(::mapCard),
exile = player.exile.orEmpty().values.map(::mapCard),
```

**Keep the counts.** They are not redundant in practice: they are what the collapsed vitals row reads
(§7.15) and they cost four bytes, while removing them would be a breaking protocol change for a
consumer that only wants the number. `count == list.size` is an invariant worth asserting in the
mapper test.

**Ordering is upstream's.** `CardsView` is a `LinkedHashMap`, so iteration order is the order the
server built it in. Preserve it and do not sort — a graveyard has a meaningful order and the client
is not the place to decide what it is.

**Reachability (standard 2).** `PlayerView`'s constructor fills `graveyard` and `exile` from the
player's zones for **every** player, not only the viewer — graveyards are public information. The
live check confirms the opponent's graveyard arrives too, which is the case a single-player fixture
cannot prove.

**Measurement, concretely.** Serialize a real `GameStateSnapshot` from a live game with a populated
graveyard using `ProtocolJson.json`, record the byte length before and after this change, and put
both numbers in the PR. That is the input §10 asks for, and it costs one assertion-free test run.

## 6. Implementation steps

1. Add the two fields to `GamePlayerView` with KDoc, including why the counts stay.
2. Map both in `mapPlayer`.
3. Extend `GameViews.kt` so a fixture player has a non-empty graveyard and exile.
4. Regenerate goldens with `UPDATE_GOLDEN=1`; read the diff — this one will be large, so read it
   rather than accepting it.
5. Measure and record the snapshot size delta.

## 7. Testing & verification

- **Proven failing first (standard 1):** the mapper test asserting a graveyard card's name arrives
  must fail against a mapper that maps only the count, then pass.
- **Unit:** `GameViewMapperTest` — graveyard and exile cards map in upstream order;
  `graveyardCount == graveyard.size` and the same for exile; a player with `graveyard == null` maps
  to `emptyList()` with count 0.
- **Live:** against the reference server, put cards in **both** players' graveyards and assert the
  opponent's arrive as well as the viewer's.
- **Measurement:** snapshot byte size before and after, from a real game, in the PR body.
- **Eyes-on:** none. Nothing renders yet.

## 8. Acceptance criteria

- [ ] `GamePlayerView.graveyard` and `exile` carry cards; the counts are unchanged and still correct.
- [ ] Upstream ordering is preserved and asserted.
- [ ] The mapper test was proven failing before passing.
- [ ] The opponent's graveyard is confirmed live, not only the viewer's.
- [ ] The snapshot size delta is measured and recorded, per §10.
- [ ] `./gradlew check` passes; goldens updated deliberately, with the diff read.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/PlayerView.java`, `GameView.java`, `ExileView.java`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapPlayer`.
- `docs/ui-modernization-plan.md` §7.13 (zone browser), §10 (payload size).
- `docs/architecture.md` open question #7.
