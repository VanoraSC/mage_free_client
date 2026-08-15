# 0061 — Combat: declaring attackers and blockers

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0057 (board interaction), 0058 (creature status and counters), 0055 (board rendering), 0052 (`GameClient`)
- **Status:** ready

## 1. Objective

Let the player fight. Declare attackers, declare blockers, and answer the pairing questions the server
asks — using 0057's tap model, with **no new protocol or bridge work**, because everything needed is
already on the wire.

Today **combat cannot be played at all**: a declaration is projected as ordinary priority controls with
`pickableObjectIds` empty, so the board offers *"attack with everything, or nothing"* and **no way to
block whatsoever**.

## 2. Context & background — this story is unusually well measured

Combat had never been reached in any run (`combatSteps=0`) until the probes for this story. Everything
below was measured live or read out of the pinned server's own plugin, and is recorded in
`docs/game-board-requirements.md` §7.2–§7.5. **Do not re-derive it.**

### 2.1 What the server asks

| | Prompt | Ids | Shortcut |
|---|---|---|---|
| **Attacking** | `Select` / message `Select attackers` | `options.possibleAttackers` | `specialButton` → "All attack" |
| **Blocking** | `Select` / message `Select blockers` | `options.possibleBlockers` | **none** |

- **`playable` is EMPTY during a declaration.** The creatures come *only* from the options. This is the
  whole defect: `controlsFor` derives `pickableObjectIds` from `state.playable`, which is why the board
  offers nothing to tap.
- **The data path is already complete.** The bridge's `optionsView()` turns *any* collection-valued
  option into an id list and carries unknown keys through, so `possibleAttackers`/`possibleBlockers`
  have been arriving intact all along. `:protocol` and `:bridge` need **no change** — verify that
  claim rather than assuming it (standard 5), but expect to change neither.

### 2.2 The pairing questions, and why no policy seam is needed

Read from `mage.player.human.HumanPlayer` (`mage-player-human-1.4.60.jar`, the plugin the reference
server loads). Both directions share one shape: **build the set of legal pairings; if it has exactly
one member, assign it silently; otherwise ask.**

- `selectDefender` → one defender: `declareAttacker(...)` and return, no prompt. More: `TargetDefender`.
- `selectBlockers` → one attacker: assign silently. More: asks, message **`Select attacker to block`**.
  The set is pre-filtered by `CombatGroup.canBlock`, so it only asks when this blocker *could* block
  more than one.

Both follow-ups arrive through `fireSelectTargetEvent` — i.e. as ordinary **`GAME_TARGET`** prompts,
which 0057 already answers with `chooseTarget`. **So the board answers a pairing question the same way
it answers any target, and nothing in this app decides when to ask.**

### 2.3 Combat is two problems, never both at once (§7.4, Pete)

The attacking player assigns attackers → **defenders (player, planeswalker, or battle)**; the blocking
player assigns blockers → attackers. They never belong to the same player at the same moment, so each
is designed for its own job. `CombatGroup` agrees: it is **per-attacker** — two attackers produced two
groups, each `attackers=1`, the defender repeated.

## 3. Scope

**In scope**
- **A declaration projection.** A `Select` carrying `possibleAttackers`/`possibleBlockers` becomes its
  own `PromptControlsUi` case — not `Priority` — with `pickableObjectIds` taken from **the options**,
  never from `playable`.
- **Tap to declare** (§7.5): tapping an offered creature sends `chooseTarget`. Per-pick, exactly as
  targeting: 0057 proved the server re-prompts, and combat is the same machinery.
- **Answering the pairing question**: when the follow-up `Target` arrives, render it in **combat
  context** (whose attacker/blocker is being paired) rather than as a bare target prompt.
- **Done** ends the declaration (`cancelPrompt`, the legitimate *done* — same as targeting).
- **"All attack"**, kept but **behind the targeting confirmation** (§16.4, Pete). Note it is *not*
  "attack the face": `selectDefenderForAllAttack` asks once for a defender for the whole team when
  more than one exists.
- **Rendering during combat**: 0055 already marks `isAttacking`/`isBlocking` from `CombatGroup`. Make
  combat *legible* — who is attacking what, and what is blocking it — within the existing portrait
  layout. Attackers and blockers are permanents already drawn on two battlefields.
- The board must show **which of the two roles it is in**, and never offer both.

**Out of scope**
- **Damage assignment order, trample, first/double strike.** These arrive as ordinary prompts
  (`Target`, `GetAmount`, `GetMultiAmount`) that 0057 already answers generically. **Do not
  special-case them**; if one renders badly, report it rather than building combat-specific handling.
