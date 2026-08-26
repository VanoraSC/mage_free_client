# 0087 — Attachments in both directions

- **Epic:** EPIC-23 — Game Information We Do Not Yet Map
- **Depends on:** nothing (bridge-side + `:protocol`).

## 1. Objective

Map the three `PermanentView` attachment fields the bridge currently drops, so an Aura or Equipment
can be rendered **on its host** rather than as a separate permanent that happens to name one.

## 2. Context & background

**The server gives us both directions; we take one.** `ui-modernization-plan.md` §7.4 records it and
the code confirms it. `PermanentView`
(`Mage.Common/src/main/java/mage/view/PermanentView.java`) carries:

| Field | Meaning |
|---|---|
| `attachments: List<UUID>` | what is attached **to this permanent** |
| `attachedTo: UUID` | what **this permanent is attached to** |
| `attachedToPermanent: boolean` | the host is a permanent (rather than a player) |
| `attachedControllerDiffers: boolean` | the host is controlled by someone else |

`GameViewMapper.mapPermanent` maps **only `attachedTo`**. The other three are unmapped.

**Why the reverse direction matters even though `attachedTo` exists.** Rendering an Aura on its host
means the host must know what it carries — otherwise every permanent has to scan the whole
battlefield for anything pointing at it, on every frame, on both players' boards. Upstream computes
`attachments` once per snapshot; deriving it in the client is work the server already did.

**`attachedControllerDiffers` is not cosmetic.** §7.4 calls it out as *"a real and easily-missed
board state"*: you control the Aura, your opponent controls the creature. A board that draws the Aura
on the opponent's creature without distinguishing whose Aura it is misreads the game.

**It is also a piling input.** §7.4's stacking rule is strict — *"a permanent carrying an attachment
never piles at all"*, because an attachment attaches to one specific instance. That rule cannot be
implemented from `attachedTo` alone; it needs `attachments` on the host.

## 3. Scope

**In scope**
- `GamePermanentView.attachments: List<String>`, `attachedToPermanent: Boolean`,
  `attachedControllerDiffers: Boolean`.
- `GameViewMapper.mapPermanent` reading all three.

**Out of scope**
- Rendering attachments as relationships, and the piling rule that consumes them. §7.4 / EPIC-19.
- `MutateView` / `mutated`, which are a different mechanic on the same view and have no consumer yet.

## 4. Prerequisites & toolchain

Project baseline; `:bridge` in-container per `docs/build-environment.md`.

## 5. Design & approach

```kotlin
// :protocol — GamePermanentView
val attachments: List<String> = emptyList(),
val attachedToPermanent: Boolean = false,
val attachedControllerDiffers: Boolean = false,
```

```kotlin
// :bridge — GameViewMapper.mapPermanent
attachments = permanent.attachments.orEmpty().filterNotNull().map(UUID::toString),
attachedToPermanent = permanent.isAttachedToPermanent,
attachedControllerDiffers = permanent.isAttachedControllerDiffers,
```

Check the exact accessor names against `PermanentView` before writing them; upstream mixes `is`/`get`
prefixes and the Kotlin property view differs accordingly. **Read the class, do not guess.**

**Reachability (standard 2).** `attachments` is populated by `PermanentView`'s constructor from
`permanent.getAttachments()` on every snapshot; `attachedToPermanent` and
`attachedControllerDiffers` are computed in the same constructor from the resolved host. The producer
is the server, unconditionally, for every battlefield permanent — the live check confirms it.

**The two directions must agree, and that is a testable invariant.** For any permanent P with
`attachedTo == H`, the permanent with id `H` must list `P` in its `attachments`. Assert it in the
live test; it is the cheapest possible check that both fields were read off the same snapshot.

## 6. Implementation steps

1. Read `PermanentView.java` and note the exact accessor names for the two booleans.
2. Add the three fields to `GamePermanentView` with KDoc, including why the reverse direction exists
   and what `attachedControllerDiffers` means.
3. Map them in `mapPermanent`.
4. Extend `GameViews.kt` so a fixture permanent can carry attachments and the two flags.
5. Regenerate goldens with `UPDATE_GOLDEN=1`; read the diff.

## 7. Testing & verification

- **Proven failing first (standard 1):** the mapper test asserting an Aura's host lists it in
  `attachments` must fail against a mapper that drops the field, then pass.
- **Unit:** `GameViewMapperTest` — a permanent with two attachments maps both ids; a permanent with
  `attachments == null` maps to `emptyList()`; both booleans round-trip.
- **Live:** against the reference server, attach an Aura to your own creature and assert the
  round-trip invariant above with `attachedControllerDiffers == false`. Then attach one to the
  **opponent's** creature and assert `attachedControllerDiffers == true` — that second case is the
  one this story exists for and the one a fixture cannot prove.
- **Eyes-on:** none. Nothing renders yet.

## 8. Acceptance criteria

- [ ] All three fields exist on `GamePermanentView`, default safely, and are documented.
- [ ] `GameViewMapper` populates them from the accessors read off `PermanentView`.
- [ ] The mapper test was proven failing before passing.
- [ ] The live test covers both the same-controller and differing-controller cases, and asserts the
      `attachedTo` / `attachments` round-trip invariant.
- [ ] `./gradlew check` passes; goldens updated deliberately.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/PermanentView.java`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapPermanent`.
- `docs/ui-modernization-plan.md` §7.4 — attachments as relationships, and the piling rule.
