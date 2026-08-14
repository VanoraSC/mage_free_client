# 0060 — Table types come from the server, not from a hardcoded list

- **Epic:** EPIC-07 — Hosting & Joining Tables (defect fix)
- **Depends on:** 0036 (table protocol/relay), 0037 (`TableClient`), 0038 (host form)
- **Status:** ready

## 1. Objective

The host form offers **7** deck types. The reference server advertises **52**. The list is hardcoded in
the app, so most of what the server supports cannot be hosted — including
`Constructed - Freeform Unlimited`, the one validator our own `docs/live-test-decklists.md` calls
reliably valid (`deckMinSize == 0`), and every Commander, Brawl, Oathbreaker, Pauper-adjacent and Block
Constructed format.

## 2. Context & background — the numbers, and the fact that the server will tell us

**Found** while hosting a table by hand during story 0057's verification: the deck-type chips scroll to
`Limited` and stop, with no Freeform anywhere.

`feature/tables/.../host/HostTableViewModel.kt` hardcodes three lists:

```kotlin
val HOST_GAME_TYPES = listOf("Two Player Duel", "Commander Free For All", "Free For All")
val HOST_DECK_TYPES = listOf(
    "Constructed - Standard", "Constructed - Pioneer", "Constructed - Modern",
    "Constructed - Legacy", "Constructed - Vintage", "Constructed - Pauper", "Limited",
)
val HOST_SEAT_TYPES = listOf(SeatPlayerType.Human, ComputerMonteCarlo, ComputerMad)
```

**These are server configuration, not constants.** The reference server's `config.xml` declares 52
`<deckType>` entries — read from the running container — spanning `Constructed - *` (including
`Freeform` and `Freeform Unlimited`), `Variant Magic - *` (Commander, Duel Commander, Tiny Leaders,
Momir Basic, Brawl, Oathbreaker…), `Block Constructed - *`, and `Limited`. Each names a jar and a
`className`, loaded as a **plugin**. A differently-configured server would advertise a different list —
so any hardcoded list is wrong by construction, and silently wrong.

**The server already offers the list to clients.** `mage.remote.SessionImpl` exposes:

| Method | Returns |
|---|---|
| `getDeckTypes()` | `String[]` |
| `getGameTypes()` | `List<GameTypeView>` |
| `getPlayerTypes()` | `PlayerType[]` |
| `getTournamentTypes()` | `List<TournamentTypeView>` |

So all three hardcoded lists have a server-provided source, and the bridge already holds the
`SessionImpl` that exposes them. Nothing needs to be invented — the data is simply not being asked for.

This is the same class as 0056 and 0058: **a value the server already provides that our stack does not
carry**, and which no test could catch because every test asserts against the same hardcoded list the
production code uses.

## 3. Scope

**In scope**
- **`:bridge`** — read `getDeckTypes()`, `getGameTypes()` and `getPlayerTypes()` from the session and
  expose them. They are properties of the connected server, so they belong with the server/session
  state, fetched once per session rather than per host attempt.
- **`:protocol`** — carry the three lists. Additive.
- **`:core:network`** — expose them through the table/connection client.
- **`:feature:tables`** — the host form renders **what the server offers**, with the hardcoded lists
  deleted. Keep a sensible default selection (`Two Player Duel` where present) and make the picker
  usable at 52 entries — the current horizontal chip strip is already awkward at 7 and will not do.
- **Seat types:** `getPlayerTypes()` supersedes `HOST_SEAT_TYPES`. Keep 0038's deliberate exclusion of
  `ComputerDraftBot` (draft-only, EPIC-08) as an explicit filter of the server's list, not as a
  hardcoded allow-list — and say why in a comment.

**Out of scope**
- Tournament types (`getTournamentTypes()`) — EPIC-08 owns tournaments; do not build a UI for them here,
  though carrying the list is acceptable if it falls out of the same call.
- Deck **validation** in the app. Legality stays the server's answer (0038 already says so on screen:
  *"the server checks legality when you sit down"*).
- The table room's deck submission — [0059](0059-table-deck-submission.md).

## 4. Constraints already verified — do not rediscover

- The reference server advertises **52** deck types; the app offers **7**. Both numbers were read
  directly (server `config.xml`; `HOST_DECK_TYPES`).
- Deck types are **plugin-loaded** and named in server config — a server may legitimately offer more,
  fewer, or different ones. Do not treat the reference server's list as canonical.
- `Constructed - Freeform Unlimited` is the validator with `deckMinSize == 0`, which is why the live
  harnesses use it and why hosting by hand from the app cannot currently reproduce them.
- Deck resolution elsewhere is by `(setCode, collectorNumber)` — unrelated, but the same host flow
  carries a deck; do not disturb it.
- A **stale or empty** list is a real state: a server that answers with nothing must not leave a form
  that cannot be submitted. Decide what the form does then, and test it.

## 5. Verification

- **Standard 1** — demonstrate failing first: with a fake server advertising a list that includes
  `Constructed - Freeform Unlimited`, the host form must offer it. Against today's code it cannot.
- **Standard 5 (unexpectedly absent)** — for each of the three lists, name what *writes* it: the bridge's
  read of the session. A protocol field with no mapper behind it is 0058's defect repeated.
- **A test that would have caught this:** assert the form's options come from the **injected server
  state**, not from a constant. A test that asserts "the form offers Vintage" passes equally against a
  hardcoded list and is worthless here.
- **Live** — against the reference server, confirm the form offers the full advertised set, and host a
  table with `Constructed - Freeform Unlimited` end to end, since that is the case that motivated this.
- **On device** — the picker must remain usable at ~52 entries.

## 6. Acceptance criteria

- [ ] Game types, deck types and seat types are read from the connected server; the hardcoded lists are
      **deleted**, not extended.
- [ ] A table can be hosted from the app with `Constructed - Freeform Unlimited`.
- [ ] The picker is usable on a phone at the real list size.
- [ ] An empty or unavailable list leaves the form in a defined, non-broken state.
- [ ] `ComputerDraftBot` remains excluded, as a filter over the server's list with a stated reason.
- [ ] Each new protocol field has a bridge mapper that writes it.

## 7. References

- `feature/tables/src/main/kotlin/magefree/feature/tables/host/HostTableViewModel.kt` — `HOST_GAME_TYPES`, `HOST_DECK_TYPES`, `HOST_SEAT_TYPES`.
- `mage.remote.SessionImpl` (`mage-common-1.4.60.jar`) — `getDeckTypes`, `getGameTypes`, `getPlayerTypes`.
- `docs/live-test-decklists.md` § Harness notes — records this gap and why it bites anyone hosting by hand.
- [`0059-table-deck-submission.md`](0059-table-deck-submission.md) — the other table defect found in the same pass.
