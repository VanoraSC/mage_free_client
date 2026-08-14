# 0057 — Board interaction: casting, targeting, cancel

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0055 (board rendering), 0052 (`GameClient`), 0054 (state cache), 0056 (card art)
- **Status:** ready

## 1. Objective

Make the board playable. Answer the server's prompts, cast spells, choose targets, pay mana, pass
priority — and **cancel**, with the cascading rollback the design session called for. This is where
§16's revision lands: **floating controls, never modals**, with a visibility toggle.

It also unblocks what 0055 could not verify: a read-only seat **hard-blocks the game** (upstream's
`HumanPlayer.chooseMulligan` → `waitForResponse` is an unbounded wait), so battlefields populating,
the stack filling and turns advancing have only ever been covered hermetically. Once the board can
answer, all of it becomes verifiable live.

## 2. Scope

**In scope**
- **Floating controls over the board** (§16.2) — nothing modal. Prompts answered *from their own
  content* (ask, get-amount, choose-choice, choose-ability, choose-pile) and prompts answered *by
  touching the board* (target, play-mana, select) share one presentation, because the board is never
  hidden either way.
- **Visibility toggle** (§16.3) — hide the floating controls to see the board unobstructed.
  **Hard constraint:** hiding them must **never** hide that the server is waiting on the player, or a
  hidden control set becomes an invisible stall. 0055's `PriorityUi.Asked` already exists for this and
  must survive the toggle.
- **Tap-select-then-confirm** (§5.1) — first tap raises the card and shows detail; a second tap or an
  explicit confirm commits. Playable cards can be played **from the detail view** (§11.1); a tap
  elsewhere closes it.
- **Targeting** (§5.2, §16.4, §17.2) — highlight the server's candidates and tap to pick, with a
  **confirmation before the choice is committed**.
  **Do not batch picks client-side.** Verified live: the prompt reads *"Select targets (selected 0 of
  2, min 1)"* and the server **re-prompts with an updated count and candidate set after each pick** —
  so pre-selecting against the first list could assemble a combination it rejects. Send each pick, let
  the prompt update, and make the player's confirmation the final **done**
  (`SendPlayerBoolean(false)`, legitimate once `min` is satisfied).
- **Mana payment** (§6.3, §6.5) — the player taps their own lands. The server already narrows the
  choice (`ManaUtil.tryToAutoPay` runs before prompting, and deliberately does *not* auto-pay when the
  spell cares about colour), so **render the choice offered; never compute a payment**.
- **Cascading cancel** (§16.5, §17.1) — proven to rewind at **both** the mana step and the target
  step: the card returns to hand, the stack clears, lands stay untapped. Offer cancel where the
  prompt's `required` is false, and roll the cast back step by step.
- **Pass priority** — manual, every time (§9.1, §14.1). Auto-pass and stops are a **named future
  feature**, so **pass policy must live in one place**, not scattered through the UI.
- Concede and quit as **separate** actions (§12.2), mirroring upstream's separate verbs.

**Out of scope**
- Combat declaration (attackers/blockers) — next story. 0055's rendering of `combat` stays read-only.
- The known-information browser (§11.2), the interstitial and coin toss (§1, §15), match flow and the
  sideboard screen (§12.1), game end beyond leaving, spectating (§13).
- Auto-pass/stops (§14.1) and bridge knowledge tracking (0053) — both post-release.

## 3. Constraints already verified — do not rediscover

- **`playable` exists only while you hold priority.** An unhighlighted card may mean *"not your
  moment"*, not "not playable". Never present it as the latter.
- **Never compute legality or mana payment** — both are the server's answers.
- **The spell goes on the stack immediately** when a cast begins (`playObject`), *then* modes/targets/
  costs are asked — which is what CR 601 says. So "cancel before it is committed" is mechanically
  *"cancel before the cast completes, and the server un-does it"*. Do not claim the spell is uncast
  while the stack briefly holds it.
- **The opponent's stack can hold a phantom** after they cancel (§17.4) — the rewind is not pushed to
  them. Do not treat the opponent's stack as authoritative between pushes; 0054's `refreshGame` is the
  reconciliation.
- **`GamePrompt.Unrecognised` has no answering method** and must render as a non-blocking, non-
  answerable notice — never a control the player cannot satisfy.
- Locate the viewer via `isViewer`; never by index.

## 4. Verification

- **Hermetic:** ViewModel tests over 0052's `FakeGameClient` for each prompt kind, the cancel paths,
  and the visibility toggle (including that priority remains visible when controls are hidden).
  Compose tests in the **hermetic gate** (Robolectric, `src/testDebug` — device-only tests do not run
  pre-merge). Standard 1: demonstrate the behavioural tests failing first.
- **Live, two clients — now genuinely possible.** With the board able to answer, drive a real game
  from the app against a second client and confirm: mulligan answered, opening hand kept, lands
  played, a spell cast and **cancelled** (card back in hand, stack clear, lands untapped), the same
  spell cast again and resolved, priority passed, turns advancing, and the stack filling and clearing
  on **both** boards. `docs/live-test-decklists.md` has decks with exact printings — the
  Mountain/Forked Bolt/Dragon Fodder deck is built for exactly this.
- **On-device (standard 3):** the real proof, and now unblocked — a person (or the harness) plays a
  turn from the installed app.

## 5. Acceptance criteria

- [ ] Every prompt kind is answerable through **floating controls**; nothing is modal.
- [ ] The visibility toggle hides the controls, and **priority remains visible** when hidden.
- [ ] A spell can be cast, targeted with a **confirmation step**, paid for by tapping lands, and
      **cancelled at any step the server permits** — with the card returning to hand and mana unspent.
- [ ] Targets are sent per-pick (never batched against a stale candidate list); the confirmation is
      the final *done*.
- [ ] Priority is passed manually, through a **single** pass-policy seam that auto-pass can later use.
- [ ] Concede and quit are separate, explicit actions.
- [ ] Live: a full turn is played from the app against a second client, including a cancel-and-recast,
      with both boards remaining correct.

## 6. References

- `docs/game-board-requirements.md` — §5 (interaction), §6 (prompts, casting as one act, mana), §9/§14 (priority), §16 (the revision), §17 (target-cancel findings, verified live).
- [`0055-board-rendering.md`](0055-board-rendering.md) — the board this makes interactive.
- `docs/live-test-decklists.md` — working decks, exact printings, harness pitfalls.
