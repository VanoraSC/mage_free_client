# Live-test decklists

Decks that are **known to work** against the pinned reference XMage server, so a future experiment does
not have to rediscover them.

**Why printings matter:** `Deck.load` resolves a card by **(setCode, collectorNumber)** — *not by
name*. A wrong pair fails with "Card not found" even when the name is spelled correctly. Every pair
below was taken from the bundled catalog (`core/cards/src/main/assets/cards.sqlite`) and used in a run
that reached its assertions.

**Table setup that works for all of them:** game type `Two Player Duel`, deck type
`Constructed - Freeform Unlimited` (the only validator that is unconditionally valid with
`deckMinSize == 0`), seats `[Human, ComputerMad]`, **AI joined first**, then the host.

---

## 1. Mono-basic — game reaches a real state
Used by `AppBridgeGameIT` / `GameRelayIT`. Simplest possible deck; proves join, opening hand, turn,
priority and `canPlayObjects` without any casting.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 60 | Forest | `M21` | `272` |

## 2. Cast-and-cancel — proves the rewind (2026-08-13)
Squadron Hawk costs `{1}{W}`, so casting genuinely requires paying mana; that mana step is the one
that gets declined.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 20 | Plains | `M11` | `230` |
| 40 | Squadron Hawk | `M11` | `33` |

**Result:** beginning the cast moved the Hawk to the stack (hand 7→6, stack 0→1) and prompted for
mana; declining returned it to hand (7), cleared the stack, and left both Plains **untapped**.

## 3. Scry — proves scry is observable with card identity (2026-08-13)
Temple of Triumph enters tapped and scries 1 on entry, so simply playing it produces a scry decision.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 24 | Swamp | `10E` | `372` |
| 20 | Thoughtseize | `2XM` | `109` |
| 16 | Temple of Triumph | `BLC` | `344` |

**Result:** the scry decision arrives as an ordinary **Target prompt carrying the real card** —
`msg='Select up to one card to PUT on the BOTTOM of your library (Scry)' cards=[Thoughtseize]
required=false`.

**Caution learned the hard way:** Temples enter **tapped** and produce R/W, so a deck relying on them
for black mana never casts Thoughtseize. If the experiment needs black mana available, prefer Swamps
and play them first.

## 4. Cast, cancel, recast and resolve — the board playing a real game (2026-08-14)

The deck the target-cancel experiment used (requirements §17) and story 0057's live run re-used. It was
recorded only in §17 until now, which meant relearning it; it belongs here.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 20 | Mountain | `10E` | `376` |
| 20 | Forked Bolt | `ROE` | `146` |
| 20 | Dragon Fodder | `ALA` | `97` |

**Why this deck.** `Forked Bolt` costs `{R}`, so it is castable off one land on turn one, and it is
genuinely multi-target (*2 damage divided among one or two targets*) — so it produces a **target prompt**,
the step §17.1 proved the server rewinds. It can also point at a **player**, which makes it the cheapest
way to reach a target prompt whose candidates are not cards. `Dragon Fodder` (`{1}{R}`) gives two Goblins
to shoot at once the game is longer.

**Result (story 0057, `BoardPlaysAGameIT`, both seats driven, app seat through the real ViewModel):**
mulligan answered and kept at 7; a Mountain played; priority passed both ways and the turn advanced;
casting moved the Bolt to the stack (`hand 8→7`, `stack 0→1`, and the opponent's board showed
`[Forked Bolt]` too); declining the **target** step returned it to hand (`8`), cleared the stack and left
the Mountain **untapped**; recasting, picking a target, confirming and paying `{R}` resolved it —
opponent `20→18`, stack clear on **both** boards. The prompt reads verbatim
`Select targets (selected 0 of 2, min 1) to divide 2 damage`, and updates to `selected 1 of 2, min 1`
after one pick, which is the server-side incremental validation §17.2 describes.

**Caution:** the candidates for both the starting-player prompt and a Forked Bolt aimed at a face are
**player ids**, and the prompt carries an empty `cards` list. A client that only lights up matching cards
has nothing to offer there — that was a blocking defect in 0057, caught on a device before merge.

## 5. Creature status and counters — a permanent that *becomes* a creature (2026-08-14)

Story 0058 needed something no static board can show: a permanent whose **creature-ness changes**, plus
a permanent carrying counters. Used by `CreatureStatusAndCountersIT`.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 24 | Mutavault | `M14` | `228` |
| 18 | Dryad Arbor | `FUT` | `174` |
| 18 | Servant of the Scale | `DTK` | `203` |

