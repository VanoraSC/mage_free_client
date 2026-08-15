# 0062 — Alternative and additional costs: convoke, delve, and `SpecialAction`

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0057 (board interaction), 0052 (`GameClient`)
- **Status:** ready

## 1. Objective

Let the player actually pay convoke, delve, and anything else upstream models as a `SpecialAction` —
today they **cannot**: the board never offers a way to trigger one, so a deck built around them is
unplayable through the app. Fix the narrow, confirmed gap and verify live, with no new prompt kind and
no `:protocol`/`:bridge` change expected.

## 2. Context & background — traced from `../mage`, not guessed

Per verification standard 5 ("unexpectedly absent"): `GameState.specialActionsAvailable` is mapped end
to end (`:protocol` → `GameViewMapper` in both `:bridge` and `:core:network`) and even asserted in
`GameEventFoldTest` and printed by a live-test transcript — but **`feature/game` never reads it**.
`BoardControls.controlsFor` only offers the `UseSpecial` action when `prompt.options.specialButtonText`
is set, which is the **unrelated** mechanism behind combat's "All attack" button
(`Constants.Option.SPECIAL_BUTTON`, a labelled hint on one specific prompt). Convoke and delve never set
that hint; they only flip the separate `GameView.special` boolean. See requirements §18 for the full
trace, read from:

- `Mage/src/main/java/mage/abilities/keyword/ConvokeAbility.java` — registers a `SpecialAction`
  targeting one untapped creature to tap; repeatable (one creature per invocation).
- `Mage/src/main/java/mage/abilities/keyword/DelveAbility.java` — registers a `SpecialAction` costing
  `ExileFromGraveCost(TargetCardInYourGraveyard(...))`.
- `Mage.Server.Plugins/Mage.Player.Human/.../HumanPlayer.java:2295` (`activateSpecialAction`) — exactly
  one special action activates directly (asking its own `Target`/cost prompt); more than one fires
  `fireGetChoiceEvent`, which is our already-mapped `GAME_CHOOSE_ABILITY`.
- `Mage.Common/.../GameView.java:206-213` — `special` is a **boolean only**, computed from
  `state.getSpecialActions().getControlledBy(priorityPlayer, inManaPaymentMode)`; it carries no label
  or enumeration.

**Why this needs no protocol change.** Every step in the sequence above — the trigger, the ability
choice when there's more than one, and the eventual cost/target — already routes through prompt kinds
0057 answers generically (`ChooseAbility`, `Target`). The gap is purely that the board never *offers*
the trigger. `useSpecialAction()` already exists on `GameClient` (built for combat's special button,
but the wire verb — `SPECIAL` — is generic) and needs no change.

## 3. Scope

**In scope**
- Offer a generic "Special action" control whenever `GameState.specialActionsAvailable` is true, in
  addition to the ordinary controls for whichever prompt is currently showing — `Select` (priority) and
  `PlayMana`, the two moments upstream actually calls `activateSpecialAction` from.
- Wire it to `GameClient.useSpecialAction()` (already implemented); render whatever the server asks
  next (`ChooseAbility` or `Target`) through the existing generic machinery — no special-casing.
- Live verification with a convoke deck and a delve deck: confirm `specialActionsAvailable` flips true
  during a real cast, the button appears, tapping it produces the sequence read from source, and the
  cost is actually applied (mana pool gains mana from convoke; the graveyard shrinks from delve).
- Confirm whether declining the resulting `Target` prompt rewinds the cast the way §17.1 proved for an
  ordinary target — this is the concrete case requirements §6.4a/§16.5a names as still unverified.

**Out of scope**
- Any new prompt kind, `:protocol` message, or `:bridge` mapper change — none is expected; if one
  proves necessary, that is a finding worth stating loudly (standard 5), because this story's premise
  is that none is needed.
- A dedicated convoke/delve-specific UI (e.g. highlighting eligible creatures/graveyard cards before
  the player even taps "special action"). Ship the generic path first; special-case only if it renders
  badly, per 0061's precedent.
- Other `SpecialAction`-based mechanics not measured here (e.g. Quenchable Fire-style one-off special
  actions) — the fix is generic, so they should work the same way, but are not individually verified by
  this story.

## 4. Constraints already verified — do not rediscover

- `specialActionsAvailable` is already threaded through `:protocol`/`:bridge`/`:core:network` — no
  mapper work needed, only reading the field that already exists.
- The trigger is available during **both** `Select` (ordinary priority) and `PlayMana` — not only one
  of them; upstream's `activateSpecialAction` is called from both contexts (`HumanPlayer.java:1403` and
  `:1661`).
- Convoke is repeatable per creature (each `ConvokeSpecialAction` targets exactly one); the board should
  let the player press "special action" again after each tap, the same way it re-offers after any other
  incremental choice.
- Never widen or duplicate what the server offers — the button's only job is to send `SPECIAL`; the
  server decides what happens next.

## 5. Verification

- **Hermetic:** a `BoardControlsTest` fixture with `specialActionsAvailable = true` on a `Select`/
  `PlayMana` state must produce the "special action" control; one with it `false` must not, even if
  `specialButtonText` is absent (discriminates this from the "All attack" mechanism, so a fixture that
  conflates the two would pass against a broken projection).
- **Live, two decks:** a convoke deck (e.g. any commons-level convoke creature + a handful of small
  creatures) and a delve deck (a cheap delve spell + a stocked graveyard). Record the exact printings in
  `docs/live-test-decklists.md` once they work, per the standing instruction.
- **Standard 5:** confirm live that no `:protocol`/`:bridge` change was actually needed, rather than
  assuming §18.2's read is complete.

## 6. Acceptance criteria

- [ ] A "special action" control appears whenever `specialActionsAvailable` is true, during both
      priority and mana payment, independent of `specialButtonText`.
- [ ] Tapping it, with exactly one special action available, goes straight to that action's own
      `Target`/cost prompt.
- [ ] Tapping it, with more than one available, shows a `ChooseAbility` prompt first.
- [ ] A convoke cast live-verified: the button appears, tapping a creature via the resulting `Target`
      prompt taps it and reduces the cost, and the spell resolves.
- [ ] A delve cast live-verified: the button appears, exiling graveyard cards via the resulting `Target`
      prompt reduces the cost, and the spell resolves.
- [ ] Whether declining mid-payment on either path rewinds is recorded (confirmed either way — this
      closes requirements §6.4a/§16.5a for these two cases specifically).
- [ ] No `:protocol`/`:bridge` change, or the need for one is called out explicitly.

## 7. References

- `docs/game-board-requirements.md` — §18 (the full trace and design), §6.4/§16.5 (cancel), §6.2
  (prompt-presentation rule this reuses).
- `feature/game/.../board/BoardControls.kt` — `controlsFor`, where `UseSpecial` is currently gated on
  `specialButtonText` only.
- `core/network/.../game/GameState.kt` — `specialActionsAvailable`, mapped and unread today.
