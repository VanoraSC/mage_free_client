# 0128 — A card travels between zones

- **Story:** #230
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0114/0118 (the stack flights and `BoardAnchors`), 0123 (the rail's graveyards and counts).
- **Specified by:** Pete —
  1. *"Animate lands played from hand to the battlefield."*
  2. *"Animate cards being discarded by having the card fly to the graveyard."*
  3. *"Animate cards leaving the battlefield to either go to hand, graveyard, or 'other'. For anything
     other than graveyard or hand, animate it going to the card count panel that you click on to bring
     up the other zones."*

## 1. Objective

When a card changes zone between two snapshots, draw it travelling from where it was to where it went,
the way a spell already travels to the stack. §7.3: *"an animation exists because a game action
happened"*, and zone moves are the plainest case.

## 2. What the snapshots can say

**A card keeps its id across zones.** Upstream moves a `Card` between zones without re-identifying it
(the zone-change counter changes, the UUID does not), so a card id seen in one zone in one snapshot and in
another zone in the next is the same card moving. That is what makes this derivable from two whole
snapshots without any event stream. A token that leaves the battlefield ceases to exist, so it has
nowhere to go.

**What is visible, and what is not.** The battlefield, every graveyard, every exile pile and the viewer's
own hand carry ids. An opponent's hand and every library carry only counts. So:

| Move | Seen as | Flies |
|---|---|---|
| Land played from the viewer's hand | a hand id now on the battlefield | hand card → the permanent |
| The viewer discards | a hand id now in a graveyard | hand card → that graveyard on the rail |
| An opponent discards | a new graveyard card that was nowhere visible, as their hand count falls | their hand → their graveyard |
| A permanent dies | a battlefield id now in a graveyard | the permanent → that graveyard |
| A permanent returns to the viewer's hand | a battlefield id now in the viewer's hand | the permanent → the hand card |
| A permanent returns to an opponent's hand | gone from view, as that hand count rises | the permanent → their hand |
| Anything else — exile, library, anywhere hidden | gone from the battlefield to no hand or graveyard | the permanent → its owner's count panel |

**Where from and to is measured.** Every origin and destination comes from `BoardAnchors`, which is
never pruned: a card that has just left the hand or the battlefield still answers with the box it was
last drawn in, which is exactly where it flew from. A destination that has not been laid out yet (the new
permanent, the card arriving in hand) is waited for, as the stack flights already do. An origin the
board never measured draws no flight.

## 3. Design

- **`zoneMoves(previous, current)`** — a pure function over two snapshots returning the moves above,
  each with the card's face as it was, its origin anchor and its destination anchor. Testable without
  a board.
- **New anchors.** Each hand card also reports itself under its id; each graveyard on the rail, each
  seat's count panel and the opponent's hand region report theirs.
- **The flight overlay is shared** with the stack flights: the same `FlyingCard`, the same duration.
- **The destination waits for the card.** A land that is the first of its name, and a card arriving in
  the hand, are drawn invisible until the flight lands, as a stack arrival already is. A graveyard keeps
  showing its previous top card until the card arrives.

## 4. Scope

**Out of scope:** stack → battlefield and stack → graveyard (a spell resolving), library → hand (a draw),
and a reconnect's resync, which is not a sequence (§7.3).

## 5. Acceptance criteria

- [ ] A land played from hand flies from the hand to its place on the battlefield.
- [ ] A card the viewer discards flies from their hand to their graveyard.
- [ ] A card an opponent discards flies from their hand to their graveyard.
- [ ] A permanent that dies flies to its owner's graveyard.
- [ ] A permanent returned to a hand flies to that hand.
- [ ] A permanent exiled, or put anywhere else, flies to its owner's count panel.
- [ ] A token that leaves the battlefield does not fly.