- Auto-pass and stops (§14.1) — though declare-attackers/blockers are the steps players most want
  stops at, so leave the seam untouched and usable.
- Match flow, sideboarding, game end.

## 4. Constraints already verified — do not rediscover

- **Never widen the server's set.** `getCreaturesForcedToAttack` is consulted before `selectDefender`,
  so a creature *forced* to attack has a **restricted** defender set — narrower than the legal
  defenders generally. Render what the server offers, nothing more.
- **`playable` is empty during a declaration** (§2.1). A projection that reads it offers nothing.
- **Blocking has no shortcut** — do not invent an "all block".
- **A defender is not always the opposing player** — planeswalkers and battles are legal defenders in
  ordinary 1v1.
- **`CombatGroup` is per-attacker**, with the defender repeated per group.
- **0058 gates power/toughness on `isCreature`** — combat renders creatures, so attackers and blockers
  show P/T through the existing gate. Do not add a second path.

## 5. Verification

- **Standard 1**, and the test must discriminate the *actual* bug: a declaration `Select` must offer
  `possibleAttackers` as pickable. A test built on a prompt whose ids also appear in `playable` would
  pass against today's broken projection — build the fixture the way the server really sends it, with
  **`playable` empty**.
- Also prove: blocking is not offered during declare-attackers (and vice versa); a pairing `Target`
  arriving mid-declaration is answerable; "All attack" does not fire without its confirmation.
- **Standard 2 (reachability):** name what produces every id the UI gates on — the server's `Select`
  during `DeclareAttackers`/`DeclareBlockers`.
- **Standard 5:** confirm `possibleAttackers`/`possibleBlockers` genuinely arrive through the bridge's
  generic `optionsView()` before relying on it. If `:protocol`/`:bridge` turn out to need a change,
  that is a finding worth stating loudly, because §7.2 says they should not.
- **Hermetic gate**, Compose tests via Robolectric in `src/testDebug`.
- **Live**, through the real `GameBoardViewModel`, driving a declaration from the board's own controls
  — `CombatProbeIT` and `CombatPairingProbeIT` show how to reach combat and what the transcript should
  look like. Reaching combat is **nondeterministic**: one of six runs got there. Budget for re-runs.
  Use the §5 decklist (24 Mountain / 36 Dragon Fodder), and see §7.6 for the three stalls that will
  otherwise eat the run — above all, the mana-pool *"Pass anyway?"* must be answered **affirmatively**.
- **Eyes-on (standard 3) — hand Pete this checklist.** Do **not** drive the UI programmatically.
  1. Get a creature onto your battlefield and reach your combat step. Confirm the board offers the
     creature to tap, not just "Pass" and "All attack".
  2. Tap one creature. Confirm it is marked as attacking, and that the board says so.
  3. With a planeswalker on the opponent's side, confirm you are asked **which** defender — and with
     no planeswalker, confirm you are **not** asked.
  4. Press "All attack" and confirm it asks you to confirm before committing the team.
  5. On the other side of a real attack, confirm you can block, and that double-blocking one attacker
     asks **"Select attacker to block"** only when the blocker could block more than one.
  6. Confirm the board never offers attacking and blocking at the same time.

## 6. Acceptance criteria

- [ ] Attackers can be declared by tapping creatures the server offered; the ids come from
      `possibleAttackers`, never from `playable`.
- [ ] Blockers can be declared the same way, from `possibleBlockers`.
- [ ] A pairing question (`which defender` / `Select attacker to block`) is answerable when the server
      asks it, and **is not asked by the board when the server does not**.
- [ ] "All attack" is offered, and confirmed before it commits.
- [ ] The board never offers both roles at once, and states which one it is in.
- [ ] Attacking and blocking are legible on the board during combat.
- [ ] No change to `:protocol` or `:bridge` (or, if one proves necessary, it is called out explicitly).
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `docs/game-board-requirements.md` — §7.2/§7.3 (measured prompt shapes), §7.4 (two assignment problems), §7.5 (declaration decisions + the upstream read), §7.6 (harness stalls), §16.4 (confirmation).
- `feature/game/.../board/BoardControls.kt` — `controlsFor`, where `pickableObjectIds` comes from `playable`.
- `feature/game/src/test/.../live/CombatProbeIT.kt`, `CombatPairingProbeIT.kt` — how to reach combat.
- `docs/live-test-decklists.md` §6 — the creature-dense deck, and the nondeterminism warning.
