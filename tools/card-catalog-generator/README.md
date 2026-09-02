# Card catalog generator

Generates the bundled, on-device XMage card catalog — `core/cards/src/main/assets/cards.sqlite` —
from XMage's own card pool. This is the **only** place in the repo that touches XMage's `mage.*`
types; it emits an app-schema SQLite database, and no `mage.*` type ever reaches `:core:cards` or the
device.

The bundle is a **committed asset**, regenerated only when the pinned XMage version changes. The host
Android build never runs this — it just packages the committed `cards.sqlite`.

## What it does

`Generator.java` runs inside a container that has XMage's full runtime classpath (including
`Mage.Sets`, the card-set classes). It:

1. calls `mage.cards.repository.CardScanner.scan()`, which builds XMage's card database by scanning
   every card-set class,
2. reads every `CardInfo` row back out, and
3. writes a normalized SQLite bundle:
   - `card` — one row per **oracle** card (name, mana cost, mana value, colors, super/card/subtypes,
     rules, P/T/loyalty/defense, and DFC/split/flip/meld/adventure face metadata),
   - `printing` — one row per **printing** (`card_id`, set code, collector number, rarity),
   - `meta` — provenance (`schema_version`, `xmage_version`, `xmage_ref`, counts).

   Normalization (card + printing) avoids repeating identical rules/type text across every reprint.
   All printings are kept — per-printing set + collector number + rarity are the identity downstream
   uses for artwork, deck references, and legality. Indexes: `card(name)`,
   `card(card_types)`, `card(mana_value)`, `printing(set_code, collector_number)`, `printing(card_id)`.

## Pinned version

`MAGE_REF=e0fe4b6f6a` (XMage `1.4.60`) — the same ref the build/server images are pinned to
(`docker/jvm/Dockerfile`, `docker/server/Dockerfile`). To bump XMage: rebuild those images at the new
ref, then regenerate (below) and bump `ASSET_VERSION` in
`core/cards/.../internal/CardCatalogDatabase.kt`.

## Regeneration command

Prerequisites (built once, reused): the `mage-free-client/build` and `mage-free-client/xmage-server`
images (see `docker/`). Docker runs in WSL. From the repo root:

```bash
# 1. Build the generator image (assembles the JDK build image + the server image's runtime jars).
docker build -t mage-free-client/card-catalog-generator:latest tools/card-catalog-generator

# 2. Run it, writing the bundle straight into the :core:cards assets dir.
docker run --rm \
  -v "$(pwd)/core/cards/src/main/assets:/out" \
  mage-free-client/card-catalog-generator:latest
```

Output: `core/cards/src/main/assets/cards.sqlite`. At `1.4.60` this is **~14 MB**, **32,037 cards /
91,536 printings**. The generator prints the counts; the same numbers are recorded in the bundle's
`meta` table.

## Format-legality bundle

The same image also produces `core/decks/src/main/assets/formats.json` — the bundled per-format
legality data the offline `DeckLegality` checker in `:core:decks` reads. `FormatGenerator.java`
instantiates XMage's constructed-format classes and reads each one's legal set codes, banned +
restricted card names, allowed rarities, and constraints (deck-min-size, sideboard-max, max-copies +
the per-card copy overrides).

Those format classes (`mage.deck.Standard`, `Modern`, …) are NOT in the pinned `org.mage:*` jars —
they live only in upstream source. So the Docker build **ephemerally, blob-less sparse-clones the
pinned `magefree/mage` ref** (the same repo + `MAGE_REF` the jvm/server images build from), fetching
only `Mage.Server.Plugins/Mage.Deck.Constructed/src/mage/deck`, and compiles just those format sources
against the pinned jars. No XMage source is committed into this repo — only the generated
`formats.json` (factual legality data) is.

Formats bundled: **Standard, Pioneer, Modern, Legacy, Vintage, Pauper**. At `1.4.60` the bundle is
**~62 KB** with (legal sets / banned / restricted): Standard 21/165/0, Pioneer 69/184/0,
Modern 114/207/0, Legacy 544/223/0, Vintage 544/156/51, Pauper 544/191/0 (Pauper also restricts
rarities to COMMON + LAND). Banned counts include XMage's format-invariant base bans
(conspiracy/ante/dexterity/acorn/offensive/Gleemox). Provenance (`xmageRef`, `xmageVersion`,
`generatedAt`) is embedded in the JSON.

### Regeneration command

```bash
# 1. Build the generator image (also clones the pinned format source; see above).
docker build -t mage-free-client/card-catalog-generator:latest tools/card-catalog-generator

# 2. Run FormatGenerator, writing the bundle into the :core:decks assets dir.
docker run --rm --entrypoint sh \
  -v "$(pwd)/core/decks/src/main/assets:/out" \
  mage-free-client/card-catalog-generator:latest \
  -c 'java -cp "/xmage-lib/*:/gen" FormatGenerator /out/formats.json "$MAGE_REF"'
```

The bundle reproduces **bit-for-bit** at a pinned ref **except** for `Standard`: XMage computes
Standard's legal sets from the *current date* (rotation), so its `legalSetCodes` are date-sensitive —
`generatedAt` records the run date, and only a fixed date reproduces Standard's list exactly. Every
other format is date-stable at the ref.

## Test fixture

`core/cards/src/test/resources/fixture-cards.sqlite` is a tiny (~110 KB) subset carved from the full
bundle (a DFC, a split + halves, a flip, a planeswalker, a few mono/multicolor cards) for
deterministic unit tests. Regenerate it with `tools/card-catalog-generator/build-fixture.py` after a
bundle refresh (`python3 build-fixture.py`).
