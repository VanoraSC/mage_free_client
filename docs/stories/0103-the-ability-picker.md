# 0103 — Answering the ability picker, and drawing the symbols it is written in

- **Epic:** EPIC-20 — The Cast Flow
- **Story:** #178
- **Depends on:** 0102 (the surface this completes), and Phase 2 step 2a in
  [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md).

> **Rewritten 2026-09-03, twice over.** This began as the whole cast UI with an editable payment and
> one Confirm; both went with the declared-intent model. What was left was "§7.7's filtering: do not
> ask a question that has only one answer" — and tracing that before building it showed **the server
> already does all three of §7.7's rules, per decision** (trace §2.7). So there is no filtering for us
> to write. What there is, is a prompt 0102 rendered with no way to answer it.

## 1. Objective

Make the ability picker answerable, and pin the fact that the narrowing behind it belongs to the
server.

## 2. Context & background

**§7.7's three surviving rules are upstream's, already.** Traced before writing anything:

- *One possible mana → no prompt.* `suppressAbilityPicker` returns `isManaActivatedAbility()` for
  anything on the battlefield, and a single suppressed ability is activated directly. A basic land
  taps with no picker, server-side.
- *Only the real options.* `ManaUtil.tryToAutoPay` narrows a permanent's mana abilities against the
  **unpaid cost** — by symbol where the cost has them, by mana otherwise. Narrowed to one, the picker
  disappears by the rule above. It bows out when the spell cares which colour paid, which is correct:
  there the choice is real.
- *Mid-cast, only mana abilities.* Payment goes through `getUseableManaAbilities`, which is mana
  abilities and nothing else.

**So a client that filtered would be choosing which mana to produce** — content, not form, and the one
thing §7.6's safety rule forbids. The server knows the cost and the abilities; we know neither better.

**The actual gap.** 0102 mapped `ChooseAbility` and `ChooseChoice` to a headline and an exit, with no
options. A dual land against a coloured cost, or any modal spell, therefore dead-ends mid-cast: the
server asks, and the surface has nothing to press.

**The second gap, added mid-story.** A picker's options are mana abilities, and every one of them is
written in symbols: `{T}: Add {G}.` The app drew those braces literally, and so did every mana cost and
every payment prompt — `Pay {2}{R}`. Those tokens are not a format this app chose; they are what
upstream's `ManaCost.getText()` produces, and they arrive on every card, rules line and prompt. Drawing
a picker whose options read as punctuation would have made the story's own surface the worst example of
it, so the symbols were brought into this story rather than deferred.

## 3. Scope

**In scope**
- Rendering the options of `ChooseAbility` and `ChooseChoice`, in the server's own text, in its order.
- Replying with the right identifier for each — an ability id for one, a choice key for the other.
- A catalog step showing a real picker.
- Drawing `{…}` tokens as symbols wherever the app shows server text: prompts, mana costs, rules lines,
  card tiles, the full card view, and the board's attachment costs.

**Out of scope**
- Any filtering of the options. It has already happened, better than we could do it.
- `useFirstManaAbility`, upstream's blunter per-user suppression. It is a setting, and there is no
  settings surface in this phase.
- Board-side land tapping, which is a board interaction and belongs to Phase 3.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`. 0102 merged.

## 5. Design & approach

**Every option the server sent is offered.** That is the whole rule, and it is the inverse of what this
story originally proposed. It is asserted directly rather than left implied.

**The reply is not the label.** An ability choice carries an id and a mode carries a key; both come
with rendered text meant for reading. Sending the text would be a wrong action submitted to a live
game, so the two are separate event types rather than one carrying a string — the type system rules
out the confusion for free.

**The symbols are a font, and the design system still cannot draw a picture.** Upstream's Swing client
maps each token to a PNG and rewrites the string into HTML `<img>` tags; that route needs an image
pipeline this module does not have and is not getting. A font does not: the glyphs ship as
`res/font/mana.ttf`, and a symbol is a character in a `Text` like any other. Layout stays here, drawing
goes through `LocalSymbolSlots`, which is the same seam as `CardArtSlot` for the same reason.

**The words survive.** Each symbol is an `InlineTextContent` whose `alternateText` is the original
token, so the string a test or a screen reader reads is still exactly what the server sent. That is not
a nicety: without it, every existing assertion about a prompt's wording would have quietly started
matching nothing, and the app would have traded the server's words for a picture.

**An unknown code falls back to its token.** Sets add symbols. Drawing an arbitrary glyph for a code the
font has never heard of would be misinformation; `{WUBRG}` is at least readable, and is what the app
showed before any of this existed.

**Licence.** The Mana font is **SIL OFL 1.1**, not MIT — the MIT licence on that project covers the
CSS/LESS/Sass only, and the symbol artwork remains © Wizards of the Coast. OFL permits bundling the
font in an application; it requires the notice, which is in
[`docs/third-party-notices.md`](../third-party-notices.md).

## 6. Implementation steps

1. Trace §7.7's rules before building anything. (Done, and it changed the story.)
2. Carry the options into the cast model.
3. Render them, and reply with the right identifier per prompt.
4. A catalog step with a real picker.
5. Trace how upstream draws `{T}` and `{W/U}` before choosing an approach. (Done: PNGs and HTML, which
   is why this uses a font instead.)
6. Ship the font, parse the tokens, and route every surface that shows server text through it.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test that a real ability choice is answerable must fail
  against 0102's surface, then pass.
- **Unit:** every option the server listed is offered and none dropped; the label is the server's own
  text and the reply is the id; a mode carries its key rather than its label.
- **Hermetic Compose:** picking an option reports the right event *type*, so an ability can never be
  answered as a mode.
- **Unit (symbols):** the parser splits and, round-tripped, reproduces its input exactly; a hybrid stays
  one symbol rather than two; an unclosed brace is prose; the glyph table covers every code an ordinary
  cost is made of, in both hybrid orders, and resolves an unknown code to nothing rather than to
  something wrong.
- **Hermetic Compose (symbols):** a rendered prompt still matches the server's own sentence, symbols and
  all — including the unknown-code fallback.
- **Eyes-on:** the catalog's cast flow, which now includes a dual land against a coloured cost, and the
  *Mana and tap symbols* section, which shows each family and a symbol sitting inside a sentence.

## 8. Acceptance criteria

- [x] `ChooseAbility` and `ChooseChoice` are answerable, in the server's own text and order.
- [x] Every option the server sent is offered; none is filtered out client-side.
- [x] An ability is replied to with its id and a mode with its key, and the two cannot be confused.
- [x] The finding that §7.7's filtering is already the server's is recorded in the trace.
- [x] `{…}` tokens render as symbols in prompts, mana costs, rules lines, tiles, the full card view and
      the board's attachment costs.
- [x] The text a test or screen reader reads is unchanged — still the server's own string.
- [x] A code the font cannot draw shows its literal token rather than a blank or a wrong symbol.
- [x] The font's licence is recorded in `docs/third-party-notices.md`.
- [x] `./gradlew check` passes and the catalog shows a real picker and every symbol family.

## 9. References

- [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md) §2.7 — why there is no filtering to
  write.
- `docs/ui-modernization-plan.md` §7.7.
- [`docs/third-party-notices.md`](../third-party-notices.md) — the Mana font's OFL notice.
- Upstream `ManaSymbols.java` / `GuiDisplayUtil` — the PNG-and-HTML route this deliberately does not
  take.
