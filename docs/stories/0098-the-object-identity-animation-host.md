# 0098 — The animation host: stable identity, one owning slot, ordered playback

- **Epic:** EPIC-19 — Game Board Rebuild (Phase 1)
- **Depends on:** 0095 (motion tokens), 0096 (the Board tier it moves).

## 1. Objective

Build the shared coordinate space and sequencing rules of §7.3, so that when a snapshot moves an
object, the object **moves** — from where it was to where it now is — and when several things happen
at once they **play in order** instead of collapsing into the final state.

## 2. Context & background

**§7.3 calls this "the load-bearing new subsystem", and states why an animation exists at all:**
*"An animation exists because a game action happened. It is not decoration and it is not a transition
between two renderings — it is how the player finds out what the game did."* That single sentence
generates every rule in the section, and it is why this is a subsystem rather than a set of
`animate*AsState` calls sprinkled over the board.

**The rules it generates:**

- Every renderable object has a **stable id** and a **single owning layout slot** per snapshot.
- The board lives in one shared coordinate space; a slot change animates from the previous measured
  position to the new one rather than destroying and recreating the object — `LookaheadScope` plus
  shared-element transitions, not a hand-rolled coordinator.
- **Every state change gets its turn on screen.** A chain of triggers resolving, or a sequence of
  tokens entering, plays in order. Collapsing them is the failure case: *"the player sees the board
  end up somewhere and has no idea how it got there."*
- **Trailing the server is intended** while a sequence plays, because a player watching a sequence
  resolve has no decision to make during it.
- **Being asked to act is the sync point.** When the server wants a decision, the remaining sequence
  finishes quickly rather than making the player wait — nobody acts on a stale board.
- **A resync is not a sequence.** After a reconnect the board snaps to current state; there is no
  backlog to replay, and replaying one would narrate events the player already missed.

**The identity the host needs already crosses the wire.** Every card and permanent carries an id, and
the zones an object can move between — hand, battlefield, graveyard, exile, the command zone, the
stack — are all present in the snapshot. Object identity is therefore something to *use*, not
something to invent client-side.

**Nothing like this exists today.** The current board re-renders each snapshot in place; a card that
moves zones disappears from one list and appears in another. That is the behaviour this story
replaces on the new path, and leaves alone on the old one.

## 3. Scope

**In scope**
- The host: one shared coordinate space, objects addressed by stable id, one owning slot per snapshot,
  and movement between slots animated from measured position to measured position.
- The sequencer: a queue of snapshot deltas played in order, on 0095's durations.
- The sync rule: an incoming prompt drains the queue quickly rather than jumping it or waiting it out.
- The resync rule: a resync snaps, and does not replay.
- The reduce-motion path, which shortens through 0095's motion scale and never removes a move.
- A catalog or harness that drives the host with scripted snapshot sequences — the only practical way
  to see ordered playback without a live game.

**Out of scope**
- The board layout that defines the slots. The host takes slots; it does not decide where a
  battlefield row sits. That is Phase 3.
- Targeting and combat arrows, the resolution spotlight's visual design, and any per-signal art.
- Wiring to a live game session.
- Any change to the current board.

## 4. Prerequisites & toolchain

Project baseline. `:core:designsystem`, plus the Compose animation artifact, which is not currently
declared in the version catalog and needs adding from the pinned BOM. The exact shared-element API
surface available in that BOM is to be confirmed by compiling against it, not assumed.

## 5. Design & approach

**Sequencing is the part to design first, and it is testable without any pixels.** "Play in order",
"drain on a prompt", "snap on resync" are decisions about a queue of deltas — pure logic that can be
driven by a virtual clock and asserted exactly. If that logic is right, the visual layer is the
mechanical part. If it is wrong, no amount of correct animation code will hide it.

**So the subsystem splits in two:** a pure sequencer that turns a stream of snapshots into an ordered
list of timed changes, and a Compose host that plays them. The seam between them is where the tests
live.

**Deriving deltas needs care about what "the same object" means.** An id present in two consecutive
snapshots in different zones is a move; one that appears is an entry; one that vanishes is an exit.
Tokens that cease to exist and cards that change identity as they transform are the cases that will
be got wrong first, and the data to handle both is on the wire.

**The queue must not grow without bound.** A long sequence arriving while the player is idle is fine;
an unbounded backlog is not, and the sync rule is what bounds it in practice. The behaviour when a
sequence is still playing and a prompt arrives is specified — finish quickly — so it is asserted
rather than left to emerge.

**What was actually built, confirmed against the pinned BOM (Compose UI 1.9.2).** The API surface was
read out of the artifact rather than assumed, and the host uses `LookaheadScope` together with
`movableContentOf` and `Modifier.approachLayout` — all stable `androidx.compose.ui.layout` API, plus
`Animatable` from `androidx.compose.animation:animation-core`, which is the dependency added to the
version catalog. It does **not** use `SharedTransitionLayout`. That API matches elements *across* an
`AnimatedContent`/`AnimatedVisibility` boundary, where the two are different composables in different
subtrees; here the object is one composable that changes parent inside a single tree, and
`movableContentOf` keeps it alive across that change directly. The rule §7.3 asks for — one shared
coordinate space, movement from measured position to measured position, never destroy-and-recreate —
is what both approaches serve, and this is the one the shape of the problem calls for.

**The bound is on time, not on count.** "The queue must not grow without bound" is enforced by
compressing what has not started yet into a fixed span, never by discarding a change: a change that
is never shown is a game action the player was never told about, which is the collapse failure
reached from the other side. One mechanism serves both callers — a prompt asks for a short span
because the player is about to act, a backlog that has outgrown what trailing can justify asks for a
longer one.

## 6. Implementation steps

1. Read §7.3 in full, and confirm the shared-element API surface by compiling against the pinned BOM.
2. Add the Compose animation dependency to the version catalog.
3. Build the pure sequencer: snapshots in, ordered timed changes out, with the prompt and resync
   rules.
4. Build the Compose host over `LookaheadScope`, objects keyed by stable id.
5. Add the scripted harness and a catalog entry.

## 7. Testing & verification

- **Proven failing first (standard 1):** the ordering test must fail against a sequencer that
  collapses to the final state, then pass.
- **Unit (the sequencer, virtual clock):** two changes in one snapshot play in order, not together;
  a move is a move rather than an exit plus an entry; a prompt drains the queue; a resync snaps and
  replays nothing; the queue does not grow without bound; the motion scale shortens every duration
  and never reaches zero.
- **Hermetic Compose (`src/testDebug`):** an object whose slot changes keeps its identity across the
  change rather than being recreated.
- **The current board is untouched**, asserted by its existing tests passing unedited.
- **Eyes-on:** the scripted harness — does a chain of three changes read as three things happening,
  and does a resync look like arriving rather than like a rewind.

## 8. Acceptance criteria

- [x] Objects are addressed by stable id, hold one owning slot per snapshot, and animate between
      slots in one shared coordinate space.
- [x] Changes play in order; a sequence is never collapsed into its final state.
- [x] A prompt drains the queue; a resync snaps without replaying.
- [x] Reduce-motion shortens and never removes.
- [x] The existing board is unchanged.
- [x] `./gradlew check` passes and the harness demonstrates ordered playback.

## 9. References

- `docs/ui-modernization-plan.md` §7.3 (motion and object identity), §11 Phase 1.
- `core/network/src/commonMain/kotlin/magefree/network/game/GameState.kt` — the ids and zones the
  host keys on.
