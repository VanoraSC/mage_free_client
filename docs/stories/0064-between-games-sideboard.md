# 0064 — Between-games sideboard

- **Epic:** EPIC-11 — In-Game Play (match flow)
- **Depends on:** 0036/0037 (table protocol/relay/client), 0033 (deck model & legality), 0059 (deck
  submission state-gating), 0057 (a game can actually be played and finished)
- **Status:** ready — **`:protocol`/`:bridge` change required, not UI-only (corrects requirements
  §12.1's original claim)**

## 1. Objective

Let a player sideboard between games of a match. This is the one piece standing between "a single game
is playable" and "a full best-of-N match is playable": today, `SideboardPrompt` fires and is folded into
a `TablePhase.Constructing` **label** in the table room — nothing lets the player see or change their
deck, and nothing ever answers the prompt from the app side. A match that reaches game 2 has a player
stuck on a screen that says "Sideboarding" forever (or until the server's own 180s timer auto-completes
their deck for them, silently).

## 2. Context & background — traced from `../mage`, and requirements §12.1's claim was wrong

Requirements §12.1 originally asserted *"story 0036 already maps the server's `ConstructPrompt`/
`SideboardPrompt` — so the trigger and the deck payload exist; this is a UI to build, not a protocol
gap."* That was never checked against source. It is **half right**: the trigger exists; **the deck
payload does not** — see requirements §12.1 (corrected 2026-08-15) for the full trace. Read directly:

- `Mage.Common/.../mage/view/TableClientMessage.java` — carries a `DeckView deck` field. Upstream's
  `TableController.sideboard(playerId, deck)` (`Mage.Server/.../TableController.java:773`) sends it via
  `user.ccSideboard(deck, tableId, parentTableId, remainingSeconds, isLimited)`, where `deck` is the
  player's **actual registered deck** (`MatchImpl.sideboard()` passes `player.getDeck()`).
- `bridge/.../mapping/table/SideboardMapper.kt` maps every other field but never reads
  `message.getDeck()`. `:protocol`'s `SideboardPrompt` has no `deck` field to receive it. This is
  standard 5's exact shape — a real, populated upstream field, silently dropped downstream — except
  caught before implementation started, not after.
- **The mechanism (full detail in requirements §12.1):** sideboarding is match-level, fires between
  games, is timed (`Match.SIDEBOARD_TIME = 180`s), auto-completes stragglers on the server's own timer
  with **no distinct "time's up" event** sent to the client, and is answered with the **same**
  `submitDeck`/`updateDeck` verbs 0037 already built for join-time deck submission — `updateDeck` for
  live auto-save (upstream's own comment: *"used for auto-save deck"*), `submitDeck` as the final,
  binding commit.
- `isConstruct` (mapped, unused downstream) distinguishes strict constructed sideboarding (swap only
  within the registered 15-card sideboard — no adding outside cards) from limited-style construction
  (basics free and unlimited). This is a **pool constraint**, not just format legality, and 0033's
  `DeckLegality` does not currently model it — it checks format rules (size, banned list), not "is this
  swap a re-partition of the same card pool the player started with."

## 3. Scope

**In scope**
- **`:protocol` + `:bridge` fix (do first — everything else depends on it):** add a deck payload to
  `SideboardPrompt` (mirror 0033's `Deck ↔ DeckCardLists`-equivalent wire shape, the same interchange
  0036 already uses for join-time submission) and read `message.getDeck()` in `SideboardMapper`. Verify
  live that the payload actually arrives non-empty and matches the deck that was just played with
  (standard 5 — confirm the source, not just that it compiles).
- **The sideboard screen itself:** shows the deck that was just played (main + sideboard), lets the
  player move cards between the two, shows live legality against the format **and** the pool
  constraint (§2), shows the remaining time from `remainingSeconds` counting down, auto-saves via
  `updateDeck` as the player edits, and commits via `submitDeck` (or "keep as-is" — an unmodified
  submit is a legitimate answer).
- **Timeout handling without a server signal.** Because the app never receives an explicit "time's up"
  event, the screen must not depend on one: when the countdown reaches zero, treat the *next* thing the
  server sends (next game starting, or match end) as authoritative, exactly as the server does. Don't
  invent a client-side "you timed out" state the server never confirms.
- **`TablePhase.Constructing`'s existing label** (`TableRoomScreen.kt`) becomes the entry point into
  this screen rather than a dead-end string.
- A pool-constraint check in (or alongside) 0033's `DeckLegality`: the submitted main+sideboard must be
  exactly a re-partition of the deck the player registered (or, for `isConstruct = true`, unlimited
  basics plus the same non-basic pool) — not an arbitrary new deck.

**Out of scope**
- `CONSTRUCT` (draft/tournament pool building) — a different flow with a different, already-justified
  reason its deck payload is omitted (§2); not touched by this story.
- Reusing or extending the deck builder (0035) as the sideboard UI — explicitly rejected by §12.1's
  original decision; this is a purpose-built, timed, match-scoped screen.
- Any change to match/tournament formats that don't allow sideboarding — `isSideboardingAllowed()` is
  upstream's own call, not ours to second-guess.

## 4. Constraints already verified — do not rediscover

- The trigger (`SideboardPrompt`) already fires and folds into `TablePhase.Constructing` — confirmed in
  `TableEventFold.kt`. Only the deck payload and the answering UI are missing.
- `submitDeck`/`updateDeck` already exist on `TableClient` (0037) using app-schema `Deck`, already
  state-gated server-side to `SIDEBOARDING`/`CONSTRUCTING` — no new client verb needed, only a UI that
  calls the existing ones at the right moment.
- The server auto-completes a straggler's deck on timeout **silently** — no event names this. Do not
  build UI that waits for one.
- `isConstruct` is already mapped onto `SideboardPrompt` but read by nothing downstream today.

## 5. Design & approach

- **Protocol change first, verified live, before any UI work** — this is the one part of Epic 11's
  in-game work so far that genuinely needs `:protocol`/`:bridge` changes rather than only board-side
  work (unlike 0062/0063). Confirm the shape against a real sideboarding window (a best-of-3 match,
  `isSideboardingAllowed()` true — e.g. a `Two Player Duel` constructed match) before designing the
  screen around an assumed payload.
- **Reuse 0033's deck model and legality checker**, extended with the pool-constraint check (§3), rather
  than inventing a parallel deck representation for this one screen.
- **The countdown is real, not decorative.** `remainingSeconds` arrives once, at prompt time — the
  screen must run its own local countdown from it, the same honesty standard as everywhere else in this
  document about not inventing data the server doesn't keep pushing.

## 6. Verification

- **Standard 5, first.** Before writing UI: a live IT that reaches a real sideboarding window and prints
  the mapped `SideboardPrompt`, confirming the deck payload is non-empty and matches what was
  registered. If it does not arrive the way §2 predicts, that supersedes this story's design.
- **Hermetic:** mapper tests for the new `SideboardPrompt.deck` field; screen/ViewModel tests over a
  fake `TableClient` for the swap UI, live legality (format + pool constraint), auto-save via
  `updateDeck`, and submit via `submitDeck`.
- **Live, a full match:** play a best-of-3 to game 2, sideboard for real (swap at least one card), and
  confirm game 2 starts with the modified deck. Also verify the timeout path: let the countdown expire
  without submitting and confirm the server-side auto-complete still lets the match proceed.
- **On-device (standard 3):** eyes-on checklist — the countdown is legible, the swap is obvious, and
  legality (including the pool constraint) is visible before submitting.

## 7. Acceptance criteria

- [ ] `SideboardPrompt` carries the player's current registered deck; `SideboardMapper` reads it from
      `TableClientMessage.getDeck()`. Verified live, not assumed.
- [ ] The room's "Sideboarding" phase opens a real screen, not a dead-end label.
- [ ] The player can move cards between deck and sideboard, sees live legality (format + pool
      constraint), and the change auto-saves via `updateDeck` as they edit.
- [ ] Submitting (`submitDeck`) or leaving the deck unmodified are both legitimate ways to finish.
- [ ] A visible, accurate countdown from `remainingSeconds`; no client-invented "time's up" state.
- [ ] A live best-of-3 match sideboards between games 1 and 2 with a real, confirmed deck change.
- [ ] The server-side timeout auto-complete path does not strand the app — it proceeds on whatever the
      server sends next.

## 8. References

- `docs/game-board-requirements.md` — §12.1 (corrected, full trace), §12.2 (concede/quit, same epic).
- `../mage`: `Mage.Server/.../TableController.java` (`sideboard`, `submitDeck`, `autoSideboard`,
  `setupTimeout`), `Mage/src/main/java/mage/game/match/MatchImpl.java` (`sideboard()`,
  `fireSideboardEvent`), `Mage.Common/.../mage/view/TableClientMessage.java` (the dropped `deck` field).
- `bridge/.../mapping/table/{SideboardMapper,ConstructMapper}.kt` — the mapper to fix, and the
  contrasting, justified omission in `ConstructMapper` to not confuse with this one.
- [`0059-table-deck-submission.md`](0059-table-deck-submission.md) — the same `submitDeck`/state-gating
  territory, from the join-time side.
- [`0033-deck-model-storage-and-legality.md`](0033-deck-model-storage-and-legality.md) — the deck model
  and legality checker this reuses.
