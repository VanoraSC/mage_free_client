# 0035 — Deck library & builder UI

- **Epic:** EPIC-09 — Deck Management & Building
- **Depends on:** 0033 (model/storage/legality), 0034 (import/export), 0032 (card search/art), 0018 (feature pattern), EPIC-03 (design system)
- **Status:** ready

## 1. Objective

Build the **`:feature:decks`** module: a deck **library** (list/create/duplicate/rename/delete/
favorite) and a touch-first **builder** (search the catalog → add/remove/sideboard, mana-curve +
live legality feedback), plus import/export/share. **Every deck operation works with no network
connection** (per Pete) — the only networked thing is **artwork**, and its **download is initiated
from the builder** per the chosen cache policy, scoped to the deck's own cards.

## 2. Context & background

- **Offline-first (hard requirement, Pete 2026-08-01):** viewing, creating, editing, sideboarding,
  legality — all offline (0033 storage + bundled catalog/legality). Art is the sole network use.
- 0033 gives the `Deck` model, `DeckRepository`, and offline `DeckLegality`; 0034 gives `DeckIO`
  (import/export); 0032/0030/0031 give card search, tiles/inspection, and the on-demand art loader +
  `ArtDownloadManager` + `CardArtCachePolicy`.
- Design system (EPIC-03): reuse 0014 card components (`CardTile`/`CardDisplay`/`FullCardView`),
  `StateViews`, list/section chrome, `DecisionPrompt`; UX (`../ux-principles.md`): fast add/remove on
  a phone, legible mana curve + legality at a glance.
- **Art download from the builder (Pete):** the builder surfaces the 0031 cache-policy setting AND an
  action to **download art for this deck's cards** (a deck-scoped prefetch), respecting the policy —
  so a player can make a deck fully viewable offline before leaving connectivity.

## 3. Scope

**In scope** (all in `:feature:decks`, MVVM, immutable `StateFlow<UiState>`):
- **Library**: list saved decks (name, format, favorite, card count, colors) with create / duplicate /
  rename / delete / favorite / import; empty state; all over `DeckRepository` — **offline**.
- **Builder**: for a deck, a **card search/add** surface (reuse 0032's search over the 0030 catalog) →
  add/remove to main or **sideboard** with fast quantity controls; the current deck list grouped
  (type/curve); a **mana-curve** view; **live legality feedback** (format picker → `DeckLegality`
  violations shown inline); tap a card → 0032 inspection. All offline.
- **Import/export/share**: import a deck file (0034) into the library; export/share the current deck
  (0034 content → the platform share sheet).
- **Deck-scoped art download**: a builder action "download art for this deck" that prefetches this
  deck's card printings' art via 0031's `ArtDownloadManager` (a deck-scoped target set) honoring the
  active `CardArtCachePolicy`; plus surface the policy toggle. Progress/cancel. Opt-in.
- ViewModels exposing `StateFlow<UiState>`; stateless previewable Composables (light + dark);
  ViewModel tests over library CRUD, add/remove/sideboard, curve, legality, and the deck-scoped
  prefetch — via fakes (fake `DeckRepository`/`CardCatalog`/`DeckLegality`/art manager).

**Out of scope**
- Submitting a deck to a table / playing (**EPIC-07** — consumes 0033's model).
- New import/export formats or storage/legality logic (**0033/0034**).
- Advanced deck stats beyond mana curve + counts; collaborative/cloud sharing.
- The card artwork *mechanism* (**0031** — reused here) and in-game inspection (**EPIC-11+**).

## 4. Design & approach

```
feature/decks/
├── library/    LibraryViewModel + LibraryScreen (+ create/rename/delete/import dialogs)
├── builder/    BuilderViewModel + BuilderScreen (search/add, deck list, sideboard, curve, legality)
├── art/        deck-scoped ArtDownloadManager target + the download affordance (reuses 0031)
└── di/
```

- Screens stateless/previewable; ViewModels over 0033/0034/0032 fakes. Reuse 0032's search
  Composables + 0014 card components; the mana-curve + legality panels are design-system-styled.
- **Deck-scoped prefetch**: provide 0031's `ArtDownloadManager` a target source enumerating **this
  deck's** printings (front + DFC backs); the same policy/progress/cancel model as the global one.
- All data flows are offline; only the art loader/prefetch reaches the network.

## 5. Implementation steps

1. Create `:feature:decks` (conventions; deps `:core:decks`, `:core:cards`, `:core:designsystem`);
   register in `settings.gradle.kts` (Android guard).
2. Library (VM + screen) over `DeckRepository`: CRUD/favorite/import; offline.
3. Builder (VM + screen): search/add (reuse 0032), main/sideboard quantity edits, grouping, mana
   curve, format picker + live `DeckLegality` feedback; card tap → 0032 inspection.
4. Import/export/share via 0034; deck-scoped art download + policy affordance via 0031.
5. Wire the shell **Decks** entry to the library (minimal nav; keep shell intact).
6. ViewModel tests (library CRUD, add/remove/sideboard, curve, legality, deck-scoped prefetch) via
   fakes; light+dark previews. `:feature:decks:check` + `:app:testDebugUnitTest` + `:app:assembleDebug` green.

## 6. Testing & verification

- **Hermetic gate (offline):** ViewModel tests over fakes — library CRUD/favorite/import; builder
  add/remove/sideboard/quantity, mana-curve computation, legality feedback per format, and the
  deck-scoped prefetch enqueue — no network, no device. Compose previews compile.
- **Live (opt-in):** build a deck fully offline; the deck-scoped art download fetches this deck's art
  per the policy; export/import round-trips through the share sheet.

## 7. Acceptance criteria

- [ ] A deck **library** (list/create/duplicate/rename/delete/favorite/import) and a touch-first
      **builder** (search→add/remove, **sideboard**, grouping, **mana curve**, **live legality
      feedback** per format) — **all fully offline**, on the design system (reusing 0014/0032 components).
- [ ] Import/export/share work via 0034 (into the library / out to the share sheet).
- [ ] The builder offers a **deck-scoped art download** (prefetch this deck's cards' art via 0031,
      honoring the chosen `CardArtCachePolicy`, with progress/cancel) + the policy toggle.
- [ ] No deck operation requires the network — only art fetch/prefetch does.
- [ ] Reachable from the shell **Decks** entry; shell/0008–0010 behavior unchanged (their tests green).
- [ ] ViewModel tests + previews cover library/builder/legality/curve/prefetch; `:feature:decks:check`
      + `:app:testDebugUnitTest` + `:app:assembleDebug` green; prior suites green.

## 8. References

- [`0033-deck-model-storage-and-legality.md`](0033-deck-model-storage-and-legality.md) — model / repository / legality.
- [`0034-deck-import-export.md`](0034-deck-import-export.md) — `DeckIO`.
- [`0032-card-search-ui-and-inspection.md`](0032-card-search-ui-and-inspection.md) — the search/inspection to reuse; [`0031-…`](0031-card-artwork-loading-and-cache.md) — the art loader + `ArtDownloadManager`/policy.
- [`../ux-principles.md`](../ux-principles.md) — touch-first deckbuilding; offline.
