# 0116 — The two piles

- **Story:** #205
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0112 (the board), 0113 (the question takes the screen), 0114 (the stack).
- **Specified by:** a real game — Liliana of the Veil's `-6` was unanswerable.

## 1. Objective

Answer a target question in a place built for answering it: a battlefield overlay with two columns —
what you have not chosen, and what you have — with cards moved between them by tap or by drag.

## 2. Context & background

**The prompt that started it.** Liliana of the Veil's `-6` is *"Separate all permanents target player
controls into two piles. That player sacrifices all permanents in the pile of their choice."* It
reached the board as a target prompt over an opponent's whole battlefield, and there was no way to
play it: no sign of which cards answered it, no sign that a pick had landed, and no second pile
anywhere on screen. 0116's predecessor commit fixed the first two by marking candidates on the board.
This is the third: a **set** being assembled has no home on a board that draws one card at a time.

**The wire cannot tell us this is a pile split, and the bridge cannot help.** Read before designing:

- `LilianaOfTheVeilEffect` calls `player.choose(Outcome.Neutral, target, source, game)` with a plain
  `TargetPermanent(0, count, filter, true)`. There is no pile-specific message, event or option.
- `HumanPlayer.getOptions(target, options)` puts exactly three things on the wire: `UI.right.btn.text`,
  `targetZone`, and (from the calling loop) `chosenTargets`. **No min, no max.**
- The cardinality exists in exactly one place: `TargetImpl.getMessage` appends
  `" (selected N of M, min K)"` **iff** `getMaxNumberOfTargets() != 1`. That is prose, and this app
  does not parse the server's prose.
- The bridge receives a `GameClientMessage`, never the `Target`. It has nothing to translate. A new
  bridge signal here would be an invention, which is the mistake 0115 already made once.

**So the overlay does not ask what kind of prompt it is.** Two columns are a correct rendering of
*any* target prompt whose candidates are on the board:

| prompt | left column | right column |
| --- | --- | --- |
| Liliana's `-6` | pile 2 (implicit) | pile 1 (`chosenTargets`) |
| "sacrifice up to three creatures" | creatures | sacrificing |
| "destroy target creature" | creatures | the one you picked, briefly |

**The second pile is free.** Upstream never sends it — `LilianaOfTheVeilEffect` computes it as *every
permanent that player controls, minus pile 1*. The client already holds exactly that: `possibleTargets`
for this prompt **is** every permanent the target player controls, and
`TargetPermanent.possibleTargets` does not remove chosen ones. So `pickable − chosen` is pile 2,
derived rather than guessed.

**Both halves are the server's own state.** `chosenTargets` is sent by both prompt loops —
`HumanPlayer.choose` and `HumanPlayer.chooseTarget` each `options.put("chosenTargets", …)` before
firing — so the right column is read, never accumulated locally. Nothing here holds picks back: every
move sends one `chooseTarget` and the columns redraw from the server's reply, which is the same rule
the rest of the board follows.

**Moving a card back is upstream's own gesture.** `HumanPlayer.choose` answers a response id it
already holds with `target.remove(responseId)`, and keeps chosen permanents in `possibleTargets` so
they can be sent again. So right→left is the same message as left→right, and no new verb is needed.

## 3. Scope

**In scope**
- A battlefield overlay for a target prompt whose candidates are on the board: two columns, the
  prompt's own message, and its own buttons.
- Tap a card to move it across; drag a card to move it across. Both, both directions.
- The same card representation as the battlefield, per 0114's rule.
- A collapse control, the same one the controls panel has, so the board underneath can be read.

**Out of scope**
- **Prompts whose candidates are not on the board** — a library search, an ability picker. Those carry
  their own cards and 0113's panel already draws them.
- **Telling a one-of prompt from a many-of one.** The wire does not carry it (§2) and the overlay does
  not need it.
- **Ordering within a pile.** No effect this covers cares which order a pile is in.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:feature:game`. No `:protocol` or `:bridge` change — see §2.

## 5. Design & approach

**Two files, and only one of them knows about Compose.** `TargetPiles.kt` turns a `PromptControlsUi`
and a `BattlefieldModel` into the question and its two columns, or `null` for a prompt this does not
answer. `TargetPilesOverlay.kt` draws it. So what opens, what is in each column and what a move sends
are all decided in a plain unit test.

**The columns are in board order.** Opponents above, the viewer below, which is top to bottom on
screen. The server's order is not meaningful for a set, and a player reading two columns is looking
for a card they can see on the battlefield — finding it in the same order it sits there is the only
ordering that helps.

**Tap and drag are the same message.** Both send one `BoardAction.ChooseTarget` for the card moved,
and upstream decides which direction that was: `HumanPlayer.choose` removes a target it already
holds. Two client verbs would have got exactly one of them wrong. The drag is a long-press drag with
the card following the finger in a `graphicsLayer` — the layout does not reflow mid-drag, so the piles
stay where the player is aiming — and it commits when the card is released over the *other* column's
measured bounds. Released where it started, nothing happens: saying so beats guessing.

**Nothing is held locally except the drag in flight.** The columns are recomputed from the server's
`chosenTargets` on every snapshot, so a move the server declines simply does not appear.

**The overlay carries the prompt's own buttons, and the floating panel steps aside while it is up.**
Those buttons already include any candidate the board cannot draw — a player, above all — plus Done
and Cancel, so moving the question here loses none of its answers, and two panels saying the same
things would be two places to press for one question.

**Collapsing is a look, not an answer.** The same `HiddenControlsToggle` brings it back, and system
back collapses the piles before it leaves the board — a back gesture that silently sent an answer
would be the worst kind.

## 6. Testing & verification

- **Unit (`TargetPilesTest`):** the second pile is `pickable − chosen`; the chosen column is the
  server's own answer rather than a tally of what was sent; a chosen card can still be moved back; the
  prompt's buttons come with it; the columns follow board order. And the three ways it must *not*
  open: a prompt carrying its own cards, a prompt with no candidate on the board, and a prompt that is
  not targeting at all.
- **Hermetic Compose (`TargetPilesOverlayTest`):** both columns and their counts are on screen; a tap
  in either column moves that card; a long-press drag onto the other column sends the same single
  move; the prompt's own Done answers it; collapsing sends nothing.
- **Eyes-on:** Liliana of the Veil's `-6` against a developed board.

## 7. Acceptance criteria

- [ ] Liliana's `-6` is playable: both piles visible, permanents moved either way, and the split sent.
- [ ] A card can be moved by tapping it and by dragging it, in both directions.
- [ ] The right column matches what the server holds, not what the client sent.
- [ ] The prompt's own Done and Cancel are on the overlay and do what they did before.
- [ ] The board can be read underneath without losing the overlay.
- [ ] A single-target prompt is answerable the same way, and the overlay closes when it resolves.

## 8. Known gaps

- **A card cannot be read from inside the overlay.** Tap moves and long-press drags, which leaves no
  gesture for "show me this card properly". Collapsing to the board and pressing it there works.
  Worth a control of its own if it turns out to matter in play.
- **Candidates in a pile are not marked on the board.** `battlefieldModel` and `handCards` take the
  prompt's candidates; `tableZones` does not yet, so a target offered from a graveyard is drawn
  unmarked in the seat window. It is still answerable — the overlay does not use zone cards.