**Why this deck.**

- **Mutavault** is a land that is *not* a creature, with `{1}: Mutavault becomes a 2/2 creature … until
  end of turn`. One card, one activation, no second card needed — the cheapest live path to a permanent
  whose creature-ness changes. It also taps for `{C}`, so a second copy pays for the first, and the whole
  animation is reachable on **turn two**.
- **Dryad Arbor** is a land that *is* a creature with no effect involved (`Land Creature — Forest
  Dryad`, 1/1) — the static half of the same pair, and the deck's green source.
- **Servant of the Scale** (`{G}`) enters as a 0/0 **with a +1/+1 counter**, so one cheap cast produces
  both a counter to render and a creature whose printed 0/0 is not its current P/T.

**Result (2026-08-14, both seats driven, app seat through the real `GameBoardViewModel`):**

```
PLAIN LAND    Mutavault id=8e5d10ec  board.powerToughness=null  board.showsSummoningSick=false
              server.power=0/0  server.isCreature=false  server.summoningSick=true  server.cardTypes=[Land]
LAND CREATURE Dryad Arbor powerToughness=1/1 isCreature=true showsSummoningSick=true
              typeLine='Land Creature - Forest Dryad'
ANIMATED      id=8e5d10ec  powerToughness=2/2  isCreature=true  server.cardTypes=[Land, Creature]
              server.power=2/2  typeLine='Land Creature'
COUNTERS      Servant of the Scale counters=[+1/+1 ×1] powerToughness=1/1
              server=[GameCounter(name=+1/+1, count=1)]
```

The animated line carries **the same object id** as the plain-land line: one permanent, two snapshots,
`[Land]` → `[Land, Creature]`. Note also that the server really does send `power=0` and
`summoningSickness=true` for a plain land — the board declines to *show* them, which is a different thing
from the server not sending them, and is why the transcript prints both.

**Cautions learned in this run:**

- **Mutavault has two activatable abilities**, so activating it produces a `GAME_CHOOSE_ABILITY` prompt
  (`Choose spell or ability to play Mutavault [340]`). The animate one is picked by the server's own
  text (`becomes`); the ability ids mean nothing to a client.
- **Pick the mana source by what the prompt is owed.** Mutavault makes `{C}` and Dryad Arbor makes
  `{G}`, and `{C}` cannot pay `{G}`. A first attempt that simply took the first offered id tapped the
  same source **199 times** without the payment ever completing.
- **Dryad Arbor is summoning sick the turn it lands** — it is a creature — so it cannot tap for mana
  until the next turn. Play it early if the run needs green.
- **Rebuild the `bridge` container before a live run that depends on a bridge change.** The first
  attempt of this run reported `cardTypes=[]` and `isCreature=false` for a Dryad Arbor whose type line
  read `Land Creature — Forest Dryad`: the container was 27 hours old and did not have the new mapper.
  `docker compose -f docker/docker-compose.yml build bridge && … up -d bridge`.

---

## 6. Reaching combat — creature-dense on purpose (2026-08-14)

Used by `CombatProbeIT`. §17's run never reached combat at all (`combatSteps=0`) because its deck spent
nine turns drawing lands before it had a creature. Dragon Fodder is `{1}{R}` for **two** 1/1 Goblin
tokens, so both seats have attackers and blockers within a couple of turns.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 24 | Mountain | `10E` | `376` |
| 36 | Dragon Fodder | `ALA` | `97` |

**Result:** reached `DeclareAttackers` with a real declaration — see
`docs/game-board-requirements.md` §7.2 for the prompt shape. Runs are **not deterministic**: of six
runs, one reached the declaration and the rest ended earlier on mana draws or the stalls below. Budget
accordingly, and re-run rather than concluding from a single quiet transcript.

**Reused by `CombatDeclarationIT` (story 0061), and it got much luckier.** With **both** seats driven
by a real `GameBoardViewModel` — rather than one board and one raw client — the run reached combat on
turn 5 of the **first** attempt: attackers declared from the board's own controls on one seat,
blockers on the other, in the same turn. Two seats that both develop a board seems to matter more than
luck did: neither seat wastes turns on a harness that cannot attack, so combat arrives as soon as both
have creatures. Still budget for re-runs — one good run is not a distribution.

