# Project Plan

The feature plan for Mage Free Client, organized into **epics**. Each epic describes, in
plain language, what the feature is and what it should do. Implementation-level breakdown
and ordering live in the numbered story documents (see [`stories/`](stories/), produced from
this plan).

Read [`architecture.md`](architecture.md) (how we integrate with the server),
[`ux-principles.md`](ux-principles.md) (how the UX diverges from desktop) and
[`ui-modernization-plan.md`](ui-modernization-plan.md) (**the UI/UX specification** — every
epic below that touches the interface is implemented against it) alongside this.

---

## Goal

A native Android client that can do **everything the XMage Java desktop client can do
against a server** — connect, browse and join tables and tournaments, build and import
decks, and play full rules-enforced games — with **behavioral and protocol parity** to the
desktop client. The server stays authoritative; we implement no MTG rules on device.

The UX is **phone-native and rebuilt from scratch.** It borrows the *organizing principles*
of Magic: Arena's interface — a home hub with a prominent path to play, a first-class deck
manager, an immersive full-screen game view, decisions brought to the player, tap-first
interaction — **without using any of its IP, art, or assets.** Everything visual and
interactive is original.

## Foundations (decided)

These are settled; details in [`architecture.md`](architecture.md). Epics assume them.

- **Bridge (Option A).** A headless Kotlin/JVM (Ktor) service reuses XMage's own client
  library and re-exposes WebSocket + JSON to the app. The app never speaks XMage's native
  wire protocol.
- **Monorepo.** Bridge and app share this repo; the bridge↔app schema is a versioned
  contract both build against.
- **Correctness first.** Every feature is verified against a locally-run XMage server. We
  don't build UI against invented game states; fakes used in tests are recordings of real
  bridge output.
- **Auth** is XMage's own account auth, proxied through the bridge.
- **Self-hosted server.** We run our own version-pinned XMage server, so the bridge/client is
  always version-matched and we upgrade on our own schedule (XMage enforces exact-version
  lockstep). See [`architecture.md`](architecture.md).
- **Android, Kotlin and Compose.** One **ship** target, and the one the work is done on. A minimum
  desktop build exists to demonstrate the client is portable (EPIC-22), not as a product and not as
  a development surface; iOS is not being built.
- **The shared logic is multiplatform, and that is verified by compiling it.** `:core:*` and
  `:protocol` carry a JVM target that builds in CI, so portability is a build result rather than a
  claim (EPIC-18). This runs ahead of the UI rebuild, because its cost scales with how much code
  exists when it happens.
- **The new UI is built alongside the old one.** New surfaces ship from new entry points
  ("New Deck Builder", "New Battlefield") over the same `:core:network` state, and the current
  screens keep working untouched. This is diagnostic: with both live, "is this a new-UI bug or a
  real regression?" is answerable in one tap. Old code is not edited to accommodate new code —
  anything shared moves behind an interface. Each old surface is retired once its replacement is
  at parity and has been played on.

## Out of scope

Permanently deferred. Not later increments — do not spend stories on them, and do not list them
as future work.

- **Chat of any kind** (lobby, in-game, whisper), **emotes**, and **player presence and social
  data** — profiles, online status, friends, invites, mentions. The only thing needed from that
  surface is **seeing joinable tables**, which is EPIC-06 and is table state, not presence.
- **Accessibility**, including screen-reader support and a11y semantics.
- **Sound design and haptics.**
- **More than two players** — free-for-all, Commander pods, Two-Headed Giant. The server supports
  them; the phone-landscape board layout does not.
- **Clocks and rope burn-down**, beyond displaying a time the server actually sends.

One thing that looks like chat and is not: the **server's game log** arrives as
`ChatMessage.MessageType.GAME` and is mapped to `ChatKind.GAME`. It is a wanted feature
(EPIC-24). The deferred kinds are `TALK`, `WHISPER_IN` and `WHISPER_OUT`.

## How to read the epics

Epics are grouped by area and carry stable IDs (`EPIC-01`, …) so the story documents can
reference them. **IDs are not implementation order** — sequencing is decided when stories
are written. Each epic lists a short "What it is" and representative user stories in the
form *As a player, I want … so that …*.

---

## Platform & Foundation

