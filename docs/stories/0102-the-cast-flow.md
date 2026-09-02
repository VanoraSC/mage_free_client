# 0102 — The cast flow, driven by the server's prompts

- **Epic:** EPIC-20 — The Cast Flow
- **Story:** #177
- **Depends on:** Phase 2 step 2a, written up in
  [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md). Read it first; this story does not
  repeat the trace, only what follows from it.

> **Superseded design.** This story was first written as "the cast intent contract" — a `CastIntent`
> in `:protocol` and a bridge-side player that answered the server's prompts from it as a batch. That
> model was dropped on 2026-09-02 (plan §7.6). The reason is in the trace's §2.3: the model depended
> on showing *"the server's proposed solution for the remainder"* as an editable default payment, and
> no such proposal exists anywhere upstream. Building it meant computing a payment ourselves, which is
> client-side rules work. The current design follows the server.

## 1. Objective

The surface a player casts a spell through: the server's own prompt sequence, rendered on the board
rather than as a chain of dialogs, with a cancel affordance on exactly the prompts the server accepts
one for.

## 2. Context & background

**The server asks an ordered sequence of questions** — additional costs, X, modes, targets, then one
prompt per mana source (trace §1, §2). The old design tried to collapse that into a single local act.
This one renders each question as it arrives and submits each answer as it is given.

**Almost all of the plumbing exists.** `AskPrompt`, `ChooseAbilityPrompt`, `GetAmountPrompt`,
`TargetPrompt` and `PlayManaPrompt` all cross `:protocol` today, and `:core:network` already folds
them into `GameState.prompt`. There is no new wire type and no new bridge state. What is missing is
the surface.

**Changing the form is still allowed; inventing content is not.** A prompt saying "Pay {1}{R}" is
shown as the board highlighting what can pay it, not as a modal — 0095's `pendingCost` signal and
0096's card tier already exist for this. But every answer is now a direct response to a question the
server just asked, so there is nothing to reconcile and nothing to guess.

**Cancellation is per-prompt and is not uniform** (trace §3), which is the part that cannot be
guessed:

| Prompt | Cancellable? |
|---|---|
| Mana | **Always** — aborts the payment and rolls the cast back |
| Targets | **Usually** — arrives as `required = false`; **not** for a free cast such as Suspend |
| Modes | **Yes** — aborts the cast when too few modes were taken |
| **X** | **No** — the server loops until a valid value arrives |
| Optional cost | Not a cancel; declining continues the cast without the cost |

**X is the one point of no return**, and that is worth telling the player rather than letting them
discover it. Changing your mind about an X spell means answering X, continuing to the mana prompts,
and cancelling there.

**Cancelling is mostly clean** (trace §2.5): lands tapped during payment are untapped by the rollback.
Two rare exceptions are recorded there — pre-floated mana stays floating, and a mana source reporting
itself as not undoable may prevent the rollback entirely. Both are worth a live test rather than a
design.

## 3. Scope

**In scope**
- Rendering each prompt of a cast on the board, in the order the server sends them.
- A cancel affordance on exactly the prompts that accept one, and none on the prompts that do not.
- Telling the player, before they commit to an X spell, that X cannot be backed out of.
- Convoke/improvise offered only while they are still usable (trace §2.4).
- The prompts appearing in the Prompt component (0097) with the board carrying the selection.

**Out of scope**
- Land-tapping filtering — 0103.
- Any `CastIntent`, batch playback, or bridge-side cast state. Dropped with the old design.
- A proposed default payment. Nothing upstream proposes one and we are not computing one.
- Split, fused and spliced spells, and alternative casting methods that replace the whole cost —
  explicitly not traced in 2a, so explicitly not designed here.
- Trigger ordering (§7.8) and the stack view.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`. No `:protocol` or `:bridge`
change is expected — if one turns out to be needed, that is a finding worth recording, because the
trace says it should not be.

## 5. Design & approach

**Follow the server, and make that invisible.** The player should experience choosing a spell,
choosing what it hits, and tapping what pays for it. That it is five round trips is an implementation
detail they never need to see — but it is also not something to hide behind a local model that can
disagree with the server.

**Cancel is a first-class part of the surface, not an escape hatch.** With no local assembly, cancel
is the only correction available, so it has to be obvious and it has to be accurate about when it is
there.

**Nothing here re-derives rules.** Every option offered comes from what the server reported as legal
in the snapshot riding with the prompt.

## 6. Implementation steps

1. Re-read the trace's §2 and §3; the prompt list and the cancel table come from there.
2. The presentation model for a cast in progress, built from `GameState.prompt` plus the snapshot.
3. Render each prompt on the board, reusing 0097's Prompt and 0096's signals.
4. The cancel affordance, driven by the per-prompt table.
5. Catalog entry: the sequence of a real cast, scripted.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test that no cancel affordance is offered on an X prompt
  must fail against a surface that offers cancel everywhere, then pass.
- **Hermetic Compose (`src/testDebug`):** each prompt type renders; cancel is present on mana, absent
  on X; a target prompt marked `required = true` offers no cancel while one marked `false` does.
- **Live against the reference server (standard 5):** cast a plain creature; cast a spell with X; cast
  a spell with a target and cancel at the target; cancel during mana payment and assert the lands are
  untapped again. Then the two rare cases from trace §2.5 — pre-floated mana, and a non-undoable mana
  source — pinning whatever actually happens.
- **The old cast path is unchanged**, asserted by its existing tests passing unedited.
- **Eyes-on:** the catalog in landscape — does a cast read as one act, and is it always clear whether
  backing out is possible.

## 8. Acceptance criteria

- [ ] Every prompt in a cast renders on the board rather than as a dialog chain.
- [ ] Cancel is offered on exactly the prompts the server accepts it for, and the X prompt is not one.
- [ ] Cancelling during mana payment leaves the lands untapped, asserted against a live server.
- [ ] The two rare cancellation cases from trace §2.5 are pinned by tests rather than assumed.
- [ ] No `:protocol` or `:bridge` change was needed, or the need is recorded as a finding.
- [ ] `./gradlew check` passes and the catalog renders the flow.

## 9. References

- [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md) — §2 for the prompts, §3 for what
  can be cancelled.
- `docs/ui-modernization-plan.md` §7.6 (rewritten 2026-09-02), §11 Phase 2.
