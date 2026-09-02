# 0101 — The keyword badges upstream does not send (optional)

- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0099, which establishes what does arrive.
- **Status:** optional. Schedule only if the gap turns out to matter in play.

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
about the code, which is why this is deferred rather than specified in detail now.

**The obvious fix is the forbidden one.** Deriving these client-side means reading rules text and
deciding whether a keyword currently applies — after layers, after granted and removed abilities.
That is a rules engine, and the client does not get to be one. Any solution has to come from the
server.

## 3. Scope

**In scope**
- A decision, recorded: live with upstream's set, or pursue one of the routes below.
- If pursued: the smallest change that gets the data from the server rather than inferring it.

**Candidate routes, in the order they should be considered**

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

## 5. Design & approach

Not designed yet, deliberately. The first step is playing with the board from 0096 and 0099 and
noticing whether the missing badges are missed.

## 6. Implementation steps

1. Play with the completed board and note which absent keyword actually caused a misread.
2. If none: close this story with that finding recorded. That is a real outcome, not a failure.
3. If some: read the local clone for structured ability data before choosing between routes 2 and 3.

## 7. Testing & verification

Follows whichever route is chosen. Route 2's verification is upstream's; route 3's is EPIC-23's shape
— a mapper test proven to fail first, then a live check against the reference server.

## 8. Acceptance criteria

- [ ] A decision is recorded, with the reason.
- [ ] If a route is taken, no keyword is ever derived from rules text.

## 9. References

- `docs/stories/0099-card-icons-on-the-wire.md` — what does arrive.
- `Mage/src/main/java/mage/abilities/icon/CardIconType.java` — the upstream set.
