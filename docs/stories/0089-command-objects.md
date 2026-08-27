# 0089 — Command objects: emblems, commanders, dungeons, planes

- **Epic:** EPIC-23 — Game Information We Do Not Yet Map
- **Depends on:** 0088 (same view, same overlay; landing them in order avoids two people editing
  `GamePlayerView` at once). Not a technical dependency.

## 1. Objective

Map `PlayerView.commandList` — the objects that belong to a *player* rather than to a zone of cards.
It is unmapped in full today, and it is what P1 #25 and §7.15 expand the vitals overlay to show.

## 2. Context & background

**`commandList` is dropped entirely.** `ui-modernization-plan.md` §7.13: *"`commandList` is not
mapped at all."* Confirmed — no reference anywhere in `GameViewMapper` or `:protocol`.

**It is a polymorphic list, and that is the whole design problem.**
`PlayerView.commandList` is a `List<CommandObjectView>` populated in `PlayerView`'s constructor with
four concrete types (verified in `PlayerView.java`):

| Added as | When |
|---|---|
| `EmblemView` | each emblem the player controls |
| `DungeonView` | the player's active dungeon |
| `PlaneView` | the current plane |
| `CommanderView` | each of the player's commanders |

`CommandObjectView` is the common interface, and its surface is **narrower than this story first
assumed**. Read against `CommandObjectView.java` and `SelectableObjectView.java`, it declares exactly:
`getExpansionSetCode`, `getName`, `getId`, `getImageFileName`, `getImageNumber`, `getRules`, plus the
selection/playability methods it inherits.

**There is no `getCardNumber()` on it.** Only `EmblemView` declares one of its own (filled solely for
an `EmblemOfCard`, `""` otherwise), and `CommanderView`, which gets it by extending `CardView`.
`DungeonView` and `PlaneView` have no card number at all. So the collector number cannot be read off
the interface — it has to come from the same branch that decides the kind.

**The accessor is `getCommandObjectList()`**, not `getCommandList()`: the field is named for the list,
the getter for what is in it.

**`MageObjectType` already names the kinds.** `mage.view.MageObjectType` has `EMBLEM`, `COMMANDER`,
`DUNGEON` and `PLANE` among its constants, so the discriminator does not have to be invented — it can
be read off the concrete view's own type and carried as a code, the way `CardTypeCode` is.

**Why they are not just cards.** §7.15: they *"belong to a player, not to a pile of cards, and the
browser (§7.13) is for zones you look through. One home, not two."* Modelling them as `GameCardView`
entries in some pseudo-zone would put them in the wrong surface.

## 3. Scope

**In scope**
- A `GameCommandObjectView` in `:protocol` carrying the interface's fields plus a kind discriminator.
- The same list on `:core:network`'s `GamePlayer`, so it reaches the app rather than stopping at the wire.
- `GamePlayerView.commandList: List<GameCommandObjectView>`.
- `GameViewMapper` mapping all four concrete types through the interface.

**Out of scope**
- The vitals overlay that renders them (§7.15, EPIC-11), and emblem art.
- Dungeon *progress* (which room you are in) and plane-specific state. `DungeonView` carries more
  than the interface exposes; if the overlay later needs it, that is its own story with its own
  upstream read. This story maps the common surface, deliberately.
- `isPlayable` / `setPlayableStats`. Playability already has a home in `GameStateView.playable`
  (story 0054); a second, per-command-object playability signal needs a consumer before it needs a
  field.

## 4. Prerequisites & toolchain

Project baseline; `:bridge` in-container per `docs/build-environment.md`.

## 5. Design & approach

**One flat type with a kind code, not a sealed hierarchy.** The four concrete views differ in what
they *are*, not in what the app reads off them — the interface is the whole payload. A sealed
hierarchy would add four wire shapes to distinguish four values of one enum, and `ProtocolVersion`'s
additive rule is easier to keep with one type.

```kotlin
// :protocol
@Serializable
public data class GameCommandObjectView(
    val id: String,
    val name: String,
    val kind: CommandObjectKind = CommandObjectKind.UNKNOWN,
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val rules: List<String> = emptyList(),
)

@Serializable(with = CommandObjectKind.Serializer::class)
public enum class CommandObjectKind { EMBLEM, COMMANDER, DUNGEON, PLANE, UNKNOWN }
```

