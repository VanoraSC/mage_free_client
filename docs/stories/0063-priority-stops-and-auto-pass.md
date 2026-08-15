# 0063 — Priority: stops and configurable auto-pass

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0057 (board interaction), 0061 (combat declaration)
- **Status:** ready — **mechanism traced from source; touch-first presentation is not yet designed**

## 1. Objective

Answering every single priority window by hand, for an entire game, is a lot of taps — manual-only
priority (§9.1/§14.1) is correct for the first playable board but is not, by itself, a comfortable way
to play a full match. This story grounds the eventual "stops and configurable auto-pass" feature (named
in §14.1: *"similar functions to Arena and MTGO"*) in what desktop XMage **actually does**, then scopes
a first cut of it. §14.1's constraint — passing priority stays a policy decision made in one place — is
what makes this possible without touching anything else in `feature/game`.

## 2. Context & background — traced from `../mage`, not guessed

`PassPolicy` (`feature/game/.../board/PassPolicy.kt`) already exists as the single seam every pass goes
through, and its own KDoc had already guessed — correctly — that the eventual feature would not fit its
one-shot `decide(state): AskThePlayer | PassImmediately` interface: *"the standing 'pass until …' verbs
are a different thing entirely — they are player actions, not answers."* This story confirms that guess
against upstream and gives it a concrete shape. Traced from:

- `Mage.Client/src/main/java/mage/client/game/GamePanel.java` (`skipButtonsList`, `holdPriority`) — the
  desktop Swing client. **This is entirely client-local**: nothing in `Mage`/`Mage.Common`/`Mage.Server`
  implements auto-pass. The server sends the identical `Select` prompt every time; whether the client
  shows it to the human or answers it silently is our decision alone, same as it is upstream's.
- `Mage/src/main/java/mage/players/net/UserSkipPrioritySteps.java` and `SkipPrioritySteps.java` — the
  persisted per-user stop settings.

**The real shape, in full, is requirements §14.3** — read that section before designing anything here;
it is not repeated in full below. Summary:

1. **Six explicit "skip to X" player actions**, not a standing toggle: skip to next turn / to the
   opponent's-or-next end step / to the opponent's-or-next main phase / to your turn / until the stack
   resolves (or stops early on a new stack object) / to the end step before your turn. Each starts
   auto-passing *now* and stops itself when its own condition is met.
2. **Persistent stop settings**, tracked separately for **your** turn and the **opponent's** turn
   (per-phase-step: upkeep/draw/main1/before-combat/end-of-combat/main2/end-of-turn), plus global flags
   independent of whose turn it is (`stopOnDeclareAttackers` default **true**,
   `stopOnDeclareBlockersWithAnyPermanents` default **true**,
   `stopOnDeclareBlockersWithZeroPermanents` default **false**, `stopOnAllMainPhases`/
   `stopOnAllEndPhases` default **true**, `stopOnStackNewObjects` default **true** — a skip always
   breaks the moment something new hits the stack).
3. **Hold priority** — a third, independent mechanism: after *your own* action, don't auto-pass, so a
   second action can be chained before priority moves on. Not consulted by `PassPolicy` at all; it
   governs what happens right after our own `playObject`/ability call, not incoming prompts.

**The known trap (§14.1) is upstream's own model, not an edge case we invented.**
`stopOnDeclareAttackers`/`stopOnDeclareBlockersWith*` existing as **dedicated** settings, separate from
the ordinary phase-step list, confirms combat declaration needs its own stop handling — corroborating
0061's finding that a combat declaration is also a `GamePrompt.Select` and a naive `PassImmediately`
would silently decline to attack or block.

## 3. Scope

**In scope**
- A `SkipState`/equivalent that tracks whether a skip is currently active and which one, consulted by a
  new `PassPolicy` implementation that returns `PassImmediately` while armed and disarms itself the
  moment a stop condition fires. `ManualPassPolicy` stays the default/only policy until this lands; the
  new policy replaces it behind `BoardModule`, per `PassPolicy`'s own documented seam.
- The **global stop flags** (§14.3.2) as the first cut of configurable stops — sensible defaults matching
  upstream's (stop on your own main phases, stop on declare-attackers/declare-blockers-with-permanents,
  stop when anything new hits the stack), before attempting the full per-phase-step ×
  your-turn/opponent-turn matrix.
- At minimum **one or two skip actions** to prove the mechanism end to end — "skip to next turn" and
  "skip until the stack resolves" are the strongest candidates: they cover the two most common reasons
  a player wants to stop tapping through their own or the opponent's empty turn.
