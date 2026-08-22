# 0077 — Manual "flip" control to peek at a DFC's other face

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0055 (board rendering), 0076 (`GameCard.transformed`, the automatic face signal
  this story deliberately does not reuse for flippability — see §2)

## 1. Objective

Pete's request, made while 0076 was in flight: when the tapped-card detail (§5.1/§11.1) is showing a
double-faced card, offer a button to flip the shown art to the card's other face — a manual peek,
independent of whether the card has actually transformed on the battlefield.

## 2. Design

**This is a local viewing choice, not a game-state change.** It never sends anything to the server
and never affects [BoardUi]'s own, automatic face selection (0076): a battlefield permanent's *live*
art (drawn everywhere else on the board) always reflects `GameCard.transformed`, exactly as before.
The peek only affects what the tapped-card detail overlay shows while it's open.

**Flippability is a catalog fact, not a live-state one.** `GameCard.transformed` (0076) tells you
*whether a permanent is currently showing its back face* — it is `false` for an untransformed
permanent and for every non-permanent zone (hand, stack, …), by design (only a permanent transforms).
That is the wrong question for this feature: an untransformed Kytheon in hand should still offer a
flip button, because it *is* a DFC, it just isn't showing its back face right now. The bundled
catalog (`:core:cards`, story 0030) is what actually knows "is this printing double-faced at all"
(`CardFaces.doubleFaced`/`modalDoubleFaced`) and "what's the other face called"
(`CardFaces.secondSideName`) — the same two facts `:feature:cards`' `CardInspectionViewModel`
already uses for its own flip control (story 0032), reused here rather than re-derived.

**Looked up by the front-face name.** The catalog only ever stores a DFC under its *front* face's
name (confirmed by reading `SqliteCardCatalog`'s query: `SELECT ... FROM card WHERE name ...`, one
row per printing, `second_side_name` a column on that row — there is no separate row for the back
face). A transformed permanent's live `GameCard.name` is the *back* face's name and would never
match. `if (transformed) alternateName ?: name else name` reliably gives the front name in both
cases: `GameCard.transformed` says whether `name` is currently the back face at all, and
`GameCard.alternateName` — upstream's own "other face" name, unconditionally present on any DFC
regardless of state — supplies the front name precisely when it's needed.

**Resolved asynchronously, off the pure projection.** `BoardUi.from()` is a deliberately pure,
synchronous function (0055) — no IO, hermetically testable on a plain JVM. The catalog lookup is a
`suspend` call, so it happens in `GameBoardViewModel` instead, seeded with a `canFlip = false`
default the instant a card is tapped (so the overlay renders immediately, without a flip button
flashing in after a beat) and upgraded once the catalog answers, guarded against a stale answer
landing after the selection has already changed.

## 3. Scope

**In scope**
- `GameBoardUiState.detailFace: CardDetailFaceUi?` — `face` (which face the *overlay* is showing),
  `canFlip` (the catalog's `doubleFaced`/`modalDoubleFaced` answer), `displayName`.
- `GameBoardViewModel.selectCard()` seeds it and kicks off the catalog resolution;
  `flipDetailFace()` toggles it.
- `CardDetailOverlay` gains a `detailFace`/`onFlip`; when `canFlip`, an outlined "Flip" button
  appears next to the existing action/close buttons. The overlay's art and title swap to the peeked
  face via a local `card.copy(...)` — no change to the live `CardUi` the rest of the board draws from.
- `CardUi.alternateName`/`CardUi.transformed`, exposed (previously internal to `BoardUi.toCardUi()`)
  so the ViewModel can compute the front-face name without re-deriving 0076's logic.
- Reset on prompt change (closing the detail also drops the peek state) and on reselecting the same
  card (closing then reopening always starts back at the live face).

**Out of scope**
- No new oracle-text-per-face modeling — same limit `:feature:cards`' inspection screen already
  documents: the peeked back face gets its own name and art, not its own rules text.
- Split cards, flip cards (Kamigawa), meld cards: `CardFaces.isMultiFace` covers them, but this
  story's `canFlip` predicate is deliberately narrower (`doubleFaced || modalDoubleFaced` only,
  mirroring `:feature:cards`' `artFlippable`) — the art-request shape (`CardArtFace.BACK`) is
  specific to transform/modal DFCs and hasn't been shown to apply to the others.

## 4. Verification

- **Standard 1**, discriminating tests in `GameBoardViewModelTest`: a double-faced hand card offers
  `canFlip = true` once the catalog answers and flips between the front/catalog-back names and art
  faces; an ordinary card stays `canFlip = false`; a transformed permanent opens already on the back
  face (matching its live art) and flips to the front; reselecting resets the peek. All five proven
  to fail against the unfixed code first — the whole feature was new, so the proof was a build
  failure (`Unresolved reference 'CardDetailFaceUi'` etc.) with the ViewModel/BoardUi changes
  stashed, then a pass once restored.
- **Standard 2 (reachability):** `CardDetailFaceUi.canFlip` — the catalog's `CardFaces.doubleFaced`/
  `modalDoubleFaced` for the card's front-face name. `CardDetailFaceUi.face`/`displayName` — the
  live face/name on open, the peeked face/`secondSideName` after a flip.
- **Hermetic gate:** `feature/game/src/test/kotlin/.../board/GameBoardViewModelTest.kt`.
- **Live, if practical:** tap a double-faced card in hand, confirm the Flip button appears and swaps
  the art/name; tap a transformed permanent, confirm it opens on the back face and flips correctly.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. Tap a double-faced card sitting in hand (untransformed). Confirm a "Flip" button appears and
     toggles the art/name between the two faces.
  2. Tap an ordinary (non-DFC) card. Confirm no Flip button appears.
  3. Transform a permanent, tap it. Confirm the detail opens already on the back face (matching the
     board art) and Flip shows the front.
  4. Close the detail and reopen the same card. Confirm it starts back on the live face, not
     wherever it was left.

## 5. Acceptance criteria

- [ ] Tapping a double-faced card's detail (any zone) offers a Flip control once the catalog confirms
      it is double-faced.
- [ ] Flip swaps both the art and the displayed name between front and back, without altering the
      card's actual live face anywhere else on the board.
- [ ] An ordinary card's detail never shows a Flip control.
- [ ] Pete has completed the eyes-on checklist.

## 6. References

- `feature/game/src/main/kotlin/magefree/feature/game/board/GameBoardViewModel.kt` —
  `CardDetailFaceUi`, `resolveDetailFace`, `flipDetailFace`.
- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardControlsUi.kt` —
  `CardDetailOverlay`'s new `detailFace`/`onFlip` and the local `shownCard` swap.
- `feature/cards/src/main/kotlin/magefree/feature/cards/CardInspectionViewModel.kt` — the sibling
  flip control this story's `canFlip`/back-name logic mirrors (`artFlippable`, `faces.otherFaceName`).
- `core/cards/src/main/kotlin/magefree/cards/internal/SqliteCardCatalog.kt` — confirms the catalog
  indexes a DFC by its front-face name only, which is why lookups here account for `transformed`.
- `docs/stories/0076-transformed-permanent-art.md` — `GameCard.transformed`/`GameCard.alternateName`'s
  own semantics, and why they answer different questions than this story's `canFlip`.
