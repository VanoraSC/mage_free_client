# 0101 — The keyword badges upstream does not send

- **Epic:** EPIC-19 — Game Board Rebuild
- **Story:** #172
- **Depends on:** 0099, which establishes what does arrive.
- **Status:** **decided, 2026-09-03.** Route 1 — live with upstream's set. Traced in §5; one part of
  the gap closed with data in 0104.

## 1. Objective

Decide what to do about the evergreen keywords upstream computes no icon for — **menace, ward, haste,
flash, protection, prowess** — and act on that decision, or record that we are not going to.

## 2. Context & background

Story 0099 threads `CardView.cardIcons` through, and the badges it feeds are exactly what the server
decides. That set is fourteen abilities, chosen upstream for its own client, and it is close to but
not the same as the evergreen keyword list.

**The gap is real but may not matter.** Haste and flash change what a player can do, not what they
should read off a board at a glance; menace and protection change combat maths and arguably should be
visible. Whether the absence is actually felt is a question about playing with the board rather than
about the code, which is why this was deferred rather than specified in detail.

**The obvious fix is the forbidden one.** Deriving these client-side means reading rules text and
deciding whether a keyword currently applies — after layers, after granted and removed abilities.
That is a rules engine, and the client does not get to be one. Any solution has to come from the
server.

## 3. Scope

**In scope**
- A decision, recorded: live with upstream's set, or pursue one of the routes below.
- If pursued: the smallest change that gets the data from the server rather than inferring it.

**Candidate routes, in the order they were considered**

1. **Accept the set.** Free. The board shows what upstream shows, and the inspect view (0100) carries
   the full rules text for anything the badges do not cover.
2. **Contribute upstream.** `CardIconType` is an enum plus an `Ability.getIcons(game)` hook, so adding
   the missing keywords is a small, well-shaped upstream change — and upstream is the correct owner of
   the question.
3. **A bridge-side mapper over structured ability data**, only if the server exposes keywords
   structurally somewhere other than `cardIcons`. This needs the local clone read before anyone
   assumes it is possible.

**Out of scope**
- Parsing rules text, in the client or the bridge. It is wrong for the same reason in both places.

## 4. Prerequisites & toolchain

Project baseline. Route 2 additionally needs an upstream contribution path.

## 5. The decision, and the trace behind it

**Route 1. Accept upstream's set.** Not because the gap does not matter, but because nothing available
closes it honestly.

### 5.1 The art turned out to be free, and it changed nothing

The Mana font shipped in 0103 draws **every one of the six**, and they were checked by rendering rather
than assumed: `ms-ability-menace`, `-ward`, `-haste`, `-flash`, `-protection` (plus five coloured
variants) and `-prowess` all resolve to real glyphs.

That is worth stating plainly because it is the trap: having the picture makes it feel like the feature
is nearly done. It is not. **The picture was never the missing half.** The client still has no way to
know that a creature has menace, and drawing a menace badge would mean deciding it does — which is
§7.6's forbidden move, and would be wrong in exactly the cases that matter, after layers and after
granted or removed abilities.

### 5.2 Route 3 is dead, traced rather than assumed

`mage.view.CardView`'s full field list carries **no structured ability data**. The ability-shaped
things that cross the wire are:

- `cardIcons` — the fourteen, which is the set in question;
- `rules` — rendered text, which is the forbidden route.

There is nothing else: no ability list, no keyword set, no per-keyword flag. Every keyword icon
upstream sends is attached in the ability class's own static initialiser — `FlyingAbility` calls
`addIcon(CardIconImpl.ABILITY_FLYING)` — and `MenaceAbility` and its five siblings simply do not make
that call. There is no other surface to map from, so a bridge-side mapper has nothing to map.

### 5.3 Route 2 is now cheap on our side, if anyone wants it

Because the art exists and the badge type already carries a glyph, each keyword upstream adds costs
this project **one enum entry and one codepoint**. That does not make the upstream contribution itself
free, but it removes any argument that the client work is a reason to hesitate. Left open as a
possibility rather than scheduled, for the same reason as before: nobody has yet misread a board
because a haste badge was missing.

### 5.4 One part of the gap closed, with data

**Shroud is not in the six, and it was silently conflated with hexproof.** Upstream sends both under
`CardIconType.ABILITY_HEXPROOF`, told apart only by the hint — `CardIconImpl.ABILITY_SHROUD` is
`new CardIconImpl(CardIconType.ABILITY_HEXPROOF, "Shroud")` — and that hint already crosses the wire
in `GameCardIcon.hint`. The font has a separate shroud glyph. So 0104 gives shroud its own badge, and
whoever builds board state from the wire picks between the two on the hint.

This is worth separating from the rest of this story: it is not inference. The server said "Shroud",
and until now the board threw that word away. It also matters in play — hexproof stops *their* spells,
shroud stops yours as well, and a player who reads one as the other will mis-target their own trick.

The same mechanism is available for **coloured** hexproof: upstream's hints read "Hexproof from blue"
and the font has `ms-ability-hexproof-blue`. Not taken in 0104, and noted there as available.

## 6. Implementation steps

1. ~~Play with the completed board and note which absent keyword actually caused a misread.~~ Overtaken:
   the font arriving for 0103's mana symbols made the question answerable from source instead.
2. Trace whether structured ability data exists on the wire. **Done — it does not (§5.2).**
3. Record the decision. **Done: route 1, with route 2 left open and cheapened.**

## 7. Testing & verification

Route 1 has nothing to verify beyond what already holds: no keyword is derived from rules text
anywhere in the client or the bridge. The shroud badge from §5.4 is verified in 0104.

## 8. Acceptance criteria

- [x] A decision is recorded, with the reason.
- [x] If a route is taken, no keyword is ever derived from rules text.

## 9. References

- `docs/stories/0099-card-icons-on-the-wire.md` — what does arrive.
- `docs/stories/0104-the-badges-and-counters-get-their-art.md` — the badges this decision leaves in
  place, drawn properly, and the shroud badge from §5.4.
- `Mage/src/main/java/mage/abilities/icon/CardIconType.java` — the upstream set.
- `Mage/src/main/java/mage/abilities/icon/CardIconImpl.java` — where shroud and hexproof share a type.
- `Mage.Common/src/main/java/mage/view/CardView.java` — the field list §5.2 rests on.
