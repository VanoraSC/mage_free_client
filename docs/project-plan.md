# Project Plan

The feature plan for Mage Free Client, organized into **epics**. Each epic describes, in
plain language, what the feature is and what it should do. Implementation-level breakdown
and ordering live in the numbered story documents (see [`stories/`](stories/), produced from
this plan).

Read [`architecture.md`](architecture.md) (how we integrate with the server) and
[`ux-principles.md`](ux-principles.md) (how the UX diverges from desktop) alongside this.

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
- As the app, I want the bridge to relay server→client push (game updates, prompts, chat)
  faithfully, so that gameplay stays in lockstep with the server.

### EPIC-02 — App Shell & Navigation
**What it is:** the Arena-style information architecture — a home hub with a prominent path
to play, and top-level destinations (Play, Decks, Profile/Social, Settings). Connection
status is always visible; the in-game experience is a separate immersive full-screen mode.

- As a player, I want a home screen with an obvious way to jump into a game, so that playing
  is one tap away.
- As a player, I want clear top-level sections for playing, decks, and my profile, so that I
  always know where things live.
- As a player, I want to always see whether I'm connected, so that I trust the app's state.

### EPIC-03 — Design System & Theming
**What it is:** the original Material 3-based visual system — theme, typography, color, and
the reusable, card-forward components (card tile, full-bleed card view, decision/prompt
surface, list rows, buttons). Full light/dark support and adaptive layouts (phone ↔
tablet/foldable).

- As a player, I want one coherent look across every screen, so that the app feels
  deliberate and trustworthy.
- As a player, I want full dark mode and readable type at my chosen size, so that it's
  comfortable in any setting.
- As a developer, I want shared components for cards and prompts, so that screens stay
  consistent and fast to build.

---

## Connectivity & Account

### EPIC-04 — Server Connection & Sign-In
**What it is:** choosing or adding a server, signing in with an XMage account (proxied auth),
registering where the server allows it, and seeing live connection state.

- As a player, I want to add and pick a server and sign in, so that I can reach my games.
- As a new player, I want to register an account when the server supports it, so that I can
  start without a desktop client.
- As a player, I want clear feedback on connecting, connected, and failed states, so that I
  know what's happening.

### EPIC-05 — Session Resilience & Notifications
**What it is:** surviving backgrounding, rotation, and network drops without losing the game,
with automatic reconnection; and push notifications for the things that need the player back
— "it's your turn," table invites, and chat mentions.

- As a player, I want the app to reconnect and restore my game after a drop or backgrounding,
  so that a flaky connection doesn't cost me the match.
- As a player, I want a push notification when it's my turn while the app is backgrounded, so
  that I never stall a game by accident.
- As a player, I want notifications for invites and mentions, so that I don't miss social
  activity.

---

## Lobby & Getting Into Games

### EPIC-06 — Lobby & Game Browser
**What it is:** browsing rooms, open tables, and active/watchable games, with who's playing
and useful filters/sorting — the surface behind the home "Play" path.

- As a player, I want to browse open tables and games with filters, so that I can find
  something to play or watch.
- As a player, I want to see players and table settings at a glance, so that I can choose
  wisely before joining.

### EPIC-07 — Hosting & Joining Tables
**What it is:** creating a table (constructed or limited, with match options and seats),
joining open tables, readying up, submitting a deck, and inviting/spectating.

- As a player, I want to create a table with the format and options I choose, so that I can
  host the game I want.
- As a player, I want to join an open table and submit my deck, so that I can get into a
  match.
- As a host, I want to invite specific players and manage seats, so that I control who
  joins.

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

### EPIC-10 — Card Database, Search & Inspection
**What it is:** a searchable, filterable card catalog (art, oracle text, rulings) used by the
builder and gameplay; a first-class full-bleed card inspection view (including current
in-game modifications and activatable abilities); and efficient on-device caching of card
images and static set data.

- As a player, I want to search and filter all cards, so that I can find what I need for a
  deck or a decision.
