# 0033 — Deck model, storage & legality data

- **Epic:** EPIC-09 — Deck Management & Building
- **Depends on:** 0030 (card catalog / `CardId`), 0020–0022 (generator infra)
- **Status:** ready

## 1. Objective

The offline data foundation for decks: a deck domain model (main + sideboard) that round-trips
XMage's `DeckCardLists`, a **local** deck library repository, and **bundled** format-legality data
+ an offline `DeckLegality` checker. **Everything here is fully offline** — no network is ever
required to create, view, edit, or validate a deck (per Pete: the only thing that touches the
network anywhere in the deck experience is *artwork*, 0031). UI is 0035; import/export is 0034.

## 2. Context & background

- **Offline-first is a hard requirement (Pete, 2026-08-01):** all deck manipulation, viewing,
  construction, sideboard, and legality must work with **no network connection**. Decks are the
  user's data, stored on-device; the card catalog (0030) and legality data (this story) are bundled;
  only art (0031) is fetched.
- **XMage deck contract (baked jars, `javap`-confirmed):** `mage.cards.decks.DeckCardLists` = `name`,
  `author`, `cards: List<DeckCardInfo>`, `sideboard: List<DeckCardInfo>` (+ layouts). `DeckCardInfo` =
  `cardName`, `setCode`, `cardNumber`, `amount` (+ `cardKey`). This is the shape `submitDeck` /
  import / export use, so our model must round-trip it losslessly (identity by set + collector number,
  matching 0030's `CardPrinting`).
- **Legality:** `Constructed.validate(Deck)` + `getSetCodes()` exist, but the **format definitions**
  (per-format legal set lists + banned/restricted names, e.g. Standard/Modern/Legacy/Pioneer/etc.)
  live in XMage's format classes (`mage.deck.*`), which — like `Mage.Sets` — are **not** in the light
  jars but ARE in the full build. So we **bundle** them, generated from XMage exactly as 0030 bundles
  the catalog (pinned version, reproducible). Bundling gives **offline** legality feedback while
  building, not just a server rejection at submit-time.

## 3. Scope

**In scope**
- **`:core:decks` deck model** (pure, app-schema): `Deck` (id, name, author?, format?, main +
  sideboard as ordered `DeckEntry{ printing/CardId, quantity }`, favorite flag, timestamps) and
  lossless mappers **`Deck` ↔ `DeckCardLists`-equivalent** (`DeckCardInfo` fields: name/set/number/
  amount) so 0034 (import/export) and Epic 7 (submit) share one representation. No `mage.*` on device.
- **`DeckRepository`** — a **local**, offline deck library: list/observe, create, duplicate, rename,
  delete, favorite, load/save. Persist on-device (Room over SQLite recommended for a queryable
  library; justify the choice). No network. Injected dispatchers; errors as results/state.
- **Bundled legality data**: extend the 0030 generator (or a sibling step) to emit the format
  definitions — for each supported format: legal set codes, banned/restricted card names, and the
  constructed constraints (min deck size, max copies, sideboard size). Reproducible + documented
  (pinned XMage ref). A small bundled asset (JSON/SQLite table).
- **`DeckLegality`** — an **offline** checker: given a `Deck` + a format, report legality (legal /
  the specific violations: illegal sets/cards, banned/restricted, deck-size/copy-count issues) as
  structured results the builder (0035) renders live. Uses only the bundled data + `:core:cards`.
- Tests: model↔`DeckCardLists` round-trip, repository CRUD/favorite/persistence, and legality over
  representative decks (legal, a banned card, an illegal set, too-few-cards, >4 copies) per format.

**Out of scope**
- The library/builder **UI** (**0035**) and file **import/export** parsers (**0034**).
- Submitting a deck to a table (**EPIC-07** — reuses the `DeckCardLists`-equivalent from here).
- Artwork (**0031**); deck *sharing* transport beyond the export file (0034).

## 4. Design & approach

```
tools/… (generator)   — extend 0030's generator to also emit format-legality data (pinned version)
core/decks/
├── model/            # Deck, DeckEntry, DeckId, DeckFormat + Deck <-> DeckCardLists-equivalent mappers
├── DeckRepository.kt # local library: CRUD/favorite/observe (Room-backed); offline
├── legality/         # bundled format data loader + DeckLegality checker (offline)
└── di/
```

- **Model** is app-schema; a `DeckCardLists`-equivalent DTO (name/author/main/sideboard of
  name+set+number+amount) is the interchange type 0034/Epic 7 consume. Entries reference 0030
  printings (set + collector number) so the builder and submit agree on identity.
- **Repository**: Room deck/entry tables (or a documented alternative); reactive `Flow` of the
  library; all ops offline on injected dispatchers.
- **Legality**: load the bundled format table; validate structurally (size/copies/sideboard) +
  set/ban/restrict against the deck's printings and `:core:cards` — fully local.

## 5. Implementation steps

1. Extend the generator to emit the format-legality bundle (formats → legal sets + bans + constraints);
   document regeneration + card/format counts.
2. `:core:decks` model + `Deck` ↔ `DeckCardLists`-equivalent mappers; round-trip tests.
3. `DeckRepository` (Room-backed local library: CRUD/favorite/observe); persistence tests.
4. `DeckLegality` over the bundled data + `:core:cards`; legality tests per format/violation.
5. `:core:decks:check` + `:app:assembleDebug` green (host); generator reproducible.

## 6. Testing & verification

- **Hermetic gate (all offline):** model round-trip; repository CRUD/favorite/persistence (Robolectric
  SQLite, no device); legality over crafted decks (legal + each violation kind) per representative
  formats. No network anywhere.
- **Regeneration check:** the legality bundle reproduces from the pinned XMage version (documented).

## 7. Acceptance criteria

- [ ] `Deck`/`DeckEntry` model round-trips a `DeckCardLists`-equivalent losslessly (name/set/number/
      amount, main + sideboard); no `mage.*` on device.
- [ ] `DeckRepository` is a fully **offline** local library: create/duplicate/rename/delete/favorite/
      list/load/save, reactive, persisted; no network.
- [ ] Format-legality data is **bundled** (generated reproducibly from the pinned XMage version);
      `DeckLegality` validates a deck against a format **offline**, reporting structured violations.
- [ ] Tests cover round-trip, repository CRUD/persistence, and legality (legal + banned + illegal-set +
      size/copy) per format; `:core:decks:check` + `:app:assembleDebug` green; prior suites green.
- [ ] No UI, no import/export parsers, no submit, no artwork here. Nothing requires the network.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/cards/decks/{DeckCardLists,DeckCardInfo,Constructed,DeckValidator}.java` — the deck contract + validation.
- `../mage/Mage.Common/.../mage/deck/*` (in the full build) — the format definitions to bundle.
- [`0030-card-catalog-data-and-local-search.md`](0030-card-catalog-data-and-local-search.md) — the catalog + the generator this extends.
- [`../architecture.md`](../architecture.md) — bundle XMage data; offline-first.
