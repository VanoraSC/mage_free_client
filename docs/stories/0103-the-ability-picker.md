# 0103 — Answering the ability picker, and why the filtering is not ours

- **Epic:** EPIC-20 — The Cast Flow
- **Story:** #178
- **Depends on:** 0102 (the surface this completes), and Phase 2 step 2a in
  [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md).

> **Rewritten 2026-09-03, twice over.** This began as the whole cast UI with an editable payment and
> one Confirm; both went with the declared-intent model. What was left was "§7.7's filtering: do not
> ask a question that has only one answer" — and tracing that before building it showed **the server
> already does all three of §7.7's rules, per decision** (trace §2.7). So there is no filtering for us
> to write. What there is, is a prompt 0102 rendered with no way to answer it.

## 1. Objective

Make the ability picker answerable, and pin the fact that the narrowing behind it belongs to the
server.

## 2. Context & background

**§7.7's three surviving rules are upstream's, already.** Traced before writing anything:

- *One possible mana → no prompt.* `suppressAbilityPicker` returns `isManaActivatedAbility()` for
  anything on the battlefield, and a single suppressed ability is activated directly. A basic land
  taps with no picker, server-side.
- *Only the real options.* `ManaUtil.tryToAutoPay` narrows a permanent's mana abilities against the
  **unpaid cost** — by symbol where the cost has them, by mana otherwise. Narrowed to one, the picker
  disappears by the rule above. It bows out when the spell cares which colour paid, which is correct:
  there the choice is real.
- *Mid-cast, only mana abilities.* Payment goes through `getUseableManaAbilities`, which is mana
  abilities and nothing else.

**So a client that filtered would be choosing which mana to produce** — content, not form, and the one
thing §7.6's safety rule forbids. The server knows the cost and the abilities; we know neither better.

**The actual gap.** 0102 mapped `ChooseAbility` and `ChooseChoice` to a headline and an exit, with no
options. A dual land against a coloured cost, or any modal spell, therefore dead-ends mid-cast: the
server asks, and the surface has nothing to press.

## 3. Scope

**In scope**
- Rendering the options of `ChooseAbility` and `ChooseChoice`, in the server's own text, in its order.
- Replying with the right identifier for each — an ability id for one, a choice key for the other.
- A catalog step showing a real picker.

**Out of scope**
- Any filtering of the options. It has already happened, better than we could do it.
- `useFirstManaAbility`, upstream's blunter per-user suppression. It is a setting, and there is no
  settings surface in this phase.
- Board-side land tapping, which is a board interaction and belongs to Phase 3.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`. 0102 merged.

## 5. Design & approach

**Every option the server sent is offered.** That is the whole rule, and it is the inverse of what this
story originally proposed. It is asserted directly rather than left implied.

**The reply is not the label.** An ability choice carries an id and a mode carries a key; both come
with rendered text meant for reading. Sending the text would be a wrong action submitted to a live
game, so the two are separate event types rather than one carrying a string — the type system rules
out the confusion for free.

## 6. Implementation steps

1. Trace §7.7's rules before building anything. (Done, and it changed the story.)
2. Carry the options into the cast model.
3. Render them, and reply with the right identifier per prompt.
4. A catalog step with a real picker.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test that a real ability choice is answerable must fail
  against 0102's surface, then pass.
- **Unit:** every option the server listed is offered and none dropped; the label is the server's own
  text and the reply is the id; a mode carries its key rather than its label.
- **Hermetic Compose:** picking an option reports the right event *type*, so an ability can never be
  answered as a mode.
- **Eyes-on:** the catalog's cast flow, which now includes a dual land against a coloured cost.

## 8. Acceptance criteria

- [x] `ChooseAbility` and `ChooseChoice` are answerable, in the server's own text and order.
- [x] Every option the server sent is offered; none is filtered out client-side.
- [x] An ability is replied to with its id and a mode with its key, and the two cannot be confused.
- [x] The finding that §7.7's filtering is already the server's is recorded in the trace.
- [x] `./gradlew check` passes and the catalog shows a real picker.

## 9. References

- [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md) §2.7 — why there is no filtering to
  write.
- `docs/ui-modernization-plan.md` §7.7.
