# UI/UX Plan

What the client's UI must do, the UX system that delivers it, and the platform it runs on.

**Ship target: Android.** iOS and desktop are not being built, but the stack supports them and §9
records the discipline that keeps that true.

Read alongside [`ux-principles.md`](ux-principles.md) (this extends it),
[`game-board-requirements.md`](game-board-requirements.md) (the measured, source-verified behaviour
of the server — nothing here contradicts it), [`architecture.md`](architecture.md) and
[`project-plan.md`](project-plan.md).

---

## 1. Where we are

### 1.1 What exists

| Layer | Modules | Main LOC | Test LOC |
|---|---|---:|---:|
| **Server-side** | `:bridge` | 4,681 | 6,675 |
| **Wire contract** (bridge↔app) | `:protocol` | 2,411 | 903 |
| **Client logic** (no UI, portable) | `:core:model`, `:core:network`, `:core:cards`, `:core:decks` | 9,584 | 10,437 |
| **Client UI** | `:core:designsystem`, `:feature:*`, `:app` | 17,624 | 12,317 |

The client logic layer is the valuable part: the `GameViewMapper`, `GameEventFold`,
`GameClient`/`TableClient` session machinery, deck legality, catalog search. ~9.6k lines backed by
~10.4k lines of tests, most of them recordings of real bridge output against a real server. It is
also the highest-bug-density code in the project — stories 0066, 0072, 0074, 0076 and 0079 were all
found by playing real games, not by reasoning.

### 1.2 What the client is like today

`:feature:game` is a proof of concept and reads like one:

- **Fixed-height bands, reserved whether or not they hold anything.** `VitalsBarHeight = 44.dp`,
  `StatusRailHeight = 132.dp`, `StackStripHeight = 56.dp`, `HandPeekHeight = 64.dp`. Every region
  has a defined empty state and keeps its full height while empty, so an empty stack and an empty
  combat zone cost exactly as much screen as full ones — and the battlefield, which is what the
  player actually needs to read, is a horizontally scrolling row inside whatever is left.
- **Nothing moves.** No animation anywhere. A card going hand → stack → battlefield → graveyard
  appears in a different band on the next snapshot. In a hidden-information game, motion is the
  only channel that carries causality, so this is the largest single gap.
- **No spatial affordance.** Nothing indicates it is castable. Targeting is a candidate list
  (`CandidateRow`) rather than the board lighting up. Combat is a text-labelled declaration
  context, not attackers moving into a red zone.
- **Prompt storms.** Casting a spell is a chain of separate dialogs, and tapping lands asks far
  more often than the choice warrants. This is the worst of it — §7.6 and §7.7 are the fix.
- **Text-forward, not card-forward.** `BoardCardFace`, `CounterLine`, `zoneCountsLabel()` — the
  board communicates in labels; art is incidental.
- **No token concept.** Neither `GameState` nor `BoardUi` mentions one, so Magic tokens render as
  placeholders and a token copy of a creature is indistinguishable from the creature (§7.11).
- **No piling and no ordering on the battlefield.** `BattlefieldBand` is a flat `Row` over
  `seat.battlefield` in the order the server lists it, one full-size card per permanent. Ten lands
  cost ten cards of width, and a Plains played after a creature renders to the right of that creature
  rather than beside the other Plains. Story 0065 designed the fix and it was never implemented.
- **No way to look in a zone.** Graveyard and exile are counts on a vitals bar. `GameViewMapper`
  reduces the graveyard to `player.graveyard?.size` and throws the cards away, and does not map
  `PlayerView.commandList` at all, though the server sends both in full (§7.13).
- **No attachment relationships.** The bridge maps `attachedTo` but not `PermanentView.attachments`,
  so an Aura or Equipment renders as a loose permanent with no visible link to its host (§7.4).
- **Poison is invisible.** `PlayerView.counters` — poison, energy, experience — is not in
  `:protocol` at all, nor are `monarch`, `initiative` or `designationNames`. A player can lose to
  poison without the board ever showing it (§7.15).
- **The game log arrives and is discarded.** The server sends its log as `MessageType.GAME` chat and
  the bridge maps it to `ChatKind.GAME`, but no client code consumes `ChatEvent` (§7.12).
- **Portrait phone only**, where the board's target is landscape (§7.4).

One defect outside the board: **the table room asks for a deck that has already been chosen.**
`DeckPicker` is used correctly at host and join time — [`JoinTableScreen.kt:94`](../feature/tables/src/main/kotlin/magefree/feature/tables/join/JoinTableScreen.kt)
passes a real `selectedId` and gates the Join button on it, which is a genuine selection. But
[`TableRoomScreen.kt:175`](../feature/tables/src/main/kotlin/magefree/feature/tables/room/TableRoomScreen.kt)
renders the same picker again under "Submit your deck" with `selectedId = null`, so every deck shows
an unselected radio that can never become selected and tapping one submits immediately.

The radio buttons are the visible symptom; the real fault is that the choice was already made before
the table existed. The room should not re-ask.

### 1.3 What must survive the rebuild

- **The server is authoritative and we speak it correctly.** No rules engine on device.
- **Every game object has a stable id across snapshots** — the precondition for animating between
  snapshots.
- **Prompts are non-modal when they need the board** (R§6.2, R§16.2).

---

## 2. The design thesis

> **MTGO's information model. Arena's presentation. Built for a phone.**

MTGO always shows exactly what the game is asking, what is on the stack, and what your options are;
it is also dense and hostile to a newcomer. Arena makes the board readable at a glance through
motion, highlight and spatial grouping; it is also automated in ways that quietly take decisions
away and cannot express a large fraction of Magic.

Our server is XMage: full rules enforcement, everything asked explicitly, and it expects a client
that can express every decision. So we need MTGO's completeness, presented the way Arena presents
it, showing only the decision at hand.

---

## 3. What we take from MTG Arena

### 3.1 Board presentation

| Feature | Why | Tier |
|---|---|---|
| Cards animate between zones | The only channel carrying causality | P0 |
| Playable-now highlight on hand and permanents | Removes "can I do this?" entirely | P0 |
| Targeting arrows from source to target, persistent while on the stack | Makes the stack readable at a glance | P0 |
| Attack and block arrows | Combat is two assignment problems (R§7.4); arrows show the assignment | P0 |
| Counters rendered on the card face | Board state readable without inspecting | P0 |
| Tapped = rotated 90° | Universal Magic idiom, cheaper to read than a badge | P0 |
| Stack as a fanned centre pile that expands when large | A 56 dp strip cannot show a stack you must respond to | P0 |
| Phase bar with clickable per-phase stops, coloured by whose turn | The right control for 0063's auto-pass | P0 |
| Additional costs chosen before mana (below) | Delve and convoke stop being modal interruptions | P0 |
| Life-total change animates with a ±N delta | You notice what happened to you | P1 |
| Spotlight on the object currently resolving | Turns "the log scrolled" into "I saw it" | P1 |
| Keyword reminder text on demand | Large benefit for less-experienced players | P1 |
| Auto-tap highlights the lands it would use when a card is dragged from hand | Makes the proposed payment checkable before committing | P1 |
| Alternate art, per card and per deck | An explicit EPIC-11 requirement | P1 |
| Damage numbers float off creatures | Combat maths becomes visible | P2 |
| Gameplay warnings ("you still have untapped mana") | Cheap guard-rail | P2, opt-in |

#### Delve and convoke: additional costs come first

Arena's handling is the model:

1. Casting a spell with delve, the player opens the graveyard and selects cards to exile **before
   any mana is tapped**. Convoke works the same way, tapping creatures.
2. Mana then solves for **the remainder** — the difference between the spell's cost and what the
   additional cost already covered.
3. Pending costs are **highlighted on the objects they will consume**, so the payment is visible
   while it is being assembled.
4. When payment completes, the spell goes on the stack and the costs are paid together.

Two properties matter: additional costs are chosen first rather than interrupting mana payment
partway through, and the whole payment is visible before anything commits. Both fall out of the
cast flow in §7.6.

### 3.2 Automation

Arena's gameplay toggles map onto what our server asks for, but the defaults do not suit our
audience — people who chose an XMage client should not have decisions taken silently.

- **Auto-tap (with manual override) and auto-assign-combat-damage** are offered, **defaulted off**.
- **Auto-order-triggers is not offered.** §7.8 makes ordering a single drag, so automating it buys
  nothing and costs the player a decision.
