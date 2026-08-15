# 0066 — Card identity for graveyard, companion, and looked-at zones

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0051/0052 (`GameClient`/`GameState`), 0055 (board rendering)

## 1. Objective

Three upstream zones already carry full per-card identity (`CardsView`, the same shape hand and exile
already use) and are currently reduced to nothing, or a bare count, on our side. Fix all three in one
pass — they are the same shape of gap, found in two separate reviews (requirements §11.3 and §21.2).

Without this, a player cannot tell what a Flashback/Escape/Jump-start/Unearth/Embalm/Eternalize/Disturb
card **is** before casting it (it renders as `"Unnamed candidate 1"`), cannot see their own or an
opponent's companion, and cannot see what a scry/surveil-adjacent "look at" window actually contained
after the fact.

## 2. Context & background — traced from `../mage`, not guessed

- **`PlayerView.graveyard: CardsView`** — a full per-card view, sent for **every player, always**
  (graveyard is public information): `graveyard.put(card.getId(), new CardView(card, game, …))`
  (`Mage.Common/src/main/java/mage/view/PlayerView.java:40,84-85`). Our bridge reads only
  `player.graveyard?.size` (`bridge/.../GameViewMapper.kt:92`) — the `CardsView` itself is dropped.
  `PlayerImpl.getPlayable(...)` already scans `Zone.GRAVEYARD` when computing `playable`
  (`Mage/src/main/java/mage/players/PlayerImpl.java:4359-4369`), so graveyard-castable spells are
  **already** offered to the app — they just can't be named. See requirements §21.2 for the full trace,
  including `BoardControls.kt`'s own `offBoardCandidateButtons` comment naming this exact gap
  ("flashback from a graveyard is the everyday case").
- **`companion`** and **`lookedAt`** — flagged independently in requirements §11.3 (2026-08-13):
  *"populated upstream and were not mapped by 0051 — a `:protocol` + `:bridge` fix, independent of the
  board."* `companion` also gates story 0062's design for the Companion mechanic (requirements §21.4) —
  `CompanionAbility` is a `SpecialAction` in `Zone.OUTSIDE`, so 0062's board affordance already
  *triggers* it correctly; this story is what lets the player see which card it is.

## 3. Scope

**In scope**
- `:bridge`: map `PlayerView.graveyard`'s `CardsView` (not just its size) — likely into a new
  `:protocol` field alongside the existing `graveyardCount`, mirroring how `hand`/`exile` are already
  shaped.
- `:bridge`: map `companion` and `lookedAt` similarly (both already exist on the upstream view types
  per §11.3 — confirm the exact source field per zone before mapping, don't assume the shape matches
  graveyard's).
- `:core:network`: extend `GameState`/`GamePlayer` with the new per-card collections, keeping
  `graveyardCount` etc. as-is (additive, not a replacement — other code may still want just a count).
- `feature/game`: extend `nameFor` (`BoardControls.kt`) to check the newly-available graveyard cards —
  this alone fixes the `offBoardCandidateButtons` fallback for Flashback-style casting, turning
  `"Unnamed candidate 1"` into the real card name (and, per 0031, its art).
- A minimal way to **see** a companion pre-game (own and opponent's) and browse `lookedAt` contents —
  does not need to be the full "known-information browser" (§11.2, still out of scope generally), just
  enough that the data isn't invisible once it's mapped.

**Out of scope**
- The full known-information browser (§11.2) — still a later story; this only makes the *data* available.
- Command zone (`commandList`) — same shape of fix, but scoped separately as story 0067 because it
  carries genuinely new rendering concerns (a zone with no board precedent) beyond a card-identity map.
- Any change to `graveyardCount`/existing count fields — additive only.

## 4. Constraints already verified — do not rediscover

- Graveyard scanning for `playable` already happens server-side and reaches every graveyard in range,
  not only the viewer's own — a Deathrite-Shaman-style ability can already legally target an opponent's
  graveyard card; naming it requires this fix to apply to **both** players' graveyard mappings, not
  only the viewer's.
- A `Target` prompt that names a graveyard card directly (Delve, Deathrite Shaman) already carries its
  **own** `cards` list per-prompt (proven live for Scry, §11.3) — that path is not broken by this gap and
  needs no fix. This story is specifically about the `playable`/`offBoardCandidateButtons` path, where
  there is no per-prompt card list to fall back on.

## 5. Verification

- **Hermetic:** mapper tests confirming the new fields round-trip; a `nameFor` test proving a graveyard
  card id now resolves to its real name instead of falling through to `UNNAMED_CANDIDATE_LABEL`.
- **Live:** a Flashback deck (or any graveyard-castable card) — confirm the board shows the real card
  name (and ideally art) for a Flashback-eligible candidate instead of "Unnamed candidate N", and that
  tapping it still casts correctly (the mechanism already worked; only the label was wrong).
- **Standard 5:** confirm live that `companion`/`lookedAt` actually populate in the situations that
  should produce them (a companion-eligible deck; a scry/surveil-adjacent look-at effect) before
  promising them — §11.3 already found one field (`opponentHands`) that *looked* available and never
  was; don't assume these two are different without checking.

## 6. Acceptance criteria

- [ ] A Flashback/Escape/Jump-start/Unearth/Embalm/Eternalize/Disturb card offered through `playable`
      renders with its real name (not `"Unnamed candidate N"`), for either player's graveyard.
- [ ] The companion mechanic (story 0062) shows which card is being fetched, for both players.
- [ ] `lookedAt` contents are visible somewhere on the board, even minimally, once populated.
- [ ] All three fields verified live to actually populate — not just asserted from source reading.
- [ ] Existing count-only fields (`graveyardCount`, etc.) are unchanged.

## 7. References

- `docs/game-board-requirements.md` — §11.3 (the original `companion`/`lookedAt` finding), §21.2 (the
  graveyard trace, and why the `Target`-prompt path is unaffected), §21.4 (companion ties to 0062).
- `PlayerView.java`, `PlayerImpl.getPlayable`, `GameViewMapper.kt` — the exact source/sink of the gap.
- `feature/game/.../board/BoardControls.kt` — `offBoardCandidateButtons`/`nameFor`, the code that
  already anticipated this fix.
- [`0062-alternative-costs-convoke-delve.md`](0062-alternative-costs-convoke-delve.md) — companion
  reuses its interaction design entirely; this story only unblocks legibility.
