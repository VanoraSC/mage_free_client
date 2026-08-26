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
- The same three on `:core:network`'s `GamePermanent`, carried by its own mapper — same reason as
  story 0086: a field that stops at `:protocol` has not reached the app.

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
attachments =
    permanent.attachments
        .orEmpty()
        .filterNotNull()
        .map(UUID::toString),
attachedToPermanent = permanent.isAttachedToPermanent,
attachedControllerDiffers = permanent.isAttachedToDifferentlyControlledPermanent,
```

**The last accessor is not named after its field, and reading the class is what caught it.** The field
is `attachedControllerDiffers`; the getter is `isAttachedToDifferentlyControlledPermanent()`. There is
no `isAttachedControllerDiffers`.

**`attachedControllerDiffers` is narrower than its name.** Upstream computes both flags inside a single
`game.getPermanent(attachedTo)` lookup:

```java
if (attachment != null) {
    attachedToPermanent = true;
    attachedControllerDiffers = !attachment.getControllerId().equals(permanent.getControllerId());
}
```

So it is **always false when the host is a player** — a Curse on an opposing player included. It
answers "is the host a permanent someone else controls", never "is the host someone else's". That is
what the protocol KDoc says, because a consumer that read it the other way would be wrong in exactly
the case it cared about.

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
4. Extend `GameViews.kt` so a fixture permanent can carry attachments and the two flags, leaving
   `attachments` unset by default — upstream's constructor never sends it null, but the serialization
   constructor the fixtures use does, which is the sparse-view path the mapper has to survive.
5. Carry the same three through `:core:network`'s `GamePermanent` and its mapper.
6. No golden to regenerate: the only committed golden is `chat_talk.json`.

## 7. Testing & verification

- **Proven failing first (standard 1):** two `:bridge` mapper tests and the `:core:network` fold test
  each fail against a mapper that drops the fields. So does the live test — proven three times over,
  once per field, each break producing a *different* failure:

  | Break | Live failure |
  |---|---|
  | `attachments = emptyList()` | "the host must list the Aura back" |
  | `attachedControllerDiffers = false` | "never saw a Rancor on the opponent's creature" |
  | `attachedToPermanent = false` | "never saw a Rancor on our own creature" |

- **Unit (`:bridge`):** a host lists its attachments in upstream's order; an Aura on an opponent's
  creature is flagged and one on your own is not; an unattached permanent carries no attachment state
  at all, including from a sparse view where `attachments` is null.
- **Unit (`:protocol`):** both directions round-trip, and a frame from an older bridge decodes with
  both flags false — "the bridge said nothing" is never "your Aura is on their creature".
- **Unit (`:core:network`):** the relationship survives the fold *across two players' battlefields*,
  which is the shape the differing-controller case actually has.
- **Live:** `GameRelayIT` plays a mono-green game and casts `Rancor` twice — on our own creature and
  on the opponent's — asserting the round-trip invariant both times, plus
  `attachedControllerDiffers == false` then `== true`.

  **The detector filters to Auras we control, and the first run is why.** Both seats hold the same
  deck, so the AI casts Rancor too; without that filter the test matched *its* Aura on *its* own
  creature within eight seconds and failed asserting the host was on our battlefield. The filter is
  what makes the assertions about the casts this test made.

  The opponent's creature is the one thing not ours to arrange — but it is not a coin flip either:
  the AI holds twenty creatures in sixty cards and plays one on essentially every turn it can, and
  the loop simply keeps passing until one is there.
- **Eyes-on:** none. Nothing renders yet.

## 8. Acceptance criteria

- [x] All three fields exist on `GamePermanentView`, default safely, and are documented.
- [x] `GameViewMapper` populates them from the accessors read off `PermanentView` — including
      `isAttachedToDifferentlyControlledPermanent()`, which is not named after its field.
- [x] The mapper tests were proven failing before passing, and the live test once per field.
- [x] The live test covers both the same-controller and differing-controller cases, and asserts the
      `attachedTo` / `attachments` round-trip invariant in both.
- [x] The fields reach the app: `GameState`'s `GamePermanent` carries them.
- [x] `./gradlew check` and `:bridge:check` pass (the latter with `XMAGE_SERVER` set, so the live
      tests really ran); no golden needed regenerating.

## 9. References

- `../mage/Mage.Common/src/main/java/mage/view/PermanentView.java`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt` — `mapPermanent`.
- `docs/ui-modernization-plan.md` §7.4 — attachments as relationships, and the piling rule.
