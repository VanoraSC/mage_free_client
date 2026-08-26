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
- The app-side half of the same thread: `GameCard.targets` in `:core:network`'s `GameState`, carried
  by its own `GameViewMapper`. The objective is "from the server to the app", and a field that stops
  at `:protocol` has not reached the app — nothing above `:core:network` can see a `:protocol` type.
- A live check that a real targeted spell arrives with its target.

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
targets =
    card.targets
        .orEmpty()
        .filterNotNull()
        .map(UUID::toString),
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
3. Extend the `GameViews.kt` fixture builder so a card can be given targets — leaving the field unset
   by default, because upstream's `null` is the ordinary case and a fixture that pre-fills it would
   hide the null path.
4. Carry the same field through `:core:network`'s `GameCard` and its own mapper.
5. No golden to regenerate: the only committed golden is `chat_talk.json`, and no game-state golden
   exists. The mapper tests are the drift detector here.

## 7. Testing & verification

- **Proven failing first (standard 1):** three bridge mapper tests and the `:core:network` fold test
  were each run against a mapper that drops the field and seen to fail, then pass. The live test was
  put through the same treatment — with `targets` mapped to `emptyList()` it fails on the real
  server, so it is discriminating rather than merely green.
- **Unit (`:bridge`):** `GameViewMapperTest` — a spell with two targets maps to both ids in upstream
  order; a `StackAbilityView` carries its target too (one read covers both stack object kinds); a
  target that is itself a stack entry is a plain id that resolves inside the same snapshot; a card
  with `targets == null` maps to `emptyList()`, not to a crash.
- **Unit (`:protocol`):** the list round-trips in order, and a frame from a bridge older than this
  story decodes with no targets — the additive-compatibility promise, asserted rather than asserted
  about.
- **Unit (`:core:network`):** `GameEventFoldTest` — the ids survive the fold and resolve against the
  same snapshot; a card that targets nothing folds to an empty list.
- **Live:** `GameRelayIT` casts a real `Lightning Bolt` at the AI seat against the reference server
  and reads the target id back off the pushed snapshot. This is the step that proves the *server*
  populates the field on the path we read. The target is a **player**, chosen by the test from the
  server's own `GAME_TARGET` candidates and asserted by identity — deterministic, and it exercises
  precisely the "one flat list, no per-kind branching" property the mapper claims. Targeting a
  creature would have meant waiting for the AI to put one on the board, which is an AI decision, not
  a fact about the field.
- **Eyes-on:** none. This story renders nothing; the live test is the verification.

## 8. Acceptance criteria

- [x] `GameCardView.targets` exists, defaults to empty, and is documented.
- [x] `GameViewMapper` populates it for stack entries and leaves it empty elsewhere.
- [x] The mapper tests were proven failing against a dropped field before passing — including the
      live one.
- [x] A live targeted cast against the reference server arrives with the target id.
- [x] The field reaches the app: `GameState`'s `GameCard` carries it.
- [x] `./gradlew check` and `:bridge:check` pass (the latter with `XMAGE_SERVER` set, so the live
      tests really ran); no golden needed regenerating.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/CardView.java` — `targets`, `addTargets`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard`.
- `protocol/src/commonMain/kotlin/magefree/protocol/GameMessages.kt` — `GameCardView`.
- `docs/ui-modernization-plan.md` §7.14 (the stack), §3.1 (targeting arrows).
