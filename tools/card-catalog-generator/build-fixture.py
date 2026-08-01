#!/usr/bin/env python3
"""Carve the small deterministic test fixture out of the full bundle.

Reads   core/cards/src/main/assets/cards.sqlite  (the generated bundle)
Writes  core/cards/src/test/resources/fixture-cards.sqlite

Run after regenerating the bundle:  python3 tools/card-catalog-generator/build-fixture.py
"""
import os
import sqlite3

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(REPO, "core", "cards", "src", "main", "assets", "cards.sqlite")
DST_DIR = os.path.join(REPO, "core", "cards", "src", "test", "resources")
DST = os.path.join(DST_DIR, "fixture-cards.sqlite")

# A representative slice: name-ranking cluster, a split (+ halves), a DFC, a flip, a planeswalker,
# a basic land, plus mono/multicolor instants.
NAMES = [
    "Lightning Bolt", "Lightning Helix", "Chain Lightning", "Ball Lightning",
    "Fire // Ice", "Fire", "Ice",
    "Delver of Secrets", "Akki Lavarunner", "Jace, the Mind Sculptor",
    "Island", "Serra Angel", "Counterspell",
]


def main():
    os.makedirs(DST_DIR, exist_ok=True)
    if os.path.exists(DST):
        os.remove(DST)

    src = sqlite3.connect(SRC)
    ddl = [r[0] for r in src.execute(
        "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' "
        "ORDER BY (type='index')")]

    dst = sqlite3.connect(DST)
    for stmt in ddl:
        dst.execute(stmt)

    q = ",".join("?" * len(NAMES))
    cards = src.execute(f"SELECT * FROM card WHERE name IN ({q})", NAMES).fetchall()
    ncols = len(src.execute("SELECT * FROM card LIMIT 1").description)
    dst.executemany("INSERT INTO card VALUES (" + ",".join("?" * ncols) + ")", cards)

    ids = [c[0] for c in cards]
    pq = ",".join("?" * len(ids))
    prints = src.execute(f"SELECT * FROM printing WHERE card_id IN ({pq})", ids).fetchall()
    pcols = len(src.execute("SELECT * FROM printing LIMIT 1").description)
    dst.executemany("INSERT INTO printing VALUES (" + ",".join("?" * pcols) + ")", prints)

    dst.executemany("INSERT INTO meta VALUES (?,?)", [
        ("schema_version", "1"), ("xmage_version", "1.4.60"), ("xmage_ref", "e0fe4b6f6a"),
        ("card_count", str(len(cards))), ("printing_count", str(len(prints))), ("fixture", "true"),
    ])
    dst.commit()
    print(f"fixture: {len(cards)} cards / {len(prints)} printings -> {DST}")


if __name__ == "__main__":
    main()
