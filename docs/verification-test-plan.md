# Verification test plan

Every item the requirements document (`game-board-requirements.md`) or a story flags as **traced from
source but not yet verified live** — collected here as one checklist, organized by what's testable
**right now** against the current merged build versus what needs a story implemented first. Each entry
names the feature to exercise (not a decklist — build your own from whatever satisfies it) and the
pass/fail criteria to check against.

Standard 3 applies throughout: independent, eyes-on verification, not the implementer's own say-so.
Record the result (pass, fail, or "didn't reach it") back into the relevant requirements section or
story doc the same way every other live-tested claim in this project is recorded — a date, what you
ran, what happened.

---

## Part 1 — Verifiable now, no new code needed

These exercise machinery already merged (0051–0061). Nothing needs to be built first.

### 1. Combat damage among multiple blockers, and trample (requirements §19)

**Claim being verified.** Dividing combat damage among multiple blockers — including trample's excess
going to the defending player — uses the same `GetMultiAmount` prompt already proven live for Forked
Bolt's damage division (§17), not a bespoke combat-damage mechanism. Traced from source
(`HumanPlayer.chooseTargetAmount`), never exercised live in an actual combat.

**What you need.** An attacker blocked by **two or more** creatures. For the trample half specifically,
the attacker needs trample and enough power that lethal damage to every blocker still leaves excess.

