# 0030 — Card catalog data & local search

- **Epic:** EPIC-10 — Card Database, Search & Inspection
- **Depends on:** 0020–0022 (container/upstream build), 0004-era module conventions
- **Status:** ready

## 1. Objective

Ship XMage's **authoritative** card catalog **bundled on-device** and provide fast, fully-offline
**search/filter/lookup** over it. This is the data foundation for the card browser (0032), the deck
builder (Epic 9), and gameplay (Epic 11+). Card **artwork** is out of scope here (it is *not*
bundled — 0031 loads it on demand); everything else about a card **is** bundled.

## 2. Context & background

- **Decision (2026-07-31, Pete):** the card *data* is XMage's own (not an external source like
  Scryfall) and is **fine to bundle** on-device; only the **artwork** cannot be bundled. Using
  XMage's catalog keeps decks valid for `SessionImpl.submitDeck(DeckCardLists)` (the server validates
  against its own card pool), and keeps legality/formats consistent with what is actually playable.
- **The data is extractable from the baked XMage jars** (verified via `javap` on `mage-common`/`mage`
  `1.4.60`): `mage.cards.repository.CardRepository` + `mage.cards.repository.CardInfo` expose the full
  catalog — `getName`, `getManaValue`, `getManaCost`/color, `getTypes`/`getSubtypes`, `getRules`,
  `getPower`/`getToughness`/`getStartingLoyalty`, `getSetCode`, `getCardNumber`, and split/flip/DFC/
  meld metadata. XMage builds this DB during its **full upstream build** (the `xmage-server` image,
  story 0022, already runs the full reactor incl. the card sets).
- **Generation, not hand-authoring.** The bundled dataset is *generated* from XMage's card DB by a
  reproducible, cached build step (in the container, which has the jars/DB), then transformed into a
  compact on-device format. It must be regenerable at the pinned XMage version so it stays in lockstep
  with the server.
- The catalog is **static per XMage version** and read-only on device, so search is a **local** query
  (no bridge, no network) — text/search works fully offline; only 0031's artwork needs the network.

## 3. Scope

**In scope**
- A **generator** (container build step / Gradle task using the XMage jars + card DB) that emits a
  compact bundled dataset of the catalog — every card printing with the browse/build-relevant fields
  (identity: set code + collector number + name; mana cost + mana value + colors; super/card/subtypes;
  rules text; P/T/loyalty; rarity; face metadata for DFC/split/flip/meld; and the format-legality
  inputs). Choose a bundle format suited to on-device search (a **pre-built SQLite DB shipped as an
  asset** is the natural fit; justify the choice). Document the regeneration command + pinned version.
- A **`:core:cards`** module: the card domain model (a `Card`/`CardPrinting` type + `CardId`,
  `ManaCost`/colors, type line, faces, legality) and a **`CardCatalog`** repository that loads the
  bundled dataset and offers `search(query)` (name substring/prefix, ranked), `filter(...)` (by
  color/identity, type, set, mana value, rarity, format legality), and `card(id)` lookup — all local,
  reactive where useful, on injected dispatchers.
- Tests: catalog load, representative searches/filters, DFC/split lookups, and a **coverage sanity
  check** (the bundle has the expected order-of-magnitude card count and known sample cards resolve).

**Out of scope**
- Card **artwork** (loading/caching/download) — **0031** (not bundled).
- The search/inspection **UI** — **0032**.
- Deck building / legality *enforcement* UX — **Epic 9** (this story exposes legality data + can reuse
  XMage's `DeckValidator`/`DeckFormats` for a format's legality of a single card, but builds no deck UI).
- Rulings text beyond what XMage's card data carries; oracle-text reconciliation with external sources.

## 4. Design & approach

```
build/ (container step)  — generate the bundled dataset from XMage's CardRepository (pinned version)
core/cards/
├── model/            # Card / CardPrinting / CardId / ManaCost / TypeLine / Legality / face metadata
├── CardCatalog.kt    # interface: search / filter / card(id); backed by the bundled dataset
├── (impl)            # loads the bundled SQLite asset; queries locally (indexed by name/set/type)
└── di/               # provides CardCatalog (@Singleton)
```

- **Generation:** a documented, reproducible step in the container extracts `CardInfo` records from
  `CardRepository` (at the pinned XMage ref) and writes the compact bundle (e.g. a normalized SQLite
  DB with indexes on name/set/type/mana). Keep it deterministic and versioned. (Whether the bundle is
  committed as an asset or produced by a build task is the implementer's call — document it; ~30–40k
  text rows is a few MB, acceptable to bundle.)
- **`CardCatalog`:** opens the bundled DB read-only; search is an indexed local query; results are
  `:core:cards` model types (no XMage `mage.*` types escape the module's generation boundary — the
  on-device model is app-schema, like the mapper boundary elsewhere).
- **Offline-first:** all catalog queries succeed with no network/bridge/connection.

## 5. Implementation steps

1. Prototype the generator in-container: read `CardRepository`/`CardInfo`, confirm field coverage, emit
   the bundle; document the regeneration command + pinned XMage ref.
2. Define the `:core:cards` model + `CardCatalog` interface; register the module in `settings.gradle.kts`.
3. Implement the catalog over the bundled dataset (indexed search/filter/lookup) on injected dispatchers.
4. Bundle the dataset as an app-consumable asset; wire DI.
5. Tests: load + count sanity, name search ranking, filters (color/type/mana/set/legality), DFC/split
   lookup; a few known-card assertions.
6. `:core:cards:check` green (host); `:app:assembleDebug` builds with the bundled asset.

## 6. Testing & verification

- **Hermetic gate:** unit tests over `CardCatalog` against the bundled dataset (or a representative
  fixture subset) — search/filter/lookup + a coverage/count sanity check; no network.
- **Regeneration check (documented):** the generator reproduces the bundle from the pinned XMage
  version; note the command and the resulting card count.

## 7. Acceptance criteria

- [ ] A bundled, on-device XMage card catalog exists, **generated reproducibly** from the pinned XMage
      version (regeneration command documented); it carries the browse/build fields (identity, mana,
      types, rules, P/T, set/number, faces, legality inputs) — **no artwork**.
- [ ] `:core:cards` exposes a local `CardCatalog` with `search`/`filter`/`card(id)` that works fully
      **offline** (no bridge/network), returning app-schema `:core:cards` types (no `mage.*` on device).
- [ ] Tests cover search ranking, the filter axes, DFC/split lookup, and a coverage/count sanity check;
      `:core:cards:check` + `:app:assembleDebug` green; prior suites green.
- [ ] No artwork, no search/inspection UI, no deck-builder UI here.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/cards/repository/{CardRepository,CardInfo}.java` — the catalog source.
- `../mage/Mage.Common/src/main/java/mage/cards/decks/{DeckValidator,DeckFormats,Constructed}.java` — legality (used by Epic 9; legality *data* surfaced here).
- [`../architecture.md`](../architecture.md) — card data/caching; XMage as the source of truth.
- [`../build-environment.md`](../build-environment.md) — the container/upstream build the generator runs in.
