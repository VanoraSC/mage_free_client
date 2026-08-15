# 0067 — Commander format support

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0051/0052 (`GameClient`/`GameState`), 0055 (board rendering), 0066 (the same shape of
  card-identity mapping this story's command zone reuses)
- **Status:** planned — **parked, a future increment (Pete, 2026-08-15), not the first playable board.**
  Fully traced and specified below so nothing needs re-deriving when it's picked up.
  **§21.3a (commander damage) is a genuine product decision, not a default; resolve it before building
  anything that depends on the answer.**

## 1. Objective

Commander cannot be played through the app today, at all: there is no command zone on the board, no way
to cast a commander from it, and no way to see the running tax or accumulated commander damage. This
story closes that — except the one piece that is an upstream data constraint rather than a mapping gap
(commander damage), which needs a decision recorded here before the rest is built around an assumption.

## 2. Context & background — traced from `../mage`

Full trace in requirements §21.3; summary:

- **Commander identity is a deck-registration concern, not a game prompt.** At game init, cards in the
  player's sideboard are moved to the command zone (`GameCommanderImpl.init()`,
  `Mage/src/main/java/mage/game/GameCommanderImpl.java:100-130`) — "who is your commander" is already
  0033/0059's territory. This story starts from the command zone already being populated.
- **`PlayerView.commandList: List<CommandObjectView>`** carries Commander, Emblem, Dungeon, and Plane
  objects together, per player (`PlayerView.java:47,116-136`). `CommanderView extends CardView`
  (`CommanderView.java`) — the same shape hand/graveyard/exile already use. **Nothing on our side maps
  this zone at all** — not a partial gap, the entire zone is absent from `GameState`.
- **Commander tax needs no board work.** It is a `CostModificationEffectImpl` applied automatically,
  server-side, before the mana cost is ever shown (`CommanderCostModification.java` — CR 903.8, +{2}
  per previous cast from the command zone). Once casting from the command zone works at all, the
  increased cost is simply what `PlayMana` asks for — correct for free.
- **Commander damage is not exposed by upstream at all**, structurally. `CommanderInfoWatcher` tracks
  it purely server-side (`damageToPlayer: Map<UUID, Integer>`); an exhaustive grep of
  `Mage.Common/src/main/java/mage/view/` for it returns nothing. The only signal is the narration log
  (`game.informPlayers(...)`) — the same channel §1.3 already rejected parsing. **Even desktop XMage has
  no dedicated commander-damage HUD.** This is a real constraint, not something our bridge dropped.
- **"May move to command zone instead"** (a commander's own replacement on death/exile/bounce/tuck) is
  an ordinary `chooseUse` → `GamePrompt.Ask` — already fully generic, confirmed in requirements §21.1's
  table.
- **Partner/background/multiple commanders** need no new shape — `commandList` is already a `List`, and
  `getCommandersIds(player, CommanderCardType.ANY, false)` already handles more than one.

## 3. Scope

**In scope**
- `:protocol`/`:bridge`/`:core:network`: map `PlayerView.commandList` into `GameState`, reusing the
  card-mapping approach story 0066 establishes for graveyard/companion/lookedAt (`CommanderView`'s
  `CardView` shape means this is the same kind of fix, not a new one). Include emblems/dungeons/planes
  in the mapped shape even though this story's UI focus is the commander card itself — dropping them
  silently would repeat the exact pattern this project keeps finding and fixing.
- A command-zone region on the board — a new zone, so it needs its own placement decision (does it
  share space with another zone-count strip, get its own small area, or something else on a portrait
  layout that's already tight per §16.1's own cost note). Renders one or more commanders per seat.
- Casting a commander from the command zone: it should already appear in `playable` once the zone is
  mapped and the card can be named (reuses 0057's existing cast machinery entirely — no new interaction
  code, only the new region to tap from).
- Verify tax is correct for free, live, once casting from the zone works (§21.3's claim, not yet
  live-proven).
- **Resolve §21.3a before building any commander-damage UI**: decide between (a) surfacing the
  narration line as-is (no structured total, consistent with §8.1's "we never claim a winner we cannot
  identify" honesty standard) or (b) client-side accumulation from combat snapshots, and record which,
  with the reasoning, in this section once decided.

**Out of scope**
- Deck-building/registration of which cards are commanders — 0033/0059's territory, already assumed
  done by the time this story's game starts.
- Any commander-damage UI until §21.3a is resolved — do not build ahead of the decision.
- Color-identity deck-legality enforcement — a deck-builder concern (0033's `DeckLegality`), not a board
  concern.
- Full known-information browsing of emblems/dungeons/planes beyond what's needed to not drop them
  silently — deeper interaction with those (e.g. tracking dungeon room progress) is its own future scope
  if the game ever needs it live-verified.

## 4. Design & approach

- **Reuse 0066's mapping pattern exactly** rather than inventing a parallel one — `commandList` is
  another "a `CardsView`-shaped upstream collection is currently dropped" fix, same as graveyard.
- **Don't assume the command-zone region's layout** — portrait is already the scarce-height regime
  (§16.1's own cost note); this is a genuinely new board region with no prior placement decision to
  copy, so treat its layout as its own small design pass, not an afterthought bolted onto vitals.
- **§21.3a is a product decision, not an implementation detail.** Do not pick an answer inside a PR;
  record the decision (with Pete) at the top of this story's own text once made, the same way other
  resolved open questions in the requirements document are recorded in place.

## 5. Verification

- **Standard 5, first:** confirm live that `commandList` actually arrives non-empty in a real Commander
  game before designing the region around an assumed shape.
- **Hermetic:** mapper tests for the new field; board region tests for one commander, more than one
  (partner/background), and an emblem/plane/dungeon not silently dropped.
- **Live:** a real Commander game — cast a commander from the command zone, confirm tax increases
  correctly on a second cast from the zone, and confirm the "move to command zone instead" replacement
  (already generic) actually offers the choice when the commander would otherwise die/be exiled/bounced.
- **On-device (standard 3):** eyes-on — the command zone reads clearly in portrait without crowding out
  the rest of the board, and (once §21.3a is resolved) whatever commander-damage presentation was chosen
  is legible.

## 6. Acceptance criteria

- [ ] `commandList` (commander, emblem, dungeon, plane) is mapped into `GameState` for every seat.
- [ ] The command zone renders on the board, for both seats, including more than one commander when
      partner/background applies.
- [ ] A commander can be cast from the command zone via the existing cast machinery — no new casting
      code, only the new region to initiate it from.
- [ ] Commander tax is confirmed correct live on a second cast from the command zone.
- [ ] "May move to command zone instead" is confirmed to offer the choice on a real commander death.
- [ ] §21.3a is resolved and recorded, with reasoning, before any commander-damage UI is built.
- [ ] Emblems/dungeons/planes are not silently dropped by the mapping, even if their board presentation
      is minimal in this first pass.

## 7. References

- `docs/game-board-requirements.md` — §21.3 (the full trace), §21.3a (the open decision), §1.3/§8.1
  (the "no prose parsing" / "never claim what we cannot identify" precedents §21.3a weighs against).
- `PlayerView.java` (`commandList`), `GameCommanderImpl.java`, `CommanderCostModification.java`,
  `CommanderInfoWatcher.java`, `CommanderReplacementEffect.java` — the source this story is grounded in.
- [`0066-graveyard-companion-lookedat-identity.md`](0066-graveyard-companion-lookedat-identity.md) — the
  mapping pattern this story reuses for the command zone.
