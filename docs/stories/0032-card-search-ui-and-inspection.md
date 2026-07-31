# 0032 — Card search UI & inspection view

- **Epic:** EPIC-10 — Card Database, Search & Inspection
- **Depends on:** 0030 (catalog & search), 0031 (artwork loader), 0018 (feature pattern), EPIC-03 (design system)
- **Status:** ready

## 1. Objective

Build the **`:feature:cards`** module: a fast, filterable **card search/browse** screen over 0030's
local catalog, and a first-class **full-bleed card inspection view** (large readable art + oracle
text/rulings) using 0031's on-demand image loader. This is the player-facing card catalog used by the
deck builder (Epic 9) and reachable for standalone browsing. The **in-game** inspection variant
(current modifications, activatable abilities) is gameplay-coupled and **deferred to EPIC-11+** — this
story establishes the reusable static inspection surface.

## 2. Context & background

- 0030 provides `CardCatalog` (local `search`/`filter`/`card(id)`, fully offline). 0031 provides
  `CardImageLoader` (on-demand art + cache, graceful placeholder). This story is the UI over them —
  MVVM with immutable UI state.
- Design system (EPIC-03): `MageTheme`, list/grid rows, `StateViews`, section chrome; the design
  system already has **card-forward components** (0014 `card/CardTile`, `CardDisplay`, `CardInspect`,
  `FullCardView`) — **reuse them**, don't hand-roll card rendering.
- UX (`../ux-principles.md`): readable on a small screen (a tap gives a large, full-detail view),
  responsive search, art loads fast/offline once cached with a graceful placeholder.
- Card **text/search works offline** (0030); art appears as loaded/cached (0031).

## 3. Scope

**In scope** (all in `:feature:cards`, MVVM, immutable `StateFlow<UiState>`):
- **Search/browse screen**: a search field (debounced, over `CardCatalog.search`) + filters (color/
  identity, type, mana value, set, rarity, format legality) + results as tiles/rows (name, mana cost,
  type, set, thumbnail via 0031). Loading / empty ("no matches") / offline-art states via the design
  system; a "showing N" count. Client-side use of the catalog's own search/filter.
- **Full-bleed inspection view**: tap a card → a large, readable detail (full art via 0031 with
  placeholder fallback, name, mana cost, full type line, oracle/rules text, P/T/loyalty, set/rarity),
  built on the design system's card-forward components; DFC/split **face flip/toggle**. Read-only.
- **Cache-policy affordance**: surface 0031's setting (Persistent/SessionOnly) and the opt-in bulk
  pre-download action (progress/cancel) — a lightweight settings entry, not a full settings screen
  (EPIC-17).
- ViewModels exposing `StateFlow<UiState>`; stateless previewable Composables (light + dark);
  ViewModel tests over search/filter/empty/selection via fakes.

**Out of scope**
- The **in-game** inspection (current modifications, activatable abilities, targeting) — **EPIC-11+**.
- Catalog data/search internals (**0030**) and the artwork loader/download mechanism (**0031**).
- Deck-building actions (add/remove to a deck) — **Epic 9** (this screen is browse/inspect; a deck-add
  affordance may be a disabled/"in Epic 9" hook, documented).
- A full settings screen (**EPIC-17**) — only the minimal art cache/download affordance here.

## 4. Design & approach

```
feature/cards/
├── CardSearchViewModel.kt (+ CardSearchUiState, filter model)   # over CardCatalog; debounced search + filters
├── CardSearchScreen.kt + CardTileRow/Grid.kt                    # results (design-system card components + 0031 art)
├── CardInspectionViewModel.kt / CardInspectionScreen.kt         # full-bleed detail; DFC face toggle
└── CardArtSettings.kt (or inline)                               # cache policy + bulk pre-download affordance
```

- Screens are stateless/previewable; ViewModels hold `StateFlow<UiState>` over 0030's `CardCatalog`
  and drive 0031's loader for thumbnails/full art. Reuse the 0014 card-forward components
  (`CardTile`/`FullCardView`/`CardInspect`) for rendering; art requests go through 0031 with the
  design-system placeholder on miss.
- Debounced search on an injected dispatcher; filters applied via the catalog; empty/loading/offline
  states via `StateViews`.
- Reachable for standalone browsing (a nav entry) and consumable by Epic 9's builder.

## 5. Implementation steps

1. Create `:feature:cards` (conventions: `magefree.android.library` + `.compose` + `.hilt`; depends on
   `:core:cards`, `:core:designsystem`); register in `settings.gradle.kts` (Android guard).
2. `CardSearchViewModel` + `CardSearchUiState` over `CardCatalog` (debounced search + filters);
   `CardSearchScreen` + tiles/rows using the design-system card components + 0031 art.
3. `CardInspectionViewModel`/`Screen` — full-bleed detail with DFC/split face toggle; art via 0031 + placeholder.
4. Minimal art cache-policy + bulk-pre-download affordance (surfacing 0031).
5. ViewModel tests (search/filter/empty/selection/offline-art) via fakes; light+dark previews.
6. `:feature:cards:check` + `:app:testDebugUnitTest` green (host); `:app:assembleDebug` builds; nav entry wired minimally.

## 6. Testing & verification

- **Hermetic gate:** ViewModel tests over `CardCatalog`→UiState (search debounce logic, filters,
  empty, selection→inspection) with a fake catalog + fake image loader; Compose previews compile.
- **Live (opt-in):** real search returns cards; a card's art loads/caches; DFC flip works; the
  placeholder shows offline.

## 7. Acceptance criteria

- [ ] A search/browse screen queries the local `CardCatalog` (debounced) with color/type/mana/set/
      rarity/legality filters and shows results (name/mana/type/set + thumbnail), on the design system.
- [ ] Tapping a card opens a **full-bleed, readable inspection view** (large art via 0031 with
      placeholder fallback, full oracle text/type/P-T/set), reusing the 0014 card components; DFC/split
      faces can be toggled.
- [ ] The art **cache policy** + opt-in **bulk pre-download** are surfaced (minimal affordance).
- [ ] Text/search work **offline**; uncached art degrades to the placeholder (no crash/blank).
- [ ] ViewModel tests + previews cover search/filter/empty/selection/offline-art; `:feature:cards:check`
      + `:app:testDebugUnitTest` + `:app:assembleDebug` green; prior suites green.
- [ ] No in-game inspection, no deck-add action (Epic 9), no full settings screen (EPIC-17).

## 8. References

- [`0030-card-catalog-data-and-local-search.md`](0030-card-catalog-data-and-local-search.md) — `CardCatalog`.
- [`0031-card-artwork-loading-and-cache.md`](0031-card-artwork-loading-and-cache.md) — `CardImageLoader` + cache policy.
- [`0014-card-forward-components.md`](0014-card-forward-components.md) — the design-system card components to reuse.
- [`0018-connect-and-sign-in-ui.md`](0018-connect-and-sign-in-ui.md) — the feature-module + MVVM pattern to mirror.
- [`../ux-principles.md`](../ux-principles.md) — readable on a small screen; responsive; graceful offline art.
