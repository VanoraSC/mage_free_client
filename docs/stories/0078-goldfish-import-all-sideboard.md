# 0078 — MTGGoldfish text import puts the whole deck in the sideboard

- **Epic:** EPIC-09 — Deck Library & Builder
- **Depends on:** 0042 (plain-text deck import)

## 1. Objective

Fix a defect found live (Pete, 2026-08-20): importing a deck exported from MTGGoldfish (75 cards: 60
main + a note in the paste plus 15 sideboard, in this shape)

```
About
Name Boros

Deck
4 Ajani, Nacatl Pariah
...
4 Marsh Flats

Sideboard
1 High Noon
...
1 Celestial Purge
```

put **every single card — the entire main deck included — into the sideboard.**

## 2. Root cause — confirmed by reading both our port and the upstream source it's ported from

`TextFormat.parse()` (`core/decks/src/main/kotlin/magefree/decks/io/internal/TextFormat.kt`) is a
port of upstream's `mage.cards.decks.importer.TxtDeckImporter`. Both share the same rule: **a blank
line, once any "card" has been seen, switches everything after it to the sideboard** — and, read
directly against upstream's `TxtDeckImporter.readLine`, that switch is **one-directional**: `sideboard`
is only ever set to `true`, never reset, anywhere in that class.

Upstream's own `DeckImporter.getDeckImporter` pre-scans a `.txt` file for `//sideboard` or `SB: ` and,
if found, disables the blank-line rule entirely (`new TxtDeckImporter(haveSideboardSection(file))`).
That pre-scan does **not** look for a bare `Sideboard` header line — only those two literal marks — so
a Goldfish-style file (headers, no `//sideboard`/`SB:`) reaches upstream's importer with the
blank-line rule still active, and traced by hand through `readLine` line by line, upstream's own
desktop client mishandles this exact shape too: `About` and `Name Boros` are read as two bogus
1-count "cards" (harmless — they fail to resolve and are reported as not-found), but the **blank line
right after them** fires the one-directional switch, and every card from that point on — the entire
`Deck` section — lands in the sideboard. This is a genuine gap in the format upstream's `.txt` importer
was designed for (MTGO-style: main deck, blank line, sideboard, no headers), not a bug our port
introduced; `Deck`/`Sideboard`-headered exports are a newer convention several sites (Goldfish,
Moxfield, and others) now use.

**The fix does not need to replicate the gap.** [MtgaFormat] (`core/decks/src/main/kotlin/magefree/
decks/io/internal/MtgaFormat.kt`), already in this codebase for MTG Arena's own `Deck`/`Sideboard`
headers, already does this correctly: it explicitly resets to `sideboard = false` on a `Deck`/
`Mainboard` line, which is exactly the "switch back" upstream's plain-text importer never has.
`TextFormat.parse()` gains the same explicit header recognition, so a blank line inside a `Deck`/
`Sideboard`-headered file is harmless — the very next header line corrects whatever the blank line
did.

## 3. Scope

**In scope**
- `TextFormat.parse()` recognizes `Deck`/`Mainboard` (→ main) and `Sideboard`/`Commander`/
  `Maybeboard` (→ sideboard) as whole-line, case-insensitive headers, mirroring [MtgaFormat]'s own
  header handling — narrowed to an exact whole-line match (not a prefix) specifically so it does not
  collide with the existing `"sideboard cards"` entry in [IGNORE_NAMES], a decorative inline label
  used by some flat-list exports, not a zone divider.
- `About` and a leading `Name <deck name>` line, while no real card has been seen yet, are recognized
  as file metadata (skipped as pseudo-cards) rather than parsed as 1-count cards literally named
  "About"/"Name Boros" — this also happens to be *why* the blank line right after them was
  previously misread as "blank line after the first cards", since those two lines had counted as
  card lines under the old code.
- `Name <deck name>` populates `ParsedDeck.name` (already an existing, currently `.dck`-only, field)
  so a Goldfish import also picks up the deck's name — free, given the line is already being parsed
  for a different reason, and silently discarding an explicitly-named deck would be its own smaller
  defect.
