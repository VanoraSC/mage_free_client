# 0056 — Card art: send a User-Agent

- **Epic:** EPIC-10 — Card Database (defect fix, **app-wide**)
- **Depends on:** 0031 (art loader), 0043 (artwork pipeline fixes)
- **Status:** ready

## 1. Objective

Make card art load. `CardImageLoader` sends **no `User-Agent`**, and Scryfall rejects the default
OkHttp one — so **every card image request in the app has always failed**. One line fixes it.

## 2. Context & background

**Found** while verifying story 0055 on-device (art rendered as placeholders everywhere), and
**confirmed independently against the live service**:

```
User-Agent: okhttp/4.12.0        ->  HTTP 400   (Scryfall: generic_user_agent)
User-Agent: mage-free-client/1.0 ->  HTTP 302   (redirect to the image)
```

Scryfall [requires a descriptive `User-Agent`](https://scryfall.com/docs/api) and rejects generic
client defaults. `CardImageLoader` builds its Coil `ImageLoader` with an `OkHttpNetworkFetcherFactory`
and never sets one.

**Scope of the breakage — everything that shows a card image:**
- the card browser and card inspection (`:feature:cards`, 0032)
- the deck builder's add-cards grid and deck rows (`:feature:decks`, 0035)
- the global and **deck-scoped** art pre-downloads (0031/0043) — including the
  "make this deck viewable offline" promise, which cannot have been fulfilling it
- the new game board (0055)

**Why no test caught it.** Every art test uses a fake source, a fake warmer, or asserts on the
*request* rather than the response — correct unit-testing that is structurally blind to a server
rejecting the call. 0043's live-ish work asserted which URLs were warmed, not that bytes arrived.
This is verification standard 5 (*unexpectedly absent*) in a new place: the field was present, the
call was made, and the **response** was never checked.

**Provenance note.** A local one-line patch confirmed the cause during 0055 and was **reverted, not
committed** — 0055 was a rendering story and this is `:core:cards`.

## 3. Scope

**In scope**
- Set a descriptive `User-Agent` on the art `Call.Factory` (an OkHttp interceptor on the client
  `CardImageLoader` builds), identifying the app — e.g. `mage-free-client/<version>` plus a contact or
  project URL, as Scryfall asks.
- **A test that would have caught this**: assert the outgoing request carries a non-generic
  `User-Agent`. A `MockWebServer`-style check that inspects the actual recorded request is the
  cheapest honest version; asserting only that an interceptor exists is not.
- Confirm the fix end-to-end: a real image request returns a redirect/bytes rather than 400.

**Out of scope**
- Any other art behaviour — 0043's size warming, fallback candidates, and failure containment are
  unchanged.
- The emulator's lack of external egress (an environment problem, not a defect).
- Retry/back-off policy for the art service.

## 4. Verification

- **Hermetic (standard 1):** the new header test demonstrated **failing** against today's loader
  (no `User-Agent`), then passing.
- **Live:** with real egress, request a known printing (`Forest` `M21` #272 works) and confirm a
  non-400 response. Then confirm on a device with egress that art actually renders in the card
  browser — the surface that has been silently broken longest.
- Prior `:core:cards` suites stay green (`CardImageLoaderTest`, `XMageImageSourceTest`,
  `ArtDownloadManagerTest`, and 0043's additions).

## 5. Acceptance criteria

- [ ] Art requests carry a descriptive `User-Agent`; Scryfall no longer returns 400.
- [ ] A test inspects the **actual outgoing request** and fails without the header.
- [ ] Card art renders in the card browser on a device with external egress.
- [ ] No change to 0043's size warming, fallback, or failure containment; prior suites green.

## 6. References

- `core/cards/src/main/kotlin/magefree/cards/art/CardImageLoader.kt` — where the `Call.Factory` is built.
- [`0031-card-artwork-loading-and-cache.md`](0031-card-artwork-loading-and-cache.md), [`0043-artwork-pipeline-fixes.md`](0043-artwork-pipeline-fixes.md) — the art pipeline this fixes without otherwise changing.
- `docs/stories/README.md` § Verification standards — standard 5; this is the same class, one layer out.
