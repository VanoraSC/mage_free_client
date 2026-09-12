# 0125 — Full Control

- **Story:** #227
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0115/0123 (stops, and the path that sends them to the server).
- **Specified by:** Pete — *"Add a full control mode. This mode is how the game is now. When not in full
  control, auto pass priority after the player puts something onto the stack."*

## 1. Objective

Out of Full Control, priority is passed for the player the moment they put a spell or an activated
ability on the stack. In Full Control, it comes back to them, which is how the board played until now.

## 2. What upstream knows

**This is a server feature, and it already exists.** `UserData` carries `passPriorityCast` and
`passPriorityActivation`; the desktop client's preferences call them *"Pass priority automatically
after you have put a spell / an activated ability on the stack"*. `HumanPlayer.priority()` checks them
before anything else: when the player has just activated a spell (`AbilityType.SPELL`) or a non-mana
activated ability, and is not holding priority, it passes and returns without building a prompt.

Upstream defaults both to `false`, which is Full Control.

The bridge already writes the player's `UserData` for stops (`PriorityStopsMapper`, applied through
`updatePreferencesForServer`), so the flags go the same way.

## 3. Design

- **Wire.** `SetPriorityStops` gains `passPriorityCast` and `passPriorityActivation`, defaulted to
  upstream's `false` so an older peer changes nothing. The bridge writes both, both ways.
- **Store.** `BoardStops.fullControl`, carried with the stops because it decides whether a priority
  window arrives at all and reaches the server the same way. Every change is published with the stops.
- **Default: off.** A pinned mode a player turns on (§3.2: *"Full Control is a pinned mode … it asks for
  more decisions, not fewer"*).
- **Control.** An item in the corner menu with a check when on, and a badge beside the menu while it is
  on — a pinned mode has to look pinned.

## 4. Scope

**Out of scope:** persisting the setting across app restarts (the stops are not persisted either), and
holding priority for a single cast.

## 5. Acceptance criteria

- [ ] Out of Full Control, casting a spell passes priority without asking.
- [ ] Out of Full Control, activating a non-mana ability passes priority without asking.
- [ ] In Full Control, priority comes back after both, as before.
- [ ] The corner menu turns it on and off; a badge shows while it is on.
- [ ] Pressing a stop does not change Full Control.
