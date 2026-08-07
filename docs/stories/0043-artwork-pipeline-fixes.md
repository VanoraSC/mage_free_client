# 0043 — Artwork pipeline fixes

- **Epic:** EPIC-10 (defect fixes from the 2026-08-07 audit)
- **Depends on:** 0031 (art loading/cache + `ArtDownloadManager`), 0032 (search UI), 0035 (deck-scoped download)
- **Status:** ready

## 1. Objective

Fix three verified defects in the artwork pipeline: the bulk pre-download **warms the wrong image
size**, so "download all art" leaves the browse grid blank offline; the documented **fallback URL is
never used**, so promo/variation printings that 404 render a placeholder forever; and a failure inside
the prefetch worker **crashes the app** and leaves progress stuck.

## 2. Context & background

All three verified directly against the merged code.

- **A. Prefetch warms LARGE; the grid requests SMALL.** Both prefetch entry points hard-code
  `CardArtSize.LARGE` (`feature/cards/.../CardArtSettings.kt:56`,
  `feature/decks/.../art/DeckArtDownloader.kt:57`), but the browse/add grid maps rows with
  `Card.toCardRow(size: CardArtSize = CardArtSize.SMALL)` (`CardBrowseModels.kt:44`) and
  `CardSearchViewModel.resolve` uses the bare method reference — so **SMALL**. The Coil cache key is
  the resolved URL (`CardImageLoader`), and `applySize` appends `version=small` for SMALL
  (`XMageImageSource.kt`), so SMALL and LARGE are **different cache entries**.
  **Failure:** user taps "Download all art", waits for it to finish (32k+ images), goes offline, opens
  card search — **every thumbnail is a placeholder**, because nothing warmed the `version=small` URLs.
  Inspection (LARGE) and builder rows (LARGE) do work. The same applies to the deck-scoped
  "make this deck viewable offline" download, which is the whole point of that feature.
- **B. The fallback URL is dead code.** `XMageImageSource.resolve` returns two candidates and its
  KDoc says *"Callers (the Coil fetcher) try them in order and fall back on a miss."* **No caller
  does**: the request mapper and the cache key both use `primaryUrl(...)` = `resolve(...).first()`.
  The `include_variations=true` alternative — justified in the class doc as upstream's documented
  workaround for promos/variations that 404 on the localized path — is exercised only by its unit test.
  **Failure:** a promo/variation printing whose localized path 404s renders a placeholder forever,
  online or offline, while `XMageImageSourceTest` stays green.
- **C. A prefetch failure crashes the app.** `ArtDownloadManager.run`'s `coroutineScope { … }` is
  wrapped only in `catch (cancellation: CancellationException)`. `warmer.isCached` calls
  `diskCache?.openSnapshot(key)`, which can throw `IOException` on a corrupt/unreadable entry; that
  cancels siblings, propagates out of `run`, and lands in an `appScope.launch` with a `SupervisorJob`
  but **no `CoroutineExceptionHandler`** → uncaught → crash. `_progress` is left at `RUNNING`, so the
  UI shows a frozen progress bar until process restart.
  **Related (same file):** targets are launched one coroutine each *before* acquiring the semaphore
  permit — for `PrefetchScope.All` that is ~32k simultaneously-suspended coroutines. It works, but a
  channel/chunked producer is far cheaper.

## 3. Scope

**In scope**
- **A** — make the pre-download warm the sizes that are actually displayed. Warm **both** SMALL and
  LARGE per target (preferred: the grid and inspection both become offline-capable), or make the grid
  request LARGE and let Coil downscale — pick one, justify it, and apply it to **both** the global and
  the deck-scoped download. Progress accounting must reflect the real target count.
- **B** — either implement the fallback (the fetcher walks `resolve()` and tries the next candidate on
  a 404/miss) **or** delete the unused candidate and correct the KDoc. Prefer implementing it: the
  class doc's own rationale says promos/variations need it. If implemented, the cache key must stay
  stable (key on the request, not on whichever candidate won).
- **C** — a per-target failure must be **counted and skipped**, not fatal: add a non-cancellation
  `catch` that publishes a terminal failed/partial status, and make `isCached`/`warm` swallow
  per-target errors into a `failed` count. Progress must never be left stuck at `RUNNING`.
  Optionally address the coroutine-per-target launch pattern.
- Tests: a fake warmer/source proving the prefetch warms every displayed size; a 404-then-fallback
  test (if implemented) or the corrected doc; an error-injection test proving one bad target does not
  abort the run, does not escape the scope, and lands the run in a terminal state with a failed count.

**Out of scope**
- The cache-policy tiers themselves (0031's persistent/session-only behavior) beyond what these fixes
  touch; any change to `cards.sqlite` or the generators; new art sources.
- Deck/catalog defects — **0042**; hygiene — **0044**.

## 4. Design & approach

- **A** is a correctness bug in the *promise* of the feature ("make this viewable offline"), so
  prefer warming everything the UI displays over quietly narrowing the promise.
- **C**: bulk work over 32k network targets must treat per-item failure as normal, not exceptional.
  Terminal states must be reachable so the UI can stop showing progress.

## 5. Implementation steps

1. Warm all displayed sizes in both prefetch paths; fix progress totals (A).
2. Implement the resolve-fallback in the fetcher with a stable cache key, or remove it and fix the
   KDoc (B).
3. Per-target error containment + terminal failed/partial status (C).
4. `:core:cards:check`, `:feature:cards:check`, `:feature:decks:check`, `:app:assembleDebug` green.

## 6. Testing & verification

- **Hermetic:** fakes only — no network. Assert the set of warmed requests covers every size the UI
  requests; assert one throwing target yields a completed-with-failures run (not a crash, not a stuck
  `RUNNING`); fallback behavior asserted or removed with the doc corrected.
- Manual (optional): download art, go offline, confirm the browse grid renders.

## 7. Acceptance criteria

- [ ] After a completed art download (global **and** deck-scoped), the **browse/add grid renders
      offline** — the prefetch warms every size the UI actually requests.
- [ ] The `resolve()` fallback is either genuinely used on a primary miss (with a stable cache key) or
      removed, with the KDoc matching reality — no documented-but-dead code path.
- [ ] A failing target is counted and skipped: the run reaches a terminal state with a failure count,
      nothing escapes the app scope, and progress is never stuck at `RUNNING`.
- [ ] All prior suites green (`ArtDownloadManagerTest`, `XMageImageSourceTest`, `CardImageLoader` tests).

## 8. References

- The 2026-08-07 audit (findings A/#5, B/#6, C/#9), each verified against the merged code.
- [`0031-card-artwork-loading-and-cache.md`](0031-card-artwork-loading-and-cache.md), [`0032-card-search-ui-and-inspection.md`](0032-card-search-ui-and-inspection.md), [`0035-deck-library-and-builder-ui.md`](0035-deck-library-and-builder-ui.md).
