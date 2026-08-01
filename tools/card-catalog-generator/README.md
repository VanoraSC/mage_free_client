# Card catalog generator (story 0030)

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
   uses for artwork (0031), deck references, and legality (Epic 9). Indexes: `card(name)`,
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

## Test fixture

`core/cards/src/test/resources/fixture-cards.sqlite` is a tiny (~110 KB) subset carved from the full
bundle (a DFC, a split + halves, a flip, a planeswalker, a few mono/multicolor cards) for
deterministic unit tests. Regenerate it with `tools/card-catalog-generator/build-fixture.py` after a
bundle refresh (`python3 build-fixture.py`).