- As a player, I want to tap any card for a large, readable, full-detail view, so that I can
  actually read it on a small screen.
- As a player, I want card art and data to load fast and work offline once cached, so that
  the app stays responsive on mobile data.

---

## Playing a Game

### EPIC-11 — Game Board & Presentation
**What it is:** the phone-adapted board — a focus view, not a shrunk desktop. Battlefield
grouped by type/role, hand, the stack, graveyards/exile, life totals, and clear turn/phase
indicators; zones expand/collapse to match the current phase.

- As a player, I want a board that highlights what matters now instead of cramming
  everything on screen, so that I can read the game at a glance.
- As a player, I want to open any zone (graveyard, exile, hand) on demand, so that I can
  check detail without clutter.
- As a player, I want life totals, turn, and phase always clear, so that I never lose track
  of game state.
- As a player, I want to be able to load in the art for cards in the same manner as the desktop app and
  have a easy way to facilitate the download.
- As a player, I want to be able to easily choose the art that the cards in my deck use in the case where 
  the card has alternative art.

### EPIC-12 — Priority, Stack & Taking Actions
**What it is:** passing and holding priority, casting spells, playing lands, activating
abilities, and paying costs (auto-tap with manual override, mana-pool visibility); watching
the stack build and resolve. Includes Arena-style stops / auto-pass preferences.

- As a player, I want to play cards and activate abilities with a clear tap, so that acting
  is obvious and reliable.
- As a player, I want to see the stack build and resolve, and respond to it, so that I can
  play at instant speed.
- As a player, I want stops/auto-pass settings, so that the game only stops for me when I
  want it to.

### EPIC-13 — Targeting, Choices & Combat
**What it is:** every server-driven decision surfaced as an unmissable, thumb-reachable
prompt — targets, modal choices, ordering triggers, yes/no, numbers — plus combat: declaring
attackers and blockers and assigning order/damage, touch-first.

- As a player, I want decisions to come to me as a clear prompt I can't miss, so that I never
  stall the game without realizing.
- As a player, I want to choose targets and options with confident taps, so that I don't
  misfire on a small screen.
- As a player, I want a touch-friendly way to declare attackers and blockers, so that combat
  is fast and unambiguous.

### EPIC-14 — Game Setup, Mulligans & Match Flow
**What it is:** pre-game setup (starting player, opening hand), the mulligan decision, and
match flow across a best-of-N — game/match results, sideboarding between games, and
conceding.

- As a player, I want a clear opening-hand and mulligan flow, so that I start each game well.
- As a player, I want to sideboard between games in a match, so that I can adapt.
- As a player, I want to see results and concede cleanly, so that matches end gracefully.

### EPIC-15 — Spectating
**What it is:** watching a game in progress with the same board presentation, read-only.

- As a player, I want to watch an ongoing game, so that I can learn or follow friends.
- As a spectator, I want the same clear board view without controls, so that watching is as
  readable as playing.
- As a spectator, I want to be able to see all the hidden information that either player's game 
  client has access to, not just the information of a single player.

---

## Social & Settings

### EPIC-16 — Chat & Player Presence
**What it is:** lobby chat, in-game chat, and whispers; player profiles, online presence, and
invites. Mentions feed the notification layer (EPIC-05).

- As a player, I want to chat in the lobby and in games, so that I can coordinate and
  socialize.
- As a player, I want to see who's online and invite them, so that I can play with friends.

### EPIC-17 — Settings, Preferences & Accessibility
**What it is:** connection defaults, gameplay preferences (stops/auto-pass), notification
controls, theme, and accessibility options (dynamic type, content descriptions, adequate
touch targets).

- As a player, I want to set connection and gameplay defaults, so that the app fits how I
  play.
- As a player, I want notification and theme controls, so that the app fits my environment.
- As a player with accessibility needs, I want dynamic type, labels, and large touch
  targets, so that the app is usable for me.
