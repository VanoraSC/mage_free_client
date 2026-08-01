# 0034 — Deck import & export

- **Epic:** EPIC-09 — Deck Management & Building
- **Depends on:** 0033 (deck model + repository), 0030 (catalog, for name→printing resolution)
- **Status:** ready

## 1. Objective

Move decks **between clients** in XMage-compatible formats: parse imported deck files into the 0033
model and write decks back out, plus share/export a deck as a file/text. **Fully offline** — parsing
and writing are pure/local; only artwork (0031) ever touches the network. UI wiring is 0035.

## 2. Context & background

- XMage ships a family of importers/exporters (`javap`/jar-confirmed: `DckDeckImporter` — XMage's
  native `.dck`; `TxtDeckImporter` / `DecDeckImporter` — plain-text/`.dec`; `MtgaImporter` — MTGA
  export; `XmlDeckImporter`, `CodDeckImporter`; and exporters), all producing/consuming
  `DeckCardLists`. Our 0033 model already round-trips a `DeckCardLists`-equivalent, so import/export
  is: **file text ↔ `DeckCardLists`-equivalent ↔ 0033 `Deck`**.
- These parsers are simple text formats; we **port** the parse/serialize logic (the importers live in
  the JVM jars, not on Android) — study the upstream source (`../mage`) for each format's grammar and
  reproduce it faithfully. Prefer the common, high-value formats first.
- Name/set resolution uses 0030's catalog (a `.txt` line like `4 Lightning Bolt` resolves to a
  printing; a `.dck` line carries set+number). Unresolvable lines are reported, not silently dropped.

## 3. Scope

**In scope**
- **Importers** (text → 0033 `Deck`) for the high-value XMage formats — at minimum **`.dck`** (XMage
  native, carries set+number) and **plain-text/`.dec`** (`[SB:] N Card Name`), resolving names via
  0030's catalog; ideally MTGA export too. Each: parse main + sideboard, resolve entries, and report
  **unresolved/ambiguous** lines as structured results (no silent loss).
- **Exporters** (0033 `Deck` → text) for `.dck` and plain-text, round-tripping what we import.
- A `DeckIO` surface (`suspend fun import(text, format): DeckImportResult`, `export(deck, format): String`)
  — pure/local, on injected dispatchers, format auto-detect where reasonable.
- Share/export plumbing: produce the file/text for the platform share sheet (the actual share
  *intent* is wired by 0035; here it's the content + a filename/mime).
- Tests: round-trip (import→export→import) for each format; sideboard handling; unresolved-line
  reporting; catalog name→printing resolution (incl. a DFC/split name); malformed input → error, not crash.

**Out of scope**
- The library/builder **UI** and the share *intent* (**0035**).
- Deck storage/model/legality (**0033**).
- Cloud/deck-database sharing services; formats beyond the ported set (document what's supported).
- Artwork (**0031**).

## 4. Design & approach

```
core/decks/io/
├── DeckIO.kt              # import(text, format?) / export(deck, format); auto-detect
├── DckFormat.kt           # XMage .dck parse/serialize (ported)
├── TextFormat.kt          # plain-text/.dec parse/serialize (ported)
├── (MtgaFormat.kt)        # optional
└── DeckImportResult.kt    # imported Deck + unresolved/ambiguous line reports
```

- Each format module is a pure parser/serializer over the `DeckCardLists`-equivalent (0033); `DeckIO`
  maps that to/from the `Deck` model and resolves names → printings via `:core:cards` `CardCatalog`.
- Ambiguity (a name with many printings, no set given) resolves to a sensible default printing
  (e.g. latest/most-common) and is reported; a truly unresolved name is reported as an error line.
- Everything is offline + deterministic; no network.

## 5. Implementation steps

1. Port `.dck` parse/serialize from upstream; round-trip tests.
2. Port plain-text/`.dec` parse/serialize (incl. `SB:` sideboard); resolution via the catalog; tests.
3. (Optional) MTGA import.
4. `DeckIO` (auto-detect + map to 0033 `Deck` + unresolved reporting); export content + filename/mime
   for sharing.
5. `:core:decks:check` green (host); prior suites green.

## 6. Testing & verification

- **Hermetic gate (offline):** per-format round-trip (import→export→import equality), sideboard,
  unresolved/ambiguous reporting, name→printing resolution (incl. DFC/split), malformed→error. Uses a
  representative catalog fixture or the real bundle; no network.

## 7. Acceptance criteria

- [ ] `.dck` and plain-text/`.dec` **import** produces a 0033 `Deck` (main + sideboard) with names
      resolved to printings via the catalog; unresolved/ambiguous lines are **reported**, not dropped.
- [ ] `.dck` and plain-text **export** round-trips imported decks; a shareable file/text + filename/mime
      is produced.
- [ ] Import/export are pure, **offline**, deterministic; malformed input yields a structured error, not a crash.
- [ ] Tests cover round-trip per format, sideboard, resolution (incl. DFC/split), and error handling;
      `:core:decks:check` green; prior suites green.
- [ ] No UI/share-intent, no storage/legality changes, no artwork here. No network.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/cards/decks/importer/{DckDeckImporter,TxtDeckImporter,DecDeckImporter,MtgaImporter}.java` and `.../exporter/*` — the formats to port.
- [`0033-deck-model-storage-and-legality.md`](0033-deck-model-storage-and-legality.md) — the `Deck` model + `DeckCardLists`-equivalent.
- [`0030-card-catalog-data-and-local-search.md`](0030-card-catalog-data-and-local-search.md) — name→printing resolution.