### EPIC-01 — Bridge & Server Integration
**What it is:** the headless service that connects to a real XMage server using the desktop
client's session library, and relays the full server contract to the app over a modern
JSON/WebSocket protocol. It authenticates, holds the session alive (ping/keepalive,
reconnect), relays the room/table/deck/match calls, drives the game callback loop, and maps
server view objects into the app's schema.

- As the app, I want a single modern endpoint that exposes every server capability the
  desktop client uses, so that the client never touches the legacy transport.
- As a maintainer, I want server view objects mapped to a versioned app schema at one
  boundary, so that upstream changes are absorbed in one place.
- As the app, I want the bridge to relay server→client push (game updates, prompts, the game
  log) faithfully, so that gameplay stays in lockstep with the server.

### EPIC-02 — App Shell & Navigation
**What it is:** the Arena-style information architecture — a home hub with a prominent path
to play, and top-level destinations (Play, Decks, Settings). Connection status is always
visible; the in-game experience is a separate immersive full-screen mode.

- As a player, I want a home screen with an obvious way to jump into a game, so that playing
  is one tap away.
- As a player, I want clear top-level sections for playing and for decks, so that I
  always know where things live.
- As a player, I want to always see whether I'm connected, so that I trust the app's state.
- As a player, I want **decks and card browse reachable before I sign in**, so that the offline
  half of the app is not behind a connection it never uses. They mount in the **root** graph
  alongside the connect flow, so the shell still requires a live session
  ([`ui-modernization-plan.md`](ui-modernization-plan.md) §7.18; carried by EPIC-25).

### EPIC-03 — Design System & Theming
**What it is:** the original Material 3-based visual system — theme, typography, colour,
**motion tokens**, and the reusable card-forward components. Specified by
[`ui-modernization-plan.md`](ui-modernization-plan.md) §7.2, §7.5 and §7.4.

Two things make this more than a re-skin. The **palette is a grey scale deep enough to separate
zones by value alone**, with saturated colour reserved exclusively for information — playable-now,
targeting, combat, pending cost, threat. And the card component is a **three-tier family** —
Board (cropped art, name, P/T, counters, tap state), Tile (full card, downsampled), Full (oracle
text, current modifications, activatable abilities, flip control) — where only Full loads
full-resolution art.

- As a player, I want one coherent look across every screen, so that the app feels
  deliberate and trustworthy.
- As a player, I want the only bright colour on the board to mean something, so that I can find
  what the game is telling me without hunting.
- As a player, I want full dark mode and readable type at my chosen size, so that it's
  comfortable in any setting.
- As a developer, I want one card component family and one Prompt component, so that every
  surface presents cards and decisions identically.
- As a developer, I want **every surface landscape with no rotation support**
  ([`ui-modernization-plan.md`](ui-modernization-plan.md) §7.19), so that no screen carries a
  second layout. During the alongside period the new surfaces request landscape at runtime; it
  becomes a manifest attribute once the portrait screens are gone.
- As a developer, I want the design system's **text field to set `flagNoExtractUi`**, so that
  typing in landscape does not replace the screen with a fullscreen IME panel. It is the one
  landscape hazard that is cheap to fix centrally and expensive to discover per screen.

---

## Connectivity & Account

### EPIC-04 — Server Connection & Sign-In
**What it is:** choosing or adding a server, signing in with an XMage account (proxied auth),
and seeing live connection state. **Account registration is deferred**, including the email
flow — accounts are created elsewhere.

- As a player, I want to add and pick a server and sign in, so that I can reach my games.
- As a player, I want clear feedback on connecting, connected, and failed states, so that I
  know what's happening.

### EPIC-05 — Session Resilience & Notifications
**What it is:** surviving backgrounding, rotation, and network drops without losing the game,
with automatic reconnection; the **notice surface** that tells the player what the session is
doing ([`ui-modernization-plan.md`](ui-modernization-plan.md) §7.17); and the one push
notification that is about the game rather than about people — "it's your turn."

A notice is a statement about the game or session that is **not a decision**, so it must never
compete with the Prompt. The load-bearing case: reconnect restores the outstanding prompt and the
board **snaps** to current state rather than replaying. Without a notice saying so, that snap is
indistinguishable from a turn having happened while the player was away.

