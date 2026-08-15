# Game board — requirements

**Status:** in progress. Built in a question-and-answer design session with Pete (started 2026-08-12).
Decisions are recorded with their rationale **and** the data constraint behind them, so a later reader
can tell what was chosen from what was forced.

**Ground rule:** every requirement names the server-produced data that satisfies it. Where the data
cannot satisfy it, that is recorded as a **constraint**, not quietly designed around. (This is
verification standard 2 applied at design time — the discipline that caught an entire epic's worth of
unreachable state earlier in the project.)

---

## 0. What the data actually provides

Established live in stories 0051/0052 against a real XMage server. This is the material the board has
to work with.

- **State arrives as full snapshots, never deltas** — the board re-renders from a complete picture.
- **The server owns the rules.** `playable` (from `GameView.canPlayObjects`) is the only source of
  "may this be played now". The board never computes legality.
- **`playable` is populated only while you hold priority** — it is a fact about *your* priority window,
  not continuous truth.
- **There is no way to *read* game state.** Upstream has no "get game" verb, and re-joining a running
  game does not resync. The board is **push-only**: there is no pull-to-refresh at any price.
- **No priority id** — only a display name; per-seat `hasPriority` is the id-safe source.
- **No structured game result** — `GAME_OVER` is a single prose line.
- **Player order is unstable** — find your own seat via `isViewer`, never by index.
- **Server narration is HTML** (e.g. `Draw - Waiting for <font color='#20B2AA'>Computer</font>`).
- **`exile` is a list containing one zone even when nothing is exiled** — inspect cards, not size.
- **`manaCost` is null for lands.**
- **Clocks read 0 on an untimed table.**

---

## 1. Entry: joining the game

### 1.1 Match start shows a brief interstitial
**Decision.** On `MatchStarting`, show a short transitional screen before the board.

**Why.** The server needs a beat to set the game up, and the first snapshot legitimately has an empty
hand. The interstitial gives that setup somewhere to live instead of showing a half-built board.

**Data.** `MatchStarting` carries the game id (0051); `joinGame` then produces the first snapshot.

### 1.2 The board appears before the hand exists
**Decision.** Once past the interstitial, render the board immediately; the hand region shows an
empty/loading state until the dealt hand arrives.

**Why.** Honest to the data rather than pretending. Preferred over holding the whole board hostage to
the deal.

**Data.** Verified live: the first snapshot after `GAME_INIT` has an empty hand; a later snapshot
carries the seven cards.

**Implication.** Every board region must have a defined empty state — the board is *never* guaranteed
to arrive fully populated. This applies beyond the hand: stack, combat and revealed are routinely empty.

### 1.3 The coin toss is stated explicitly, win or lose
**Decision.** Tell the player the toss result plainly, then present the choice when it is theirs
("You won the toss — play first or draw?" / "Opponent won the toss and chose to play").

**Data — satisfiable, with a caveat.** The server *does* announce it:
`GameImpl.pickChoosingPlayer()` fires an inform event `"<player> won the toss"`. Two possible sources:

| Signal | Robustness | Gives us |
|---|---|---|
| **Structural** — we receive the "who goes first" prompt | Strong; no parsing | *That we won.* Says nothing when we lose. |
| **Narration** — the inform text `"X won the toss"` | Brittle; prose + the name is HTML-marked | The winner's name either way. |

**Resolved (1.3a).** **Structural signal only, with a generic loss line.** Being asked the "who goes
first" question means we won → *"You won the toss — play first or draw?"*. Not being asked means we
lost → *"Opponent won the toss"*, stated generically. **No prose parsing at all**, so nothing breaks
if upstream rewords its narration, and no dependency on HTML-wrapped log names. The cost is that the
loss line cannot name the opponent from the toss event itself — acceptable, since the opponent's name
is already on screen from the player list.

---

## 2. Orientation

### 2.1 Landscape only — ⚠️ SUPERSEDED by §16.1 (portrait)

> **Superseded 2026-08-13.** Kept for the reasoning only. The board is **portrait**, like every other
> screen — see §16.1.
**Decision.** The board targets landscape exclusively.

**Why.** Matches how Magic is physically laid out and how desktop XMage reads: battlefields run
left-to-right with room per row, rather than fighting for vertical space against two battlefields, a
hand and the stack.

**Implication (recorded deliberately).** This breaks with the rest of the app, which is portrait
throughout (lobby, decks, cards, tables). Entering a game becomes an orientation change, and the board
is the only landscape surface in the product. Accepted knowingly.

**Open question (2.1a):** does the app force the rotation on entering a game, or ask the player to
rotate? — **moot.** Portrait (§16.1) means the board never rotates the device at all.

---

---

## 3. Layout

### 3.1 Opponent's battlefield top, yours bottom
**Decision.** Two horizontal bands: the opponent's battlefield across the top, yours across the bottom.

**Why.** Mirrors a physical table seen from your seat, and matches how desktop XMage reads.

**Data.** Both battlefields come from `players[].battlefield`. **Seats must be located via `isViewer`
/ `viewerPlayerId`, never by list index** — player order is not stable and is not viewer-first
(observed live: the AI appeared first in two runs, the viewer first in two others).

**Open question (3.1a):** with both bands taken by battlefields, where do the stack, phase indicator
and combat live? — *see §4*

### 3.2 Hand is peek-and-expand
**Decision.** A slim peeking edge (count / card backs) along the bottom, expanding over the board on
tap or swipe.

**Why.** Maximises battlefield space on a phone-sized landscape screen, at the cost of one gesture to
see your hand.

**Implication.** The hand's empty/loading state (§1.2) must work in the *peeking* form too — the very
first thing the player sees is the peek edge with no cards behind it.

**Open question (3.2a):** does the expanded hand stay open while you act (play a card, answer a
prompt), or collapse on each action? — *pending*

---

**Resolved (3.1a).** The stack does **not** take a centre lane — see §4.1. Both battlefields keep
full height.

---

## 4. Stack, phase and priority

### 4.1 Stack lives in a side panel
**Decision.** A vertical panel down one edge, shared with the phase indicator and the game log.

**Why.** Keeps both battlefields full-height on a short landscape screen. Accepts that the stack is
less spatially intuitive there than "between" the players.

**Data.** `stack` is empty in the common case (verified live) and fills abruptly — the panel must read
sensibly empty, and must not reflow the battlefields when it fills.

**Open question (4.1a):** which edge, and what else shares the panel (log, life totals, phase)? —
*pending*

### 4.2 Priority is stated explicitly, not just implied
**Decision.** A persistent indicator — "Your turn to act" / "Waiting for opponent" — **in addition to**
playable cards carrying a visible affordance.

**Why.** Glow alone is ambiguous in a state that genuinely occurs: **holding priority with nothing
playable**. Observed live after passing priority — `viewerHasPriority=false`, `playable=0` — and the
inverse (priority, empty `playable`) is reachable too. Without an explicit banner the player cannot
tell "not my moment" from "my moment, nothing to do".