**Steps.**
1. Get the attacker blocked by two (or more) creatures.
2. Reach the combat damage step and observe what prompt appears.
3. If the attacker has trample: assign damage so each blocker gets **at least** lethal, and confirm you
   can route the remainder to the defending player (or a planeswalker/battle, if that's the defender).
4. If the attacker has **no** trample: confirm you can still divide damage among the blockers (order/
   amount), even though there's no "excess" destination.

**Pass criteria.**
- [ ] The prompt is a `GetMultiAmount`-shaped prompt (a total to divide across entries with a min/max
      each), not a `Target`, `Ask`, or anything bespoke.
- [ ] Every blocker actually takes the damage you assigned it (check board state after the step
      resolves — permanent damage marked, or the blocker dies if lethal was assigned).
- [ ] With trample: the leftover amount reaches the player/planeswalker/battle, and their life/loyalty/
      defense total drops by exactly that amount.
- [ ] Without trample: the total assignable never exceeds the attacker's power, and the interaction
      doesn't offer routing anything to the player.

**If it fails or doesn't match:** this contradicts a source-level trace repeated elsewhere in the
document (§7.4's "do not special-case combat damage" call rests on this), so a mismatch is worth
reporting precisely — the exact prompt shape you saw, not just "it didn't work."

---

### 2. Modal spells, including Spree (requirements §21.1)

**Claim being verified.** Choosing a mode (a "choose one" or "choose one or more" spell) is *not* a
distinct prompt kind — it reuses `GamePrompt.ChooseAbility`, the same wire message as picking one of a
permanent's abilities, in a loop until a "Done"/"Cancel" entry is picked or the max is reached. Spree
specifically is not a separate mechanism at all — just a modal spell configured to allow choosing more
than one mode, each with its own additional cost. `BoardControls.kt`'s `ChooseAbility` handling was
already written with this in mind but never exercised against a real card.

**What you need.** Any modal spell ("choose one —", "choose two —", etc.) for the base case; a Spree
card (modes each with their own additional cost, "choose one or more") for the fuller case.

**Steps — ordinary modal spell.**
1. Cast it. Confirm the prompt lists the modes as tappable/selectable entries with real rule text, not
   raw ids or blank labels.
2. Pick a mode. Confirm the prompt updates (e.g. "selected 1 of 2") rather than immediately committing.
3. If the spell allows more than one mode, pick a second. Confirm a "Done" entry appears once the
   minimum is satisfied, and finish through it.
4. Confirm the spell resolves doing what the chosen mode(s) say.

**Steps — Spree card.**
1. Cast it. Confirm each mode's own additional cost is visible (in the mode's label or the subsequent
   payment step).
2. Choose **one** mode only, pay its cost, and confirm the spell resolves doing just that.
3. On a separate cast, choose **more than one** mode and confirm you're asked to pay for **each** —
   don't let a single payment step silently cover every chosen mode.

**Pass criteria.**
- [ ] Modes render with real text (server-supplied rule text), never a raw ability id or "Unnamed
      candidate."
- [ ] Selecting one mode doesn't end the prompt if more can/must be chosen — it re-prompts.
- [ ] A "Done" (or equivalent) control appears once the selection is valid, and completes the choice.
- [ ] For Spree: each chosen mode's additional cost is paid **separately** and visibly, not merged into
      one payment.
- [ ] The resolved effect matches exactly the mode(s) chosen — no more, no less.

---

### 3. Declining a mode mid-cast (requirements §16.5a, the mode half)

**Claim being verified.** §17.1 already proved declining a *target* mid-cast rewinds the spell
completely (card back to hand, stack cleared, mana untapped). Whether declining at the **mode-choice**
step does the same is still unverified — this is the one piece of §16.5a's cascading-cancel promise
that has never been tested, independent of any story (it uses only existing 0057 machinery).

**What you need.** Any modal spell with a real cost (so you can confirm mana comes back unspent).

**Steps.**
1. Begin casting it. Reach the mode-choice prompt.
2. Instead of completing mode selection, use whatever cancel/decline control is offered.
3. Check: is the card back in hand? Is the stack empty again? Are your lands untapped?

**Pass criteria.**
- [ ] Declining at the mode-choice step returns the card to hand, exactly like the proven target-decline
      case.
- [ ] No mana was spent (lands you hadn't tapped yet stay untapped).
- [ ] If it does **not** rewind the same way, that's a real, useful finding — it means the cascading
      cancel promise (§16.5) does not hold at every step, and the board must not offer "cancel" at the
      mode step as if it were guaranteed to work. Record precisely what happened (partial rewind? no
      rewind at all? an error?).

---

## Part 2 — Ready for when each story ships

These need their story implemented first (0062–0066). The checklist is written now so nothing needs
re-deriving later — each story's own "Verification" section has the fuller detail; this is the
condensed, check-off version.

### 4. Story 0062 — Convoke / Delve

- [ ] A convoke spell shows a way to tap creatures toward its cost during mana payment (not just via
      lands).
- [ ] Tapping a creature for convoke reduces the spell's remaining cost by the right amount, and the
      creature is now tapped.
- [ ] A delve spell shows a way to exile graveyard cards toward its cost.
- [ ] Exiling a card via delve reduces the remaining generic cost by 1 and the card leaves the
      graveyard for exile.
- [ ] With more than one special action available at once, you're asked **which** before either
      proceeds (not silently picked for you).
- [ ] Declining mid-payment on either path is tested and the result (rewinds, or doesn't) is recorded
      — this is the concrete case §6.4a/§16.5a names as unverified.
- [ ] Companion: paying its cost pre-game puts the named companion into hand, and (once 0066 lands) you
      can see which card it is before and after.

### 5. Story 0063 — Stops / auto-pass

- [ ] Each of the **six** skip actions, exercised individually, stops exactly where its own definition
      says it should (see requirements §14.3.1 for what each one is).
- [ ] The stop-settings matrix actually interrupts an active skip — try at least one global flag (e.g.
      "stop when something new hits the stack") and one per-phase-step setting.
- [ ] A combat declaration is **never** auto-passed, no matter what settings are active — this is the
      one trap 0061 and the upstream source both flagged explicitly. Deliberately try to break this: arm
      a skip, then reach a declare-attackers step, and confirm control returns to you rather than the
      board silently declining to attack/block.
- [ ] Hold-priority: take an action, confirm priority does **not** auto-pass afterward while it's
      active, and does otherwise.
- [ ] Floating mana at the moment a skip would pass is handled the way the story's implementation
      documents (not silently lost without warning, unless that's the documented behaviour).

### 6. Story 0064 — Between-games sideboard

- [ ] Play a match to the end of game 1. Confirm the sideboard screen actually appears (not stuck on a
      "Sideboarding" label with nothing to do, today's behaviour).
- [ ] The screen shows your **real, just-played deck** — main and sideboard — not empty or placeholder.
- [ ] Move at least one card between deck and sideboard; confirm legality feedback updates live,
      including the pool-constraint (you can't add cards from outside your registered pool unless the
      table is limited/`isConstruct`).
- [ ] Submit, and confirm game 2 starts with the **modified** deck (not the original).
- [ ] Let the countdown run out without submitting on a second pass — confirm the match still proceeds
      (the server auto-completes silently; the app must not hang waiting for an event that never comes).

### 7. Story 0065 — Battlefield stacking

- [ ] Get 4+ identical, fungible permanents on one battlefield (e.g. basic lands). Confirm they render
      as one pile capped at 3 visible faces with a correct count badge.
- [ ] Tap the pile repeatedly (e.g. paying a generic cost). Confirm each tap consumes exactly one
      member and the visible count decreases correctly, matching the server's actual tapped state
      afterward.
- [ ] Tap one land in the pile via whatever mechanism taps it (not all of them) — confirm the pile
      **splits**: tapped members move to their own pile, untapped members remain in theirs.
- [ ] Deliberately create a difference that should **prevent** piling — e.g. one permanent takes damage,
      or gets a counter the others don't have — and confirm it separates out rather than being hidden
      inside the pile.
- [ ] In combat, get several same-name attackers going at the same defender with no blocks assigned yet
      — confirm they stay one pile. Then have only *some* of them blocked — confirm the blocked ones
      split off automatically.
- [ ] Tap a pile with nothing currently prompting (idle inspection) — confirm it opens the ordinary card
      detail view, same as tapping a single card.

### 8. Story 0066 — Graveyard / companion / looked-at card identity

- [ ] Get a graveyard-castable card (flashback or similar) into a graveyard, with priority to cast it.
      Confirm it shows its **real name** among the playable candidates — not "Unnamed candidate N."
- [ ] Confirm this works for a card in **either** player's graveyard, not only your own.
- [ ] With a companion-eligible deck, confirm you can see **which card** your companion is, both before
      you fetch it and — if visible to opponents in real Commander/constructed play — that an opponent
      can identify it too.
- [ ] Trigger a "look at" effect (e.g. scry/surveil-adjacent) and confirm the contents are visible
      somewhere on the board once the window is open, not just inferred from narration text.

---

## Notes on running these

- **Combat and multi-blocker scenarios are inherently harder to reach than they look** —
  `docs/live-test-decklists.md`'s own experience was that combat wasn't reached at all in several runs
  until decks were built creature-dense on purpose. Don't read a quiet run as a negative result; budget
  for a few attempts.
- **Record results in place.** A pass confirms a "traced from source" claim and can be marked so in
  `game-board-requirements.md` (the same way §17's target-cancel experiment or §7.2's combat measurement
  are recorded — a dated note with what was run and what happened). A fail is at least as valuable —
  it's exactly the kind of thing this project's verification standards exist to catch before it ships.
