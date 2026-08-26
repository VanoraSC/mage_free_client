# 0086 — Spell and ability targets on the wire

- **Epic:** EPIC-23 — Game Information We Do Not Yet Map
- **Depends on:** nothing. The work is bridge-side (JVM) plus a `:protocol` field; it does not wait
  on EPIC-18, which is complete.

## 1. Objective

Carry `CardView.targets` — what a spell or ability on the stack is pointing at — from the server to
the app. It is the one piece of genuinely new data the stack and the targeting arrows need, and the
app has no access to it today at any layer.

## 2. Context & background

**Nothing maps targets, anywhere.** `ui-modernization-plan.md` §7.14 states it plainly: *"We do not
map `targets` anywhere — not in `:protocol`, not in `GameState`."* Verified against the code — no
`targets` field exists in `GameMessages.kt`, and `GameViewMapper` never reads one.

**Upstream has exactly the right shape already, and it is deliberate.** `CardView.targets` is a
`List<UUID>`, populated by `CardView.addTargets(Targets, Effects, Ability, Game)`
(`Mage.Common/src/main/java/mage/view/CardView.java`), which carries the comment *"need only unique
targets for arrow drawing"*. It:

- collects `target.getTargets()` for every `Target` where `target.isChosen(game)`;
- adds every effect's `TargetPointer.getTargets(game, source)`, so a mode that declares no target of
  its own still resolves through the pointer;
- de-duplicates through a `LinkedHashSet`, so the order is stable across snapshots.

Because the ids are resolved through `game.getObject(uuid)`, **a target that is itself a spell on the
stack arrives the same way a permanent does** — one flat id list, no per-kind branching.

**This is a "correct upstream field that is simply not mapped"** — the same shape as story 0076's
`transformed` and story 0058's `cardTypes`. Thread it through unchanged; do not compute anything.

## 3. Scope

**In scope**
- `GameCardView.targets: List<String>` in `:protocol`, defaulting to `emptyList()`.
- `GameViewMapper` reading `CardView.getTargets()` for every card it maps, including stack entries.
- Golden-file coverage and a live check that a real targeted spell arrives with its target.

**Out of scope**
- Drawing anything. Targeting arrows are §3.1 and EPIC-19; this story ends at the data.
- Resolving an id to what it points at. The app already has every battlefield permanent and every
  stack entry keyed by id in the same snapshot; joining them is the renderer's job.
- `StackAbilityView`'s other fields, and the stack's own presentation (§7.14).

## 4. Prerequisites & toolchain

Project baseline. `:bridge` builds **in the container only** (`scripts/dev`, see
`docs/build-environment.md`); the reference XMage server is the `xmage-server` Compose service from
story 0022.

## 5. Design & approach

**One field, one read, no interpretation.**

```kotlin
// :protocol — GameCardView
val targets: List<String> = emptyList(),
```

```kotlin
// :bridge — GameViewMapper.mapCard
targets = card.targets.orEmpty().filterNotNull().map(UUID::toString),
```

`targets` is `null` on a `CardView` that never had `addTargets` called, which is the ordinary case
for a hand card or a battlefield permanent — so `orEmpty()` is load-bearing, exactly as it already is
for `counters` (see `GameViewMapper`'s KDoc on the null-is-ordinary rule).

**Reachability (standard 2).** What *produces* a non-empty `targets` in production: the server calls
`addTargets` when building a `CardView`/`StackAbilityView` for a spell or ability with a chosen
target, so the field is populated for stack entries and empty everywhere else. The live check below
is what confirms that rather than assuming it — cast a targeted spell against the reference server
and read the snapshot.

**Additive by construction.** A new field with a default is the `ProtocolVersion` additive-compatible
change; no version bump, and an older app ignores it.

## 6. Implementation steps

1. Add `targets` to `GameCardView` with its KDoc, saying what the ids reference and that a stack
   target may itself be a stack entry.
2. Map it in `GameViewMapper.mapCard`.
3. Extend the `GameViews.kt` fixture builder so a card can be given targets.
4. Regenerate the affected goldens with `UPDATE_GOLDEN=1` and read the diff before committing it.

## 7. Testing & verification

- **Proven failing first (standard 1):** the mapper test asserting a targeted stack entry arrives
  with its target ids must be shown failing against a mapper that drops the field, then passing.
  A golden that was regenerated without ever being wrong proves nothing.
- **Unit:** `GameViewMapperTest` — a `CardView` with two targets maps to both ids in order; a
  `CardView` with `targets == null` maps to `emptyList()`, not to a crash.
- **Live:** against the reference server, cast a spell targeting an opponent's creature and assert
  the resulting `GameStateSnapshot` carries that permanent's id in the stack entry's `targets`. This
  is the step that proves the server actually populates the field on the path we read.
- **Eyes-on:** none. This story renders nothing; the live test is the verification.

## 8. Acceptance criteria

- [ ] `GameCardView.targets` exists, defaults to empty, and is documented.
- [ ] `GameViewMapper` populates it for stack entries and leaves it empty elsewhere.
- [ ] The mapper test was proven failing against a dropped field before passing.
- [ ] A live targeted cast against the reference server arrives with the target id.
- [ ] `./gradlew check` passes; goldens updated deliberately, with the diff reviewed.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/CardView.java` — `targets`, `addTargets`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard`.
- `protocol/src/commonMain/kotlin/magefree/protocol/GameMessages.kt` — `GameCardView`.
- `docs/ui-modernization-plan.md` §7.14 (the stack), §3.1 (targeting arrows).
