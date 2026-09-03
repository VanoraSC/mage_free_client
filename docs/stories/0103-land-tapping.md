# 0103 — Tapping lands: prompt only when the choice is real

- **Epic:** EPIC-20 — The Cast Flow
- **Story:** #178
- **Depends on:** 0102 (the flow this happens inside), and Phase 2 step 2a in
  [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md).

> **Reduced scope.** This story was first written as the whole cast UI, including an editable payment
> and a single Confirm. Both went with the declared-intent model on 2026-09-02 (plan §7.6). What
> survives is the part that was always about filtering rather than about local state: not asking a
> question that has only one answer.

## 1. Objective

Tapping a land during a cast costs one tap in the common case, and prompts only when the choice is
genuinely real.

## 2. Context & background

**The mana prompt arrives once per source** (trace §2.2): paying `{2}{R}` from three lands is three
separate `GAME_PLAY_MANA` callbacks, each showing the remaining unpaid cost. Each one is answered by
naming a permanent. What happens next is where the friction lives — the server may then ask *which of
that permanent's mana abilities* to use.

**§7.7's three surviving rules:**

1. **One possible mana → no prompt.** A tapped Mountain is a red mana, full stop. This is the
   overwhelmingly common case.
2. **Genuinely multiple options → prompt, showing only the real ones.** A Sacred Foundry produces `R`
   or `W`.
3. **Mid-cast, only mana abilities are offered.** Non-mana abilities cannot be activated while
   casting, so a Creeping Tar Pit mid-cast is case 1, not case 2. This removes most of the remaining
   prompts.

**Upstream already does some of this, bluntly.** `HumanPlayer` suppresses the ability picker when the
object is a land whose first ability is a mana ability *and the user has set the
`useFirstManaAbility` option* (trace §2.6), and `ManaUtil.tryToAutoPay` narrows a chosen permanent's
abilities when exactly one fits the unpaid cost (§2.3). Both are per-user or per-cost switches rather
than per-decision judgements. The client can do better by deciding per decision, from what the server
reported.

**Two of §7.7's original five rules were retired with the old design**, and they are named here so the
loss is deliberate rather than forgotten:

- *"Tapping a tapped land untaps it"* — each tap is submitted as it is made, so there is nothing local
  to undo. The correction is to cancel the payment, which the server allows at every mana prompt, and
  start again.
- *"Meeting the cost never fires the cast"* — the server fires it when the last mana is supplied.
  There is no point at which a confirmation could be inserted that the server would honour.

## 3. Scope

**In scope**
- Deciding, per mana prompt, whether an ability picker is a real question or a formality, from the
  abilities the server reported.
- Rendering the picker only when it is real, with only the genuinely available options.
- Making a single-option tap cost exactly one tap.

**Out of scope**
- The rest of the cast flow — 0102.
- Any editable payment, proposed default, or Confirm. Dropped with the declared-intent model.
- Undo, and any local model of a partially-assembled payment.
- Deciding *which* lands to tap on the player's behalf. Nothing upstream proposes a payment and we are
  not inventing one.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`. 0102 merged.

## 5. Design & approach

**This is a filtering problem, not an invention problem.** Every option shown comes from what the
server reports as legal for that permanent, in the snapshot riding with the prompt. Which case a land
falls into is derived from the server's reported abilities, never from a hardcoded notion of what
lands do — a client that "knew" a Mountain taps for red would be wrong the first time an effect
changed it.

**The filtering is per decision, not per preference.** Upstream's `useFirstManaAbility` is a setting
the player turns on once; the rule here is that a question with one answer is not asked, whoever the
player is.

## 6. Implementation steps

1. The predicate: given the abilities the server reported for a permanent and the outstanding cost,
   is there more than one real choice?
2. Suppress the picker when there is not, answer directly.
3. Render the picker with the real options when there is.
4. Catalog entry: a basic land, a dual land, and a land whose second ability is not a mana ability.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test that a land with one usable mana ability produces no
  picker must fail against a surface that always shows one, then pass.
- **Unit:** the predicate, over the reported-abilities cases — one mana ability; two mana abilities;
  one mana ability plus one non-mana ability.
- **Hermetic Compose (`src/testDebug`):** a basic land taps with no picker; a dual land shows both
  colours and nothing else; a land whose other ability is not a mana ability shows no picker mid-cast.
- **Live against the reference server (standard 5):** pay a cost from a basic land and from a dual
  land, asserting the prompt counts the flow produced.
- **Eyes-on:** does paying a three-mana cost from three basics feel like three taps.

## 8. Acceptance criteria

- [ ] A land with one usable mana ability taps with no picker.
- [ ] A land with several real options shows exactly those options.
- [ ] Mid-cast, non-mana abilities are never offered.
- [ ] Every option shown comes from server-reported abilities, asserted by a test over the predicate.
- [ ] `./gradlew check` passes and the catalog renders the cases.

## 9. References

- `docs/ui-modernization-plan.md` §7.7 (rewritten 2026-09-02).
- [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md) §2.2, §2.3, §2.6.
