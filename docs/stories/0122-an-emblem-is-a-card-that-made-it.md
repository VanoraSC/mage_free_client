# 0122 — An emblem is drawn, and read

- **Story:** #215
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0114 (the stack), 0118 (`sourceId` on the wire), 0123 (the seat window).
- **Specified by:** a real game — Liliana, the Last Hope's emblem, drawn as a grey placeholder.

## 1. Objective

Draw an emblem with a picture instead of a placeholder — on the stack and in the command zone — and let
it be opened and read like any other object on the board.

## 2. What upstream knows

This story first proposed drawing an emblem with the art of **the card that created it**. Checked against
upstream, that printing is not reachable, so the design below is different.

**An emblem names no printing, and no view names its source.** `Emblem.setSourceObjectAndInitImage`
asks `TokenRepository.findPreferredTokenInfoForClass` for the emblem's image, then sets
`expansionSetCode` from it, `cardNumber` to `""` and an `imageNumber`. `EmblemView` carries exactly that,
plus the id, name and rules. The emblem object on the server does hold its `sourceObject`, but nothing
copies it onto a view. `GameView` builds an emblem trigger's stack entry from `new CardView(new
EmblemView(...))`, and the line that would have set the source card's set code is commented out. The
bridge reads views, so the planeswalker's printing cannot be put on the wire.

**Upstream's own client draws emblems from a table.** `ScryfallImageSource` resolves every token and
emblem through `ScryfallImageSupportTokens.findTokenLink(set, name, imageNumber)`. That is a
hand-maintained map from `SET/Name`, or `SET/Name/N` where one set has two images of a name, to a
Scryfall link. It has 130 emblem entries (`EMN/Emblem Liliana` → `cards/temn/9`), and every one is a plain
set-and-number link. This client's by-name token lookup cannot find them because Scryfall names them
differently (`Emblem Nixilis` is `Ob Nixilis Reignited Emblem`).

**What that picture is.** It is Scryfall's emblem card for that emblem — not the planeswalker card's own
printing — and it is what upstream's client shows.

## 3. Design

- **The table, ported as printings.** `emblemArtRequest(set, name, imageNumber)` in `core/cards`, keyed
  exactly as `findTokenLink` keys it. It resolves to an ordinary set-and-number request, so it goes
  through the same image path as every other card. Where upstream has no entry (eight emblems in its
  token database today, such as Dominaria United's Karn) there is no image, and the placeholder stays.
- **`imageNumber` on the wire**, on cards and command objects. It is the `N` in the key, and the only
  thing telling Commander Masters' two `Emblem Chandra`s apart. Additive.
- **On the stack.** An emblem trigger's source card is the emblem, and the bridge already reads name and
  set off it, so `artRequestOf` asks the table for any card without a collector number. Only an exact
  key answers.
- **In the command zone.** The seat window draws command objects as cards in their own column rather
  than as names in the status list. A commander, or an emblem made of a card, draws its real printing;
  other emblems use the table; dungeons and planes keep the placeholder. A press opens the ordinary card
  detail with the object's rules.

Nothing depends on the planeswalker still being in play, which is the case an emblem exists for.

## 4. Scope

**In scope:** the wire field, the ported table, emblem art on the stack and in the seat window, and the
detail for command objects.

**Out of scope**
- Dungeon and plane images. They are not emblems and name no printing.
- An always-visible command zone on the board. Where it would go is a design decision; today the command
  zone is in the seat window.

## 5. Acceptance criteria

- [ ] An emblem in the command zone draws upstream's image for it.
- [ ] The emblem's trigger on the stack draws the same image.
- [ ] Pressing an emblem in the seat window opens the card detail, which shows its rules.
- [ ] Commander Masters' two `Emblem Chandra`s draw different images.
- [ ] An emblem upstream has no image for draws the placeholder, as before.
- [ ] An emblem whose planeswalker has left the battlefield still draws correctly.