**What this deck cannot show you.** It has no planeswalkers and no battles, so the attacking seat has
exactly **one** legal defender and upstream's `selectDefender` assigns silently — the *"which
defender"* question is never asked, correctly. To see that question you need a second permanent type
to attack, which is what `CombatPairingProbeIT`'s deck adds (12× `Tibalt, the Fiend-Blooded`, `AVR`
`161`). The *blocking* pairing question (`Select attacker to block`) **is** reachable with this deck,
because two attackers make a blocker's choice genuinely ambiguous.

---

## Other verified printings (catalog-checked, for future decks)

| Card | Set | № |
|------|-----|---|
| Mountain | `10E` | `376` |
| Forked Bolt | `ROE` | `146` |
| Dragon Fodder | `ALA` | `97` |
| Arid Mesa | `MH2` | `244` |
| Thoughtseize | `2XM` | `109` (also `AKR` 127, `IMA` 110) |
| Temple of Triumph | `BLC` | `344` (also `C21` 327) |
| Plains | `M11` | `230` |
| Swamp | `10E` | `372` |
| Mutavault | `M14` | `228` |
| Dryad Arbor | `FUT` | `174` |
| Servant of the Scale | `DTK` | `203` |

## Harness notes

- Live tests are env-gated: `BRIDGE_URL=localhost:8080` for the app-side suites
  (`core/network/src/test/.../live/` and `feature/game/src/test/.../live/`),
  `XMAGE_SERVER=xmage-server:17171` for the `:bridge` ITs.
- **The app's own host form does not offer `Constructed - Freeform Unlimited`** — the validator this
  document calls the reliably-valid one (`deckMinSize == 0`). A table hosted *from the app* therefore
  gets a validator with real deck requirements, and the decks above may be rejected. Programmatic
  harnesses pass the deck type themselves and are unaffected; anyone hosting by hand from the app should
  expect this. Noticed during story 0057's independent verification.
- **Deck submission from the table room is currently declined by the server** (`submitDeck`), on multiple
  deck types and multiple decks. A match still starts because the host's deck is bound by `joinTable` at
  table creation, so *Start match* works without submitting. That is `:feature:tables` (Epic 7); recorded
  here because it shapes how a live game can be set up by hand today.
- **Receive timeouts must tolerate the AI's turn** — but a longer timeout is *not* a cure-all. 20 s
  starved; raising it to **75 s starved too** (`no GameState emitted within 75000ms`).
  **Solved, 2026-08-14** (`CombatProbeIT`), and the earlier guess was right: it is a prompt the
  auto-responder answers wrongly, not the server going quiet. Upstream asks
  *"You still have mana in your mana pool and it will be lost. Pass anyway?"*, and answering with the
  **negative** arm hands priority straight back with the mana still floating — forever. Identify it
  without parsing the prose: it is the only `Ask` carrying **`autoAnswerMessage`** in its options, and
  it must be answered **affirmatively**.
- **Two more stalls behind that one**, both found the same day:
  - A `PlayMana` prompt does **not** carry `possibleTargets`. Its sources are in **`playable`** — the
    same place `controlsFor` reads them. Reading the wrong field cancels the cast and stalls the run.
  - An auto-responder that plays `playable.first()` will cast a spell **before** playing its land, then
    strand itself mid-payment and silently retry the cast forever. **Play lands first**; `manaCost` is
    null for lands, which is the only signal needed.
- **A harness that declares attackers must remember what it has already declared** (story 0061).
  Upstream keeps a declared creature in `possibleAttackers`, because re-selecting it is how a player
  takes the declaration *back*. A loop that always taps the first candidate therefore toggles one
  creature in and out forever and never reaches blockers. Keep the declared ids per seat and per step,
  and send the *done* (`cancelPrompt`) once nothing new is offered.
- **Drive both seats through the real board where the feature is two-sided** (story 0061). Combat's
  two roles never belong to the same player at once, so a run with one board and one raw client can
  only ever exercise half of it — and the seat that cannot play the feature also wastes its turns,
  which is part of why combat was so hard to reach. Two `GameBoardViewModel`s reached a declared,
  blocked combat on turn 5 of the first attempt.
- **Deep-game experiments are therefore expensive.** Anything needing several turns of real play is
  better done *inside* the story that builds the relevant surface, where the loop can be driven by the
  real UI logic, than as a standalone scripted probe.
- The main-phase state returned by `playUntilOurMainPhase` is **already in hand**; act on it before
  the next `receive`, or the loop waits for a state the server will not send until you answer.