- **Full Control is a pinned mode**, not a held key — there is no `Ctrl` on a phone.

### 3.3 Outside the board

- Home hub with one dominant path to play (EPIC-02 models this).
- Deck manager as art-driven deck boxes, not a list of strings.
- Deck builder with a **query syntax** (`mana>=3 mana<=5`, `owned:false`, colour identity) *and* a
  filter pane — power users type, everyone else taps.
- Mana curve, type counts and legality as live feedback while editing.
- Text paste import/export (0034, 0078).

---

## 4. What we take from MTG Online

### 4.1 The Prompt Box

MTGO's Prompt Box is the main source of information on what can or must be done to progress the
game. It highlights when action is required and it is always in the same place. Everything the game
asks flows through it.

This is the organizing element of our board too — see §7.2.

### 4.2 Priority passing

MTGO offers rich per-card auto-yields with a revocation panel. **We use Arena's simpler model
instead** (§7.9): pass once, or pass on everything currently on the stack, with the latter
interrupted whenever something new is put on the stack.

The simpler model carries no persistent automation state, so nothing accumulates silently and there
is nothing to build a revocation ledger for. It is also the only model that stays legible on a
phone, where a menu tree of name-scoped yields would not.

### 4.3 Zones, including the one most clients omit

MTGO's pop-out zones are Graveyard, Exile, **Revealed**, and **Effects** — replacement effects and
emblems awaiting application. That last is rare and useful: continuous and replacement effects are
invisible in most clients and they decide games.

**Emblems and their kin are already sent to us.** `PlayerView.commandList` is a
`List<CommandObjectView>` built per player from `game.getState().getCommand()`, filtered by
controller — emblems, dungeons, commanders, planes — alongside `designationNames`. The bridge does
not map it. That is the Effects zone's main content, available for the cost of a mapping.

Applied continuous and replacement effects as such have no view of their own; the desktop client
surfaces that kind of thing through card hints rather than a zone. So our Effects zone is emblems,
dungeons, designations and commanders — not a general effect inspector.

`PlayerView` also carries **`monarch`** and **`initiative`** as plain booleans. Neither is an object
in a zone, so they belong on vitals (§7.15) rather than here — but they are the same class of thing:
game-deciding state that lives nowhere on the battlefield and that we do not currently map.

See §7.13 for the browser these live in.

### 4.4 Layout and hotkeys

MTGO allows dragging grid splitters to resize panels, exporting and importing a settings file, and
extensive rebindable hotkeys. On a phone splitters are the wrong idiom, and hotkeys have nowhere to
live. Both become relevant only if a desktop build happens (§12).

---

## 5. What XMage demands that neither client has

Documented and measured in [`game-board-requirements.md`](game-board-requirements.md). Section
numbers prefixed **R§** below refer to that document, not to this one.

- **Manual mana payment is the baseline**, with the server's own proposed solution offered as the
  fast path (R§6.5). See §7.7 for the interaction.
- **`SpecialAction` costs** — convoke, delve, companion (R§18, R§21.4), presented in the order
  described in §3.1.
- **Cancel/rollback with cascading rewind** (R§16.5, R§17.1), including that a cancel is *not* pushed
  to the opponent (R§17.4).
- **Simultaneous trigger ordering** (0072) — see §7.8.
- **Casting is one act, not a series of dialogs** (R§6.4) — see §7.6.
- **Graveyard cards need individual identity** (R§21.2).
- **Resync restores the outstanding prompt** (0074) — reconnect lands on the same decision, not a
  blank board.

---

## 6. Feature tiers

### P0 — required for the board to stop being a POC

1. **The cast flow as one fluid act** (§7.6). The most important item here: it is the weakest part
   of the current client and it is what playing Magic actually feels like.
2. **Low-friction land tapping** (§7.7) — prompt only when the choice is real.
3. **Object-identity animation.** Every game object tweens between snapshots: zone changes, taps,
   counter changes, entering and leaving. Nothing teleports.
4. **Fluid battlefield layout** (§7.4). Creatures in front, non-creature permanents behind them,
   lands piled tight at the back; card size scales to the population with a legibility floor; no
   fixed-height scroll bands.
5. **Playable-now affordance.** Castable and activatable objects are visually distinct, everywhere.
6. **Spatial targeting.** Legal targets highlight on the board; an arrow is drawn from source to
   each chosen target; confirm before submit (R§16.4). Candidate lists remain only for off-board
   targets.
7. **Combat as spatial assignment.** Attackers move to a red zone, blockers connect with arrows,
   and the two assignment problems stay separate (R§7.4).
8. **The Prompt as the organizing element** (§7.2).
9. **Stack as an expandable pile floating over the board** (§7.4), present only while non-empty,
   each entry showing its source art, its own rules text, a link to its source, and its targets
   (§7.14).
10. **Counters, P/T modifications, tap state and status rendered on the card.**
11. **Phase bar with per-phase, per-player stops** driving 0063's auto-pass.
12. **Pass-priority control** (§7.9).
13. **Trigger ordering by drag** (§7.8).
14. **Card inspection v2** — full-bleed, oracle text, current modifications, activatable abilities,
    DFC flip control (0077).
15. **Tokens render with art and are visibly marked as tokens** (§7.11). The marking is game
    information, not decoration — a token copy must be distinguishable from the real card at Board
    tier.
16. **Phone-landscape board layout** (§7.4) — a stable battlefield with transient information
    floating over it, creatures front and lands piled at the back, no decorative chrome. One layout
    target; tablets render it scaled.
17. **The game log** (§7.12) — game state changes, not interim actions. Animation carries what
    happened while you were watching; the log is the only way to recover it afterwards.
18. **The zone browser** (§7.13) — any zone, either player, one interaction, floating over the
    board. Exile grouped by zone name, with playable cards marked wherever they sit.
19. **Attachments rendered as relationships** (§7.4) — Auras and Equipment on their hosts, not in a
    bucket, and a permanent carrying one never piles. Needs `PermanentView.attachments` mapped.
20. **Player vitals** (§7.15) — including **poison, energy and experience counters**, monarch and
    initiative. Poison is a win condition and is currently unmapped and unrendered.
21. **Starting and finishing a game** (§7.16) — opening hand, mulligan, sideboarding, concede,
    results. The board has to be reachable from a table and exitable from a game.
22. **Notices and session state** (§7.17) — disconnected, reconnecting, resynced, opponent conceded.
    What distinguishes a resync snap from something the opponent did.

### P1 — required for it to be good

23. **Auto-tap and auto-assign-damage toggles**, defaulted off, with auto-tap highlighting its
    proposed lands.
24. **Full Control mode** as a pinned toggle.
25. **Effects & Emblems in the zone browser** (§4.3) — `PlayerView.commandList`, which the bridge
    does not yet map.
26. **Life-total deltas, resolution spotlight, keyword reminders.**
27. **Alternate art chosen in the builder renders in the game** (§7.11), for cards and for the
    tokens a deck produces.
28. **Art prefetch at match start** — we submitted our own deck, so its art can be warmed before
    turn one. The opponent's deck is hidden and arrives card by card.
29. **Deck builder v2** — query syntax and filter pane, live curve and legality, art-driven deck
    boxes.
30. **Home hub, lobby and tables** rebuilt on the new design system.
31. **Undo.** The server supports the rewind (R§17.1). The cast flow removes most of the misfires it
    would catch, since intent is editable before submission, so it covers what remains.

### P2 — polish and reach

32. Damage number floats; attack and block animation beats.
33. Gameplay warnings, opt-in.
34. Spectating on the new board (EPIC-15), including both players' hidden information.
35. Replays.

### Not in scope

- **Emotes, and player chat of any kind** — lobby, in-game, or whisper. Permanently deferred.
- **Player presence and social data** — profiles, online status, friends, invites, mentions.
  Permanently deferred. **The only thing needed from that surface is seeing joinable tables**, which
  is the lobby (EPIC-06) and carries no presence data: a table listing is table state.
- **Sound design and haptics.** Permanently deferred.
- **Accessibility.** Permanently deferred, including screen-reader support.
- **Clocks and rope burn-down.** If the XMage server does not implement it, neither do we. Should
  the server drive a time control and push remaining time, we would display what it sends — that is
  showing a server fact, not designing a clock feature.
