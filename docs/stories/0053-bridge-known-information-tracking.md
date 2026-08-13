# 0053 — Bridge-side known-information tracking

- **Epic:** EPIC-11 — In-Game Play (**post-initial-release increment**)
- **Depends on:** 0051 (game protocol/relay), 0052 (game client/state), the board UI increment
- **Status:** planned — **deliberately after the first playable release**

## 1. Objective

Make information the player has **already been shown** stay available to them, without relying on
memory. Once a card has been revealed, looked at, or scried, the client should be able to show it
again — and keep it correct as the game moves on. **This requires augmenting the bridge**, because
XMage does not track it.

## 2. Context & background

### Why the client cannot do this alone
Established by experiment (2026-08-13, recorded in `docs/game-board-requirements.md` §11.3):

- **XMage does not maintain known-information state.** `GameView.opponentHands` is declared with a
  getter and **never written to anywhere in the codebase** — permanently empty (this is the case that
  motivated verification standard 5, *unexpectedly absent*).
- **There is no library-position concept** — after a scry or a tuck the snapshot carries only
  `libraryCount`. XMage expects the player to remember, exactly as in paper.
- **Reveal and look-at windows are transient.** Thoughtseize uses `DiscardCardYouChooseTargetEffect`,
  which shows the hand *at resolution*; scry arrives as a **Target prompt carrying the real card**.
  Both are moments, not memory.

So the information genuinely reaches us — it simply is not retained by anyone.

### Why the bridge is the right place
- The bridge **sees every snapshot and every prompt**, including the reveal and scry moments the
  client might miss or discard.
- It is **already stateful and long-lived** per session (story 0023's registry), and story 0050 made
  its keepalive run for the whole session, so it is present even while the app is backgrounded.
- The reconnect design (`docs/game-board-requirements.md` §10.1) **already makes the bridge the
  authority on current board state**. Known-information tracking is the same idea extended over time:
  the bridge remembers what has been shown, not just what is showing.
- Doing it client-side would put a knowledge model behind an app that can be killed, reinstalled, or
  reconnected mid-game — and would duplicate the work per client.

### Why it is deferred (deliberately)
Pete, 2026-08-13: *"let's just implement it as it is in XMage and then plan a follow on increment
(post initial release) to improve the information tracking in the bridge."* The first playable release
matches XMage's own behaviour, so a player is never worse off than on the desktop client. This story is
strictly an improvement on parity, not a prerequisite for playing.

## 3. Scope

**In scope**
- **A bridge-side knowledge model, per game, per player**, accumulated from what that player is
  actually shown: reveal windows, look-at windows, scry decisions, and any other prompt that discloses
  a specific card.
- **Tracking individual cards by id**, not just "you once saw a Thoughtseize" — so the record can be
  kept correct as cards move.
- **Invalidation as the game moves on.** A tracked card must stop being reported as known when it is no
  longer knowable: it leaves the zone it was seen in, the library is shuffled, or the game ends. The
  invalidation rules matter more than the capture — a stale "known" card is worse than none, because
  the player will act on it.
- **Exposing it over `:protocol`** as an additive, clearly-separate channel — the app must be able to
  tell *"the server says this"* from *"the bridge remembers this"*. Never blend inferred knowledge into
  the authoritative snapshot.
- A UI affordance in the known-information browser (`docs/game-board-requirements.md` §11.2) that
  presents remembered information as remembered, with its provenance.

**Out of scope**
- Changing XMage or the pinned upstream ref in any way.
- Inferring information the player was never shown (deducing a decklist, counting outs) — this records
  what was seen, it does not deduce.
- Anything in the first playable release.

## 4. Design constraints

- **Never present remembered information as current truth.** The snapshot is authoritative; the
  knowledge model is a separate, clearly-labelled overlay.
- **Shuffles invalidate library knowledge.** Arid Mesa (or any shuffle) must clear position knowledge —
  this is exactly where a naive implementation misleads a player into a punt.
- **Hidden movement is the hard case.** A card seen in hand that leaves for a zone we cannot observe
  must become unknown, not silently persist. When in doubt, forget it.
- **Reachability (standard 2)** applies to every field this exposes: name what produces it, and
  distinguish *server-produced* from *bridge-remembered* in the protocol itself.

## 5. Open questions to settle when this is picked up

- What does a **reveal window actually contain** (card identity, zone, duration)? Three live attempts
  did not get Thoughtseize to resolve — see `docs/live-test-decklists.md` for the decks and the
  starvation problem to solve first.
- Does declining a **target** rewind the cast the way declining the **mana step** does (proven)?
- Should knowledge survive a **reconnect** (bridge-side, so it can) and a **rejoin**?
- Per-game only, or across games in a match (sideboarding implies the latter is meaningful)?

## 6. Acceptance criteria (draft)

- [ ] Information the player has been shown remains retrievable without their memory, for the cases
      XMage discloses: reveals, look-at windows, scry decisions.
- [ ] Tracking is per individual card, and is **invalidated** when the card is no longer knowable —
      zone change, shuffle, game end — with shuffle-clears-library-knowledge explicitly tested.
- [ ] The protocol distinguishes server-authoritative state from bridge-remembered knowledge; the UI
      presents remembered information *as* remembered.
- [ ] No change to XMage or the pinned ref; the first-release behaviour is unaffected when the feature
      is off.

## 7. References

- `docs/game-board-requirements.md` §11.2–11.3 — what is knowable, and the experiment that established it.
- `docs/live-test-decklists.md` — working decks and harness pitfalls for the outstanding experiments.
- `docs/stories/README.md` § Verification standards — standard 5 (*unexpectedly absent*) came from this investigation.
