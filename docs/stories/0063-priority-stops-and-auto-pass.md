# 0063 — Priority: stops and configurable auto-pass

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0057 (board interaction), 0061 (combat declaration)
- **Status:** ready — **scope resolved (Pete, 2026-08-15): full mechanism parity with upstream, not a
  narrowed v1; touch-first presentation is a separate, still-undesigned pass**

## 1. Objective

Answering every single priority window by hand, for an entire game, is a lot of taps — manual-only
priority (§9.1/§14.1) is correct for the first playable board but is not, by itself, a comfortable way
to play a full match. This story grounds the eventual "stops and configurable auto-pass" feature (named
in §14.1: *"similar functions to Arena and MTGO"*) in what desktop XMage **actually does**, then builds
**the whole mechanism** — Pete's resolution of the scope question: *"implement parity with the server
code."* Not a narrowed v1 subset; every skip action and every stop setting upstream has. §14.1's
constraint — passing priority stays a policy decision made in one place — is what makes this possible
without touching anything else in `feature/game`. Parity is of **mechanism**, not **presentation** —
see §3's out-of-scope note on why desktop's own UI is not what's being matched.

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

**In scope — the full mechanism, matching upstream one for one**
- A `SkipState`/equivalent that tracks whether a skip is currently active and which one, consulted by a
  new `PassPolicy` implementation that returns `PassImmediately` while armed and disarms itself the
  moment a stop condition fires. `ManualPassPolicy` stays the default/only policy until this lands; the
  new policy replaces it behind `BoardModule`, per `PassPolicy`'s own documented seam.
- **All six skip actions** (§14.3.1): skip to next turn, skip to the opponent's-or-next end step, skip
  to the opponent's-or-next main phase, skip to your turn, skip until the stack resolves (with its own
  "or stop early on a new stack object" toggle), skip to the end step before your turn.
- **The full stop-settings matrix** (§14.3.2): the global flags (`stopOnDeclareAttackers`,
  `stopOnDeclareBlockersWithAnyPermanents`/`WithZeroPermanents`, `stopOnAllMainPhases`,
  `stopOnAllEndPhases`, `stopOnStackNewObjects`, upstream's defaults) **and** the per-phase-step
  settings, tracked **separately for your turn and the opponent's turn**
  (upkeep/draw/main1/before-combat/end-of-combat/main2/end-of-turn).
- **Hold priority** (§14.3.3) — the third mechanism, genuinely independent of the skip/stop state:
  after the player's own action, don't auto-pass, so a second action can be chained before priority
  moves on. In scope alongside the rest, since parity means the whole mechanism, not two of three
  pieces.
- Explicit interaction with floating mana (§14.2): a skip must not silently pass with mana in the pool
  unless the player has said that's fine — flagged in §14.2 as needing deliberate handling, not
  inherited by default.

**Out of scope**
- **The touch-first presentation itself.** Building the full mechanism does not mean porting desktop's
  UI — seven buttons plus a settings dialog with per-phase checkboxes ×2 turns is Swing-desktop-shaped,
  and porting *that* verbatim would be exactly the "don't port the Desktop UI" mistake `AGENTS.md` warns
  against (see [`docs/ux-principles.md`](../ux-principles.md)). How six skip actions and a full stop
  matrix become something usable on a phone is its own design pass, informed by this story's mechanism
  but not decided by it.
- Per-user persisted settings (DataStore-backed preferences) — start with in-session defaults; whether
  stop settings need to survive across games/app launches is a separate app-infrastructure question, not
  part of matching the server-code mechanism itself.

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
- **Hermetic:** `PassPolicy` tests over fakes for **each of the six skip actions** reaching its own stop
  condition correctly; the **full stop matrix** (every global flag, every per-phase-step setting on both
  your-turn and opponent-turn) interrupting an active skip at the right moment; hold-priority preventing
  auto-pass immediately after the player's own action; skip never auto-passing a combat declaration
  regardless of settings; floating mana at the moment a skip would pass handled per whatever §14.2
  policy this story lands (documented explicitly, not left implicit).
- **Live:** drive a real game exercising a representative sample of the six skip actions across several
  turns/phases and confirm each stops exactly where expected — both for "your turn" defaults and
  "opponent's turn" defaults, and confirm hold-priority chains a second action without an intervening
  pass.
- **Eyes-on (standard 3):** hand Pete a short checklist confirming the skip controls are discoverable,
  their stops are legible (the player must always be able to tell *why* control came back to them), and
  the full settings surface doesn't overwhelm a phone screen — flag to a follow-up presentation pass
  anything that does, per §3's out-of-scope note.

## 6. Acceptance criteria

- [ ] `PassPolicy`'s seam is unchanged in shape; the new policy is swapped in via `BoardModule` only.
- [ ] **All six** skip actions work end to end and each disarms itself at its own documented stop
      condition.
- [ ] The **full** stop-settings matrix (global flags **and** per-phase-step, tracked separately for
      your turn and the opponent's) is consulted and can interrupt an active skip.
- [ ] Hold-priority prevents auto-pass immediately following the player's own action, independent of
      the skip/stop state.
- [ ] A combat declaration (`Select` with `CombatRole.of(prompt.options) != null`) is **never**
      auto-passed by this policy, under any setting.
- [ ] Floating mana's interaction with an active skip is handled deliberately, not silently (§14.2).
- [ ] No `:protocol`/`:bridge` change, or the need for one is called out explicitly.
- [ ] Live-verified across at least one full turn cycle with each skip action exercised.

## 7. References

- `docs/game-board-requirements.md` — §14.1–§14.3 (the full upstream trace and design), §9.1 (manual
  priority baseline), §7.4/§7.5 (why combat declarations are `Select` too).
- `feature/game/.../board/PassPolicy.kt` — the existing seam and its own KDoc, which already
  anticipated this story's shape.
- `../mage`: `Mage.Client/src/main/java/mage/client/game/GamePanel.java`,
  `Mage/src/main/java/mage/players/net/{UserSkipPrioritySteps,SkipPrioritySteps}.java`.
- [`docs/ux-principles.md`](../ux-principles.md) — why the desktop skip-button/settings-dialog
  presentation should not be ported verbatim.