**Data.** `viewerHasPriority` (from the viewer's own `PlayerView.hasPriority()`) drives the banner;
`playable` drives the per-card affordance. Both are server facts — **neither is inferred**.

---

## 5. Interaction

### 5.1 Play is tap-to-select, then confirm
**Decision.** First tap raises the card and shows its detail and options; a second tap (or explicit
confirm) commits the action.

**Why.** Guards against misplays on a small screen, and — more importantly — creates the moment where
targeting can begin (§5.2). A single-tap-to-play model has nowhere to put "and now choose targets".

**Data.** A card is actionable only if its id is in `playable`; each entry carries its ability ids
(observed: exactly one per land). The confirm step maps to the reply the prompt expects.

### 5.2 Targeting highlights candidates; tap to pick
**Decision.** When a target prompt arrives, the valid objects are highlighted on the board and picked
by tapping them directly.

**Why.** The simplest faithful mapping of the data, and consistent with §5.1's tap model.

**Data.** Target prompts carry their candidate ids, so **the board never computes validity** — it
highlights exactly what the server offered. Verified live: a required "who goes first" target prompt
appears at game start (in the runs where the toss was won).

---

**Resolved (4.1a).** **Right edge, stack + phase only.** The game log moves to an on-demand overlay
rather than living permanently in the panel — which also contains the HTML-narration problem to a
surface the player opens deliberately, instead of it being always on screen.

### 4.3 Player info is a compact bar per player
**Decision.** A thin persistent strip per player — life prominent, zone counts small — separate from
the battlefield bands.

**Why.** Consistent placement no matter how full a battlefield gets.

**Data.** `players[]` carries `life`, `libraryCount`, `handCount`, `graveyardCount`, `exileCount`,
`wins`/`winsNeeded`, `manaPool`. **Note `exileCount` needs care:** the exile zone list contains one
entry even when nothing is exiled, so "is anything exiled" must come from the cards, not the size.

---

## 6. Prompts

### 6.1 Self-contained prompts are modal — ⚠️ SUPERSEDED by §16.2 (floating controls)

> **Superseded 2026-08-13.** Nothing is modal; prompts are answered by **floating controls over the
> board**, which can be hidden. See §16.2. The *distinction* 6.2 drew — between prompts answered from
> their own content and prompts answered by touching the board — still holds and is why the floating
> model works for both.
**Decision.** Yes/no questions, "choose a number", and "pick from a list" appear as a modal dialog
that blocks the board.

**Why.** Unmissable, and honest to the server's behaviour — the game thread is genuinely blocked
waiting on the answer (0052 confirmed prompts are one-at-a-time, never queued, because upstream blocks).

### 6.2 Board-interactive prompts are NOT modal — the necessary exception
**Decision.** **Targeting** and **mana payment** are presented *on the board*, not in a modal.

**Why.** They are answered by tapping the board itself: targeting picks highlighted candidates (§5.2),
and mana payment taps your own lands (§6.3). A modal would hide the very thing being chosen. Recorded
as an explicit exception so 6.1 is not later applied blindly to every prompt.

**Consequence — the rule for future prompts.** Presentation follows *how the prompt is answered*:
- answered from the prompt's own content → **modal** (ask, get-amount, choose-choice, choose-pile,
  choose-ability);
- answered by touching the board → **on-board**, with the question docked and the board live
  (target, play-mana, play-x-mana, select).

**Data.** The prompt set is closed and typed (0051/0052), so each kind can be routed to the right
presentation at compile time — there is no generic "server asked something" case to guess about.
`GamePrompt.Unrecognised` exists and deliberately has **no answering method**; it must render as a
non-blocking, non-answerable notice rather than a modal the player cannot dismiss.

### 6.3 Mana payment is explicit — tap your own lands
**Decision.** The player taps lands to produce mana; nothing is auto-tapped.

**Why.** Full control, mirroring paper Magic and desktop XMage.

**Implication.** Mana payment becomes one of the most frequent interactions in the game, so it must be
fast and forgiving — and it is board-interactive, hence §6.2.

**Open question (6.3a):** does a mana prompt need a visible "mana produced so far / still required"
readout, and where? — *pending*

---

### 6.4 Casting is ONE act, not a series of dialogs — the organizing principle
**Decision (Pete).** *"You decide your modes, your targets, your costs, then you pay your costs
including alternate costs such as delve or convoke, then you pass priority. I want the UX to feel like
paper decision making."*

This **supersedes** the modal-vs-docked framing of §6.1/§6.2 as the primary rule. Those remain true
about individual prompts, but they are no longer the organizing idea. The organizing idea is:

> A player casting a spell is performing **one continuous act** with several decisions inside it. The
> board must present it that way — not as unrelated dialogs that happen to arrive in sequence.

**Why this maps cleanly onto the data.** XMage's prompts *are* the steps of the paper casting
sequence (CR 601), arriving one at a time because the server blocks on each answer:
choose ability/mode → choose targets → pay costs (mana, and alternative/additional costs such as
delve or convoke) → priority. The client does not orchestrate this; it renders a sequence the server
is already walking.

**What this requires of the board:**
1. **Persistent context.** Throughout the sequence the player can see *what they are casting* and
   *what they have already chosen* — the spell does not vanish behind each new question.
2. **Visible progress.** Which decision is being made now, and what remains.
3. **Cost visibility before commitment.** What this will cost, including alternative costs, before mana
   is paid — the paper act of working out whether you can afford it.
4. **Backing out where the rules allow it** (see below).

**Data — backing out is supported, per step.** The protocol carries it already:
`TargetPrompt.required = false` means the choice may be declined; the answer is
`SendPlayerBoolean(false)` ("done/cancel"), also legitimate once enough targets are chosen.
`SelectPrompt`/`PlayManaPrompt` additionally carry a *special* arm (`GamePromptOptions.SPECIAL_BUTTON`).

**Constraint to respect:** this is a **per-prompt** decline, not a client-side "undo the whole cast".
Whether declining a step rewinds the entire cast is the **server's** behaviour, not ours to invent —
and the board must not imply a rewind it cannot deliver.

**Resolved (6.4a) — the server fully rewinds. "Cancel" can be offered honestly.**

