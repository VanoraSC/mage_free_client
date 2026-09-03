# 0104 — The badges and counters get their art, and 0101 gets its answer

- **Epic:** EPIC-19 — Game Board Rebuild
- **Story:** #184
- **Depends on:** 0096 (the Board tier that draws them), 0099 (what the server marks), and the Mana
  font shipped in 0103.
- **Closes out:** 0101, whose decision this story records.

## 1. Objective

Draw keyword badges and counters as the things they are, using glyphs already in the font this project
ships — and settle 0101, now that the art half of it is free.

## 2. Context & background

**The badges were placeholder art and said so.** `BadgeSquare` carried its own doc comment admitting
it: a small square with `FLY`, `DTH`, `DS` in it. Three letters is a legend a player has to learn, and
it is a legend nobody wrote down.

**The counters were colour and a number.** [`CounterPalette`](../../core/designsystem/src/main/kotlin/magefree/designsystem/card/CounterPalette.kt)
answers *is this a different kind from that one*, which is the question that matters when several kinds
sit on one board — but it never answers *which kind is this*. That question got deferred to the inspect
view.

**The font shipped in 0103 answers both.** It was brought in for mana costs, and its glyph range is
much wider: every evergreen keyword, thirty-odd counter kinds, the card types, the double-faced
markers. The art for everything this story needed was already in the repository, unused.

## 3. Scope

**In scope**
- A badge drawn as its keyword's own symbol, with the keyword still readable to anything that reads.
- A counter drawn as its kind's symbol alongside its count, keeping the colour system underneath.
- The glyph tables, checked against upstream's own names rather than against what sounds right.
- A catalog section showing every badge and a spread of counters at board size.
- 0101's decision, recorded with the trace behind it.

**Out of scope**
- Deriving any keyword from rules text, in the client or the bridge. Unchanged and permanent.
- Coloured hexproof variants (`ms-ability-hexproof-blue` and friends). The font has them and the
  server sends the hint that would pick between them; it is five more entries for a distinction that
  matters less than shroud-versus-hexproof, and it can be added the day someone wants it.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`. The font and its notice are already in the tree from 0103.

## 5. Design & approach

**A glyph is placed by its measured ink, never by its line box.** This is the same rule the mana
symbols arrived at, extracted into `ManaFont.kt` so both use one implementation. A font's baseline and
metrics describe where *letters* sit; an icon font's glyphs are pictures that happen to be encoded as
characters, and their ink sits wherever the designer put it.

**The plate stays under the badge, and the colour stays under the counter.** Neither is decoration.
A badge sits on card art, which can be any colour, and an unbacked glyph disappears against half the
cards in a set. A counter's colour is what still separates two kinds the font has never heard of —
which is most kinds, since the set is open and runs to hundreds. The glyph is added information, not a
replacement for the system beneath it.

**What cannot be drawn is still said.** Four badges keep their short form: a restriction, a target and
an unrecognised icon are not keywords and no font has a picture of them, and the font's `ms-level`
banner is microtype in a wide plate that becomes a smudge at 13dp. Every badge that *is* drawn carries
its full keyword name as its content description, so a badge reads as "Flying" rather than as nothing —
the same promise the mana symbols make about the server's sentences.

**Names are checked, not guessed.** Every counter key was checked against `mage.counters.CounterType`'s
own strings. The stylesheet has art for several things that are not counter kinds at all — goad,
damage, a skull — and those are left out. The rule cuts the other way too: **poison** has no icon in
the stylesheet, there is an obvious candidate next door in the infect and toxic glyphs, and it is left
unmapped anyway, because those glyphs mean the ability that hands out the counter rather than the
counter.

**The boost counters are matched by shape.** Upstream generates `+1/+1`, `+2/+2`, `+1/+0` in
`CardUtil.getBoostCountAsStr`, so no table could list them; and `mana.css` draws an even boost
differently from an uneven one, which is a distinction the game makes too.

## 6. Implementation steps

1. Extract the measured-ink glyph drawing out of the mana symbols into one place.
2. Give `BoardBadge` its glyphs, and `BadgeSquare` a picture with the keyword as its description.
3. Map the counter kinds, boost counters by shape, and draw the counter as a chip.
4. A catalog section with every badge and a spread of counters at board size.
5. Trace 0101 to an answer rather than leaving it open.

## 7. Testing & verification

- **Unit:** the counter mapping — boost counters matched by shape, even told from uneven, mixed signs
  taking the leading half, an unknown kind resolving to nothing, and nothing mapped that upstream does
  not actually call a counter.
- **Hermetic Compose:** a badge still says which keyword it is; a badge with no glyph still shows its
  short form; a counter says its kind *and* keeps its count; a counter kind the font does not know
  still shows its count.
- **Eyes-on:** the catalog's *Badges and counters* section, which is the only way to judge whether
  twenty symbols are tellable apart at 13dp.

## 8. Acceptance criteria

- [x] Every badge upstream marks, and that the font can draw legibly, is drawn as its own symbol.
- [x] A badge with no usable glyph still shows its short form.
- [x] Every drawn badge carries its full keyword name as a content description.
- [x] A counter shows its kind's symbol where the font has one, and always shows its count.
- [x] The counter colour system is unchanged, and still carries the kinds with no symbol.
- [x] No counter is mapped to a name upstream does not use for a counter.
- [x] 0101's decision is recorded with the trace behind it.
- [x] `./gradlew check` passes and the catalog shows every badge and a spread of counters.

## 9. References

- `docs/stories/0101-the-missing-keyword-badges.md` — the decision this story records.
- `docs/stories/0103-the-ability-picker.md` — where the font arrived.
- `Mage/src/main/java/mage/counters/CounterType.java` — the names checked against.
- `Mage/src/main/java/mage/abilities/icon/CardIconImpl.java` — which icons upstream attaches, and to
  what.