- The existing blank-line-switches-to-sideboard rule is untouched for files with **no** headers at
  all (the classic MTGO shape) — it remains the fallback, exactly as before.

**Out of scope**
- Format auto-detection (`DefaultDeckIO.detectFormat`) is unchanged — this file still resolves to
  `DeckFileFormat.TEXT` (the `MtgaFormat.looksLikeMtga` first-line check correctly does not claim it,
  since the first line is `About`, not an MTGA header or a `(SET)`-carrying card line), and that is
  fine: `TextFormat` itself now handles the shape correctly, no rerouting needed.
- Any other Goldfish export variant (`.mtga`/Arena format, CSV) — this story is the plain-text `.txt`
  export specifically.

## 4. Constraints already verified — do not rediscover

- `TxtDeckImporter.readLine`'s `sideboard` flag is a one-way switch — confirmed by reading the whole
  method directly at the pinned ref; there is no branch anywhere in that class that sets it back to
  `false`.
- `DeckImporter.haveSideboardSection(file)` only searches for `//sideboard` or `sb: ` — confirmed by
  reading it directly — so a bare `Sideboard` header line does **not** disable the blank-line rule
  upstream's own way; this is a genuine gap in the ported format, not a misreading of the library.
- `MtgaFormat.parse()` already resets `sideboard = false` on `Deck`/`Mainboard` (`MtgaFormat.kt:46-49`)
  — the pattern this story's fix mirrors for `TextFormat`.
- `IGNORE_NAMES` already contains the literal `"sideboard cards"` (plural, with "cards") as an inline
  decorative label some exports use — a prefix match on `"sideboard"` would have misfired on it, which
  is why the new header check uses an exact whole-line match instead.

## 5. Verification

- **Standard 1**, discriminating test: the exact `About`/`Name`/blank/`Deck`/…/blank/`Sideboard`/…
  shape must resolve two cards to `MAIN` and one to `SIDEBOARD`, and the deck's `name` must be
  `"Boros"`. Proven to fail against the unfixed parser first (all three cards landed in the
  sideboard).
- **Standard 2 (reachability):** `DeckEntry.zone` for each parsed line comes from `TextFormat`'s
  `sideboard` flag, itself now driven by the nearest preceding `Deck`/`Mainboard`/`Sideboard`/
  `Commander`/`Maybeboard` header when one exists, or the blank-line fallback when it doesn't.
- **Hermetic gate:** `core/decks/src/test/kotlin/magefree/decks/io/DeckIOTest.kt`.
- **Regression check:** the existing `text import handles counts and empty-line sideboard` and
  `text import handles SB prefix sideboard` tests (no headers at all) still pass unchanged, proving
  the classic MTGO shape is untouched.

## 6. Acceptance criteria

- [ ] An MTGGoldfish plain-text export (`About`/`Name`/`Deck`/`Sideboard` headers) imports with the
      main deck and sideboard correctly separated.
- [ ] The deck's name is picked up from the `Name` line when present.
- [ ] A header-less MTGO-style plain-text file (blank line only, no headers) is unaffected.
- [ ] `"sideboard cards"` as an inline label (no real deck uses this as a Goldfish-style header) is
      still ignored rather than treated as a zone switch.

## 7. References

- `core/decks/src/main/kotlin/magefree/decks/io/internal/TextFormat.kt` — the fix.
- `core/decks/src/main/kotlin/magefree/decks/io/internal/MtgaFormat.kt` — the header-reset pattern
  this story's fix mirrors.
- `core/decks/src/main/kotlin/magefree/decks/io/internal/DefaultDeckIO.kt` — `detectFormat()`,
  confirmed unaffected/unnecessary to change.
- `Mage/src/main/java/mage/cards/decks/importer/TxtDeckImporter.java` (pinned ref `e0fe4b6f6a`) — the
  one-way `sideboard` switch, read directly.
- `Mage/src/main/java/mage/cards/decks/importer/DeckImporter.java` (pinned ref `e0fe4b6f6a`) —
  `haveSideboardSection`, read directly, confirming it only looks for `//sideboard`/`SB: `.
