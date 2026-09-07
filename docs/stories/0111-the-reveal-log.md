# 0111 — The reveal log: what has been shown, and whether it is still there

- **Story:** #195
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0110 (the status rail and the player window this opens from).
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.13 (zones),
  §7.4 (floating layers).

## 1. Objective

Keep a record of every reveal in a game — what was shown, what caused it, on which turn — and say of
each card whether it has since been seen to move.

## 2. Context & background

**Upstream throws reveals away.** `GameImpl` calls `getState().clearRevealed()` at both
`fireUpdatePlayerAction` sites, so a `RevealedView` survives until the next update and then vanishes.
A player who was looking elsewhere when Duress resolved has no way to ask what it showed. The
reference client has the same hole: it opens a window when the reveal happens and the window's
contents are gone with the next push.

**A reveal is worth remembering because it changes what you know.** Seeing three cards in an
opponent's hand on turn four is a fact that stays useful for several turns — right up until those
cards are played. That is the whole value, and it is exactly the part the server does not keep.

**Nothing but the client can keep it.** The server does not, and will not: `Revealed` is game state
that gets cleared, and asking upstream to hold it would be asking for a different game engine. So the
bridge remembers, because the bridge is the one place that sees every snapshot go past.

## 3. Scope

**In scope**
- A per-session record in the bridge of every reveal set it has seen this game: the server's own
  title, the turn it was first seen on, and the cards.
- Per card in a reveal, whether it has since been observed in a zone anybody can see.
- A new `GameStateView.reveals` carrying that record. `revealed` keeps its current meaning.
- A reveal log in the player window, reachable from **the viewer's own** status: one row per reveal,
  collapsed to the effect's name and the turn, expanded to the cards. Tapping a card opens it.

**Out of scope**
- **Attributing a reveal to a player.** See §5 — it can be derived, imperfectly, and the row's own
  label makes it unnecessary. Left out deliberately rather than left undone.
- **Looked-at cards** (`GameView.lookedAt`). Same shape, different rules about who may see them, and
  it deserves its own thinking.
- Any of this on an opponent's window. A reveal is public; the log is parked under the viewer's own
  status because that is where a player looks for what they know.

## 4. Prerequisites & toolchain

Project baseline plus `:protocol` and `:bridge`. **`:bridge` builds only in the container**
(`./scripts/dev`) — the tracker's own logic is pure and tested on the host, and the wiring needs the
container before this merges.

## 5. Design & approach

**A row is a reveal, because upstream already groups them that way.** `GameView.revealed` is a
`List<RevealedView>`, each `{name, cards}` — one entry per reveal event. An earlier cut of this
flattened them into a single pile, which threw away the grouping and then needed inference to get it
back. Keeping the sets is both less work and more faithful.

**The title identifies the event.** `CardUtil.createObjectRelatedWindowTitle` builds every title as
`sourceObject.getIdName() + " [" + stackMomentSourceZCC + "]" + suffix`, and `getIdName()` is
`getName() + " [" + first three characters of the source's UUID + "]"`. Two casts of the same card
differ in the zone-change counter, so the title is a usable key for "is this the same reveal I already
have" — no id bookkeeping needed.

**The effect's name is that title with the machine parts trimmed.** `Duress [a1b] [2]` reads as
*Duress*. It is a parse of a format one upstream function produces, not a guess about free text — and
because it is only ever a label, a parse that misses costs an ugly row header and nothing else.

**Why the row's label replaced attributing reveals to players.** The first design tried to answer
*whose hand was this* so a column could be titled with it. It can be done: every title names its
source card, and a source can be found among the visible cards by name and id prefix, which gives its
controller; and about fifteen of the four hundred-odd `revealCards` call sites put a player's name in
the title outright — those fifteen being, as it happens, the hand reveals. But a row that says
*Duress, turn 4* has already told the player what they need, and every guess it avoids is a guess that
cannot be wrong. Attribution stays available if a later story wants it.

**"Still there" is one boolean per card, and it is derived.** Each snapshot, the tracker looks for
each remembered card in the zones anybody can see — battlefields, graveyards, exiles, the stack, the
viewer's hand, command zones. A card found there has plainly moved, and is marked so for good.

Card ids survive this: `PermanentCard`'s constructor is `super(card.getId(), …)`, so a card keeps its
UUID from hand to battlefield to graveyard. XMage's own `zoneChangeCounter` is not on `CardView`, so
the id is the only thread there is — and it is enough.

**What it cannot see** is a move from one hidden zone to another: a card shuffled back into a library
or discarded face-down leaves every visible zone, so it stays marked as present although it has gone.
The counts move but cannot say which card. The log therefore errs toward showing a card that has
already left, which is the safer direction for a record whose purpose is *what do I know*, and the row
carries the turn so a player can judge how old the information is.

**Rows live for the game.** They are history; that is the point. Cards seen to move are marked rather
than removed, so *Duress showed me these three on turn four* survives being answered.

**The memory lives in the session, not the mapper.** `GameViewMapper` and `CallbackMapper` are shared
objects and must stay pure — a reveal memory on either would leak one player's reveals into another
player's view, and every single-client test would still pass. The tracker is per `LiveSession`, on the
same pump `GameStateCache` observes from, and it runs **before** the cache so a reconnect resyncs a
snapshot with the log in it rather than an empty one.

**A new field, not a rewrite of `revealed`.** `revealed` continues to mean *what the server is showing
right now*, mapped verbatim; `reveals` is the bridge's own accumulation and is named so nobody has to
work out which is which. Rewriting `revealed` in place would also break `GameStateCache`'s promise
that what it holds is byte-identical to what crossed the wire.

## 6. Implementation steps

1. `GameRevealView` on the protocol, and `GameStateView.reveals`.
2. The tracker: pure `(record, snapshot) -> record`, plus the title parse.
3. Wire it into the session pump, ahead of the cache.
4. The app's model over `reveals`.
5. The log in the player window, on the viewer's own seat.

## 7. Testing & verification

- **Proven failing first (standard 1):** the "a reveal outlives the snapshot that carried it" test must
  fail against a tracker that simply passes `revealed` through.
- **Unit, on the host:** a reveal is remembered after the server stops sending it; the same reveal
  arriving twice is one row; two casts of one card are two rows; a card later seen on the battlefield
  is marked moved; a card never seen again stays present; the title parse strips the id and the
  counter; a game over clears the record.
- **Hermetic Compose:** the log lists a row per reveal, collapsed to effect and turn; expanding shows
  the cards; a card opens the preview; a moved card is marked; an opponent's window has no log.
- **Container:** `GameRelayIT` — the wiring, which the host cannot build.
- **Eyes-on:** the cast mock, whose fixture gains a reveal.

## 8. Acceptance criteria

- [ ] A reveal is still in the log after the server has stopped sending it.
- [ ] Each row shows the effect's name and the turn it happened on, and expands to the cards.
- [ ] A card seen in any visible zone since is marked as moved.
- [ ] The log is reachable from the viewer's own status and from nowhere else.
- [ ] Tapping a card in the log opens the card preview.
- [ ] `./gradlew check` passes on the host, and `:bridge` builds in the container.

## 9. References

- `docs/ui-modernization-plan.md` §7.13 (zones), §7.4 (floating layers).
- Upstream: `PlayerImpl.revealCards`, `CardUtil.createObjectRelatedWindowTitle`, `MageObjectImpl.getIdName`,
  `GameImpl.clearRevealed`, `PermanentCard`'s constructor.
- `docs/stories/0110-the-status-rail.md` — the player window this opens from.