`UNKNOWN` and the tolerant serializer follow `CardTypeCode`'s existing pattern exactly: upstream adds
command-object kinds, and a newer bridge sending one this build has never heard of must cost one
enum value, **not the whole snapshot**. Copy `CardTypeCode.Serializer`; do not invent a second
tolerance mechanism.

**Deriving the kind.** Read it from the concrete view type in the mapper (`is EmblemView -> EMBLEM`,
…), not from `MageObjectType` on some other object. The mapper is the only place that sees the
concrete types, which is exactly where the branch belongs — and an unrecognised implementation maps
to `UNKNOWN` with its interface fields intact rather than being dropped.

**Reachability (standard 2).** `PlayerView`'s constructor appends to `commandList` for every emblem,
the active dungeon, the current plane and every commander — so the producer is the server, per
snapshot, per player. Commanders are the cheapest to reach live (any Commander game has two);
emblems need a planeswalker ultimate. Name in the PR what was reached live and what was not.

## 6. Implementation steps

1. Read `CommandObjectView.java`, `EmblemView.java`, `CommanderView.java`, `DungeonView.java` and
   `PlaneView.java` for the interface surface and confirm the four constructor call sites in
   `PlayerView.java`.
2. Add `GameCommandObjectView` + `CommandObjectKind` with a tolerant serializer copied from
   `CardTypeCode`.
3. Add `commandList` to `GamePlayerView`.
4. Map it in `GameViewMapper.mapPlayer`, branching on the concrete type for the kind only.
5. Extend `GameViews.kt` with a fixture per concrete type.
6. Carry the same list through `:core:network`'s `GamePlayer` and its mapper.
7. No golden to regenerate: the only committed golden is `chat_talk.json`.

## 7. Testing & verification

- **Proven failing first (standard 1):** four `:bridge` mapper tests and two `:core:network` fold
  tests each fail against a mapper that drops `commandList`, then pass.
- **Unit (`:bridge`):** all four upstream implementations map to their own kind, in order, with ids;
  only the commander and a card-printed emblem carry a collector number, while a dungeon and a plane
  carry a set code and no number; a `CommandObjectView` implementation the branch has never seen maps
  to `UNKNOWN` with every interface field intact; an empty command zone maps to `emptyList()`.
- **Unit (`:protocol`):** the list round-trips with its kinds, an unknown kind string decodes to
  `UNKNOWN` rather than throwing, kinds encode as their own names, and a frame from a bridge that
  sends no command zone decodes with an empty one.
- **Unit (`:core:network`):** the kinds and printings survive the fold, and an absent kind folds to
  `Unknown`.
- **Live:** a `Freeform Unlimited Commander` game against the reference server. That variant is what
  makes it cheap: its deck validator's `validate()` returns `true` unconditionally and its minimum
  deck size is zero, so a single legendary creature in the sideboard becomes the commander with no
  hundred-card singleton list to satisfy. **The scenario needs no play at all** — a commander is in
  the command zone from the first snapshot.
- **Not reached live, and named rather than papered over:** the emblem, dungeon and plane kinds. An
  emblem needs a planeswalker's ultimate, which is many turns and an AI decision; a dungeon needs a
  venture card and two turns of setup; a plane needs a Planechase game type. All three are covered
  hermetically against the real upstream view types.
- **Eyes-on:** none.

## 8. Acceptance criteria

- [x] `GameCommandObjectView` and `CommandObjectKind` exist, with a tolerant serializer proven by test.
- [x] `GamePlayerView.commandList` is populated for all four upstream types.
- [x] An unrecognised `CommandObjectView` implementation maps to `UNKNOWN` and keeps its fields.
- [x] The mapper tests were proven failing before passing.
- [x] Live coverage of the commander kind; the other three are named, with the reason each was not
      reachable.
- [x] The fields reach the app: `GameState`'s `GamePlayer` carries them.
- [x] `./gradlew check` and `:bridge:check` pass; no golden needed regenerating.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/` — `CommandObjectView`, `EmblemView`,
  `CommanderView`, `DungeonView`, `PlaneView`, `PlayerView`, `MageObjectType`.
- `protocol/src/commonMain/kotlin/magefree/protocol/GameMessages.kt` — `CardTypeCode.Serializer`,
  the tolerance pattern to copy.
- `docs/ui-modernization-plan.md` §7.15, P1 #25.
