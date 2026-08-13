# 0055 — Board rendering (read-only)

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0052 (`GameClient`/`GameState`), 0054 (state cache), 0031 (card art), 0014 (card components), EPIC-03 (design system)
- **Status:** ready

## 1. Objective

Draw the game. A **portrait**, read-only board that renders a live `GameState` — both battlefields,
your hand, the stack, phase and turn, player vitals, and whose priority it is — with **real card art**.
**No interaction:** nothing is played, no prompt is answered, nothing is cancelled. That is 0056.

Read-only first so the portrait layout is proven against real game data before any interaction logic
exists to confuse a layout bug with a state bug.

## 2. Context & background

- **The requirements are written:** `docs/game-board-requirements.md`. This story implements the
  rendering half of §1–§4, §11 and §16. Read it first; it records not just decisions but the data
  constraint behind each, and every constraint below was verified live.
- **Sequencing decision (Pete):** read-only board first, then interaction; **functional layout but
  real card art from day one**, because a Magic board without card images cannot be honestly evaluated.
- **The data is proven and available.** 0052 delivers an app-schema `GameState` (players, hand,
  battlefields, stack, phase/step, turn, priority, playable ids, prompt) from a live game; 0054 lets a
  reconnecting client ask for the current board rather than wait for a push.

## 3. Scope

**In scope**
- **Portrait board** (§16.1): opponent's battlefield above, yours below (§3.1).
- **Hand**: peek-and-expand (§3.2) — a slim edge that expands over the board.
- **Stack, phase and turn** (§4.1) — the side panel of §4.1 was designed for landscape; **re-shape it
  for portrait** and record what you chose. The stack is usually empty and fills abruptly: it must read
  sensibly empty and **must not reflow the battlefields** when it fills.
- **Player vitals** (§4.3): a compact bar per player — life prominent, zone counts small.
- **Priority, stated explicitly** (§4.2): "Your turn to act" / "Waiting for opponent", *in addition to*
  any per-card affordance — because holding priority with nothing playable is a real state.
- **Card art from day one**, via 0031's loader and 0014's card components. Art is the only networked
  thing here.
- **Empty states everywhere** (§1.2): the board is *never* guaranteed to arrive populated — the first
  snapshot legitimately has an empty hand, and stack/combat/revealed are routinely empty.
- Reachable from the `MatchStarting` hand-off that Epic 7 currently leaves dangling.

**Out of scope**
- **All interaction** — casting, targeting, mana, cancel, combat declaration, answering prompts,
  passing priority (**0056** and later). The board renders `prompt` if it helps comprehension but must
  not offer a way to answer it.
- The interstitial and coin toss (§1, §15), the known-information browser (§11.2), match flow (§12),
  spectating (§13) — later stories.
- Visual polish beyond design-system components; animations.

## 4. Design constraints (each verified — do not rediscover)

- **Find your seat via `isViewer`/`viewerPlayerId`, never by list index.** Player order is unstable and
  is not viewer-first (observed live: opponent first in two runs, viewer first in two others).
- **Never compute legality.** `playable` is the server's own answer, and it is populated **only while
  you hold priority** — so a card being un-highlighted may mean "not your moment", not "not playable".
- **`exile` contains one zone entry even when nothing is exiled** — judge by cards, not list size.
- **`manaCost` is null for lands.**
- **Clocks read 0 on an untimed table** — a timer must not render as "0 seconds left".
- **Server narration is HTML** (`<font color='…'>`) — strip or render it, never show markup.
- **No structured game result** — `GAME_OVER` is one prose line (game end is a later story, but do not
  design a winner-badge that cannot be populated).
- **The opponent's stack can hold a phantom** after they cancel a cast (§17.4) — the rewind is not
  pushed to them. Do not present the opponent's stack as authoritative between pushes.

## 5. Verification

- **Hermetic:** ViewModel/rendering tests over fakes for each region incl. its **empty** state; a
  Robolectric/Compose test in the hermetic gate (device-only tests do not run pre-merge — that is how
  an entire epic stayed unmounted). Reachability (standard 2): every rendered field names the
  `GameState` field that produces it.
- **On-device (standard 3) — the real proof.** The board is read-only, so it cannot drive a game
  itself: use the **two-client harness** (`core/network/src/test/.../live/`, and
  `docs/live-test-decklists.md` for decks with exact printings). A test client drives a real game while
  the app renders it, and the board is observed changing as the opponent plays. Confirm: the opening
  hand appears with art, both battlefields populate, the stack fills and clears, phase/turn advance,
  and the priority indicator is correct in both directions.

## 6. Acceptance criteria

- [ ] A live game renders in **portrait**: opponent's battlefield above, yours below, hand as a
      peek-and-expand edge, stack/phase/turn visible, a compact vitals bar per player.
- [ ] **Real card art** renders for hand, battlefields and stack.
- [ ] Priority is stated **explicitly**, and is correct when you hold it with nothing playable.
- [ ] Every region has a defined **empty state**, and the board renders correctly from the very first
      snapshot (empty hand).
- [ ] The viewer's seat is located via `isViewer`; no index assumptions anywhere.
- [ ] The board **offers no way to act** — no card is playable, no prompt is answerable.
- [ ] Verified on-device against a real game driven by a second client.

## 7. References

- `docs/game-board-requirements.md` — §1–§4 (entry, layout, stack/priority), §11.3 (what is knowable), §16 (portrait/floating revision), §17 (target-cancel findings).
- [`0052-game-client-and-state.md`](0052-game-client-and-state.md) — the `GameState` this renders; [`0054-bridge-game-state-cache.md`](0054-bridge-game-state-cache.md) — reconnect.
- `docs/live-test-decklists.md` — working decks, exact printings, and harness pitfalls.
