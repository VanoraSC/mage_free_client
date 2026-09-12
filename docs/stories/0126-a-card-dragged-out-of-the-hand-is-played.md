# 0126 — A card dragged out of the hand is played

- **Story:** #228
- **Epic:** EPIC-19 — Game Board Rebuild
- **Specified by:** Pete — *"Dragging a card out of the hand should jump to 'play' and not just show the
  details."*

## 1. Objective

Dragging a playable card up out of the hand plays it. A tap still raises the card so it can be read.

## 2. What happens today

`HandRegion` offers the drag on a card the server marked playable, and on release past the threshold it
calls the same callback a tap does. On the board that callback raises the card, so a drag opened the
detail and the player still had to press Play.

## 3. Design

- `HandRegion` takes its own `onDragPlay`, and the drag calls that rather than the tap's callback.
- The board wires it to the outstanding prompt's own `actionFor`: *Play* in a priority window, a pick in
  a target question — exactly what the raised card's button would have sent. Nothing new is decided.
- If the question has moved on and offers nothing for the card, the drag falls back to raising it
  rather than sending something the server no longer offers.
- The drag threshold and the playable-only rule are unchanged.

## 4. Acceptance criteria

- [ ] Dragging an offered card out of the hand plays it without opening the detail.
- [ ] A short drag that stays in the hand does nothing.
- [ ] Dragging a card the server has not offered does nothing.
- [ ] A tap still opens the detail.
