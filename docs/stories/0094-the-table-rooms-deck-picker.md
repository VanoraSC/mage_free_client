# 0094 — The table room's deck picker

- **Epic:** none — a defect in the current UI.
- **Depends on:** nothing.

## 1. Objective

Remove the deck picker from the table room. The deck is chosen and bound at host or join time, so the
room re-asking is the fault — and in an ordinary duel the server ignores the answer entirely.

## 2. Context & background

**The deck is bound at join.** Both paths carry it: `JoinTableViewModel` submits the chosen `Deck` via
`TableClient.joinTable`, and `HostTableViewModel`'s create sequence joins the host itself carrying its
chosen deck. Upstream's `TableController.joinTable` loads that deck, validates it against the table's
format, and seats the player with `match.addPlayer(player, deck)`.

**And the room's picker calls a verb the server ignores.** This is worse than a redundant question,
and it is the finding that decides the story. Read from `TableController`:

```java
public void updateDeck(UUID userId, DeckCardLists deckList) throws MageException {
    if (table.getState() != TableState.SIDEBOARDING
            && table.getState() != TableState.CONSTRUCTING) {
        return;
    }
```

`submitDeck` is the same shape, under a comment that names its purpose exactly: *"Submit deck on
sideboarding/construction (final deck)"*. A constructed duel's table is in neither state while the
room is open, so a player who picks a deck there gets a control that renders, reports a click, and
does nothing — the precise defect class this project's wiring guards exist to catch, sitting in the
shipping UI.

**So the room has nothing left to do about decks.** The plan left this open — *"the submission itself
may still need to fire from the room rather than at join; confirm against the join path before
editing"* — and the answer, read from upstream rather than assumed, is that it does not.

## 3. Scope

**In scope**
- The "Submit your deck" section and its `DeckPicker` removed from `TableRoomScreen`.
- The plumbing that existed only to feed it: the room's `library`, `showSeatActions`, the deck
  repository observation, and the ViewModel's `submitDeck`/`updateDeck`.

**Out of scope**
- **`TableClient.submitDeck` / `updateDeck` stay.** They are the correct client API for the states
  upstream actually accepts them in, they are tested, and sideboarding will need them. What is being
  removed is a UI that calls them where they do nothing.
- Sideboarding and deck construction as a feature. When that surface is built it will have its own
  entry, its own state, and a table actually in `SIDEBOARDING`.
- The host and join deck pickers, which are where the deck is genuinely chosen.

## 4. Prerequisites & toolchain

Project baseline. Android-only; `:bridge` and `:core:network` are untouched.

## 5. Design & approach

**Delete rather than hide.** Gating the picker on the table's phase would leave a surface that is
correct-by-accident: it would still be wired to verbs that only work in a state this screen never sees,
and the next reader would have to re-derive why. The room stops having a deck concern at all.

**`submitDeck`/`updateDeck` go from the ViewModel too**, which is a deliberate deviation from the
plan's provisional "the ViewModel keeps them". That instruction was written while it was still open
whether the room had to submit; it does not, so keeping them would leave a public API on a ViewModel
with no caller — dead wiring of exactly the kind the removal is about. The client API they delegate to
is untouched and is where sideboarding will pick them up.

**Reachability (standard 2), inverted.** The usual question is "what produces this state in
production". Here it is the mirror: *what consumes this action* — and upstream's answer is "nothing,
in this table state". A control whose action the server discards is unreachable in the way that
matters.

## 6. Implementation steps

1. Remove the deck section from `TableRoomScreen` and the `onSubmitDeck` parameter it fed.
2. Remove `library`, `showSeatActions` and the deck-repository wiring from `TableRoomViewModel`.
3. Remove `submitDeck`/`updateDeck` from the ViewModel; leave `TableClient`'s alone.
4. Update the room's tests: the deck assertions go, and one is added asserting the surface is gone.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test asserting the room offers no deck surface must fail
  against the current screen, then pass.
- **Unit:** the room renders no deck picker for a host, a player or a spectator; the existing seat,
  start, leave and match-start assertions are unchanged and still pass, because none of them was about
  decks.
- **The host and join paths are untouched**, and their tests must stay green unedited — that is the
  check that this removed a surface rather than a capability.
- **Eyes-on:** host a table, confirm the room shows seats and Start with no deck section, and that the
  match still starts with the deck chosen at host time. Then join a table as the second player and
  confirm the same.

## 8. Acceptance criteria

- [x] The table room offers no deck picker in any role.
- [x] `TableClient.submitDeck`/`updateDeck` are unchanged.
- [x] The host and join deck pickers are unchanged, with their tests unedited.
- [x] The removal test was proven failing before passing.
- [x] `./gradlew check` passes and the APK builds; the match start is the eyes-on step.

## 9. References

- `../mage/Mage.Server/src/main/java/mage/server/TableController.java` — `joinTable` binding the deck,
  and the `SIDEBOARDING`/`CONSTRUCTING` guard on `submitDeck`/`updateDeck`.
- `docs/ui-modernization-plan.md` §1.2 and §11 — the item, and the question this story answers.
