# 0099 — Card icons on the wire

- **Epic:** EPIC-23 — Game Information We Do Not Yet Map
- **Depends on:** nothing. Story 0096 renders the badges it feeds.

## 1. Objective

Map `CardView.cardIcons` through `:protocol` to the app schema, so the Board tier's keyword badges are
fed by what the server computed rather than by anything the client works out.

## 2. Context & background

**The badges are already decided upstream, and the bridge drops them.** `CardView` carries
`List<CardIcon>`, built in `generateCardIconsForPermanent` from `permanent.getAbilities(game)` — the
**game-aware** abilities, so a creature granted flying until end of turn carries the icon exactly as a
printed flier does. Each icon has a `CardIconType`, a `text` and a `hint`.

Nothing in `bridge/`, `protocol/` or `core/` mentions it. This is the same shape as every EPIC-23
story: a correct upstream field, threaded through unchanged.

**It matters that this is server-computed.** The alternative is deriving keywords from rules text in
the client, which would make the client a rules engine — the one thing it is not allowed to become.
Flying granted by another permanent, or removed by a layer effect, is a question only the server can
answer correctly.

**The set upstream ships is not the evergreen set.** It carries fourteen ability icons — flying,
defender, deathtouch, lifelink, double strike, first strike, trample, hexproof, indestructible,
vigilance, reach, infect, crew, class level — plus `OTHER_FACEDOWN`, `OTHER_COST_X`,
`OTHER_HAS_RESTRICTIONS`, `OTHER_HAS_TARGETS`, `COMMANDER`, `RINGBEARER` and `PLAYABLE_COUNT`.
Menace, ward, haste, flash, protection and prowess are absent. This story takes exactly what arrives;
covering the gap is 0101 and is optional.

## 3. Scope

**In scope**
- `CardView.cardIcons` → `GameCardView.icons` → `GameCard.icons`, threaded all the way to the app
  schema, because a field that stops at `:protocol` has not reached the app.
- A tolerant enum for the icon type, so an icon a newer server adds decodes to `UNKNOWN` rather than
  throwing — the `CardTypeCode.Serializer` pattern.
- The icon's `text` and `hint`, which carry the X value and the restriction detail the board and the
  inspect view need.

**Out of scope**
- Rendering. Story 0096 already draws the badges from a presentation model; this fills it from real
  data, and the wiring of one to the other belongs to the board.
- Inventing icons upstream does not send.
- Icon **art**. The badge placeholders stay placeholders until the art work is scheduled.

## 4. Prerequisites & toolchain

Project baseline. `:bridge` builds and is verified in the container only.

## 5. Design & approach

**One flat type plus a tolerant kind code**, exactly as story 0089 modelled command objects. The
icons differ in what they *mean*, not in what the app reads off them — a type, a short text and a
hint — so a sealed hierarchy would be structure without payoff.

**`canBeCombined` and `getCombinedInfo` are upstream presentation helpers** and do not cross. The
board decides how to render a repeated icon; the wire carries what the server said.

**The hint is not decoration — for some icons it is the whole answer.** Read in the clone while
implementing: `CardIconImpl.ABILITY_SHROUD` is built on `CardIconType.ABILITY_HEXPROOF`, so shroud and
hexproof arrive as the *same type* and are told apart only by a hint of `"Shroud"` or `"Hexproof"`.
Anything downstream that reads an ability's name off the type alone will report a shrouded creature as
hexproof. The constructor is `CardIconImpl(type, hint, text)` — **hint first**, text last and usually
empty; `text` is where a value lives (an announced X arrives as text `"x=3"` with hint
`"Announced X = 3"`, and a class level carries its level there).

**`SYSTEM_COMBINED` and `SYSTEM_DEBUG` are transcribed too.** They are upstream client-side inner
usage and are not expected from the server, but the enum mirrors upstream rather than a guess at which
of its values travel; the mapper deciding what the server may say is the failure mode being avoided.

## 6. Implementation steps

1. Read `CardIcon`, `CardIconImpl` and `CardIconType` in the local clone; enumerate the exact constants
   before writing the enum.
2. Add the `:protocol` type and the tolerant serializer.
3. Map in `GameViewMapper`, the single upstream read point.
4. Thread to `:core:network`'s domain `GameCard`.

## 7. Testing & verification

- **Proven failing first (standard 1):** the mapper test must fail against a mapper that drops the
  field, then pass.
- **Unit:** an unknown icon type decodes to `UNKNOWN` rather than throwing; `text` and `hint` survive.
- **Live check against the reference server (standard 5):** a fixture proves the mapper reads the
  field; only a live game proves the server populates it on the path we read. A deck with a flier and
  a trampler makes the case reachable without contrivance.
- No eyes-on: this story renders nothing.

## 8. Acceptance criteria

- [x] `cardIcons` crosses `:protocol` and reaches `GameCard`.
- [x] An unrecognised icon type decodes to `UNKNOWN`.
- [x] A live game against the reference server shows the icons arriving.
- [x] `./gradlew check` passes, and `:bridge` passes in the container.

## 9. References

- `Mage.Common/src/main/java/mage/view/CardView.java` — `cardIcons` and its generators.
- `Mage/src/main/java/mage/abilities/icon/` — `CardIcon`, `CardIconType`.
