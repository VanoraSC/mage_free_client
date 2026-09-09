# 0122 — An emblem is the card that made it

- **Story:** #215
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0114 (the stack), 0118 (`sourceId` on the wire).
- **Specified by:** a real game — Liliana, the Last Hope's emblem, drawn as a grey placeholder.

## 1. Objective

Draw an emblem with the art of the card that made it, and let it be opened and read like any other
object on the board.

## 2. Context & background — what upstream knows

**An emblem has a set code, but not a card's.** `Emblem.setSourceObjectAndInitImage` asks
`TokenRepository.findPreferredTokenInfoForClass` for a `TokenInfo`, then sets `expansionSetCode` from
it, `cardNumber` to `""`, and an `imageNumber`. That is upstream's *own* image pipeline — a numbered
image inside an emblem set — and it is not a Scryfall printing. An art request built from
`(setCode, "")` resolves to nothing, which is the grey placeholder on the board today.

**But it knows exactly which card made it.** `Emblem.sourceObject` is the `MageObject` that created
it, and `Emblem.getSourceId()` returns that object's id. Liliana, the Last Hope has a real set code
and a real collector number, and therefore real art.

So Pete's instruction — *use the art of the card that created it* — is not a workaround. It is the
one identity on the object that maps to a printing this client can actually fetch.

## 3. The work, and the thing to check first

**Unverified, and it decides the shape:** whether `CommandObjectView` (for the emblem sitting in the
command zone) exposes the source object's set code and collector number, or only its id — and the
same question for the emblem's triggered ability on the stack, where 0118's `sourceId` currently
resolves to the *emblem*, not to the card behind it.

- If the printing is reachable on the view, this is a bridge mapping and nothing more.
- If only an id is reachable, the board can resolve it against a permanent it is already drawing —
  but a planeswalker that has left the battlefield is exactly the case an emblem outlives, so that
  resolution will often fail and the honest answer is a wire field carrying the source printing.

**Do not guess this.** `CardView` turned out to expose no owner at all in 0120, after the story had
already proposed a design that assumed one.

## 4. Scope

**In scope**
- An emblem drawn with its source card's art, in the command zone and on the stack.
- An emblem that opens the ordinary card detail, showing its oracle text — which the server already
  sends and the board already draws for everything else.

**Out of scope**
- Upstream's own emblem images. They are a different pipeline (`imageNumber` into an emblem set) and
  adopting it would mean a second art path for one object type.

## 5. Acceptance criteria

- [ ] An emblem shows the art of the card that created it.
- [ ] Pressing an emblem opens the card detail.
- [ ] The detail shows the emblem's oracle text.
- [ ] The emblem's triggered ability on the stack shows the same art.
- [ ] An emblem whose source has left the battlefield still draws correctly.