- **Persistent per-card auto-yields with a revocation ledger** (§4.2).
- **Decorative battlefield art** — illustrated grounds, themed playmats, avatars, pets, or any other
  original art beyond the cards themselves (§7.4). The board is grey; the cards carry the visuals.
- **A collapsing hand** (§7.4). The hand stays visible.
- **Statically allocated regions** (§7.4) — a permanent combat zone, a stack region beside the
  battlefield, or any band that holds height to show an empty state. Transient information floats
  over the board instead.
- **More than two players** (§7.17) — free-for-all, Commander pods, Two-Headed Giant. The server
  supports them; the mirrored two-battlefield layout on a phone in landscape does not.

---

## 7. The UX system

### 7.1 Interaction model

**Taps are the floor, gestures are accelerators** ([`ux-principles.md`](ux-principles.md)), with one
consistent touch vocabulary:

| Gesture | Meaning | Everywhere |
|---|---|---|
| **Tap** | Select or act on this object | Yes |
| **Long-press** | Inspect, and offer whatever actions this object currently affords | Yes |
| **Drag** | Accelerator only: play a card, assign an attacker to a defender | Always has a tap path |
| **Back** | Cancel the innermost thing (cancel targeting → cancel cast) | Yes |

The rule that matters: **long-press is inspection, on every card-like object, in every screen.**

### 7.2 The Prompt

One component, one position, three states:

- **Idle** — whose priority it is and what phase we are in. Low contrast.
- **Asking** — the server wants a decision. High contrast, thumb-reachable actions, stating the
  question in the server's own words (cleaned of markup, as `stripServerMarkup` does).
- **Board-interactive** — the decision requires touching the board (targets, attackers, blockers).
  The Prompt shrinks to a header, progress ("2 of 3 targets") and Confirm/Cancel, and **never blocks
  the board** (R§6.2, R§16.2).

### 7.3 Motion and object identity

The load-bearing new subsystem.

**An animation exists because a game action happened.** It is not decoration and it is not a
transition between two renderings — it is how the player finds out what the game did. That purpose
sets every rule below.

- Every renderable object has a **stable id** and a **single owning layout slot** per snapshot.
- The board is hosted in one shared coordinate space. When a snapshot changes an object's slot, the
  object **animates from its previous measured position to its new one** rather than being destroyed
  and recreated — `LookaheadScope` plus shared-element transitions, not hand-rolled coordinators.
- **Every state change gets its turn on screen.** When actions arrive faster than they can be shown
  — a chain of triggers resolving, a sequence of tokens entering — they **play in order** rather
  than collapsing into a single move that lands on the final state. Collapsing them is the failure
  case: the player sees the board end up somewhere and has no idea how it got there.
- **The board's presentation may trail the server, and that is intended.** Trailing by a moment
  while a sequence plays out costs nothing, because a player watching a sequence resolve has no
  decision to make during it.
- **Being asked to act is the sync point.** When the server wants a decision, the board shows
  current state — the remaining sequence finishes quickly rather than making the player wait on it.
  Nobody acts on a stale board.
- **A resync is not a sequence.** After a reconnect (0074) the board snaps to current state; there
  is no backlog to replay, and pretending otherwise would narrate events the player already missed.
- **Durations encode meaning:** zone moves ~250 ms, taps ~150 ms, counter changes ~120 ms,
  resolution spotlight ~400 ms hold. A **reduce-motion/fast setting is P1**, and it **shortens**
  durations rather than removing animation — removing it removes the information.

### 7.4 Board layout

#### Two layers: a stable base, and floating layers above it

**The battlefield is always visible and it does not move.** Everything transient — the stack, combat
assignment, revealed and looked-at cards, prompts, notices — renders as a **floating layer over** the
board rather than as a region that takes space beside it.

This is the whole trick. If the stack were a region, it would appear and disappear as spells came and
went, reflowing the battlefield each time and making permanents drift for reasons that have nothing
to do with the game. §7.3 says an animation exists because a game action happened; a battlefield that
re-lays-out because a trigger went on the stack would be lying in exactly the way that rule forbids.
Floating it means transient information can come and go freely **while the battlefield stays exactly
where the player left it.**

- **Base layer** — both battlefields, the hand, player vitals. Always present, stable position.
- **Floating layers** — the stack (only while non-empty), combat assignment (only during combat),
  revealed/looked-at cards, the Prompt, notices.

Floating layers must not hide what they are asking about: a board-interactive prompt (§7.2) still
never blocks the board, so a layer is placed and sized to leave the permanents it concerns visible.

**No decorative chrome anywhere.** Borders, banners, section headers and framing that delineate
rather than inform are omitted. A region is identified by its position and its shade of grey, not by
a label and a rule line. No empty state ever holds height — today every region has one and a fixed
height (`StatusRailHeight = 132.dp`, `StackStripHeight = 56.dp`), so an empty stack and an empty
combat zone cost as much screen as full ones.

The payoff is direct: space not spent on chrome and empty regions is space the battlefield gets,
which means larger cards, which is the difference between reading the board and squinting at it.

#### How the battlefield is arranged

Each player's battlefield reads front-to-back by how much attention the permanent needs:

```
   ┌─ opponent's battlefield (mirrored) ──────────────┐
   │  [ lands ]        [ other permanents ]           │   back
   │            [ creatures ]                         │   front
   ├──────────────────────────────────────────────────┤
   │            [ creatures ]                         │   front
   │  [ lands ]        [ other permanents ]           │   back
   └─ your battlefield ───────────────────────────────┘
```

- **Creatures in front.** They attack, block, and change state constantly — they are what the player
  looks at.
- **Non-creature permanents behind the creatures**, beside the lands. Present and readable, but not
  competing with the things that are about to matter in combat.
- **Lands to the side, at the back, piled tightly.** Lands are the most numerous permanents and the
  least individually interesting; **the goal is to minimise the space they take without hurting
  readability.**
- **Attached permanents render on what they are attached to**, not in a bucket of their own. An Aura
  or Equipment sits with its host; a fortified or enchanted land stays with the lands.

#### Attachments

An Aura is a non-creature permanent, but putting it in the non-creature bucket is wrong — Pacifism
belongs on the creature it is turning off, and an Equipment that has been moved this turn is a change
the player needs to see on the creature that gained it. Attachment is a **relationship**, and the
board has to draw it or the three buckets actively mislead.

**The server gives us both directions.** `PermanentView` carries `attachments` (a `List<UUID>` of
what is attached to this permanent), `attachedTo` (what this permanent is attached to),
`attachedToPermanent`, and `attachedControllerDiffers` — the last for the case where you control the
Aura but your opponent controls the creature, which is a real and easily-missed board state. The
bridge maps **only `attachedTo`**; `attachments` and the two flags are unmapped.

**Only identical objects in identical states stack.** That is the whole rule, and it is strict.

A pile says "these are interchangeable — read one, you have read them all." Anything that makes one
member different from another breaks that promise, so anything that makes one member different keeps
it out of the pile:

- **Counters.** A Grizzly Bears with a +1/+1 counter is not a Grizzly Bears.
- **Temporary modifiers**, including granted abilities — flying until end of turn, a pump effect, an
  ability granted by another permanent. Two creatures with the same printed text are not the same
  object if one of them can currently fly.
- **Summoning sickness**, which decides whether the permanent can attack or tap this turn.
- **Damage marked**, tap state, face-down/transformed state, phasing.
- **Combat assignment** — attacking, blocking, blocked by.
- **Current pick-eligibility**, so a legal target never hides inside a fan of illegal ones.
- **Attachments**, which are absolute: **a permanent carrying an attachment never piles at all.** An
  attachment attaches to one specific instance — the aura is on *that* Grizzly Bears, not on the
  group. Two identically-enchanted creatures still do not pile, because each carries its own aura.

So attachment is not merely another field in the key. Every other property above pairs permanents
that match; having an attachment disqualifies a permanent outright, and it renders alone with its
attachments on it.

**The server already reports all of this**, which is what makes the strict rule cheap. `CardView`
builds `rules` from `card.getRules(game)` — the game-aware form, so a granted "flying until end of
turn" is in the text upstream sends us — and `power`/`toughness`, `cardTypes` and `subTypes` are all
the current values after continuous effects, not the printing's. Piling compares what the server says
the permanent *is right now*; it never re-derives that from the card.

