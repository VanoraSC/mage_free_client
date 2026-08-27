# 0091 — Token and copy identity

- **Epic:** EPIC-23 — Game Information We Do Not Yet Map
- **Depends on:** nothing (bridge-side + `:protocol`).

## 1. Objective

Carry `CardView.isToken` and `CardView.mageObjectType` so the board can tell a token from a card and
a copy from an original — at Board tier, not only on inspection.

## 2. Context & background

**The signal exists, is unambiguous, and is simply not mapped.** `ui-modernization-plan.md` §7.5,
confirmed against `CardView.java`:

- `isToken` is a plain `boolean`, set when the object is a `PermanentToken`.
- `mageObjectType` is a `MageObjectType`, which separates `TOKEN`, `COPY_CARD`, `PERMANENT`, `CARD`,
  `SPELL`, `EMBLEM`, `COMMANDER`, `DUNGEON`, `ABILITY_STACK_FROM_CARD` and more.

So *"is this a token"* and *"is this a copy of a card"* are both answered directly by the server. The
plan is explicit that this is *"the same shape as story 0076's `transformed`: a correct upstream field
that simply is not mapped. Thread it through unchanged."*

**Why it belongs at Board tier.** A token and a card look identical at a glance and behave
differently — a token that leaves the battlefield ceases to exist. §7.4's piling rules also depend on
it: a token and a real card with the same name are not interchangeable, so they must not pile
together.

**Why both fields and not just the boolean.** `isToken` answers one question; `mageObjectType`
answers the neighbouring one (copy versus original) that `isToken` cannot, and it is the same read.
Two fields now is cheaper than a second story later, and the plan names both.

## 3. Scope

**In scope**
- `GameCardView.token: Boolean` and `GameCardView.objectType: MageObjectTypeCode`.
- `GameViewMapper.mapCard` reading both.
- The same pair on `:core:network`'s `GameCard`, so they reach the app rather than stopping at the
  wire.

**Out of scope**
- Rendering a token differently (§7.5, EPIC-19) and the piling rule (§7.4).
- `PermanentView.copy`, which is a different flag about a different thing (a permanent that is a copy
  of another permanent) and has no consumer yet.

## 4. Prerequisites & toolchain

Project baseline; `:bridge` in-container per `docs/build-environment.md`.

## 5. Design & approach

```kotlin
// :protocol — GameCardView
val token: Boolean = false,
val objectType: MageObjectTypeCode = MageObjectTypeCode.UNKNOWN,
```

`MageObjectTypeCode` mirrors `mage.constants.MageObjectType`'s constants and **uses the tolerant
serializer pattern** already established by `CardTypeCode`: an unrecognised value decodes to
`UNKNOWN` rather than throwing, because upstream adds object types and a newer bridge must cost one
enum value rather than the whole snapshot. Copy the existing serializer; do not write a second one.

**There are thirteen constants, not the handful this story first listed**, and `DESIGNATION` is among
them: `ABILITY_STACK_FROM_CARD`, `ABILITY_STACK_FROM_TOKEN`, `CARD`, `COPY_CARD`, `TOKEN`, `SPELL`,
`PERMANENT`, `DUNGEON`, `EMBLEM`, `COMMANDER`, `DESIGNATION`, `PLANE`, `NULL`.

**`NULL` is a real value and is carried as itself.** It is upstream's own default — `CardView`
declares `mageObjectType = MageObjectType.NULL` — and means "the server set no type on this object".
`UNKNOWN` means "this build did not recognise what arrived". Collapsing the two would throw away the
difference between a server that said nothing and a server that said something new.

**`token` and `objectType` are related but not equivalent, and the KDoc says so.** `isToken` is set
only for a `PermanentToken`; `mageObjectType = TOKEN` is set on any token object. They agree on the
battlefield and can differ elsewhere.

**Name the protocol field `token`, not `isToken`.** `GameCardView`'s existing booleans are
`faceDown`, `creature`, `transformed` — no `is` prefix. Consistency inside the wire type beats
mirroring the Java accessor.

**Reachability (standard 2).** `CardView`'s constructor sets `mageObjectType` on every card it builds
(`PERMANENT`, `COPY_CARD` and `CARD` are all assigned in visible branches) and sets `isToken = true`
for a `PermanentToken`. The producer is the server, per snapshot, for every card — the live check
confirms a real token arrives with both fields set.

## 6. Implementation steps

1. Read `CardView.java` around the `mageObjectType` / `isToken` assignments and `MageObjectType.java`
   for the full constant list.
2. Add `MageObjectTypeCode` with the tolerant serializer, and the two fields on `GameCardView`.
3. Map both in `mapCard`.
4. Extend `GameViews.kt` so a fixture card can be a token or a copy.
5. Carry both through `:core:network`'s `GameCard` and its mapper.
6. No golden to regenerate: the only committed golden is `chat_talk.json`.

## 7. Testing & verification

- **Proven failing first (standard 1):** four `:bridge` mapper tests and the `:core:network` fold test
  each fail against a mapper that drops the fields. So does the live test — with `token` hardcoded
  false it never finds a token on the board.
- **Unit (`:bridge`):** a token permanent maps to `token = true, objectType = TOKEN` and the land
  beside it to `false, PERMANENT`; a copy of a card is distinguishable from the card itself, which is
  the question `token` cannot answer; **every** upstream `MageObjectType` has a code of its own, so a
  type added upstream is a compile error at the one place the two sets meet; an object with no type
  set carries upstream's own `NULL`.
- **Unit (`:protocol`):** both fields round-trip, an unknown `objectType` string decodes to `UNKNOWN`
  rather than throwing, `NULL` survives as itself, types encode as their upstream names, and a frame
  from a bridge that sends neither field decodes as **not** a token.
- **Unit (`:core:network`):** both survive the fold, and a card with no object type folds to
  `Unknown` and not a token.
- **Live:** `Raise the Alarm` (`{1}{W}`, two 1/1 Soldier tokens, no target) puts a real token on the
  battlefield on turn two. The land played the same game is the control: a flag that is true for
  everything is no more useful than one that is false for everything, so the test asserts the pair.

  ```
  GameRelayIT[token]: [Plains(token=false,PERMANENT), Plains(token=false,PERMANENT),
                       Soldier Token(token=true,TOKEN), Soldier Token(token=true,TOKEN)]
  ```

- **Eyes-on:** none. Nothing renders differently yet.

## 8. Acceptance criteria

- [x] `GameCardView.token` and `objectType` exist, default safely, and are documented.
- [x] `MageObjectTypeCode` decodes an unknown value to `UNKNOWN`, proven by test, and keeps `NULL`
      distinct from it.
- [x] The mapper tests were proven failing before passing, the live one included.
- [x] A live token arrives with both fields set correctly, alongside a non-token control.
- [x] The fields reach the app: `GameState`'s `GameCard` carries them.
- [x] `./gradlew check` and `:bridge:check` pass; no golden needed regenerating.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/CardView.java`, `MageObjectType.java`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard`.
- `docs/ui-modernization-plan.md` §7.5 (card tiers), §7.4 (piling).
- `docs/stories/0076-*.md` — the `transformed` precedent for threading an upstream field unchanged.