- As a player, I want the app to reconnect and restore my game after a drop or backgrounding,
  so that a flaky connection doesn't cost me the match.
- As a player, I want to know when I'm disconnected and when I've resynced, so that a board that
  jumped doesn't read as something my opponent did.
- As a player, I want the board to keep showing the last state it knew rather than blanking, so
  that a stale board I know is stale is still useful.
- As a player, I want to be told when my opponent concedes, times out or leaves.
- As a player, I want a push notification when it's my turn while the app is backgrounded, so
  that I never stall a game by accident.

---

## Lobby & Getting Into Games

### EPIC-06 — Lobby & Game Browser
**What it is:** browsing rooms, open tables, and active/watchable games, with useful
filters/sorting — the surface behind the home "Play" path. **This is the only social-adjacent
surface in scope**, and it carries no presence data: a table listing is table state, including
the seat names the server puts in it.

- As a player, I want to browse open tables and games with filters, so that I can find
  something to play or watch.
- As a player, I want to see who is seated and what the table's settings are at a glance, so
  that I can choose wisely before joining.

### EPIC-07 — Hosting & Joining Tables
**What it is:** creating a table (constructed or limited, with match options and seats),
joining open tables, readying up, submitting a deck, and inviting/spectating.

- As a player, I want to create a table with the format and options I choose, so that I can
  host the game I want.
- As a player, I want to join an open table and submit my deck, so that I can get into a
  match.
- As a host, I want to manage seats, so that I control the table I made.
- As a player, I want the table room to **use the deck I already chose** rather than asking
  again, so that a settled question stays settled. The room currently re-renders the deck picker
  with nothing selectable; the deck is chosen at host or join time and the room should submit
  from it.

### EPIC-08 — Tournaments & Limited (Draft / Sealed)
**What it is:** joining and hosting tournaments, and the phone-adapted limited flows — the
draft pick loop and sealed pool building.

- As a player, I want to join and host tournaments, so that I can play structured events.
- As a player, I want a touch-friendly draft pick screen with a timer, so that I can draft
  comfortably on a phone.
- As a player, I want to build from a sealed pool on my phone, so that I can play sealed
  without a desktop.

---

## Decks & Cards

### EPIC-09 — Deck Management & Building
**What it is:** the deck library (list, create, duplicate, rename, delete, favorite) and a
touch-first builder — search/filter the card database, add/remove, sideboard, with mana-curve
and format-legality feedback. XMage-compatible (`DeckCardLists`) import and export, and deck
sharing.

- As a player, I want to manage a library of my decks, so that I can keep and reuse them.
- As a player, I want to build and edit a deck with fast add/remove and legality feedback, so
  that deckbuilding works well on a phone.
- As a player, I want to import and export decks in XMage's format, so that my decks move
  between clients.

**How it is reached and what it becomes are EPIC-25.** This epic is the deck library, the
current builder and the import/export pipeline; the pre-sign-in mount and the builder rebuild
are [`ui-modernization-plan.md`](ui-modernization-plan.md) §7.18.

### EPIC-10 — Card Database, Search & Inspection
**What it is:** a searchable, filterable card catalog used by the builder and gameplay; a
first-class full-bleed card inspection view (including current in-game modifications and
activatable abilities); and efficient on-device caching of card images and static set data.

