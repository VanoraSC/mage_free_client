# 0103 — The cast flow UI

- **Epic:** EPIC-20 — Declared Cast Intent
- **Story:** #178
- **Depends on:** 0102 (the contract it produces), Phase 1's design system, and Phase 2 step 2a in
  [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md).

## 1. Objective

The surface a player casts a spell through: additional costs first with pending costs highlighted, a
mana payment they can edit, land tapping per §7.7, and **one Confirm**.

## 2. Context & background

**Nothing is submitted until Confirm.** That is the whole design, and every rule below is a
consequence of it. While the intent is being assembled the board is a local edit: adding a land,
removing it, changing X and changing a target are all free, which is why §7.7 can say *"misfires
during payment need no Undo — the correction is another tap"*.

**§7.7's five rules for tapping a land**, in the plan's own order:

1. **One possible mana → no prompt.** A tapped Mountain is a red mana. The common case costs one tap.
2. **Genuinely multiple options → prompt, showing only the real ones.**
3. **Mid-cast, only mana abilities are offered**, which turns most case-2 lands into case 1.
4. **Tapping a tapped land untaps it.**
5. **Meeting the cost never fires the cast.** The player is *prompted to finish*, with the mana being
   spent shown.

Rule 5 is load-bearing rather than a formality: which mana paid for a spell is not always cosmetic,
and an auto-firing cast removes the last chance to back out of a misfire before it becomes server
state.

**This is a filtering problem, not an invention problem.** Every option shown comes from what the
server reports as legal; rule 3 narrows it to what is legal *here*. Nothing is derived from a
hardcoded notion of what a land does.

**The one thing §7.6 asks for that upstream does not provide.** The plan calls for *"server-proposed
mana as the editable default"* — and 2a established there is no such proposal on the wire and no
upstream code that computes one. `ManaUtil.tryToAutoPay` only narrows the abilities of a permanent the
player has *already* chosen (trace §2.3). So the default has to be derived by us, or not offered.
**Deriving it belongs in the bridge**, where it can be tested against a real server, rather than in the
UI — it is the place in this phase where the client comes closest to re-deriving rules, and the
boundary is worth defending explicitly.

## 3. Scope

**In scope**
- The cast surface: the spell being cast, its additional costs, its X, its targets, its payment.
- Additional costs presented **first**, with the costs still outstanding highlighted (0095's
  `pendingCost` signal already exists for this).
- Land tapping per §7.7's five rules, including untapping by tapping again.
- A payment the player can edit before confirming, with the mana that will be spent shown.
- One Confirm, which is the only thing that submits.
- A proposed default payment, derived in the bridge, that the player can change.

**Out of scope**
- The `CastIntent` contract itself — 0102.
- Combat, targeting arrows and the board layout the cast happens on; this story builds the flow, not
  the battlefield.
- Split, fused and spliced spells, and alternative casting methods that replace the whole cost —
  not traced in 2a, so not designed here.
- Undo. §7.7 rule 4 is what replaces it during payment.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature`. 0102 merged.

## 5. Design & approach

**Order the surface the way a player thinks, not the way the engine asks.** The engine wants X before
targets and mana before sacrifices (trace §1); a player wants to know what the spell will cost, then
what it will hit, then how to pay. 0102 puts the reordering in the bridge precisely so this surface
can be arranged for the player, and this story should use that freedom rather than mirror the engine.

**A pending cost is a signal, not a dialog.** 0095 already defines `pendingCost` and 0096 already
draws it on a card. A cost still to be paid highlights the permanents that could pay it; it does not
open a modal.

**The proposed payment must be visibly a proposal.** It is derived, not authoritative, and a player
who cannot tell the difference between "the server chose these lands" and "we guessed these lands"
will be surprised by the one case where the guess is wrong. Deceit is the motivating case in §7.7.

## 6. Implementation steps

1. The presentation model for a cast in progress: what is outstanding, what is chosen, what will be
   spent.
2. The land-tapping rules, against server-reported abilities only.
3. The derived default payment, in the bridge, with the UI treating it as editable.
4. Confirm, producing a `CastIntent`.
5. Catalog entry for the surface, and a scripted harness for the flow.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test that meeting the cost does **not** fire the cast
  must fail against a flow that submits as soon as the payment is complete, then pass.
- **Hermetic Compose (`src/testDebug`):** a land with one possible mana taps with no prompt; a land
  with two real options prompts; a land whose second ability is not a mana ability does not prompt
  mid-cast; tapping a tapped land untaps it; nothing is submitted before Confirm.
- **Unit:** the derived default payment is a legal payment for the cost, and changing it produces a
  different intent.
- **The old cast path is unchanged**, asserted by its existing tests passing unedited.
- **Eyes-on:** the catalog in landscape — is it obvious what is still outstanding, is the proposed
  payment visibly a proposal, and does tapping a land feel like one tap.

## 8. Acceptance criteria

- [ ] Additional costs come first, with outstanding costs highlighted.
- [ ] Land tapping follows §7.7's five rules, from server-reported abilities only.
- [ ] Meeting the cost prompts to finish and never fires the cast.
- [ ] The payment is editable, and the proposed default is visibly a proposal.
- [ ] Confirm is the only thing that submits, and it submits a `CastIntent`.
- [ ] The existing cast path is untouched.
- [ ] `./gradlew check` passes and the catalog renders the flow.

## 9. References

- `docs/ui-modernization-plan.md` §7.6, §7.7, §11 Phase 2.
- [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md) — in particular §2.3, on why the
  proposed payment has to be derived.