- Explicit interaction with floating mana (§14.2): a skip must not silently pass with mana in the pool
  unless the player has said that's fine — flagged in §14.2 as needing deliberate handling, not
  inherited by default.

**Out of scope — deliberately, and named so this doesn't grow unbounded**
- The **full** six-skip-action set and the full per-phase-step × per-turn stop matrix from desktop.
  Desktop's presentation (seven buttons + a settings dialog with per-phase checkboxes ×2) is
  Swing-desktop-shaped; porting it verbatim would be exactly the "don't port the Desktop UI" mistake
  `AGENTS.md` warns against (see [`docs/ux-principles.md`](../ux-principles.md)). A touch-first
  presentation of "which stops matter enough for a phone" is a **separate design pass**, not this
  story's implementation step.
- Hold-priority (§14.3.3) — genuinely independent of the skip/stop mechanism; worth its own story once
  the pattern for "act again without passing" is needed.
- Per-user persisted settings (DataStore-backed preferences) — start with in-session defaults; whether
  these need to survive across games is a product question, not a mechanism one.

## 4. Design & approach

- **This is a board-side feature only.** No `:protocol`/`:bridge` change — the server's behavior is
  identical whether a human or an armed skip answers its `Select`. Confirm this holds (standard 5)
  rather than assuming it, but expect no wire change.
- **Keep `PassPolicy` as the single seam.** The skip/stop state lives beside it (or is injected into
  it), but every pass the app ever sends still goes through exactly one
  `GameClient.passPriority` call site, per the existing KDoc's guarantee.
- **Discriminate `Select` by `CombatRole.of(prompt.options)` before ever returning
  `PassImmediately`.** This is the trap §14.1/0061 already found: a combat declaration is a `Select`
  too, and passing it is read upstream as *declining to attack/block*, not "pass priority." The default
  stop settings (attackers/blockers = true) make this safe by default, but the policy itself must still
  refuse to auto-pass a declaration even if a future setting tried to allow it — declining combat
  should never be silent.
- A skip **disarms on the earliest of:** its own stop condition, a global stop flag matching the current
  step, or (for any active skip) `stopOnStackNewObjects` firing. The player regains manual control at
  that point exactly as if they had never activated the skip.

## 5. Verification

- **Standard 1**, per behavior: a test proving a policy that ignores the combat-declaration exception
  fails first (mirrors 0061's own failing-first test for the same trap), then passes once discriminated.
- **Hermetic:** `PassPolicy` tests over fakes for: skip stays armed across consecutive ordinary
  `Select`s until its stop condition; skip disarms on a new stack object; skip never auto-passes a
  combat declaration regardless of settings; floating mana at the moment a skip would pass is handled
  per whatever §14.2 policy this story lands (documented explicitly, not left implicit).
- **Live:** drive a real game where a skip is armed across several turns/phases and confirm it stops
  exactly where expected — both for "your turn" defaults and "opponent's turn" defaults.
- **Eyes-on (standard 3):** hand Pete a short checklist confirming the skip control is discoverable and
  its stop is legible (the player must always be able to tell *why* control came back to them).

## 6. Acceptance criteria

- [ ] `PassPolicy`'s seam is unchanged in shape; the new policy is swapped in via `BoardModule` only.
- [ ] At least one skip action works end to end and disarms itself at the documented stop condition.
- [ ] The global stop flags (§14.3.2, sensible defaults) are consulted and can interrupt an active skip.
- [ ] A combat declaration (`Select` with `CombatRole.of(prompt.options) != null`) is **never**
      auto-passed by this policy, under any setting.
- [ ] Floating mana's interaction with an active skip is handled deliberately, not silently (§14.2).
- [ ] No `:protocol`/`:bridge` change, or the need for one is called out explicitly.
- [ ] Live-verified across at least one full turn cycle with a skip active.

## 7. References

- `docs/game-board-requirements.md` — §14.1–§14.3 (the full upstream trace and design), §9.1 (manual
  priority baseline), §7.4/§7.5 (why combat declarations are `Select` too).
- `feature/game/.../board/PassPolicy.kt` — the existing seam and its own KDoc, which already
  anticipated this story's shape.
- `../mage`: `Mage.Client/src/main/java/mage/client/game/GamePanel.java`,
  `Mage/src/main/java/mage/players/net/{UserSkipPrioritySteps,SkipPrioritySteps}.java`.
- [`docs/ux-principles.md`](../ux-principles.md) — why the desktop skip-button/settings-dialog
  presentation should not be ported verbatim.
