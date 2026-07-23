# UX Principles — how the mobile client diverges

Feature parity with the XMage Desktop client is the target. **UX parity is an anti-goal.**
The Desktop client is a dense, mouse-driven Swing app built for a big screen; this is a
touch-first app for a device held in the hand. This document sets the direction we design
toward. It is intentionally opinionated so that individual screen decisions have something
to be consistent with.

## Constraints we're designing for

- **Small screen, one thing at a time.** A phone can't show the whole board, both hands,
  the stack, all graveyards, and chat at once — and shouldn't try. Prioritize the *current
  decision*.
- **Touch, not a cursor.** Targets must be finger-sized. Hover doesn't exist; every hover
  affordance from Desktop (tooltips, card zoom on mouseover) needs a touch equivalent
  (long-press, tap-to-peek).
- **Interruptible sessions.** People play on phones in short bursts, on bad connections,
  while backgrounding the app. The client must survive rotation, backgrounding, and
  reconnection without losing the game.
- **Thumbs reach the bottom, not the top.** Primary actions live in the lower third.

## Direction

### 1. Progressive disclosure over dense panels
Show the minimum needed to make the current decision; let players drill in for detail.
The board is a summary; tapping a permanent opens its full state, abilities, and history.

### 2. The board is a focus view, not a scale model
Don't render a shrunk desktop battlefield. Group permanents by type/role, collapse
irrelevant zones, and expand what's relevant to the current phase (e.g., blockers during
combat). Consider landscape as the primary in-game orientation.

### 3. Decisions come to the player
When the server asks for a choice (target, mana, yes/no, mulligan, blocks), surface it as a
clear, unmissable, thumb-reachable prompt — not a small dialog the player has to hunt for.
The single most common failure mode of a mobile card game is "I didn't realize it was
waiting on me."

### 4. Gestures as accelerators, taps as the floor
Every action must be doable with an obvious tap. Add gestures (swipe to declare attackers,
drag to play, long-press to inspect) as *accelerators* for fluency — never as the only way
to do something.

### 5. Card inspection is a first-class, full-bleed surface
On desktop you hover for a big preview. On mobile, tapping a card should give a large,
readable, full-detail view — oracle text, current modifications, abilities you can
activate — because the card art thumbnail alone is unreadable at phone size.

### 6. Deck building rethought for touch
The Desktop deck editor is a multi-pane drag-heavy workflow. Reimagine as a searchable,
filterable list with fast add/remove, a compact deck summary, and mana-curve/legality
feedback that fits a phone. Deck import/export compatibility with XMage formats
(`DeckCardLists`) is a functionality requirement even though the UI is new.

### 7. Presence & "your turn" belong in the notification layer
Table invites, chat mentions, and "it's your move" should be able to reach the player via
push notifications when the app is backgrounded — leveraging the mobile platform in a way
the Desktop client never could.

### 8. One coherent visual system
Material 3, a deliberate theme (not defaulted), full dark-mode support, and adaptive
layouts (phone ↔ tablet/foldable). Consistency across screens beats per-screen cleverness.

## Anti-patterns (don't do these)

- ❌ Recreating the Desktop panel layout on a small screen.
- ❌ Tap targets smaller than ~48dp.
- ❌ Hiding a pending game decision in a corner or a dismissible toast.
- ❌ Gesture-only actions with no tap fallback.
- ❌ Blocking the UI thread on network; the game must stay responsive while waiting on the
  server.

## How this interacts with the architecture

Because the server is authoritative and we map its view into our own domain models (see
[`architecture.md`](architecture.md)), the UI is free to present game state however serves
the player best. We are not bound to the shape of `mage.view.*`. Use that freedom.