The catalog is a bundled 14 MB `cards.sqlite` generated from XMage's own card data by
`tools/card-catalog-generator`. It holds three tables — `meta`, `card`, `printing` — carrying
name, mana cost and value, colours, supertypes/types/subtypes, oracle text, power, toughness,
loyalty, the split/flip/double-faced/meld flags, and per-printing set code, collector number
and rarity. **It holds no rulings, no related-parts data and no per-format legality** — legality
is a separate bundled `formats.json`, and related parts are a Scryfall concept the generator has
no source for (§7.11 #5).

- As a player, I want to search and filter all cards, so that I can find what I need for a
  deck or a decision.
- As a player, I want to **search oracle text, power and toughness, and set**, so that I can
  find cards by what they do rather than only by what they are called. Every one of those
  columns is already in the catalog and none of them is queried today (§7.18 #3).
- As a player, I want to tap any card for a large, readable, full-detail view, so that I can
  actually read it on a small screen.
- As a player, I want card art and data to load fast and work offline once cached, so that
  the app stays responsive on mobile data.

---

## Playing a Game

### EPIC-11 — Game Board & Presentation
**What it is:** the board's **content** — what is shown and what can be opened. How it is laid
out and how it moves is EPIC-19. Specified by
[`ui-modernization-plan.md`](ui-modernization-plan.md) §7.13, §7.14 and §7.15.

- As a player, I want a board that highlights what matters now instead of cramming
  everything on screen, so that I can read the game at a glance.
- As a player, I want a **zone browser that opens any zone for either player in one
  interaction** — graveyard, exile, revealed, looked-at — floating over the board so it never
  costs the battlefield space. The command zone is not one of them: its contents belong to a
  player, so they read in that player's vitals overlay.
- As a player, I want **exile grouped by the zone that made it**, with castable cards marked, so
  that a plotted card, a rebounded spell and an ordinary exile are told apart.
- As a player, I want **each stack entry to show its source art, its own rules text, a link to
  the permanent that produced it, and its targets**, so that I know what to respond to, what
  caused it, and what it will hit.
- As a player, I want **life, library and hand counts, poison and other player counters, monarch
  and initiative, my mana pool and the match score** available on each player without hunting, so
  that I never lose to something the board didn't show me. It is one expandable overlay: collapsed
  to counts and colour — life red, poison green from the moment it is non-zero — and expanding to
  the counters, designations and emblems in full.
- As a player, I want turn and phase always clear, so that I never lose track of game state.
- As a player, I want card art to load the way the desktop app's does, with an easy way to
  facilitate the download, and **an indeterminate spinner while art is in flight** so I can tell
  "downloading" from "no art exists."
- As a player, I want to choose which art the cards in my deck use where a card has alternative
  art, **and which art its tokens use** — recorded in the deck, so the choice travels with the
  decklist and is what renders in play.

### EPIC-12 — Priority, Stack & Taking Actions
**What it is:** passing priority, playing lands, activating abilities, and the phase bar.
**Casting is EPIC-20.** Specified by [`ui-modernization-plan.md`](ui-modernization-plan.md) §7.9.

Priority passing is the server's own model, not an invention: two actions, pass once or pass
until the stack resolves, the second interrupted when something new is put on the stack. The
server owns that interrupt, and it is **conditional on a user preference we must set** and on
being the active player (EPIC-17).

- As a player, I want to play cards and activate abilities with a clear tap, so that acting
  is obvious and reliable.
- As a player, I want to see the stack build and resolve, and respond to it, so that I can
  play at instant speed.
- As a player, I want exactly two ways to pass — **once, or until the stack resolves** — so that
  I'm not managing a ledger of standing automation.
- As a player, I want passing-until-the-stack-resolves to **stop when something new is added**,
  so that I never miss a spell I hadn't seen.
- As a player, I want a **phase bar with per-phase, per-player stops**, so that the game only
  stops for me when I want it to.
- As a player, I want to see when an auto-pass is running, so that I know why I'm not being
  asked.

### EPIC-13 — Targeting, Choices & Combat
**What it is:** every server-driven decision surfaced through the Prompt, with the ones that
concern the board answered **on the board**. Specified by
[`ui-modernization-plan.md`](ui-modernization-plan.md) §7.2, §7.8 and §6 P0 6–7.

The Prompt has three states — idle, asking, and board-interactive — and in the third it shrinks
to a header, a progress count and Confirm/Cancel, and **never blocks the board**.

- As a player, I want decisions to come to me as a clear prompt I can't miss, so that I never
  stall the game without realizing.
- As a player, I want **legal targets to light up on the board and an arrow drawn to each one I
  pick**, so that targeting is a thing I do to the game rather than to a list.
- As a player, I want to confirm before a targeting choice is submitted, so that I don't
  misfire on a small screen.
- As a player, I want **attackers to move into a red zone and blockers to connect with arrows**,
  with declaring attackers and assigning blocks kept as separate steps, so that combat is
  spatial and unambiguous.
- As a player, I want to **order simultaneous triggers by dragging them into the arrangement I
  want**, seeing the stack as it will be, with the top card resolving first.
- As a developer, I want the ordering interaction as **one reusable, separately unit-tested
  component**, because an off-by-one reversal between "the order I chose" and "the order the
  server is asking for" is silent and game-losing.

### EPIC-14 — Game Setup, Mulligans & Match Flow
**What it is:** pre-game setup (starting player, opening hand), the mulligan decision, and
match flow across a best-of-N — game/match results, sideboarding between games, and
conceding. Specified by [`ui-modernization-plan.md`](ui-modernization-plan.md) §7.16.

The board is entered from a ready table, so **this is the first thing it renders** — a board
that cannot start a game is not a board.

- As a player, I want a clear opening-hand and mulligan flow, so that I start each game well.
  The London mulligan's second question is a selection of N and doesn't involve the board, so it
  is a full-card surface rather than a board interaction.
- As a player, I want to sideboard between games in a match, so that I can adapt.
- As a player, I want to see results and concede cleanly, so that matches end gracefully — with
  concede always reachable and hard to hit by accident.

### EPIC-15 — Spectating
**What it is:** watching a game in progress with the same board presentation, read-only.

- As a player, I want to watch an ongoing game from the lobby, so that I can learn from it.
- As a spectator, I want the same clear board view without controls, so that watching is as
  readable as playing.
- As a spectator, I want to be able to see all the hidden information that either player's game 
  client has access to, not just the information of a single player.

---

## Settings

**EPIC-16 is retired.** Chat and player presence are out of scope; the id is not reused.

### EPIC-17 — Settings & Preferences
**What it is:** connection defaults, gameplay preferences, notification controls, and theme.

Some preferences are **ours** and some are the **server's**, and the difference matters.
`isStopOnStackNewObjects` lives on the XMage user record and is what makes "pass until the stack
resolves" break when something new is added (EPIC-12) — we must set it, not merely expose it.

- As a player, I want to set connection and gameplay defaults, so that the app fits how I
  play.
- As a player, I want **nothing automated** — no auto-tap, no auto-assign-combat-damage, and no
  toggles for them yet — because I chose a fully rules-enforced client and shouldn't have decisions
  taken silently. These are a later enhancement: automation is additive to a client that already
  answers every prompt manually, while the reverse has to be unwound.
- As a player, I want **Full Control as a pinned mode**, since there is no held modifier key on
  a phone.
- As a player, I want a **reduce-motion setting that shortens animations rather than removing
  them**, because removing them removes information.
- As a player, I want notification and theme controls, so that the app fits my environment.

---

## The Rebuild

Seven epics carrying the work in [`ui-modernization-plan.md`](ui-modernization-plan.md) that
does not fit an existing epic. §11 there sequences them.

**EPIC-18 and EPIC-22 come first** despite their position here — the foundation, and the build that
demonstrates it worked, precede the UI work that would otherwise have to be ported afterwards. EPIC-23 does not depend on
either and can run alongside. **EPIC-25's first half — the pre-sign-in mount — is immediate work
too**: it is a mounting point plus an entry point on a feature that already runs offline.

### EPIC-19 — Motion & Board Presentation
**What it is:** how the board is laid out and how it moves. The largest piece of UI work in the
project, and the one the rest of the board depends on. Specified by §7.3, §7.4 and §7.5.

**An animation exists because a game action happened.** It is not decoration and not a
transition between two renderings — in a hidden-information game, motion is the only channel
that carries causality. That purpose decides every rule: every state change gets its turn on
screen and they play in order rather than collapsing to the final state; presentation may trail
the server, because a player watching a sequence has no decision to make during it; being asked
to act is the sync point; and a resync **snaps**, because replaying it would narrate events the
player already missed.

The layout is two layers. The **base** — both battlefields, the hand, vitals — never moves. The
**stack, combat assignment, revealed cards, the Prompt and notices float over it**, so transient
information can come and go without the battlefield reflowing for reasons that have nothing to do
with the game.

- As a player, I want cards to **travel between zones** instead of teleporting, so that I can see
  what happened rather than inferring it from a changed board.
- As a player, I want a **battlefield that stays where I left it** when a trigger goes on the
  stack or combat begins, so that the board isn't re-animating for non-game reasons.
- As a player, I want **creatures in front, other permanents behind them, and lands piled tightly
  at the back**, so that the things about to matter are the things I see first.
- As a player, I want identical permanents **piled into a fan of at most three with a count**, so
  that ten Plains cost the width of three cards.
- As a player, I want piling to be **strict** — only identical objects in identical states — so
  that a pile never hides a counter, a temporary ability, summoning sickness or damage. A
  permanent carrying an attachment never piles at all, because an aura is on *that* creature.
- As a player, I want **Auras and Equipment rendered on what they're attached to**, so that
  Pacifism reads as turning off a creature rather than as a loose permanent.
- As a player, I want **castable and activatable objects visually distinct everywhere**, so that
  "can I do this?" is never a question I have to ask.
- As a player, I want the **stack as an expandable pile floating over the board, present only
  when something is on it**, so that a stack I must respond to isn't a 56 dp strip.
- As a player, I want **counters, P/T changes, tap state and status on the card face**, so that I
  can read the board without inspecting every permanent.
- As a player, I want **tokens marked as tokens**, because a token that leaves the battlefield
  ceases to exist and that changes how I trade, bounce and sacrifice.
- As a player, I want **no chrome and no empty regions** — no banners, no borders, no band
  holding height to show it's empty — so that the space goes to the cards.
- As a player, I want the board designed for **my phone held sideways**, one layout, with tablets
  rendering it scaled.
- As a developer, I want the **animation host built and proven against recorded real snapshots
  before the board depends on it**, so that sequencing is exercised against real timing.

### EPIC-20 — Declared Cast Intent
**What it is:** making casting one fluid act. Spans `:protocol`, `:bridge` and `:feature:game`.
Specified by §7.6 and §7.7.

The server asks for a cast as an ordered sequence of separate questions — announce, special
actions, modes, targets, mana — and rendering that faithfully produces the dialog chain the
current client has. Instead the client assembles a **complete declared intent** locally and the
bridge answers the server's prompt sequence as a batch. The intent player lives in the bridge
because the prompt grammar is XMage-specific and order-dependent, and because the bridge can be
tested headlessly against a real server.

**The safety rule: the client may change the form of the conversation; it may never invent the
content of an answer.** On any prompt the intent does not unambiguously answer, the bridge stops,
rewinds if it can, and hands that prompt to the client. This is the only epic whose failure mode
is submitting a wrong action to a live game.

- As a player, I want casting a spell to be **one act with one Confirm**, not a chain of dialogs.
- As a player, I want to **choose delve exiles and convoke creatures first**, with the objects
  they'll consume highlighted, and mana solving only for the remainder.
- As a player, I want the **server's proposed mana payment offered as an editable default**,
  because cost-modifying effects mean I shouldn't trust arithmetic done on the phone.
- As a player, I want **tapping a land to just tap it for mana** when there's only one thing it
  could produce, and to be asked only when the choice is real.
- As a player, I want **only mana abilities offered mid-cast**, since nothing else can be
  activated then.
- As a player, I want to **tap a tapped land to untap it**, so that fixing a payment is another
  tap rather than a cancel.
- As a player, I want **meeting the cost never to fire the cast** — completion is always
  explicit, because which mana paid isn't always cosmetic and I want the last chance to back out.
- As a player, I want **Cancel before Confirm to cost nothing and touch no server state.**
- As a developer, I want the **upstream prompt sequence for a cast with additional costs traced
  and written down before any design work** — read from the local XMage source, then confirmed
  against a real game with an instrumented build Pete plays and returns the logs from. Source says
  what the server *can* send; only a live game says what it *does*.
- As a developer, I want **disconnect mid-playback defined against resync**, so that a drop
  between "intent submitted" and "cast complete" lands the player somewhere truthful.

### EPIC-23 — Game Information We Do Not Yet Map
**What it is:** bridge and protocol work, not UI. The server sends this correctly today and we
discard it at the mapping boundary, so no amount of rendering effort reaches it. Several items
are independently useful against the **current** UI and needn't wait for the rebuild. Amends
EPIC-01.

- As a player, I want to **see my poison counters**, because poison is a win condition and the
  board currently cannot show it. Also energy and experience (`PlayerView.counters`).
- As a player, I want to see **monarch, initiative and designations**, which decide games and
  live nowhere on the battlefield.
- As a player, I want all of that in **one expandable overlay on each player's vitals** — collapsed
  to counts and colour, expanding to the full list — so that state which is usually zero costs
  almost no board space and is one tap away when it is not.
- As a player, I want to see **what a spell or ability on the stack is targeting**, including
  when it targets another spell — `CardView.targets`, which upstream computes for exactly this
  purpose and we map nowhere.
- As a player, I want to see **what is attached to what** (`PermanentView.attachments`), and to
  be warned when I control an Aura on a permanent my opponent controls.
- As a player, I want to **look through my graveyard and exile**, whose contents the server sends
  in full and the bridge reduces to a count.
- As a player, I want **emblems, dungeons, commanders and planes** visible
  (`PlayerView.commandList`) — in that same vitals overlay, since `commandList` is filtered by
  controller and so belongs to a player rather than to a zone.
- As a player, I want **tokens to render with art and be identifiable as tokens** — `isToken`,
  `mageObjectType` and the token printing the server already resolves. Art resolves through
  Scryfall like every other card's, matched on name, subtype, P/T and colour, so there is one art
  pipeline rather than a second one only tokens use.
- As a maintainer, I want each of these threaded through **unchanged from a correct upstream
  field**, since that is the shape of every mapping bug this project has had.

### EPIC-24 — The Game Log
**What it is:** a scrollable record of what has happened. Specified by §7.12.

**The entries already arrive.** The server broadcasts its game log as
`ChatMessage.MessageType.GAME`, the bridge maps it to `ChatKind.GAME`, and nothing in the client
consumes `ChatEvent`. So this is a rendering job over a stream that already exists and is already
worded by the server — describing a game state change accurately is the last thing we should
re-derive.

The log records **game state changes, not interim actions**. EPIC-20 draws that line without
judgement calls: everything before Confirm is local and uncommitted, so nothing before Confirm is
loggable.

- As a player, I want to read what has happened in the game, so that I can recover what I missed
  while looking elsewhere or while several things resolved quickly.
- As a player, I want the log to show **what happened, not how I typed it** — no lines for
  tapping a land, untapping it, or picking and unpicking a delve exile.
- As a player, I want to check where a permanent's counters came from three turns ago.
- As a developer, I want to **confirm against a real game whether the server's stream carries
  interim actions**, which decides whether the log is a render or a render plus a filter. Run as an
  instrumented build Pete plays, with the logs returned to be read — its own step and its own
  hand-off, before the rendering story is written.

### EPIC-25 — Deckbuilding
**What it is:** deckbuilding reached without a server, and rebuilt around the loop it actually
is. Specified by [`ui-modernization-plan.md`](ui-modernization-plan.md) §7.18. Amends EPIC-09 and
EPIC-10; the deck library, import/export and the catalog itself stay theirs.

**It is already offline — only its mounting point is not.** `:core:decks` is local Room storage,
legality is a bundled `formats.json`, and card data is a bundled `cards.sqlite`; `DecksRoute`
records that only art fetch touches the network. The tabbed shell that owns the Decks tab is
entered only on a successful sign-in, so a feature that needs no server sits behind one. The fix
is a **second mount point in the root graph**, not a change to that entry policy — the shell still
requires a live session, and the lobby, tables and connection strip stay behind it where they can
work.

The builder rebuild is separately motivated: the deck and the search are two full screens, so
every add leaves the deck; the results show no count-in-deck, so the fifth copy is caught later by
the legality panel; and search reads three columns of a catalog that already carries oracle text,
power, toughness, mana cost, colours and per-printing set data.

- As a player, I want to **build decks before I sign in, and with no network at all**, so that
  the offline half of the app does not depend on a server.
- As a player, I want **import offered on an empty library**, so that a decklist from the web
  becomes a playable deck without typing sixty card names.
- As a player, I want **the deck and the search on one screen**, so that I can see the count, the
  curve and the legality move as I add. Landscape (§7.19) is what makes this a side-by-side split
  rather than a squeeze.
- As a player, I want a **result to tell me how many copies I already have** and to respect the
  four-copy limit at the moment I add, not afterwards.
- As a player, I want to **search oracle text, power and toughness, and set**, so that I can find
  cards by what they do.
- As a player, I want a **query syntax with a filter pane kept in sync with it**, so that both the
  fast path and the discoverable path lead to the same search. It is the Scryfall subset our columns
  can answer, and an operator we cannot answer is **rejected by name** rather than ignored — a
  silently dropped `f:standard` returns a confident wrong answer.
- As a player, I want **the syntax documented in the app**, in a help panel that expands from the
  search field and names both what works and what is deliberately absent, so that "it doesn't work"
  and "it isn't there" are distinguishable without trial and error.
- As a player, I want results as a **card grid** rather than one tile per row, so that a screenful
  shows a screenful of choices.
- As a player, I want **format and legality always visible**, since they decide whether I can join
  a table.
- As a player, I want to **choose which printing a deck line uses**, so that the art I picked is
  the art I get — in play as well as in the builder, since the deck records the printing and it
  travels to the server with the decklist. Offered from the catalog's printings only, never free
  text: a set/number the server cannot resolve fails the whole deck at load, not just the picture.
- As a player, I want to **draw a sample hand** from the deck I am building, so that I can check a
  curve without starting a game.
- As a developer, I want the **sideboarding surface (§7.16) to be this builder under a timer**,
  so that there is one deck-editing surface rather than two.

---

### EPIC-18 — Multiplatform Foundation
**What it is:** converting the shared logic modules to Kotlin Multiplatform — `:core:*` and
`:protocol` to KMP source sets with a JVM target, Hilt → Koin, the card catalog off raw
`android.database.sqlite`, Coil → `coil-network-ktor3`, and Robolectric out of the logic modules.
Specified by [`ui-modernization-plan.md`](ui-modernization-plan.md) §9 and §11 Phase 0.

**This runs first**, ahead of the UI rebuild. Not because a second platform is being built, but
because the cost scales with how much code exists when it happens — every module written against
Hilt beforehand is another migration site. **The UI does not move:** on Android, Compose
Multiplatform *is* Jetpack Compose.

- As a developer, I want the logic modules to **compile for a non-Android target in CI**, so that
  "portable" is a build result rather than a claim nobody checks.
- As a developer, I want **one DI framework that works everywhere**, so that features aren't
  written twice.
- As a developer, I want the **card catalog off platform SQLite** and its bundled asset reachable
  without `Context`, since that is the one genuinely large port in the module list.
- As a developer, I want the **logic modules' tests running on plain JVM**, so that Robolectric
  isn't on the critical path for code that has nothing to do with Android.
- As a maintainer, I want new `:core:*` dependencies **checked for multiplatform support before
  adoption**, so that this epic doesn't quietly rebuild itself.

### EPIC-22 — Desktop Demonstration
**What it is:** the **minimum** Compose Desktop build that shows the client runs cross-platform.
Falls out of EPIC-18's JVM target. Not a shipped product, and not where the client is developed.

**Android is the primary target and stays where the work happens.** This epic exists to make
EPIC-18's claim visible — a build that launches, connects to the bridge and plays. Anything past
that is a second product and a separate decision.

Two things it deliberately does not become. It is **not a design surface**: the board commits to one
layout target and a resizable desktop window would silently retune it, making the phone the port.
And it is **not where tests move**: the `:feature:*` suites stay on Android under Robolectric,
because that is the platform we ship, and an entire epic once stayed unmounted because device-only
tests did not run pre-merge.

- As a developer, I want a desktop build that launches and plays a game against the bridge, so that
  "the client is portable" is something you can watch rather than something the build log asserts.
- As a developer, I want the `:core:*` JVM tests in CI to be the **everyday** portability check, so
  that a regression fails a build rather than waiting for someone to run the desktop app.
- As a developer, I want anything about touch — gestures, target sizes, thumb reach, legibility at
  real density — to stay unverified until it has been played on a phone.

---

## Deferred

- **EPIC-21 — iOS Client.** Architecturally supported — the bridge is a network service, so no
  JVM runs on device. Needs transport security (WSS), APNs, background socket behaviour, a
  multiplatform card-catalog resource story, and has a different App Store risk profile.
  `ui-modernization-plan.md` §9.3 records the rest.
- **A shipped desktop client.** EPIC-22 is a demonstration. Turning it into something people install
  is a separate decision and is not taken.