**Piling does the space work. It is fully designed and not built.** Story 0065 and R§20 resolve the
presentation — 2–3 members render as a fan of real card faces; more than 3 caps the fan at 3 plus a
count badge (`×7`), so a pile never grows past a 3-card fan however many it holds; a permanent moves
between piles automatically as its state changes, because its state is the key.

The practical consequence is that **piles are for lands and tokens**, which is exactly where the
space is spent. A board of ten Plains collapses; a board of ten differently-developed creatures does
not, and should not.

None of it exists in the client. `BattlefieldBand` renders a flat `Row` over `seat.battlefield` in
the order the server lists it, one full-size card per permanent, with no grouping and no ordering by
type. Playing a Plains, then a Soul Warden, then a second Plains puts them on screen in exactly that
order, the second Plains sitting alone to the right of the creature.

So both halves are new work here: **the arrangement and the piling.** 0065's presentation stands as
designed; its grouping key takes the strict rule above — every aspect of current state, and no
piling at all for a permanent carrying an attachment.

#### Sizing

- **The base layer's regions get proportional space with minimums**, not fixed dp.
- **Card size is derived** from the widest populated row, floored at a legibility minimum; below the
  floor the row scrolls.
- **The hand never collapses.** The player reads their hand constantly to make decisions, so hiding
  it behind a peek edge and an expand gesture takes the most-consulted information on screen and
  puts it a gesture away. This also removes a class of interaction — no peek edge, no expand
  gesture, no collapse-on-back, and no question about what state the hand was in when a prompt
  arrived.

**One layout: phone landscape.** The board targets a phone held sideways and nothing else. Tablets
render the phone layout scaled up; larger form factors get real attention later. A single layout
target is what makes the constraint-driven sizing above tractable — deriving card size from row
population is a much smaller problem against one aspect ratio than against three.

**The board itself is grey.** No illustrated battlefield, no themed playmat, no decorative
background art. A pleasing neutral grey ground, with **zones and other distinctions carried by
shades of that grey** — value and elevation, not colour or texture.

The art on screen is the cards, and the motion on screen is the cards moving (§7.3). That is the
whole visual budget and it is enough.

This is not only a scope decision. A neutral ground means **the only saturated colour on the board
is information** — playable-now highlight, targeting, combat arrows, pending costs, threat. Against
an illustrated background those signals compete with decoration for attention, and the signals lose.
Keeping the ground grey is what makes §3.1's highlight vocabulary legible at card size.

> **Conflicts with [`game-board-requirements.md`](game-board-requirements.md) §16.1**, which
> supersedes its own §2.1 to specify portrait. That section is now out of date and needs correcting;
> §7.4 here is the current intent. Note that [`ux-principles.md`](ux-principles.md) §2 already
> favours landscape as the primary in-game orientation, so this restores that direction.

### 7.5 Card rendering tiers

| Tier | Where | Shows | Art |
|---|---|---|---|
| **Board** | Battlefield, stack piles | Name, P/T, counters, tap state, status | Downsampled, cropped to the art box |
| **Tile** | Hand, zone browsers, deck lists | Name, cost, type line, P/T | Downsampled full card |
| **Full** | Inspection, mulligan, sideboard | Oracle text, current modifications, activatable abilities, flip control | Full resolution |

Only **Full** loads full-resolution art, which matters for memory and for the first-turn experience.

These are *rendering sizes* and have nothing to do with Magic tokens — see §7.11 for those.

### 7.6 The cast flow — a declared intent, played back by the bridge

The largest piece of new work, and the mechanism that makes §3.1's cost ordering and §7.8's trigger
ordering possible.

**The problem.** The server asks for a cast as an ordered sequence of separate questions — announce
the spell, then special actions, then modes, then targets, then mana — each a discrete prompt with
its own round trip. Rendering that faithfully produces a chain of dialogs, which R§6.4
forbids and the current client does.

**The model.** The client assembles a **complete declared intent** locally — this spell, these
modes, these targets, these creatures tapped for convoke, these cards exiled for delve, these lands
tapped for these colours — and the server's prompt sequence is then answered **as a batch**. The
player experiences one continuous act with a single Confirm; the server receives exactly the
conversation it expects.

**Where it lives: `:bridge`.** The prompt grammar is XMage-specific and order-dependent, so keeping
the intent player in the bridge preserves the rule that nothing above `:core:network` knows the
server's shapes ([`architecture.md`](architecture.md)) — the app submits an intent and receives
results. The bridge can also be tested headlessly against a real server, which is how every other
correctness result in this project was obtained, and the UI stays declarative. New protocol surface:
a `CastIntent` message and its result, spanning `:protocol`, `:bridge` and `:feature:game`.

**The safety rule:**

> The client may change the **form** of the conversation. It may never invent the **content** of an
> answer.

Re-ordering questions, merging them into one surface, and asking a result-shaped question where the
server asked a process-shaped one are all safe — the player still supplies every real decision.
Guessing an answer the player did not give would be the worst class of bug this project could
ship: a wrong action submitted to a live game.

Concretely: **on any prompt the declared intent does not unambiguously answer, the bridge stops,
rewinds if it can, and hands that prompt to the client.** It never guesses and never partially
commits in silence. This will happen for real — an opponent's static cost increase, a target that
became illegal, an unanticipated replacement-effect choice, an optional trigger mid-cast.

**Costs are proposed by the server, not computed by us.** Cost-modifying effects mean local
arithmetic can be wrong, and R§6.5 establishes that the server proposes the solution.
So:

1. Player picks the spell.
2. Player chooses additional costs first — delve exiles, convoke creatures — with pending costs
   highlighted on the objects they consume.
3. The **server's proposed solution for the remainder** is shown as the default mana payment; the
   player accepts it or edits it by tapping lands (§7.7).
4. Targets and modes are chosen on the board.
5. One **Confirm** submits the intent. This is always an explicit act — a fully-paid cost does not
   fire the cast on its own (§7.7 rule 5), and the mana about to be spent is shown alongside it.

Everything before step 5 is local and freely editable, so **Cancel before Confirm costs nothing and
touches no server state.** After Confirm, cascading rewind (R§16.5, R§17.1) applies.

**Two things must be settled before this is built:**

- **The real prompt sequence in upstream `HumanPlayer`** for a cast carrying additional costs — the
  full path, not just `activateSpecialAction` which R§18.2 covers. The server and reference client
  are correct and available; read them rather than infer.
- **Disconnect mid-playback.** The bridge becomes briefly stateful per cast, which interacts with
  0074 (resync restores the outstanding prompt). A drop between "intent submitted" and "cast
  complete" must land the player somewhere well-defined and truthful.

### 7.7 Tapping lands — prompt only when the choice is real

**Tap a land and it taps for mana.** In order:

1. **One possible mana → no prompt.** The mana goes to the pool. A tapped Mountain is a red mana,
   full stop. This is the overwhelmingly common case and it costs exactly one tap.
2. **Genuinely multiple options → prompt, showing only the real ones.** A Sacred Foundry produces
   `R` or `W`; a Creeping Tar Pit can produce mana or animate.
3. **Mid-cast, only mana abilities are offered.** Non-mana abilities cannot be activated while
   casting, so they do not appear. Creeping Tar Pit mid-cast is therefore case 1, not case 2 — it
   produces mana with no prompt. This rule removes most of the remaining prompts.
4. **Tapping a tapped land untaps it.** Tap to pay, tap again to take it back.
5. **Meeting the cost never fires the cast.** When the last required mana is supplied the player is
   *prompted to finish the cast*, with the mana being spent shown. Completion is always an explicit
   act.

Rules 4 and 5 follow from §7.6: while the intent is being assembled nothing has been submitted, so
adding and removing lands is a local edit. That is also why misfires during payment need no Undo —
the correction is another tap.

Rule 5 is load-bearing rather than a formality. If the cast fired the instant the cost were met, the
player could never adjust *which* mana paid for it — and which mana was spent is not always
cosmetic. Deceit is the motivating case. An auto-firing cast also removes the last chance to back
out of a misfired spell before it becomes server state.

This is a **filtering** problem, not an invention problem. Every option shown comes from what the
server reports as legal, and rule 3 narrows that to what is legal *here*. Which case a given land
falls into is derived from the server's reported abilities, never from a hardcoded notion of what
lands do.

### 7.8 Trigger ordering

When several triggers go on the stack simultaneously:

