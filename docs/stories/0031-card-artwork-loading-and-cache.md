# 0031 — Card artwork loading & cache

- **Epic:** EPIC-10 — Card Database, Search & Inspection
- **Depends on:** 0030 (card catalog / card identity), EPIC-03 (design system, for the placeholder)
- **Status:** ready

## 1. Objective

Load and cache **card artwork** — the one thing that can't be bundled. The primary mechanism is an
**on-demand image loader with a configurable cache**: art loads lazily the first time a card is
viewed and is cached for next time; a **user-initiated bulk pre-download** simply warms the same
cache for full offline art. Card **text/search always works offline** (0030's bundled data) — only
art touches the network, degrading to a graceful placeholder when uncached and offline.

## 2. Context & background

- **Decision (2026-07-31, Pete):** artwork is not bundled. Reuse XMage's **image-source resolution**
  (how a card's identity → an image URL from its configured sources), but deliver it the mobile way:
  **on-demand with a cache**, not desktop-style bulk-download-only. The three behaviors layer into one
  system (Pete):
  1. **On-demand + persistent disk cache** — the **default**: fetch on first view, keep on disk, so
     it's instant next time and offline for anything already seen; you only fetch what you look at.
  2. **On-demand, memory-only (no persist)** — a **setting** for storage/privacy-conscious users:
     fetch per view into an LRU memory cache, never write disk.
  3. **Bulk pre-download (user-initiated)** — an option that **pre-warms the same disk cache** for full
     offline art (travel/flaky data). Not a separate pipeline.
- **What we reuse from XMage:** the *source/URL logic* — resolving a card (set code + collector
  number, DFC/split faces, various-art) to an image URL from XMage's configured image sources (e.g.
  Scryfall/mtgpics as XMage uses). We do **not** reuse its bulk-download UX. XMage's image-download
  code lives in the desktop client/plugins (`org.mage.plugins.card.dl.*`, not on the bridge classpath),
  so the implementer studies the upstream source (`../mage`) for the URL construction and ports it.
- Card identity comes from 0030's catalog (`CardId`/set/number/faces). No bridge involvement — the app
  fetches images directly from the sources and caches them.

## 3. Scope

**In scope**
- A **`CardImageLoader`** (in `:core:cards` or a focused `:core:cardart`): given a card identity,
  returns/loads the artwork, honoring the active **cache policy**. Backed by a standard Android image
  pipeline (**Coil** recommended — Compose-native, disk+memory cache, request de-dup) with a custom
  keyer/fetcher that resolves the XMage image URL from the card identity.
- **Cache policy** setting: `Persistent` (disk, default) / `SessionOnly` (memory-only) / (effectively
  `Off` = always fetch). Persisted via DataStore; changing to a smaller policy can clear the disk cache.
- **XMage image-source resolution**: port the identity→URL logic (set/number/face/various-art) from the
  upstream source; support DFC/split faces; a sensible source order + fallback.
- **User-initiated bulk pre-download**: an opt-in action that warms the disk cache (all cards, or a
  chosen set/format) with progress, cancellable, resumable, respecting the disk-cache policy. Off by
  default; never automatic.
- **Graceful degradation**: an uncached image with no network shows the design-system placeholder
  (never a crash/blank); the card's text is always available (0030).
- Tests: URL resolution (incl. DFC/split), the loader honoring each cache policy (with a fake
  fetcher/HTTP), and the bulk-prefetch progress/cancel state machine.

**Out of scope**
- The catalog data + search (**0030**) and the search/inspection **UI** (**0032**) — this story is the
  loader/cache/download *mechanism*, consumed by 0032.
- In-game card rendering / modified art (Epic 11+).
- Shipping any bundled artwork.

## 4. Design & approach

```
core/cards/ (or core/cardart/)
├── CardImageLoader.kt         # identity -> image (Coil-backed); honors CachePolicy
├── XMageImageSource.kt        # card identity -> source URL(s) (ported from upstream); DFC/split/faces
├── CardArtCachePolicy.kt      # Persistent / SessionOnly / (Off); DataStore-backed setting
├── ArtDownloadManager.kt      # user-initiated bulk pre-warm: progress/cancel/resume
└── di/
```

- **Loader:** wrap Coil (or Glide) so callers pass a `CardId` (or a small `CardArtRequest`), not a URL;
  a custom mapper/fetcher resolves the URL via `XMageImageSource` and Coil handles memory+disk cache,
  de-dup, and cancellation. The `CachePolicy` toggles Coil's disk cache on/off (`SessionOnly` = memory
  only).
- **Bulk pre-download:** iterate the 0030 catalog (or a filtered subset), enqueue loads that write the
  disk cache, expose a `StateFlow` progress (done/total, current, error), cancellable; resume by
  skipping already-cached entries. Bounded concurrency; polite to the source.
- **Offline/placeholder:** the loader returns a placeholder state on a miss-with-no-network; 0032
  renders the design-system placeholder.

## 5. Implementation steps

1. Port `XMageImageSource` from the upstream download source (identity→URL, DFC/split/various-art);
   unit-test the URL construction against known cards.
2. Add the Coil-backed `CardImageLoader` with a `CardId` mapper/fetcher; wire memory+disk cache.
3. Add `CardArtCachePolicy` (DataStore) and make the loader honor it (`SessionOnly` disables disk).
4. Implement `ArtDownloadManager` (bulk pre-warm: progress/cancel/resume, bounded concurrency).
5. Tests: URL resolution, policy behavior (persistent vs session-only via a fake fetcher), prefetch
   state machine; catalog available for previews.
6. `:core:cards:check` (or `:core:cardart:check`) + `:app:testDebugUnitTest` green; `:app:assembleDebug` builds.

## 6. Testing & verification

- **Hermetic gate:** URL-resolution tests (incl. DFC/split), loader cache-policy tests with a fake
  fetcher (no real network), and the prefetch progress/cancel/resume tests. No live image fetch in the gate.
- **Live (opt-in/manual):** viewing a real card fetches + caches its art; toggling to `SessionOnly`
  stops disk writes; bulk pre-download warms the cache with progress.

## 7. Acceptance criteria

- [ ] `CardImageLoader` loads a card's art **on demand** from XMage's image sources (identity→URL
      ported from upstream, DFC/split faces handled), caching per the active policy.
- [ ] Cache policy is user-settable — **Persistent (disk, default)** / **SessionOnly (memory-only)** —
      persisted; a downgrade can clear disk.
- [ ] A **user-initiated bulk pre-download** warms the disk cache with cancellable/resumable progress;
      it is opt-in and never automatic.
- [ ] An uncached image offline degrades to the design-system **placeholder** (no crash/blank); card
      **text is always available** (0030).
- [ ] Tests cover URL resolution (incl. DFC/split), each cache policy, and the prefetch state machine;
      module `:check` + `:app:testDebugUnitTest` + `:app:assembleDebug` green; prior suites green.
- [ ] No catalog/search changes (0030), no search/inspection UI (0032), no bundled artwork.

## 8. References

- `../mage/Mage.Client/.../org/mage/plugins/card/dl/**` — XMage's image-download/source logic to port (identity→URL, sources, DFC).
- [`0030-card-catalog-data-and-local-search.md`](0030-card-catalog-data-and-local-search.md) — card identity + catalog.
- [`../architecture.md`](../architecture.md) — "card images … on-device cache + CDN, not bundling."
- [`../ux-principles.md`](../ux-principles.md) — responsive on mobile data; graceful offline.
