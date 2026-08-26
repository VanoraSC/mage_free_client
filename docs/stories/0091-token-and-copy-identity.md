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

`MageObjectTypeCode` mirrors `mage.view.MageObjectType`'s constants and **uses the tolerant
serializer pattern** already established by `CardTypeCode`: an unrecognised value decodes to
`UNKNOWN` rather than throwing, because upstream adds object types and a newer bridge must cost one
enum value rather than the whole snapshot. Copy the existing serializer; do not write a second one.

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
5. Regenerate goldens with `UPDATE_GOLDEN=1`; read the diff.

## 7. Testing & verification

- **Proven failing first (standard 1):** the mapper test asserting a token permanent arrives with
  `token = true` must fail against a mapper that drops the field, then pass.
- **Unit:** `GameViewMapperTest` — a token maps to `token = true, objectType = TOKEN`; an ordinary
  battlefield permanent to `token = false, objectType = PERMANENT`; a copy to `COPY_CARD`. Plus a
  decode test that an unknown `objectType` string decodes to `UNKNOWN` rather than throwing.
- **Live:** against the reference server, create a token and assert it arrives with `token = true`.
  A token-making card is the cheapest live case in this epic — there is no excuse for a fixture-only
  claim here.
- **Eyes-on:** none. Nothing renders differently yet.

## 8. Acceptance criteria

- [ ] `GameCardView.token` and `objectType` exist, default safely, and are documented.
- [ ] `MageObjectTypeCode` decodes an unknown value to `UNKNOWN`, proven by test.
- [ ] The mapper test was proven failing before passing.
- [ ] A live token arrives with both fields set correctly.
- [ ] `./gradlew check` passes; goldens updated deliberately.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/CardView.java`, `MageObjectType.java`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapCard`.
- `docs/ui-modernization-plan.md` §7.5 (card tiers), §7.4 (piling).
- `docs/stories/0076-*.md` — the `transformed` precedent for threading an upstream field unchanged.
