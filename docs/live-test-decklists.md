# Live-test decklists

Decks that are **known to work** against the pinned reference XMage server, so a future experiment does
not have to rediscover them.

**Why printings matter:** `Deck.load` resolves a card by **(setCode, collectorNumber)** — *not by
name*. A wrong pair fails with "Card not found" even when the name is spelled correctly. Every pair
below was taken from the bundled catalog (`core/cards/src/main/assets/cards.sqlite`) and used in a run
that reached its assertions.

**Table setup that works for all of them:** game type `Two Player Duel`, deck type
`Constructed - Freeform Unlimited` (the only validator that is unconditionally valid with
`deckMinSize == 0`), seats `[Human, ComputerMad]`, **AI joined first**, then the host.

---

## 1. Mono-basic — game reaches a real state
Used by `AppBridgeGameIT` / `GameRelayIT`. Simplest possible deck; proves join, opening hand, turn,
priority and `canPlayObjects` without any casting.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 60 | Forest | `M21` | `272` |

## 2. Cast-and-cancel — proves the rewind (2026-08-13)
Squadron Hawk costs `{1}{W}`, so casting genuinely requires paying mana; that mana step is the one
that gets declined.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 20 | Plains | `M11` | `230` |
| 40 | Squadron Hawk | `M11` | `33` |

**Result:** beginning the cast moved the Hawk to the stack (hand 7→6, stack 0→1) and prompted for
mana; declining returned it to hand (7), cleared the stack, and left both Plains **untapped**.

## 3. Scry — proves scry is observable with card identity (2026-08-13)
Temple of Triumph enters tapped and scries 1 on entry, so simply playing it produces a scry decision.

| Qty | Card | Set | № |
|----:|------|-----|---|
| 24 | Swamp | `10E` | `372` |
| 20 | Thoughtseize | `2XM` | `109` |
| 16 | Temple of Triumph | `BLC` | `344` |

**Result:** the scry decision arrives as an ordinary **Target prompt carrying the real card** —
`msg='Select up to one card to PUT on the BOTTOM of your library (Scry)' cards=[Thoughtseize]
required=false`.

**Caution learned the hard way:** Temples enter **tapped** and produce R/W, so a deck relying on them
for black mana never casts Thoughtseize. If the experiment needs black mana available, prefer Swamps
and play them first.

---

## Other verified printings (catalog-checked, for future decks)

| Card | Set | № |
|------|-----|---|
| Arid Mesa | `MH2` | `244` |
| Thoughtseize | `2XM` | `109` (also `AKR` 127, `IMA` 110) |
| Temple of Triumph | `BLC` | `344` (also `C21` 327) |
| Plains | `M11` | `230` |
| Swamp | `10E` | `372` |

## Harness notes

- Live tests are env-gated: `BRIDGE_URL=localhost:8080` for the app-side suites
  (`core/network/src/test/.../live/`), `XMAGE_SERVER=xmage-server:17171` for the `:bridge` ITs.
- **Receive timeouts must tolerate the AI's turn** — but a longer timeout is *not* a cure-all. 20 s
  starved; raising it to **75 s starved too** (`no GameState emitted within 75000ms`). So the stall is
  not merely AI think time: after certain answers the game stops pushing to us altogether. **Unsolved.**
  A future attempt should log every state and prompt as it arrives (rather than only interesting ones)
  to find the last thing answered before the stream goes quiet — the answer is probably a prompt kind
  the auto-responder handles wrongly, leaving the server waiting on a reply that never comes.
- **Deep-game experiments are therefore expensive.** Anything needing several turns of real play is
  better done *inside* the story that builds the relevant surface, where the loop can be driven by the
  real UI logic, than as a standalone scripted probe.
- The main-phase state returned by `playUntilOurMainPhase` is **already in hand**; act on it before
  the next `receive`, or the loop waits for a state the server will not send until you answer.
