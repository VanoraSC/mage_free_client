# 0124 — A reveal is announced

- **Story:** #217
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0120 (what you have seen — `SeenCards`, folded in the ViewModel), 0123 (`ZoneViewer`).
- **Specified by:** a real game — Inquisition of Kozilek against a hand with no legal target.

## 1. Objective

When the server reveals cards and asks nothing about them, show them over the board, named after the
effect that revealed them, until the player puts them down.

## 2. What happens today

`DiscardCardYouChooseTargetEffect` reveals unconditionally, then asks the caster to choose. With no
legal target, `choose` returns early and fires no event, so the client is never prompted. The reveal
still arrives: `fireUpdatePlayersEvent` pushes the update **before** it clears `GameState.revealed`, and
since 0120 the ViewModel folds every snapshot, so the cards land face-up in the opponent's hand row.

**The information is not lost; it is never announced.** Nothing on the board says *you have just been
shown this*, and the reveal survives exactly one snapshot upstream.

## 3. Design

**A reveal is announced when it arrives and nothing on screen already shows it.** For each zone in
`GameState.revealed` with cards in it:

- **Already shown by the question** → not announced. A target prompt that carries the revealed cards
  as its candidates (Duress with a legal target) is already putting them in front of the player; an
  overlay on top would cover the question it is part of.
- **Already announced** → not announced again. Upstream can carry the same reveal across more than one
  snapshot (an update, then a narration with state). A reveal is identified by the **turn**, the
  **effect's name** (upstream titles each reveal after its source), and the **card ids**.
- **Otherwise** → queued.

**Announced with the pile viewer.** `ZoneViewer` already draws an ordered, scrollable pile with a title,
opens over the board, and closes on a press outside. A reveal is a pile, so it reuses that, titled with
the effect's name (*Inquisition of Kozilek*) rather than the pile's kind (*Revealed*). Cards in it open
the ordinary card detail.

**Queued, one at a time.** Two reveals in one resolution are shown one after the other, oldest first.

**The player's own hand is announced too.** An opponent's Thoughtseize revealing your hand tells you
nothing about your cards, but it does tell you what they now know, which is real information.

## 4. Scope

**In scope:** the announcement queue in `GameBoardUiState`, the rule above as a pure function, the
overlay, and dismissal.

**Out of scope:** the reveal *history* — #195 (0111), a log of every reveal this game and whether each
card has since moved. This story is the moment of a reveal; that one is its record. They complement
each other and neither absorbs the other.

## 5. Acceptance criteria

- [ ] A reveal the server asks nothing about is shown over the board, titled with the effect's name.
- [ ] A reveal whose cards are the outstanding target question's candidates is not announced.
- [ ] The same reveal arriving in two snapshots is announced once.
- [ ] Two reveals are shown one after the other.
- [ ] A press outside puts the announcement down; a press on a card opens its detail.
- [ ] The revealed cards still appear in the opponent's hand row afterwards (0120 is unchanged).