- They are shown **as the stack will look**, not as a list of questions.
- The player **drags to rearrange** until it reads the way they want.
- **The card on top resolves first.**
- One confirm submits the arrangement.

The component owns the translation between the **result-shaped** question the player answers ("what
should the stack end up as") and the **process-shaped** question the server asks ("which do you put
on next") — a §7.6-style change of form, not of content: the ordering is entirely the player's.

Built as a reusable component, since the same interaction serves any "put these in an order" prompt,
and **unit-tested on the translation itself** — an off-by-one reversal here is silent and
game-losing.

### 7.9 Passing priority

When the player has priority and the stack is not empty, the Prompt offers exactly two actions:

- **Pass once** — pass priority for this instance only.
- **Pass on everything on the stack** — keep passing until the stack empties.

The second is **interrupted the moment anything new is put on the stack**, at which point the same
two choices are offered against the new stack, and so on until the stack is empty.

Three properties make this safe without a revocation ledger: it cannot outlive the stack, so nothing
accumulates; new information always stops it, so the only thing skipped is responding to objects the
player already saw and declined to respond to; and any interrupt returns full control in one tap.

**This is a server feature, not a client one.** `PlayerAction.PASS_PRIORITY_UNTIL_STACK_RESOLVED` is
exactly the second action, and `PASS_PRIORITY_CANCEL_ALL_ACTIONS` revokes it. The server also owns
the interrupt: on taking that action it records `dateLastAddedToStack`, and `HumanPlayer` compares
that against the stack's current value each time priority comes round, clearing the flag when they
differ. We send an action and render the flag; we do not implement the behaviour.

Two conditions on that interrupt matter, because both make it weaker than "the moment anything new is
put on the stack":

- **It is gated on a user preference.** `HumanPlayer` only clears the flag when the controlling
  user's `getUserSkipPrioritySteps().isStopOnStackNewObjects()` is set. That preference must be set
  for our players, or the pass runs through the opponent's response.
- **It is gated on being the active player.** The same condition requires
  `playerId.equals(game.getActivePlayerId())`, so passing until the stack resolves on the opponent's
  turn does not break on new objects.

Neither is something we can paper over from the client without inventing behaviour the server did not
perform. So the interaction is: set the preference, and describe the second action in terms of what
the server will actually do rather than what the label implies.

The server carries a wider vocabulary than we expose — pass until next turn, until end of turn, until
next main phase, until end step before my next turn, until my next turn. Those are the phase-bar
stops (P0 #11), not priority passing, and `PlayerView` reports each as its own flag so the board can
show that an auto-pass is running.

### 7.10 Outside the board

The same design system applied to the home hub, lobby and tables, deck library and builder, card
search and settings. Lower risk and mostly a re-skin plus the P1 deck-builder work — but it is what
the app looks like before anyone reaches a game.

### 7.11 Card art and Magic tokens

Art is currently resolved from the printing the server names — `artRequestOf(setCode,
collectorNumber)` — and falls back to a placeholder when the server names none. There is **no token
concept in the client at all**: neither `GameState` nor `BoardUi` mentions one. Five things are
wanted, and they build on each other.

#### 1. Tokens render with art

**The server does identify tokens and does give them a printing.** `CardView` carries
`isToken`, and for a token it sets `expansionSetCode` and `cardNumber` from upstream's own
`TokenRepository` — the set code is `TokenRepository.XMAGE_TOKENS_SET_CODE`, with a matching
`imageFileName` and `imageNumber`. The bridge maps none of it, which is why tokens fall to the
placeholder: `artRequestOf` gets no printing and returns null.

The one real problem is that **that printing is XMage's, not Scryfall's.** Our art pipeline resolves
Scryfall URLs from set code plus collector number, and the XMage token set code will not resolve
there. Two ways out, and the choice is a real one:

- **Resolve token art from Scryfall by name.** Tokens are real queryable printings there —
  `is:token`, in sets whose `set_type` is `token`. Consistent with how every other card gets its art,
  but needs enough of name, subtype, P/T and colour to disambiguate printings that share a name (a
  1/1 white Soldier and a 2/2 white Soldier are different printings). `CardView` carries all of those.
- **Use XMage's own token images**, which is what the desktop client does, and which the server has
  already resolved for us. No matching needed; a second art source to plumb.

#### 2. Tokens are visibly marked as tokens

A token that copies a card renders as that card, and the player **must** be able to tell it apart
from the real thing — a token that leaves the battlefield ceases to exist, which changes how you
trade, bounce and sacrifice. Game information, not decoration, so it is P0 and it must be legible at
Board tier (§7.5), not only on inspection.

**The signal exists and is unambiguous.** `CardView.isToken` is a plain boolean set when the object
is a `PermanentToken`, and `CardView.mageObjectType` separates `TOKEN`, `COPY_CARD` and `PERMANENT`
— so "is this a token" and "is this a copy of a card" are both answered directly. This is the same
shape as story 0076's `transformed`: a correct upstream field that simply is not mapped. Thread it
through unchanged.

#### 3. Chosen art carries from the deck builder into the game

Picking an alternate printing while building a deck should be what renders in play.

There are two mechanisms here and they are not the same thing, so §12 asks which is wanted:

- **Pick the printing in the deck.** XMage decklists already carry set and collector number, so the
  choice travels to the server and comes back in the game view. It affects only which printing is
  registered, but it is a real change to what we submit.
- **A local display override.** A per-card art preference stored on the device and applied at render
  time, overriding whatever printing the server names. It never touches the game, works for cards
  from any source, and is the only mechanism that could apply to the opponent's cards.

#### 4. Art that is downloading says so

Art can take a moment to arrive — first sight of a card, a cold cache, a slow connection. While it
is in flight the card shows an **indeterminate spinner**, so the player knows a download is
happening rather than wondering whether the card is broken or the art is simply missing.

This applies everywhere art loads — board, hand, zone browsers, deck builder, card search — and it
is distinct from the two states around it: a card with **no printing to resolve** shows the
placeholder (nothing is coming), and a card whose art has **arrived** shows the art. The spinner
only ever means "in flight."

Prefetching our own deck at match start (§10) removes most of these, but it cannot remove them all:
the opponent's deck is hidden, so every unfamiliar card they play is a first sight.

#### 5. Token art is choosable too

A card that creates a token — where the token is its own thing, not a copy of a card — should let
the player choose that token's art in the builder, the same way card art is chosen.

**Which tokens a deck can produce is derivable from the deck itself**, because the card states what
it creates. Two routes, and the structured one is preferable:

- **Scryfall's `all_parts`.** A card that generates a token carries a Related Card entry for it, so
  "what tokens does this deck make" is the union of `all_parts` token entries across the decklist —
  a lookup, not a parse.
- **Rules text**, as the fallback where structured data is missing. Brittle, and only worth
  reaching for if `all_parts` proves incomplete.

**The bundled catalog holds neither.** `cards.sqlite` has three tables — `meta`, `card`, `printing` —
and `printing` is `(card_id, set_code, collector_number, rarity)`. There is no token table and no
related-parts data, which follows from where it comes from: `tools/card-catalog-generator` builds it
from XMage's card data, and `all_parts` is a Scryfall concept.

So route (a) is a **network lookup**, and token art does not work offline unless the catalog gains a
table. That makes the generator the natural place to fix it — it already produces the asset, and a
token/related-parts table there would keep tokens working exactly like every other card, offline
included. Whether that is worth doing before P1 #27 is a scheduling question, not a design one.

---

The remaining unknown in this section is behavioural, not structural: which of the two token-art
sources to use (§7.11 #1). Everything else is now read from upstream and from the catalog — guessing
at what the server reports is how story 0076 took four rounds.

### 7.12 The game log

A scrollable record of what has happened in the game.

**It logs game state changes, not interim actions.** Tapping a land, untapping it again, selecting a
delve exile and then deselecting it are all steps in assembling a decision — they are not things
that happened in the game, and putting them in the log buries the things that did. A player scanning
the log wants "opponent cast Lightning Bolt targeting Grizzly Bears" and "Grizzly Bears died," not
twelve lines of mana bookkeeping.

§7.6 draws that line precisely and without judgement calls: **everything before Confirm is local and
uncommitted, so nothing before Confirm is loggable.** The log begins at the point the intent is
submitted. That is the same boundary that makes Cancel free, and it means the log does not need its
own notion of what counts as significant.

The log is also the backstop for §7.3. Motion carries causality while you are watching; the log
carries it when you looked away, when several things resolved quickly, or when you want to check
what a permanent's counters came from three turns ago.

#### The entries already reach the app

The server writes its game log as chat. `GameController` broadcasts every game `TableEvent` of type
`INFO` and `STATUS` to the table's chat id with `ChatMessage.MessageType.GAME`, and the desktop
client renders exactly that stream in its game panel. Two consequences:

- **The text is the server's own and is already correctly worded.** Describing a game state change
  accurately is the last thing we should re-derive, and we do not have to.
- **It is already mapped.** `ChatMessageMapper` maps `MessageType.GAME` to `ChatKind.GAME`, and the
  bridge relays it as a `ChatEvent`. It arrives at `:protocol` today and **nothing consumes it** —
  no main-source client code handles `ChatEvent` at all. The log is a rendering job, not a plumbing
  job.

**This is not player chat and it does not die with it.** Chat is permanently deferred (§6), but the
game log rides the same transport under a different `MessageType`. `ChatKind.GAME` stays; `TALK`,
`WHISPER_IN` and `WHISPER_OUT` are the deferred ones.

**Still to establish:** whether the `INFO`/`STATUS` stream carries interim actions we would have to
filter. It is server-side `GameEvent` text rather than client-side bookkeeping, so it should already
be at the granularity §7.12 wants — but that is a claim to check against a real game's log before
building on it, not to assume.

---
### 7.13 The zone browser, and telling exiles apart

**A menu that opens any zone, for either player, in one interaction** — graveyard, exile, revealed,
looked-at, command. It floats over the board (§7.4) and never costs the battlefield space.

#### What the server already sends

`PlayerView` carries, per player and already filtered for what that seat may see:

- **`graveyard`** — a full `CardsView`, the actual cards.
- **`exile`** — a full `CardsView` of every card in any exile zone that player *owns*, flattened.
- **`commandList`** — emblems, dungeons, commanders, planes (§4.3), plus `designationNames`.

`GameView` separately carries **`exiles: List<ExileView>`**, each an `ExileZone`'s cards with its
**name** and id. The two exile views are complementary and both are needed: `PlayerView.exile`
answers "what of mine is exiled," `GameView.exiles` answers "which pile is it in."

**The bridge maps almost none of this.** `GameViewMapper` reduces the graveyard to
`graveyardCount = player.graveyard?.size ?: 0` and discards the cards; `commandList` is not mapped
at all. Only `GameView.exiles` survives, as `GameState.exile: List<GameZone>` with `name` and
`zoneId` — mapped, and never rendered.

#### Telling special exiles apart

Exile is not one pile. `Exile` holds `ExileZone`s keyed by id: a default zone named `Permanent`, and
effect-created zones named `"<effect> - Exile"`. Two independent signals distinguish them, and
**both are already available**:

- **The zone name**, for effects that make their own zone. Plot exiles to
  `"Plots of <player> - Exile"` (`PlotAbility.doExileAndPlotCard`); Rebound uses `"Rebound"`. These
  arrive as `GameZone.name` today.
- **`canPlayObjects`**, for anything castable right now — already mapped as `GameStateView.playable`.
  This is what the reference client uses: `GamePanel` marks `setPlayableStats` on card views in both
  `PlayerView.exile` and `GameView.exiles`.

**Neither signal alone is sufficient, and together they still do not cover everything.** Airbend
does not create a named zone — it calls `moveCards(Zone.EXILED)` into the general exile and grants an
`AsThoughEffectType.CAST_FROM_NOT_OWN_HAND_ZONE` letting the owner cast it for `{2}`. So an airbent
card is nameless in exile, and is only distinguishable while `canPlayObjects` says it is castable.
On a turn when it is not castable there is nothing marking it as special. A plotted card has the same
gap in reverse: named always, playable only on the turn it can be cast.

There is **no single upstream flag for "this exiled card is special,"** and the reference client does
not have one either — it opens one auto-shown window per named exile zone, titled with the zone name,
and marks playability on the cards. We do the same thing in a browser instead of windows: **group
exile by zone name, and mark playable cards wherever they are.**


### 7.14 What a stack entry shows

A stack entry answers four questions at once: **what is it, what caused it, what will it do, and
what does it hit.** Together those are what the player needs in order to decide whether to respond.

1. **The art of the card that produced it.** A triggered ability is not a card, but it came from one,
   and the art is how it is recognised at a glance.
2. **The ability's rules text**, as its own text — not the whole source card's text.
3. **Its source, shown as a relationship.** Soul Warden's trigger should visibly belong to Soul
   Warden on the battlefield, not merely wear its picture.
4. **Its targets**, drawn to whatever they are — including **other stack entries**, since Counterspell
   and Mana Leak target the spell above them.

#### What the server already gives us, and what is missing

Points 1 and 2 are done. `StackAbilityView` is a **sibling of `CardView`, not a subclass**, and sets
`name = "Ability"` unconditionally while leaving its own `expansionSetCode`/`cardNumber` blank — only
its nested `getSourceCard()` carries the real name and printing. `GameViewMapper` already reads
`sourceCard` for `displayName`, `setCode` and `collectorNumber` (Soul Warden's trigger was the live
example that produced that fix), and `rules` maps through as the ability's own text.

Point 3 needs the source as a **link**, not just borrowed art. `StackAbilityView.getSourceCard()` is
a full `CardView`, so the source's id is available to point at a permanent already on the board.

Point 4 is the real gap. **`CardView.targets` is a `List<UUID>`, populated for both spells and stack
abilities**, and upstream computes it for exactly our purpose — `StackAbilityView.updateTargets`
carries the comment *"need only unique targets for arrow drawing."* It resolves selected modes'
targets, and falls back to effects' target pointers where a mode declares none. Because it is
resolved through `game.getObject(uuid)`, a target that is itself a spell on the stack comes through
the same way as a permanent.

**We do not map `targets` anywhere** — not in `:protocol`, not in `GameState`. That is the one piece
of new data the stack needs, and it is what §3.1's targeting arrows draw.

### 7.15 Player vitals

Vitals are a base-layer element (§7.4) and they carry the things that decide games without being on
the battlefield. Per player:

- **Life**, with the ±N delta animation (P1 #26).
- **Library and hand counts.** An empty library is a loss; a hand count is public information.
- **Graveyard and exile counts**, each opening the zone browser (§7.13).
- **Player counters** — poison, energy, experience. **Poison is a win condition and we do not show
  it.** `PlayerView.counters` is a `List<CounterView>` and `:protocol`'s `GamePlayerView` does not
  have it, so no amount of rendering work reaches it today.
- **Monarch and initiative**, and **designations** (`monarch`, `initiative`, `designationNames`) —
  also unmapped, also game-deciding.
- **Mana pool**, which §7.7 fills. Already mapped as `GameManaPoolView`.
- **Match wins** (`wins` / `winsNeeded`), which is what tells the player this is game 2 of 3.

Life, library, hand, graveyard, exile, mana pool and wins are in `:protocol` today. **Player counters,
monarch, initiative and designations are the gap**, and they are the ones that change the answer to
"am I about to lose."

### 7.16 Getting into and out of a game

The new board is reached from "New Battlefield" when a table is ready (§11), so the first thing it
must render is not a board at all.

- **Opening hand and mulligan.** The London mulligan asks two questions — keep or mulligan, then
  which cards to put on the bottom. The second is a **selection of N**, and the board is not involved,
  so it is a Full-tier (§7.5) surface rather than a board interaction.
- **Sideboarding between games**, which is the same builder surface under a timer.
- **Concede**, which must be reachable and must be hard to hit by accident.
- **Game and match results**, and the transition between games of a match.

None of this is new server surface — it is EPIC-14, driven by prompts the client already receives.
It is listed here because a board that cannot start a game is not a board, and §7 otherwise describes
only the middle of a game.

### 7.17 Notices, and when the connection goes

"Notices" appear in §7.4's floating layers; this is what they are. A notice is a **statement about
the game or the session that is not a decision** — the Prompt (§7.2) is for decisions and notices
must not compete with it.

- **Session state.** Disconnected, reconnecting, resynced. The board keeps rendering the last known
  state rather than blanking, because a stale board the player knows is stale is more useful than an
  empty one.
- **The opponent's situation** — conceded, timed out, left the table.
- **Game and match outcome.**

**Reconnect lands on the outstanding prompt** (0074) and the board **snaps** rather than replaying
(§7.3). The notice is how the player learns that a jump in board state was a resync and not something
the opponent did — without it, snapping is indistinguishable from a turn happening while they were
away.

**Two players, heads-up.** Every layout in §7 assumes two seats facing each other. XMage supports
free-for-all, Commander pods and Two-Headed Giant, and none of them fit the mirrored two-battlefield
arrangement or the phone-landscape target. They are out of scope for this board, and that is a layout
constraint rather than a protocol one — the server would send them fine.

## 8. Platform

The client is **Kotlin and Compose on Android**. The whole stack is one language: the wire contract,
the client logic and its tests, and the UI.

The board's animation requirements (§7.3) are met with `Animatable`, `LookaheadScope`,
shared-element transitions and `graphicsLayer`.

**The bridge is a network service, not a library.** It runs on a JVM, embeds `mage-common`, and
speaks JBoss Remoting to the XMage server on one side and **WebSocket + JSON** on the other
([`architecture.md`](architecture.md), Option A). None of it runs on the device, so the client is
just a socket and a JSON parser as far as the server is concerned.

On Android, Compose Multiplatform **is** Jetpack Compose — the same compiler and runtime — so no
migration exists to perform and none is scheduled. Converting `:core:*` to KMP, swapping Hilt for
Koin and replacing the Robolectric tests would deliver no user-visible value against a single ship
target. §9 is what keeps that deferral safe.

---

## 9. Keeping a second platform reachable

### 9.1 The stack supports it

1. **The bridge is a network service** (§8). A second client opens a socket and parses JSON — no
   JVM, no `mage-common`, no Java-serialization interop.
2. **Every dependency in use is already multiplatform:**

| Dependency | Used by | Status |
|---|---|---|
| Ktor client 3.5.1 | `:core:network` | Multiplatform — OkHttp on Android, NSURLSession on iOS |
| kotlinx-serialization 1.11 | `:protocol`, `:core:network` | Multiplatform |
| kotlinx-coroutines 1.10 | everywhere | Multiplatform |
| Coil 3.1.0 | `:core:cards` | Multiplatform since 3.0 — one `AsyncImage` in `commonMain` |
| Room 2.7.1 | `:core:decks` | Multiplatform since 2.7 |
| **Raw `android.database.sqlite`** | `:core:cards` catalog | **Android-only, and not covered by Room's KMP support** — `SqliteCardCatalog` (377 lines) uses `SQLiteDatabase`/`Cursor` directly, and the 14 MB asset is opened through `Context`. The largest single port in the module list |
| DataStore 1.1.7 | prefs | Multiplatform |
| Lifecycle / ViewModel 2.9 | `:feature:*` | Multiplatform |
| Material 3 / Compose Foundation | `:core:designsystem` | Compose Multiplatform |
| **Hilt** | DI, every module | **Android-only — the one real swap** (→ Koin or a hand-written graph) |
| **Robolectric** | `:core:cards`, `:core:decks`, `:feature:game` tests | Android-only; those tests would move to common/JVM |

3. **Compose Multiplatform's iOS target is stable** (1.8.0, May 2025) with substantial production
   adoption.

### 9.2 The discipline that keeps it cheap

These cost nothing applied from the start, are expensive to retrofit, and are independently good
Android practice:

- **Keep Android APIs out of the logic layers.** `:core:model`, `:core:network`, `:core:decks` and
  the non-UI half of `:core:cards` should need only Kotlin, coroutines, serialization and Ktor.
  Device-specific needs (storage paths, notifications, secure storage, haptics) sit behind an
  interface at the module boundary, which is where an `expect`/`actual` would go.

  **`:protocol` and `:core:model` hold this today** — neither imports anything from `android.*` or
  `androidx.*`. They are the two modules a second client consumes first, and they are portable now.

  `:core:network` and `:core:cards` do not, and the two cases are worth telling apart because one
  is the pattern working and the other is the debt:

  - **Working.** Connectivity is already an interface — `ConnectivityObserver` (26 lines) with
    `AndroidConnectivityObserver` (57 lines) behind it. `ConnectivityManager` never leaks past that
    boundary. This is exactly where an `expect`/`actual` or a platform module would slot in.
  - **Debt.** `ProcessLifecycleOwner` for the foreground/background reconnect hook (story 0024),
    `Context` reaching into `ServerRepository` and the DI modules, and the card catalog's direct use
    of `SQLiteDatabase`. Each is small on its own; the catalog is not.
- **Check multiplatform support before adopting a dependency in a `:core:*` module.** One
  Android-only library there turns a mechanical port into a rewrite. Choosing one anyway is fine —
  knowingly, and above the logic layers.
- **Insets in exactly one place.** `:core:designsystem/layout/Insets.kt` stays the only handler.
- **Hardware Back is never the only path.** Every cancel affordance also exists on screen — already
  required by §7.1.
- **`:protocol` stays free of platform types.** It is the module a second client consumes first.

### 9.3 What a second platform would additionally need

Not scheduled; recorded so it is not rediscovered:

- **Transport security.** iOS App Transport Security requires WSS; the bridge serves plain WebSocket
  to a LAN address. Story 0068's TLS/nginx work would be a prerequisite.
- **Push notifications.** FCM vs APNs, and "it's your turn" (EPIC-05) needs a bridge-side push
  service that does not exist yet.
- **Background socket behaviour.** iOS suspends sockets aggressively; the reconnect/resync path
  (0024, 0070, 0074) would need platform testing, not redesign.
- **The bundled card catalog** (~14 MB SQLite asset, `AssetManager`-based) would need a
  multiplatform resource story.
- **Coil's network layer** would move from `coil-network-okhttp` to `coil-network-ktor3`.
- **App Store review.** An unofficial Magic client showing Wizards' card names and hotlinked art has
  a different risk profile in Apple's review than a sideloaded APK.

---

## 10. Performance

**No frame-rate target.** Build the board the way §7 describes and assume it performs. If some
situations do not hold 60 fps, that is acceptable for now — a dropped frame during a trigger chain
costs far less than a design bent around a budget nobody has measured a need for.

Two things are worth doing anyway, because they are about the experience rather than the frame:

1. **Art pipeline.** Two decoded sizes — Board tier and Full tier (§7.5) — never one, since decoding
   full-resolution card art for a battlefield is wasteful on memory to no visible benefit. Prefetch
   our own deck at match start — we submitted it, so we know exactly what is in it and the first turn
   need not wait on the network. The opponent's deck is hidden and cannot be prefetched.
2. **Snapshot payload size.** [`architecture.md`](architecture.md) open question #7 — how much of
   `GameView` a phone needs per frame, and whether to delta it — is still open. It matters for
   mobile data, not for rendering. Measure real payloads before deciding anything.

---

## 11. Roadmap

Android only. Each phase is independently valuable and the app keeps working throughout.

### The new UI is built alongside the old one, not in place of it

**Nothing described in §7 replaces an existing screen.** The new components ship as additional
surfaces reached from new entry points, and the current screens keep working untouched.

The reason is diagnostic. When something misbehaves on a new surface, the question is always "is
this a bug in the new UI, or did something underneath actually break?" — and with both UIs live over
the **same** `:core:network` state, that question is answerable in one tap: open the old screen and
see whether the data is right there. Without the old screen, every new-UI defect starts as an
investigation into the whole stack. Story 0076 took four rounds partly because a rendering symptom
and a mapping fault were hard to tell apart.

Entry points:

- **Sign-in stays exactly as it is.** No new variant, no changes.
- **"New Deck Builder"** — offered alongside the existing deck library entry.
- **"New Battlefield"** — offered when the table is ready to play, alongside the existing path into
  the board.

Two consequences worth being deliberate about:

- **The old code is not edited to accommodate the new code.** Anything shared moves behind an
  interface rather than being modified in place, so a regression cannot be introduced into the old
  path by work on the new one. That is the whole value of keeping it.
- **This is temporary, and needs an end.** Carrying two UIs indefinitely doubles the surface for
  every subsequent change. Each old surface is removed once its replacement is at parity and has
  been played on — a judgement call per surface, made deliberately rather than by drift.

### Immediate cleanup, independent of the above

**Remove the deck picker from the table room** (§1.2) — not just its radio buttons. The deck is
chosen at host or join time, so the room re-asking a settled question is the actual fault.

`TableRoomViewModel` keeps `submitDeck`/`updateDeck`, and the submission itself may still need to
fire from the room rather than at join. So the change is to drive it from the deck already chosen
instead of re-prompting for one. **Confirm against the join path before editing** whether the
server-side `deckSubmit` happens at join or in the room — that determines whether the room submits
silently on entry or has nothing left to do.

A defect in the current UI, not a new-UI item, and not worth waiting for a rebuild.

### Phase 1 — Design system and the animation host

New tokens (colour, type, elevation, **motion**), the three-tier card component family (§7.5), and
the Prompt component (§7.2).

The palette follows §7.4: a grey scale deep enough to separate zones by value alone, with saturated
colour reserved for information — highlight, targeting, combat, pending cost, threat. Getting that
split right in the tokens is what keeps every later surface consistent, so it is worth settling
before components are built on top of it.

The **object-identity animation host** (§7.3) is built here, before the board depends on it, driven
by a recorded sequence of real snapshots so the sequencing rules are exercised against real timing
rather than a synthetic one.

### Phase 2 — The cast flow

Separate from the board because it spans three modules and it is the only phase whose failure mode
is submitting a wrong action to a live game rather than looking bad.

- **2a — trace upstream.** The real prompt sequence for a cast carrying additional costs, read from
  `HumanPlayer` in `../mage`. No design work until this is written down.
- **2b — the intent contract.** `CastIntent` in `:protocol` and the bridge-side player for it,
  tested headlessly against a real server — including the bail-out path, which is the part that
  matters. Defines disconnect-mid-playback against 0074.
- **2c — the UI.** Additional costs first with pending costs highlighted, server-proposed mana as
  the editable default, land tapping per §7.7, one Confirm.

### Phase 3 — The board

**Pete-led design session first.** The rest of P0. The largest phase; broken into stories only after
that session, against §7.

### Phase 4 — The rest of the app

Home, lobby, tables, deck library and builder v2, card search, settings, on the Phase 1 system.
P1 items 27, 29 and 30.

### Phase 5 — Polish

P2 items. Undo (P1 #31) lands here or earlier, once the cast flow shows what it still needs to
cover.

### Deferred

- **Multiplatform foundation** — `:core:*` to KMP, Hilt → Koin, Coil → `coil-network-ktor3`,
  Room → KMP, Robolectric tests → common. Prerequisite for any second target; §9.2 keeps it cheap.
- **iOS** — §9.3.
- **Desktop** — the same prerequisite (§12).

### Epics

Written up in [`project-plan.md`](project-plan.md), which is where they live. In summary:

| Epic | Carries | Phase |
|---|---|---|
| **EPIC-19 — Motion & Board Presentation** | §7.3 motion, §7.4 layout, piling, attachments | 1 and 3 |
| **EPIC-20 — Declared Cast Intent** | §7.6 cast flow, §7.7 land tapping | 2 |
| **EPIC-23 — Game Information We Do Not Yet Map** | targets, attachments, player counters, `commandList`, zone contents, token identity | before what needs it |
| **EPIC-24 — The Game Log** | §7.12 | any time |
| **EPIC-03** | §7.2 Prompt, §7.5 card tiers, the grey/information palette | 1 |
| **EPIC-11** | §7.13 zone browser, §7.14 stack entries, §7.15 vitals | 3 |
| **EPIC-12** | §7.9 priority passing, the phase bar | 3 |
| **EPIC-13** | §7.2 Prompt states, §7.8 trigger ordering, spatial targeting and combat | 3 |
| **EPIC-14** | §7.16 mulligan and match flow | 3 |
| **EPIC-05** | §7.17 notices and session state | 3 |
| **EPIC-18 / 21 / 22** | multiplatform foundation, iOS, desktop | deferred |

The board work concentrates in EPIC-19 and the mapping it depends on in EPIC-23, which is why
EPIC-23 is worth starting early: several of its items improve the current UI on their own.

---

## 12. Open questions

1. **Automation defaults.** Auto-tap and auto-assign-damage are proposed **off** by default (§3.2),
   on the grounds that our audience chose an XMage client. Confirm, or say which should default on.
2. **Art choice mechanism** (§7.11). Should a chosen alternate printing be **registered in the
   deck** — travelling to the server and coming back in the game view — or held as a **local display
   override** applied at render time? The first changes what we submit; the second never touches the
   game and is the only one that could also apply to the opponent's cards. Or both.
3. **Investigations to schedule.** Two remain. The **real prompt sequence for a cast with additional
   costs** (§7.6) is already scheduled as Phase 2a. The other is **whether the server's game log
   carries interim actions** we would have to filter (§7.12) — cheap to answer from one recorded
   game, and it decides whether the log is a render or a render plus a filter. Run it now, or when
   the log is built?
4. **Token art source** (§7.11). Resolve token art from Scryfall by name, subtype, P/T and colour —
   consistent with every other card, but a fuzzy match — or use XMage's own token images, which the
   server has already resolved and the desktop client uses, at the cost of a second art source. The
   catalog holds neither today, so offline token art needs a generator change either way.
5. **Poison and the other player counters** (§7.15) are unmapped, and poison is a win condition. This
   is small, self-contained, and not really a board-rebuild item — worth doing against the current
   UI now rather than waiting for the new one?
6. **Desktop.** The only near-term reason to do the deferred KMP port: a desktop board would run
   against the bridge with no emulator, no APK install and no `adb`, which is the fastest path to
   eyes on the board. Worth it as a development accelerator, or leave it deferred?
7. **Lift §9.2's portability rules into [`AGENTS.md`](../AGENTS.md)?** They are cheap now and
   expensive to retrofit. `AGENTS.md` is canonical, so this is a question rather than an edit.
8. **[`game-board-requirements.md`](game-board-requirements.md) §16.1 specifies portrait** and is
   now out of date against §7.4. Want me to correct it, or leave that doc alone?

---

## 13. Sources

MTG Arena:
- [All 15 Settings in MTG Arena Explained — Draftsim](https://draftsim.com/mtg-arena-settings/)
- [Arena Hot Keys and Interface Guide — MTG Arena Zone](https://mtgazone.com/arena-hot-keys-and-interface-guide-simplify-your-game-with-these-easy-tricks/)
- [MTG Arena Hot Keys and Using the Interface — Flipside Gaming](https://flipsidegaming.com/blogs/magic-blog/mtg-arena-hot-keys-and-using-the-interface)
- [All MTG Arena Keyboard Shortcuts — MTGA Assistant](https://mtgaassistant.net/Article/All-MTG-Arena-Keyboard-Shortcuts)
- [MTG Arena Hidden Advanced deck builder options — Aetherhub](https://aetherhub.com/Article/MTG-Arena-Hidden-Advanced-deck-builder-options)
- [Magic Arena Deck Building Guide — Grand Screen](https://grand-screen.com/tcg/mtg/magic-arena-deck-builder-guide/)

MTG Online:
- [Gameplay: Tips and Tricks — mtgo.com](https://www.mtgo.com/getting-started/getting-started-tips-tricks)
- [Gameplay: Duels & Solitaire — mtgo.com](https://www.mtgo.com/getting-started/getting-started-gameplay)
- [Gameplay: Multiplayer & Commander — mtgo.com](https://www.mtgo.com/getting-started/getting-started-multiplayer)
- [Magic Online: Hotkeys — Wizards support](http://wizards.custhelp.com/app/answers/detail/a_id/646/~/magic-online-hotkeys)

Card data:
- [Card Objects — Scryfall API](https://scryfall.com/docs/api/cards) — `all_parts` / Related Card
  objects, which is how a card names the tokens it generates
- [Set Objects — Scryfall API](https://scryfall.com/docs/api/sets) — `set_type`, including `token`

Platform:
- [Compose Multiplatform — kotlinlang.org](https://kotlinlang.org/compose-multiplatform/)
- [Multiplatform image loading: Coil 3.0 — Cash App Code Blog](https://code.cash.app/multiplatform-image-loading)
- [Using Jetpack Room in Kotlin Multiplatform shared code — John O'Reilly](https://johnoreilly.dev/posts/jetpack_room_kmp/)
- [Kotlin Multiplatform — Android Developers](https://developer.android.com/kotlin/multiplatform)