Verified by live experiment against the reference server (2026-08-13), deck 20× `Plains` (`M11` #230)
+ 40× `Squadron Hawk` (`M11` #33), so casting genuinely required paying `{1}{W}`:

| Moment | hand | stack | battlefield | prompt |
|---|---|---|---|---|
| before casting | 7 | 0 | 2 Plains | Select |
| after `playObject(Hawk)` | **6** | **1** | 2 Plains | **PlayMana** |
| after declining the mana step | **7** | **0** | 2 Plains | Select |

Land tap state after the decline: `[(Plains, false), (Plains, false)]` — **never tapped**.

So beginning a cast moves the card to the stack and asks for mana; declining returns it to hand,
clears the stack, leaves mana unspent, and hands priority back. **The paper feel of §6.4 is
deliverable** — the board may offer "cancel" mid-cast without lying, and the player really can change
their mind right up until they pay.

**Scope of what was tested (do not over-claim):** the decline was at the **mana-payment** step. Other
steps (declining a *target* mid-cast, or an alternative-cost prompt such as delve/convoke) were not
exercised and should be confirmed the same way before the cancel affordance is offered on those steps.
The mechanism is the same — `SendPlayerBoolean(false)` where `required = false` — but the server's
rollback behaviour per step is its own, not ours to assume.

---

## 7. Combat

### 7.1 Same tap model, combat-specific highlighting
**Decision.** Attackers and blockers use the established "tap the highlighted things" model, with
highlighting tuned for combat. No separate combat mode in the first playable board.

**Why.** Nothing new for the player to learn, and consistent with §5.2. Accepts that a large combat is
a lot of small taps — revisit once real games show where it hurts.

**Data.** `combat` is present in the snapshot (empty outside combat, verified live).

> **Refined by §7.4:** combat is two separate assignment problems, not one. §7.1's tap model survives;
> its framing of combat as a single mode does not.

### 7.2 What the server actually asks for attackers — measured, 2026-08-14

§7.1 was written before any run had reached combat (§17 records `combatSteps=0`). `CombatProbeIT`
reached it deliberately, and this is what arrives:

```
[app] step=DeclareAttackers turn=5 prompt=Select msg='Select attackers'
      options.text = [specialButton, queryType]
      options.ids  = {possibleAttackers=2}  ->  [Goblin Token, Goblin Token]
      playable     = 0
```

**Findings.**
- Declaring attackers is a **`GamePrompt.Select`** whose message is the literal `Select attackers` —
  the same prompt kind as ordinary priority, distinguished by its options rather than its type.
- **`possibleAttackers` is populated**, and the bridge's `optionsView()` already carries it through:
  that mapper turns *any* collection-valued option into an id list, so the data path was complete
  before anyone looked. Nothing needs to be added to `:protocol` or `:bridge` to declare attackers.
- **`playable` is empty during the declaration.** The attackers come *only* from `possibleAttackers`,
  so a client that derives its affordances from `playable` — as `controlsFor` does today — sees
  nothing to offer.
- The server supplies a **`specialButton`**, which the board already maps and renders as **"All
  attack"**. It works: pressing it declares the whole team.

**What today's board does with it:** projects it as ordinary priority controls —
`buttons=[Pass priority, All attack]`, `pickable=0`. So the player can attack with **everything or
nothing**, and cannot choose *which* creatures attack. The ids are in the projection and there is no
surface for them — the same shape as the defect 0057 shipped and had caught on a device.

### 7.3 What the server asks for blockers — measured, 2026-08-14

```
[opp] step=DeclareAttackers turn=8 prompt=Select msg='Select attackers'
      options.ids = {possibleAttackers=2}
[app] step=DeclareBlockers turn=8 prompt=Select msg='Select blockers'
      options.text = [queryType]          <- no specialButton
      options.ids  = {possibleBlockers=2} -> [Goblin Token, Goblin Token]
      playable     = 0
```

**Findings.**
- Blocking mirrors attacking: a **`GamePrompt.Select`** with the literal message `Select blockers`,
  carrying **`possibleBlockers`**. `playable` is empty here too.
- **There is no `specialButton` for blocking.** Attacking gets "All attack"; blocking gets no
  equivalent shortcut, so blocking is inherently per-creature.
- Today's board offers `[Pass priority]` with `pickable=0` — **the viewer cannot block at all.**

**Combat groups are per-attacker.** Two attackers produced **two** groups, not one:

```
combat groups=2
group defender='app_be158b' attackers=1 blockers=0
group defender='app_be158b' attackers=1 blockers=0
```

So `CombatGroup` reads *"against this defender, this attacker, blocked by these"* — the defender is
repeated per group rather than grouping attackers under one defender.

**Still unmeasured — do not specify it until it has been seen.** Whether picking a blocker triggers a
**follow-up prompt asking which attacker it blocks**. The run's budget expired at the blocking prompt
without answering it. This is the crux of the pairing problem below, so the combat story must measure it
before designing the interaction.

### 7.4 Combat is **two** assignment problems, never both at once

**Decision (Pete).** §7.1 treated combat as one thing. It is two, and they never belong to the same
player at the same moment:

| Role | The assignment | Server shape |
|---|---|---|
| **Attacking player** | each attacker → what it attacks (**player, planeswalker, or battle**) | `Select attackers` + `possibleAttackers`, **with** `specialButton` |
| **Blocking player** | each blocker → the attacker it blocks | `Select blockers` + `possibleBlockers`, **no** shortcut |

> *"the attacking player assigns attackers to targets, player or battle or Planeswalker, etc. the
> blocker assigns blockers to attackers. we need to consider how best to represent each of these
> situations as they never occur for the same player at the same time"*

**Why it matters.** The board is only ever in one of these modes, so each can be designed for its own
job rather than compromised into a shared "combat view". It also matches the data: `CombatGroup` is
**per-attacker** (§7.3), reading *"this attacker, against this defender, blocked by these"*.

**Note on defenders.** The opposing player is **not** the only legal defender — planeswalkers and
battles are too, in ordinary 1v1. Any design that assumes "attack = point at the opponent" is wrong.

### 7.5 Declaration: tap the creature, and ask only when the choice is real

**Decision (Pete).** Both directions use the same principle — **tap the creature; the board asks for
the pairing only when it is genuinely ambiguous**:

- **Attacking:** tapping a creature declares it as an attacker. If more than one legal defender exists,
  the board then asks which one.
- **Blocking:** tapping a creature declares it as a blocker. If more than one attacker could be
  blocked, the board then asks which.

**Why.** One tap in the common case, and a second only where there is a real decision to make. Nothing
is invented for the player to learn — it is §5.2's tap model with a conditional follow-up.

**"All attack" is kept, with a confirmation (Pete).** The server supplies the shortcut and it is proven
to work live (§7.2), so it stays — but it commits the whole team, so it routes through the same
confirm step as targeting (§16.4). There is no equivalent for blocking, and none should be invented.

**✅ The dependency is resolved: upstream already behaves exactly this way.** Read from
`mage.player.human.HumanPlayer` in `mage-player-human-1.4.60.jar` — the plugin the pinned reference
server actually loads. Both directions share one shape: *build the set of legal pairings; if it has
exactly one member, assign it silently; otherwise ask.*

| | Method | One candidate | More than one |
|---|---|---|---|
| **Attacking** | `selectDefender(Set, attackerId, game)` | `declareAttacker(attacker, theOnlyDefender, …)` and returns — **no prompt** | builds `TargetDefender` and asks |
| **Blocking** | `selectBlockers(…)` | takes the single attacker from the set and assigns it | asks with the literal message **`Select attacker to block`** |

The blocking set is not "every attacker" — it is filtered by `CombatGroup.canBlock(permanent, game)`
first, so the question is only asked when this blocker genuinely *could* block more than one.

**What this means for the story.** §7.5's design costs nothing: the board taps the creature, and when
the server asks a pairing question it renders it. **No policy seam is needed** — the "only when
ambiguous" behaviour is upstream's, not ours, so nothing answers a one-option question on the player's
behalf. Both follow-ups arrive through `fireSelectTargetEvent`, i.e. as ordinary **`GAME_TARGET`
prompts**, which 0057's targeting machinery already answers with `chooseTarget`.

**Two related findings from the same read:**
- **`selectDefenderForAllAttack(Set, game)`** backs the "All attack" special button: it asks **once**
  for a defender for the whole team, via `TargetDefender`. So the shortcut is not "attack the face" —
  with several defenders it still asks, once, which supports keeping it behind §16.4's confirmation.
- **`getCreaturesForcedToAttack()`** is consulted first: a creature forced to attack has a
  **restricted defender set**, so the candidates for it may be narrower than the legal defenders
  generally. Never widen the server's set.

### 7.6 The harness stall, diagnosed

`docs/live-test-decklists.md` recorded a stall as **unsolved** — *"after certain answers the game stops
pushing to us altogether"*. It is not the server going quiet. Upstream asks

> `You still have mana in your mana pool and it will be lost. Pass anyway?`

and answering it with the **negative** arm hands priority straight back with the mana still floating,
so an auto-responder that always declines loops forever. It is identifiable **without parsing the
prose**: this is the only `Ask` that carries **`autoAnswerMessage`** in its options.

Two further stalls sit behind it, both worth knowing before writing another live harness:
- A mana prompt does **not** carry `possibleTargets`. Its sources are in **`playable`** — the same
  place `controlsFor` reads them.
- An auto-responder that plays `playable.first()` will cast a spell before playing its land, then
  strand itself mid-payment and silently retry the cast forever. **Play lands first**; `manaCost` is
  null for lands (§0), which is the only signal needed.

---

## 8. Game end

### 8.1 Result screen using the server's own line
**Decision.** A clear end screen showing the server's sentence verbatim, plus Leave / Rematch.

**Why.** Honest: **we never claim a winner we cannot identify.**

**Data.** `GAME_OVER` carries a single prose line and **no structured result** — there is no winner id
(0052). Inferring a win/loss from life totals or by parsing the sentence was explicitly rejected as
building feel on inference the data does not support.

---

### 6.5 Mana payment — the server already proposes the solution
**Question raised (Pete).** The UI should propose a tapping solution the player can accept, updating
continuously as they tap manually, with special handling for restricted sources (Cavern of Souls) and
conditional bonuses.

**Finding — the server already does this, and we neither need nor are able to reimplement it.**

- `HumanPlayer` (server-side) computes the useable mana abilities, then calls
  `ManaUtil.tryToAutoPay(unpaid, useableAbilities)` — *"eliminates other abilities if one fits
  perfectly"* — **before** prompting. The `PlayMana` prompt we receive is therefore the **ambiguous
  residue after server-side narrowing**, not a raw "pay this somehow".
- It **deliberately skips auto-pay when the spell cares about mana colour**
  (`caresAboutManaColor`, added upstream for issue #9070) — precisely the restricted/conditional cases
  that motivated the question. The server declines to guess exactly where guessing would be wrong.

**Why we cannot add a bridge query for it (asked and answered).** `ManaUtil` lives in `Mage/`, the
game **engine inside the server process**, and takes live engine objects (`ManaCost`,
`ActivatedManaAbilityImpl`) that are not serializable view types. The bridge wraps `SessionImpl` —
XMage's **client** API — which exposes no such call. Surfacing it would mean patching the server, not
the bridge. Moot, since the server already applies it.

**Decision.** The board **renders the choice the server offers** and does not compute proposals.
Per Pete: *"if the server doesn't support auto tap calculations, we don't need to implement them at
this time."* It does support them — server-side — so nothing client-side is needed.

**Still open (6.5a):** paying mana *before* casting (floating mana into the pool deliberately) is a
distinct flow from paying *when prompted*. `manaPool` is in the snapshot, and mana abilities appear in
`playable`, so it is reachable — but the interaction has not been designed. — *pending*

---

## 9. Following the opponent's turn

### 9.1 The stack is the mechanism; priority is manual
**Decision (Pete).** The player watches the **stack** and decides when to pass priority. Everything is
manual for now; **auto-passing is explicitly deferred** to a later discussion.

**Why this resolves the push-only concern.** No event-toast or recent-events strip is needed as a
first cut: the stack shows what is happening, and because the player must actively pass priority they
are present at each decision point rather than needing to reconstruct missed events.

**Implication.** Manual priority means the player is prompted often, so passing must be fast and
unambiguous — it is the single most repeated interaction in a game.

---

## 10. Reconnecting mid-game

### 10.1 The bridge should cache board state and serve it — a bridge change, not a UI workaround
**Decision (Pete).** *"The bridge needs to support the client reconnecting and the bridge should act
as a proxy for the board state making it queryable."*

**Why this is the right layer.** The push-only constraint (§0) is an *upstream* limitation: XMage has
no "get game" verb, and re-joining a running game does not resync. But **the bridge sees every
snapshot**, and state is a full snapshot rather than a delta — so the bridge can hold the latest one
and answer a query with it. That converts a UI problem ("what do we show while blind?") into a data
guarantee ("ask and you will be told"), and it removes the stale-board dilemma entirely.

**Consequence.** The board no longer needs a "stale, may be out of date" mode as its primary recovery
story: on reconnect it asks the bridge for current state.

**Scope note.** This is a **`:bridge` + `:protocol` story**, ahead of the board UI stories, and it is
new work beyond 0051/0052 — the game read verb does not exist yet. It also pairs with story 0023's
park/resume: the parked session already keeps receiving snapshots, so the cache has fresh material.

---

## 11. Card inspection and known information

### 11.1 Detail on first tap or long-press; playable from the detail view
**Decision (Pete).** A card's detail opens on **first tap or long-press**. If the card is playable it
can be **played directly from the detail view**. **Any tap elsewhere closes it.**

**Why.** Inspecting and acting are the same gesture path, so reading a card never costs a mode switch
— and the detail view becomes the natural place for the §5.1 confirm step.

### 11.2 The player can browse any information they are entitled to see
**Decision (Pete).** A way to view **any accessible data**: known cards in hand, cards in any known
zone, face-down cards in exile that the player knows, and cards placed at specific library positions.

**Why.** Magic generates a great deal of hidden-but-known information (scry/surveil placement, exiled
face-down cards you may play, revealed zones). A player who cannot review it is playing with worse
information than at a table.

**Data — reachable, but must be checked field by field.** The snapshot carries `exile`, `revealed`,
`lookedAt` and `companion` zones alongside the battlefields and hand. **Caveat already known:** the
exile zone list contains one entry even when nothing is exiled, so presence must be judged by cards,
not list size. **Library position knowledge (post-scry ordering) has not been confirmed present** —
verify before promising it.

**Resolved (11.2a).** **Both.** A single browser covering every zone the player is entitled to see,
*and* zone indicators on the board that open it **already filtered to that zone**. Direct where the
zone has a board presence (graveyard, exile), complete for the zones that do not (revealed, looked-at)
— **but not library placement**, which does not exist (see §11.3).

### 11.3 What is actually knowable — verified 2026-08-13

Investigated in the XMage source and confirmed live. **The assumption that "XMage does all of this"
does not hold**; three of the four capabilities differ from expectation.

| Capability | Verdict | Evidence |
|---|---|---|
| **Scry / look at top cards** | ✅ **Fully available, with card identity** | Live: the decision arrives as an ordinary **Target prompt carrying the real card** — `msg='Select up to one card to PUT on the BOTTOM of your library (Scry)' cards=[Thoughtseize] required=false`. Seen repeatedly across turns with different cards. It comes through the prompt channel we **already map**. |
| **Tuck / "X cards down" library position** | ❌ **No such concept upstream** | No known-library or library-order tracking on the player state; the snapshot carries only `libraryCount`. XMage expects the player to remember, as in paper. |
| **Known opponent-hand cards, tracked individually and updated as they leave** | ❌ **Not available** | `GameView.opponentHands` is declared with a getter and **never written to anywhere in the codebase** — the only two references are the declaration and the getter. Permanently an empty map. |
| **Reveal windows (e.g. Thoughtseize)** | ⚠️ **Channel exists and is mapped; contents unobserved** | `revealed` is populated upstream and mapped by 0051. Thoughtseize uses `DiscardCardYouChooseTargetEffect`, which shows the hand **at resolution** — transient, not durable. Two live attempts did not get the spell to resolve (the first rolled back at the mana step; the second ran out of observation budget just after casting), so the window's contents remain unconfirmed. |

**Consequences.**
- **Scry display is deliverable now**, with no new mapping.
- **"Cards put into specific locations in a library" is not deliverable** — drop it, or accept it as
  *our* bookkeeping rather than the server's.
- **Individually tracked opponent-hand knowledge is not deliverable from XMage.** Building it means a
  client-side knowledge model — remembering ids from a reveal window and following them across
  snapshots — inventing state upstream does not maintain, and wrong the moment a card moves through a
  zone we cannot observe.

**Genuine mapping gap, worth fixing regardless:** `lookedAt` and `companion` are populated upstream and
were **not** mapped by 0051 — a `:protocol` + `:bridge` fix, independent of the board.

---

## 12. Match flow

### 12.1 Between games: a full sideboard screen
**Decision (Pete).** A dedicated sideboard screen for swapping cards between deck and sideboard,
running on the server's timer.

**Data.** A match is best-of-N (`winsNeeded`/`wins` are in the snapshot), and story 0036 already maps
the server's **`ConstructPrompt`/`SideboardPrompt`** — so the trigger and the deck payload exist; this
is a UI to build, not a protocol gap.

**Note.** The construction surface is close to the deck builder (0035) but not the same thing: it is
timed, match-scoped, and constrained to the registered pool. Reusing the builder was considered and
rejected in favour of a purpose-built screen.

### 12.2 Concede and quit are separate actions
**Decision (Pete).** **Concede** (this game — the opponent wins it) and **quit match** (leave the whole
match) are distinct, explicit actions.

**Why.** It mirrors upstream exactly: `SessionImpl` exposes `concedeGame`-style player actions and
`quitMatch` as separate verbs (both wired in 0051/0052). Collapsing them would misrepresent what
actually happens to the match record.

---

## 13. Spectating — deferred

**Decision (Pete).** **Not in the first playable board.** The lobby's Watch action stays disabled until
the board is proven for players.

**Data note for later.** The capability already exists end-to-end — `watchGame` is relayed (0051) and
`isSpectator` is carried in `GameState` (0052) — so this is a scope choice, not a missing foundation.
A spectator board is the same layout minus hands, prompts and playable affordances.

---

---

## 14. Priority, stops and auto-pass

### 14.1 Everything explicit now; stops and auto-pass are a named future feature
**Decision (Pete).** All priority handling is **explicit and manual** in the first playable board.
*"In the future I want to support similar functions to Arena and MTGO with manual set stops and
configurable auto pass."*

**Design constraint this creates (important).** Auto-pass is **named, not vague** — so the board must
not be built in a way that precludes it. Concretely: passing priority must remain a **policy decision
made in one place**, not logic scattered through the UI. When stops arrive, they change *when the app
answers a priority prompt*, and nothing else should have to change.

**⚠️ Trap for whoever writes auto-pass (found during 0061).** `PassPolicy` is consulted on **every
`GamePrompt.Select`** — and after 0061 a **combat declaration is also a `Select`** (§7.2/§7.3). A
policy that returns `PassImmediately` on one would send `passPriority`, which upstream reads as the
declaration's *done* arm: the app would **silently decline to attack or block**. Harmless today,
because `ManualPassPolicy` never passes. So an auto-pass policy **must** discriminate the prompt — the
cheapest honest test is `CombatRole.of(prompt.options) != null` — and declare-attackers/blockers are
exactly the steps players most want a **stop** at anyway.

### 14.2 Floating mana is allowed; its interaction with auto-pass is deferred
**Decision (Pete).** *"You can tap land at any time; passing priority with mana in the pool requires
no special handling for this increment. When we implement pass, this will need to be augmented."*

**What this means now.** Lands are tappable whenever the server permits it (mana abilities appear in
`playable`, so this already works), `manaPool` is displayed, and unspent mana needs no warnings or
guards in the first version.

**Flagged for the auto-pass work (14.1).** Auto-passing with mana floating is exactly where an
unattended pass can cost a player their mana — so the auto-pass feature must handle it deliberately.
Recorded here so the requirement is not lost between increments.

### 14.3 The real upstream model — traced from `Mage.Client`, not guessed

§14.1 named "similar functions to Arena and MTGO" but did not specify a shape, and `PassPolicy`'s own
KDoc (`feature/game/.../board/PassPolicy.kt`) already correctly guessed that the eventual feature would
not fit its one-shot `decide(state): AskThePlayer | PassImmediately` seam — *"the standing 'pass
until …' verbs are a different thing entirely — they are player actions, not answers."* This section
replaces the guess with what desktop XMage **actually does**, read from `../mage`
(`Mage.Client/src/main/java/mage/client/game/GamePanel.java` and
`Mage/src/main/java/mage/players/net/{UserSkipPrioritySteps,SkipPrioritySteps}.java`). It is a **client-
local UX feature with no server counterpart** — `grep`ing `Mage.Common`/`Mage`/`Mage.Server` for
auto-pass turns up nothing outside `Mage.Client` itself. The server sends the identical `Select` prompt
every time regardless; whether the client shows it to the human or answers it silently is entirely our
decision.

**It is not one policy — it is two separate mechanisms.**

1. **Six explicit "skip to X" player actions** (`GamePanel.skipButtonsList`, each bound to a hotkey),
   not a standing toggle. Pressing one starts auto-passing *from now*, repeatedly, until its own stop
   condition fires — then control returns to the player and skipping stops:

   | Skip action | Stops when |
   |---|---|
   | **Skip to next turn** | any player's turn begins |
   | **Skip to [opponent's / next] end step** | reaches the chosen end-of-turn step (toggle: specifically the opponent's, or whichever comes next) |
   | **Skip to [opponent's / next] main phase** | reaches the chosen main phase (same toggle) |
   | **Skip to your turn** | your own turn begins |
   | **Skip until the stack resolves** | the stack is empty — **or**, on its own toggle, stops early the moment something *new* is added to the stack |
   | **Skip to the end step before your turn** | the end step immediately preceding your next turn (the last window to act before it) |

2. **Persistent per-user "stop" settings** (`UserSkipPrioritySteps`) that apply as override conditions
   **during any skip**, regardless of which skip action started it:
   - **Per-phase-step, tracked separately for your turn vs. the opponent's turn**
     (`SkipPrioritySteps`: upkeep, draw, main1, beforeCombat, endOfCombat, main2, endOfTurn) — default
     is to stop on **your own** main phases (`main1`/`main2 = true`) and nowhere else by default; the
     opponent-turn set defaults the same shape but is configured independently.
   - **Global flags**, independent of whose turn it is: `stopOnDeclareAttackers` (default **true**),
     `stopOnDeclareBlockersWithAnyPermanents` (default **true**),
     `stopOnDeclareBlockersWithZeroPermanents` (default **false** — don't interrupt a skip just because
     you have nothing to block with), `stopOnAllMainPhases` (true), `stopOnAllEndPhases` (true),
     `stopOnStackNewObjects` (true — a skip **always** breaks the moment the opponent puts something new
     on the stack, independent of the specific skip action's own stack-related toggle above).

3. **"Hold priority"** (`GamePanel.holdPriority`, Ctrl/Cmd-click or a toggle) is a **third, separate**
   mechanism: after *you* take an action (cast, activate), holding priority means the app does **not**
   auto-pass on your behalf afterward, so you can chain a second action before priority moves on. This
   is orthogonal to the skip/stop settings above, which govern *incoming* prompts, not what happens
   right after your own action.

**Consequence for our design.** `PassPolicy`'s one-shot `decide()` seam is real and correct for the
*stop-condition* checks (both the global flags and the per-phase-step settings reduce to "does this
`Select` match a condition the player cares about right now" — the KDoc's design already anticipated
this). But it cannot express the **six skip actions** on its own: those are stateful player-triggered
commands ("keep passing until…"), not a predicate evaluated once per prompt. The skip actions need
their own state (which skip is active, if any) that arms `PassPolicy` to answer `PassImmediately`
repeatedly and disarms itself the moment a stop condition (global, per-phase, or "new object on stack")
is met — exactly the "player actions, not answers" distinction the KDoc already drew. Hold-priority is
a third, independent piece of state consulted after *our own* `playObject`/ability activation, not by
`PassPolicy` at all.

**Combat's own trap (§14.1) is upstream's own model, not an edge case we invented.**
`stopOnDeclareAttackers`/`stopOnDeclareBlockersWith*` existing as **dedicated** settings, separate from
the ordinary phase-step list, confirms declare-attackers/blockers need their own stop handling — the
same conclusion §14.1 already reached from 0061, now corroborated by upstream treating it the same way.

**Not yet decided (deliberately, for a later pass):** which of these six skip actions and which stop
settings ship in v1 vs. later, and how they're surfaced as UI on a phone (desktop exposes seven buttons
+ a settings dialog with per-phase checkboxes ×2 turns — that is Swing-desktop-shaped, and porting it
verbatim would be exactly the "don't port the Desktop UI" mistake `AGENTS.md` warns against). This
section only fixes the *mechanism* (what upstream actually does and why our seam is the right shape);
the touch-first presentation of it is a separate design pass.

---

## 15. The match-start interstitial

### 15.1 Shows opponent, format, and the toss result
**Decision (Pete).** The interstitial (§1.1) names both players and the format/game type, then shows
the coin-toss outcome as it resolves.

**Why.** Turns dead server-setup time into the game's opening beat, and gives the toss (§1.3) a
natural home before the board appears.

**Data.** Opponent name and game type come from the table the match started from (Epic 7); the toss
result is the structural signal from §1.3 — *asked* means we won, *not asked* means we lost — with no
prose parsing.

---

## Summary of what still needs deciding

Everything below is **not yet designed** and is deliberately out of the first playable board:

- **Spectating** (§13) — capability exists end to end; scope choice only.
- **Stops / configurable auto-pass** (§14.1/§14.3) — the *mechanism* is now grounded from upstream
  source (six skip actions + persistent stop settings + hold-priority, §14.3); what ships in v1 and its
  touch-first presentation are still undesigned.
- **Auto-pass with floating mana** (§14.2) — must be handled when the skip/stop mechanism (§14.3) is
  built.
- **Library-position knowledge** (§11.2) — **resolved, not deliverable** (§11.3): there is no
  known-library or library-order tracking upstream. Dropped, not pending.
- **Declining a mode or an alternative-cost prompt** (§6.4a/§16.5a) — rollback is confirmed for both
  the **mana** step and the **target** step (§17.1); declining a **mode** choice or an alternative
  cost such as delve/convoke (§18) remains unverified live. Verify those specifically before offering
  cancel there.
- **Alternative/additional costs — convoke, delve** (§18) — **a confirmed defect, not just an open
  question**: `specialActionsAvailable` is mapped end to end and never read by the board, so these
  costs currently have no way to be paid at all. The fix needs no new prompt kind (§18.2) — only a
  board affordance wired to the existing state field, then live verification.
- **Combat damage among multiple blockers, trample, first/double strike** (§19) — **not actually an
  open design question**: traced to the same `GetMultiAmount` prompt already proven live for Forked
  Bolt's damage division (§17). Needs a short live combat probe to confirm, not new design.

## Work this design implies beyond the board itself

- **A bridge game-state cache + query verb** (§10.1) — new `:bridge` + `:protocol` work, ahead of the
  board UI, so a reconnecting client can ask for current state instead of waiting for a push.
- **A sideboard screen** (§12.1) — a purpose-built, timed, match-scoped surface; the
  `ConstructPrompt`/`SideboardPrompt` triggers already exist from story 0036. No story number is
  assigned to it yet — it is the one piece of "play a full match" (rather than a single game) with no
  story at all today.
- **Stops / configurable auto-pass** (§14.3) — a board-side feature; no protocol/bridge work, since the
  server sends the identical priority prompt either way.
- **Special-action affordance for convoke/delve** (§18) — a small board-side fix: wire
  `specialActionsAvailable` to a real control. No protocol/bridge work expected.

---

# 16. Major revision — 2026-08-13

A deliberate change of direction from Pete, superseding two earlier decisions and adding a
cancellation model. Recorded as a revision rather than an edit so the reasoning that was replaced
stays legible.

## 16.1 Portrait, like every other screen (supersedes §2.1)

**Decision.** The board is **portrait**.

**Why this is better than the original call.** §2.1 chose landscape to mirror a physical table, and
recorded the cost honestly: it would have made the board *the only landscape surface in the product*,
turning "enter a game" into an orientation change. Portrait removes that seam entirely — the game is
just another screen.

**What it costs.** Vertical space is now the scarce resource, with two battlefields, a hand and the
stack competing for it. §16.2's hideable controls and §3.2's peek-and-expand hand both become more
load-bearing: the board must be able to get *out of its own way*.

**Still true from §3.1:** opponent's battlefield above, yours below — that reads even better in
portrait, and seats must still be located via `isViewer`, never by index.

## 16.2 Floating controls, never modals (supersedes §6.1)

**Decision.** Prompts are answered with **floating buttons over the board**. Nothing blocks the board.

**Why.** §6.1's modal was chosen because the server genuinely blocks on an answer — but blocking the
*player's view* is not the same as the server blocking, and §6.2 had already carved out an exception
for the two prompt kinds that need the board visible. Floating controls make that exception the rule
and remove the split.

**§6.2's distinction survives and explains why this works:** prompts answered *from their own content*
(ask, get-amount, choose-choice, choose-ability, choose-pile) and prompts answered *by touching the
board* (target, play-mana, select) can now share one presentation, because the board is never hidden
either way.

## 16.3 A control-visibility toggle

**Decision.** A single control that **hides and shows the floating controls**, so the player can see
the unobstructed board, open zone views, and inspect freely.

**Why.** Portrait plus floating controls means the board is often partly covered. The player must be
able to look at the game state itself without answering anything.

**Constraint (important).** Hiding the controls must **never** hide the fact that the server is waiting
on the player. The §4.2 priority indicator, or an equivalent, must survive the toggle — otherwise a
hidden control set becomes an invisible stall, and the game appears frozen.

## 16.4 Targeting confirms before it submits

**Decision.** When choosing targets, the player picks **all** targets, then **confirms**, and only then
is the choice submitted.

**How this maps to the protocol.** The server asks for targets **one at a time** and blocks on each
answer, so "confirm then submit" means the client **holds the picks locally and sends them in order on
confirm**. That is legitimate — the server simply waits — and gives the player a real review step.

**Risk to verify before building (16.4a).** After each target the server may re-prompt with a
**narrowed candidate list**. A client that pre-selects several targets against the *first* candidate
list could assemble a combination the server would reject on the second pick. Before committing to
batch-then-submit, confirm with a genuinely multi-target spell whether the candidate set changes
between picks. If it does, the confirm step must validate incrementally (send each pick as it is made,
but delay the final "done" until the player confirms) rather than batching blindly.

## 16.5 Cancel before the spell is committed, and cascading rollback

**Decision.** After modes are chosen, the player must be able to **cancel before the spell is
committed**, and there must be **cascading cancel controls that roll the casting process back** step by
step.

**What the data supports — verified.** Declining a step rewinds: proven live (§6.4a) that beginning a
cast and declining the mana step returns the card to hand, clears the stack and leaves lands untapped.
The mechanism is `SendPlayerBoolean(false)` where the prompt's `required` is false.

**One reality to design around.** `playObject` puts the spell on the **stack immediately**, *then* asks
for modes/targets/costs — which is what the rules actually say (CR 601: the spell moves to the stack as
step 1, and the whole action is rewound if it cannot be completed). So "cancel before it goes on the
stack" is, mechanically, "cancel before the cast **completes**, and the server un-does it". The player
experience is identical — the card comes back — but the UI should not claim the spell has not yet been
cast when the stack briefly holds it.

**Open (16.5a):** cascading rollback assumes a cancel is available at **every** step. Only the **mana**
step is proven. Whether declining a **mode** or a **target** mid-cast rewinds the same way is
**unverified** — and each step's `required` flag decides whether a cancel can even be offered there.
Verify per step before promising a cascading rollback that the server may not honour at every level.

## 16.6 What this revision does not change

Unaffected: the interstitial and coin toss (§1, §15), opponent-above/you-below (§3.1), peek-and-expand
hand (§3.2), the side panel for stack and phase (§4.1 — though portrait will change its shape),
explicit priority (§4.2), tap-to-select-then-confirm (§5.1), highlight-and-tap targeting (§5.2),
casting as one continuous act (§6.4), server-side mana narrowing (§6.5), manual priority with auto-pass
as a named future feature (§9, §14), the bridge as the reconnect authority (§10, story 0054), inspection
and the known-information browser (§11), match flow and separate concede/quit (§12), spectating deferred
(§13), and everything in §11.3 about what is and is not knowable.

---

# 17. Target-cancel experiment — 2026-08-13

Two human clients, deck 20× `Mountain` (`10E` #376) / 20× `Forked Bolt` (`ROE` #146) / 20× `Dragon
Fodder` (`ALA` #97). Dragon Fodder makes two Goblins to shoot at; Forked Bolt is genuinely
multi-target (*2 damage divided among one or two targets*). Both seats driven by the test, so **both
views** are observable.

```
CAST Forked Bolt (goblins on board=2)      hand=6 stack=0
  [B sees @before-cast]                    stack=0
T1 'Select targets (selected 0 of 2, min 1) to divide 2 damage'
        candidates=4  required=false     | A: stack=1 hand=5
  [B sees @target-prompt-1]                stack=1 top=Forked Bolt
  -> DECLINE
  after decline: A  stack=0 hand=6 prompt=Select untappedMountains=2
  [B sees @after-decline]                  stack=1 top=Forked Bolt      <-- STALE
```

## 17.1 Resolved (16.5a) — declining a TARGET rewinds, exactly like the mana step
Casting put the Bolt on the stack (`hand 6→5`, `stack 0→1`); declining the target prompt returned it
to hand (`hand=6`), cleared the stack (`stack=0`) and left **both Mountains untapped**. So the
**cascading cancel of §16.5 is deliverable at the target step**, not only at mana. Rollback is the
server's, and it is complete.

## 17.2 Resolved (16.4a) — the server already tracks selection progress
The prompt is **not** a bare "pick one". It reads *"Select targets (**selected 0 of 2, min 1**) to
divide 2 damage"* with `candidates=4` and `required=false`. XMage maintains the running selection and
**re-prompts with an updated count** after each pick.

**Consequence for §16.4's confirm-before-submit.** The server is already doing incremental validation,
so the honest shape is *pick → send → prompt updates → pick → send → **player confirms** → send done*
(`SendPlayerBoolean(false)`, which the `min 1` / `required=false` wording shows is legitimate once
enough are chosen). **Do not batch picks client-side against the first candidate list** — the count and
candidates move as you go, and a batched submission would be validated against a list that no longer
applies. The "confirm" the player sees is the final *done*, not a client-side accumulator.

## 17.3 NEW FINDING — the opponent's view goes stale on a cancel
`B` saw `stack=1 top=Forked Bolt` while A was choosing targets — which is **rules-correct**: in paper
the spell is on the stack and your opponent can see it. But after A declined, **B still showed
`stack=1 top=Forked Bolt`**. The rewind was not pushed to the opponent; B's board kept a spell that no
longer exists until some later snapshot corrects it.

**Why it matters.** A player looking at a phantom spell on the stack may hold up a response, or play
around something that was never cast. It is a real, visible wrong state on the *opponent's* board.

**Open (17.3a):** is this a *transient* gap that the next push corrects within a beat, or does B stay
wrong until an unrelated game event? The experiment sampled B only twice and cannot tell. **Verify
before building**, because the answer changes the design: if it self-corrects quickly, nothing is
needed; if it persists, the board should not present the opponent's stack as authoritative between
pushes — and story 0054's cached read gives a way to reconcile it.

## 17.4 Resolved (17.3a) — the cancel really is not pushed to the opponent

Pete's alternative reading of §17.3 was that a cancel followed by a *second* Forked Bolt would leave
B correct at all times, since both spells look identical. **Tested with stack card ids, and excluded:**

```
CAST Dragon Fodder
  A stack=[Dragon Fodder#596b18]   ||  B stack=[Dragon Fodder#596b18]   <- in sync
  A stack=[]                       ||  B stack=[]                       <- resolves; both clear

CAST Forked Bolt #1 (to be cancelled)
  before-cancel | A stack=[Forked Bolt#572fd9] || B stack=[Forked Bolt#572fd9]
  after-cancel  | A stack=[]                   || B stack=[Forked Bolt#572fd9]   <- SAME id
```

**The id is identical (`#572fd9`)**, so B is not showing a different, later spell — it is holding the
very object A rewound.

**The scope of the defect is now precise, and small:**
- **Normal play is correct.** A cast appears on both boards with the same id, and clears from both when
  it resolves. B tracked nine turns of play faithfully.
- **Only the rewind is missed.** The server does not push the opponent a snapshot when a cast is
  cancelled, so B keeps a spell that no longer exists.

**Design consequence.** The board must not treat the opponent's stack as authoritative between pushes.
Story 0054's cached read is the natural reconciliation: on any state the player is about to act on,
the current snapshot can be requested rather than assumed. Worth deciding whether the board reconciles
on a timer, on gaining priority, or only when the player looks — but it must not present a phantom
spell as a reason to hold up a response.

**Not covered by this run:** combat declaration and blocks were never reached (`combatSteps=0`) — the
loop spent nine turns drawing lands before it had creatures, then ended at the cancel. Attacks, blocks
and the after-cancel *different* spell remain unexercised, and are better driven by real UI logic than
by a scripted probe.

---

## 18. Alternative and additional costs (convoke, delve, and anything else routed through `SpecialAction`)

### 18.1 A real, confirmed defect — `specialActionsAvailable` is mapped and never read

**Traced from `../mage`, not guessed** (`ConvokeAbility.java`, `DelveAbility.java`,
`Mage.Server.Plugins/Mage.Player.Human/.../HumanPlayer.java`, `Mage.Common/.../GameView.java`):

- Convoke and delve are both implemented upstream as a `SpecialAction` registered on
  `game.getState().getSpecialActions()` — `ConvokeAbility.addSpecialAction` adds one targeting a
  creature to tap; `DelveAbility.addSpecialAction` adds one costing `ExileFromGraveCost` on a
  `TargetCardInYourGraveyard`. Neither is folded into the ordinary mana-payment prompt; they are
  **separate, player-triggered actions available alongside it**.
- `GameView.special` (`this.special = !state.getSpecialActions().getControlledBy(priorityPlayer...).isEmpty()`)
  is **only a boolean** — "some special action is available right now" — with no enumeration or label.
  It is already mapped end to end on our side: `:protocol` → `GameState.specialActionsAvailable` →
  `GameViewMapper` (both bridge and app-side) — confirmed present, tested
  (`GameEventFoldTest`), and printed in a live-test transcript.
- **It is never read anywhere in `feature/game`.** `BoardControls.controlsFor` only ever offers the
  `UseSpecial` action when `prompt.options.specialButtonText` is set — the **unrelated** mechanism
  behind combat's "All attack" button (`Constants.Option.SPECIAL_BUTTON`, a labelled hint on one
  specific prompt). Convoke/delve never set that hint; they only flip `GameView.special`. This is
  standard 5's exact shape: a field that compiles, maps, round-trips, and is asserted in a test, but
  drives nothing. **A deck that wants to convoke or delve has no way to do it from the board today** —
  confirmed by reading the code, not yet by a live probe.

### 18.2 How the server actually resolves it — read from `HumanPlayer.activateSpecialAction`

`useSpecialAction()` already exists on `GameClient` and sends the literal `SPECIAL` command — it was
built for combat's special button but the wire verb is generic. Upstream's handler
(`activateSpecialAction`, `HumanPlayer.java:2295`) is:

1. Collect `game.getState().getSpecialActions().getControlledBy(playerId, inManaPaymentMode)`.
2. **Exactly one** → activate it directly. It then asks for its own cost/target the same way any
   ability does — for convoke, a `Target` prompt ("creature to tap for convoke"); for delve, a `Target`
   prompt over the graveyard (`TargetCardInYourGraveyard`). **Both are ordinary `GAME_TARGET` prompts
   0057 already answers.**
3. **More than one** → `fireGetChoiceEvent`, which is our already-mapped **`GAME_CHOOSE_ABILITY`**
   (`GamePrompt.ChooseAbility`) — the same prompt kind used for "which of this permanent's abilities."
   The player picks one, then step 2 happens for it.

**Consequence: no new prompt kind, no `:protocol`/`:bridge` change expected.** Every step in this
sequence — the initial trigger, the ability choice when there's more than one, and the eventual
cost/target — already routes through prompt kinds the board answers generically. The gap is narrow and
purely client-side: **the board must offer a "use special action" affordance whenever
`state.specialActionsAvailable` is true**, not only when the current prompt happens to carry a
`specialButtonText` hint. Convoke can be invoked repeatedly (once per creature tapped, since each
`ConvokeSpecialAction` targets exactly one) — pressing it again after tapping one creature re-offers
the (recomputed) special actions for the remaining unpaid cost, the same way "use special action" would
if nothing had changed.

### 18.3 What this means for the board

**Decision.** Whenever `GameState.specialActionsAvailable` is true, offer a generic control (e.g.
"Special action") **in addition to** the ordinary controls for whatever prompt is currently showing —
priority (`Select`) or mana payment (`PlayMana`), the two moments upstream actually calls
`activateSpecialAction` from. Tapping it sends `useSpecialAction()`; the server's own next prompt
(`ChooseAbility` if there's a choice, otherwise straight to a `Target`/cost prompt) renders through the
existing generic machinery with no special-casing.

**Cancel.** §6.4/§16.5's cascading-cancel story extends here directly: a convoke/delve `Target` prompt
is an ordinary `Target` with `isRequired` presumably false (unconfirmed — verify live), so the existing
cancel affordance should already apply. This is the same "declining a mode or an alternative-cost
prompt" gap the summary at the top of this document already names as unverified (§6.4a/§16.5a) — convoke
and delve are exactly the concrete cases that item refers to.

**Still to verify live (do not claim done from source reading alone):**
- That `specialActionsAvailable` genuinely flips to `true` during a real convoke/delve cast, using a
  deck built for it (candidate: any convoke or delve card + enough creatures/graveyard fuel).
- That tapping "use special action" produces the `ChooseAbility`/`Target` sequence exactly as read
  above, end to end, with the cost actually applied (mana pool gains a mana from convoke; the graveyard
  shrinks from delve).
- Whether declining the resulting `Target` prompt rewinds the way §17.1 proved for an ordinary target.

## 19. Combat damage among multiple blockers — already covered, confirmed from source

§7.4's out-of-scope note ("damage assignment order, trample, first/double strike... do not
special-case them") was a judgment call made without checking what prompt they actually use. Traced now
(`HumanPlayer.chooseTargetAmount`, `HumanPlayer.java:1038`): when a player must divide combat damage
among multiple blockers (or an attacker with trample beyond lethal), upstream calls the **same**
`getMultiAmount` path that produces our `GamePrompt.GetMultiAmount` — `multiAmountType = DAMAGE` when
the ability's rule text contains "damage." This is **not a new or unverified mechanism**: it is the
identical prompt kind already **proven live** by the target-cancel experiment (§17), where "Select
targets (selected 0 of 2, min 1) to divide 2 damage" for Forked Bolt is this same code path applied to
a spell instead of combat.

**Consequence.** 0061's call to leave damage assignment as a generic prompt is confirmed correct by
source, not just asserted — first/double strike need no client logic at all (the server runs an extra
combat-damage step and simply may prompt again), and trample/multi-blocker division is the
already-shipped `GetMultiAmount` renderer. **Still open:** this has not been exercised **live** for
combat specifically (only for a spell) — worth a short live probe (a creature with two blockers) before
calling it proven, the same standard applied to every other measured claim in this document, but it is
no longer an open design question.
