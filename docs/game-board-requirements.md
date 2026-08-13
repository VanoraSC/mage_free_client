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

### 2.1 Landscape only
**Decision.** The board targets landscape exclusively.

**Why.** Matches how Magic is physically laid out and how desktop XMage reads: battlefields run
left-to-right with room per row, rather than fighting for vertical space against two battlefields, a
hand and the stack.

**Implication (recorded deliberately).** This breaks with the rest of the app, which is portrait
throughout (lobby, decks, cards, tables). Entering a game becomes an orientation change, and the board
is the only landscape surface in the product. Accepted knowingly.

**Open question (2.1a):** does the app force the rotation on entering a game, or ask the player to
rotate? — *pending*

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

### 6.1 Self-contained prompts are modal
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

### 14.2 Floating mana is allowed; its interaction with auto-pass is deferred
**Decision (Pete).** *"You can tap land at any time; passing priority with mana in the pool requires
no special handling for this increment. When we implement pass, this will need to be augmented."*

**What this means now.** Lands are tappable whenever the server permits it (mana abilities appear in
`playable`, so this already works), `manaPool` is displayed, and unspent mana needs no warnings or
guards in the first version.

**Flagged for the auto-pass work (14.1).** Auto-passing with mana floating is exactly where an
unattended pass can cost a player their mana — so the auto-pass feature must handle it deliberately.
Recorded here so the requirement is not lost between increments.

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
- **Stops / configurable auto-pass** (§14.1) — named future feature; keep pass-policy in one place.
- **Auto-pass with floating mana** (§14.2) — must be handled when auto-pass is built.
- **Library-position knowledge** (§11.2) — *unverified*: confirm the snapshot actually carries
  post-scry ordering before promising to display it.
- **Declining a target or an alternative-cost prompt** (§6.4a) — rollback confirmed only for the mana
  step; verify per step before offering cancel there.

## Work this design implies beyond the board itself

- **A bridge game-state cache + query verb** (§10.1) — new `:bridge` + `:protocol` work, ahead of the
  board UI, so a reconnecting client can ask for current state instead of waiting for a push.
- **A sideboard screen** (§12.1) — a purpose-built, timed, match-scoped surface; the
  `ConstructPrompt`/`SideboardPrompt` triggers already exist from story 0036.
