# 0059 — Deck submission: offer it only where it works, and say why when it fails

- **Epic:** EPIC-07 — Hosting & Joining Tables (defect fix)
- **Depends on:** 0036 (table protocol/relay), 0037 (`TableClient`), 0038 (table room UI)
- **Status:** ready

## 1. Objective

The table room's **"Submit your deck"** picker is declined by the server **every time**, and the app
reports only *"the server declined the action"*. Two defects: a control offered in a state where it
cannot work, and a failure whose reason is thrown away.

## 2. Context & background — confirmed against the server's own bytecode

**Observed** during story 0057's independent verification: on two tables (deck types `Limited` and
`Constructed - Vintage`) with three different decks, every tap on a deck row produced
*"the server declined the action"*. The match started anyway, because the host's deck is bound by
`joinTable` at table creation — so *Start match* works without ever submitting.

**Root cause**, read from `mage-server-1.4.60.jar` (`mage.server.TableController`, the exact artifact
the pinned reference server runs):

- **`updateDeck(UUID, DeckCardLists)` is a guaranteed no-op outside two states.** The method compares
  the table state against `SIDEBOARDING`, then against `CONSTRUCTING`, and if it is neither it
  **returns immediately**, having done nothing. It returns `void` — the caller cannot even tell.
- **`submitDeck`'s private path only reaches the match in `SIDEBOARDING`.** Outside it, the code
  branches to `tournamentManager.submitDeck(tournament.getId(), …)` using the controller's `tournament`
  field, which is null for a plain match table. There is also a sibling path that logs the literal
  string **`"wtf, why it submitting?!"`** when a non-tournament table is asked to update outside
  sideboarding — upstream's own acknowledgement that this call is not expected here.
- **`submitDeck` does validate the deck** and builds a message from `DeckValidatorError.getGroup()` and
  `.getMessage()` — so a real reason exists when it declines.

`TableState` is `WAITING, READY_TO_START, STARTING, DRAFTING, CONSTRUCTING, DUELING, SIDEBOARDING,
FINISHED`. Our table sat in **`READY_TO_START`** — the app rendered it verbatim as *"Table: Ready to
start"*. So these verbs are **sideboarding/construction verbs**, and the room offers them during
waiting, where upstream never intended them to be called.

**Why the reason vanished.** `SessionImpl.submitDeck` returns a bare `boolean`, and
`TableRelay.submitDeck` maps it straight to a success/failure result. The validator errors the server
assembles are delivered to the user over a **different channel** (a user message), which the bridge does
not correlate with the action. So the app can only ever say "declined" — which is exactly why the state
problem and a possible validation failure were indistinguishable during 0057, and why this took a
bytecode read to settle.

## 3. Scope

**In scope**
- **Offer deck submission only where it can work.** Drive it from the table's **state**, not from the
  seat: submission belongs to `CONSTRUCTING` and `SIDEBOARDING`. In `WAITING`/`READY_TO_START` the
  seat's deck is the one bound at `joinTable`, and the room should say so rather than offering a
  control that silently fails.
- **Changing your deck before the match starts** is the real user need behind the control. Decide and
  record how it is served — the honest options are to re-seat with a different deck (leave + rejoin), or
  to omit it until sideboarding. **Do not** keep a button that calls a verb upstream ignores.
- **Carry the server's reason.** When an action is declined, the app must be able to say *why* where a
  reason exists. That means the bridge correlating the user-message channel with the action, or at
  minimum distinguishing "the server said no" from "this action is not valid in this state" locally.
- `updateDeck`'s `void` return is worth noting in the client's KDoc: a success result from it means
  "the message was delivered", never "the deck was accepted".

**Out of scope**
- The **sideboard screen** itself (§12.1 of the board requirements) — that is the surface where
  `submitDeck` is genuinely correct, and it is its own story. This story must not pre-empt its design;
  it only stops the room lying about a verb it cannot fulfil.
- Tournament and draft flows (EPIC-08).
- The host form's deck-type list — that is [0060](0060-server-provided-table-types.md).

## 4. Constraints already verified — do not rediscover

- `joinTable` carries a `DeckCardLists`; **the deck is bound at join**, which is why matches start fine.
- `updateDeck` outside `SIDEBOARDING`/`CONSTRUCTING` does nothing and reports nothing.
- `submitDeck` outside `SIDEBOARDING` takes a tournament path with a null tournament on a match table.
- The failure surfaced today is a bare boolean; **the reason exists but arrives elsewhere**.
- The app already renders the state text verbatim (*"Table: Ready to start"*), so the state is present
  in the app schema and no new field is needed to gate the control.

## 5. Verification

- **Standard 1** — demonstrate the gating test failing first: a room in `READY_TO_START` must offer no
  deck submission, and a room in `SIDEBOARDING` must.
- **Standard 2 (reachability)** — name what *produces* each table state the UI branches on. A state the
  app can never observe is a branch that can never be tested.
- **Live** — the honest proof is a table that actually reaches `SIDEBOARDING`, which needs a match of
  **at least two games** (`1 win(s)` will not do — raise the match win count when hosting). Record the
  setup in `docs/live-test-decklists.md` once it works, per the standing instruction.
- Confirm the previously-failing path no longer offers a dead control, and that a genuine decline
  surfaces something more useful than "the server declined the action".

## 6. Acceptance criteria

- [ ] The table room offers deck submission **only** in states where upstream acts on it.
- [ ] In waiting/ready states the room states which deck the seat is bound to, rather than offering a
      control that silently fails.
- [ ] A declined action surfaces the server's reason where one exists, and otherwise distinguishes
      "not valid in this state" from "the server refused".
- [ ] Verified live against a table that actually reaches sideboarding.
- [ ] No change to `joinTable`'s deck binding, which is the path that works today.

## 7. References

- `bridge/src/main/kotlin/magefree/bridge/mapping/TableRelay.kt` — `submitDeck`/`updateDeck`, where a boolean becomes a result.
- `feature/tables/src/main/kotlin/magefree/feature/tables/room/TableRoomViewModel.kt` — `submitDeck`/`updateDeck` and the generic error.
- `mage.server.TableController` (`mage-server-1.4.60.jar`) — the state guards quoted above.
- [`0060-server-provided-table-types.md`](0060-server-provided-table-types.md) — the other table defect found in the same pass.
