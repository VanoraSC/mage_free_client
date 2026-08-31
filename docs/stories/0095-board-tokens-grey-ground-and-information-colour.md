# 0095 — Board tokens: a grey ground, and colour reserved for information

- **Epic:** EPIC-03 — Design System & Theming (Phase 1)
- **Depends on:** nothing.

## 1. Objective

Add the token set the new board is built on: a grey scale deep enough to separate zones by **value
alone**, a small set of saturated colours that mean something, a type and elevation scale for cards
at board size, and **motion tokens** with the durations §7.3 specifies.

## 2. Context & background

**The board is grey, and that is a functional decision rather than an aesthetic one.** §7.4: no
illustrated battlefield, no themed playmat, no decorative background — "a pleasing neutral grey
ground, with zones and other distinctions carried by shades of that grey — value and elevation, not
colour or texture."

The payoff is stated in the same section: *"A neutral ground means the only saturated colour on the
board is information — playable-now highlight, targeting, combat arrows, pending costs, threat.
Against an illustrated background those signals compete with decoration for attention, and the
signals lose."* Every P0 item in §3.1's board-presentation table is one of those signals, so the
palette is what decides whether they read at card size.

**The current palette is the opposite of that, and it must not be touched.** `MagePalette` is a
deliberate arcane identity — violet primary, teal secondary, amber accent — consumed through
`MageTheme` by every existing screen. §11 is explicit that the new UI is built **alongside** the old
one and that *"the old code is not edited to accommodate the new code"*, precisely so a regression
cannot be introduced into the working path.

**So this is additive: a second token layer, not a repaint.** The board tokens live beside the
existing ones and are consumed only by new surfaces. When the last old surface is retired, the two
can be reconciled — deliberately, as its own change, not as a side effect of this one.

**Motion tokens belong here too**, because §7.3's durations are meaning rather than taste: zone moves
~250 ms, taps ~150 ms, counter changes ~120 ms, resolution spotlight ~400 ms hold. Putting them in
the token layer is what lets the reduce-motion setting **shorten** them centrally — §7.3 is emphatic
that it shortens rather than removes, "because removing it removes the information."

## 3. Scope

**In scope**
- A board grey scale: enough steps to separate the ground, each battlefield, floating layers and
  card surfaces **by value alone**, with no hue carrying meaning.
- An information-colour set, one entry per signal §3.1 names: playable-now, targeting, combat
  (attack and block), pending cost, threat. Each named for what it *means*, never for what it looks
  like.
- Elevation steps for the two-layer model in §7.4 — a stable base and floating layers above it.
- A type scale sized for the board tiers, where a card name at Board size is the smallest thing that
  must stay legible.
- Motion tokens: the four durations above, an easing set, and a single **motion scale** the
  reduce-motion setting drives.
- A catalog entry rendering the scales side by side, because a value ramp is only checkable by eye.
- The story references the previous documentation pass missed: 44 of them across the build and
  container config — `libs.versions.toml`, four Dockerfiles, `docker-compose.yml`, two
  `AndroidManifest.xml`, `proguard-rules.pro` and `smoke-on-device.sh`. That pass scoped its search to
  Kotlin, so the rule was written into `AGENTS.md` and then left unenforced everywhere else. This is
  the token story only because it is small and the rule is the same one; the acceptance check here is
  extension-agnostic so the shape cannot be missed a third time.

**Out of scope**
- Any change to `MagePalette`, `MageTheme`, or any existing screen. If an old screen changes
  appearance, the story went wrong.
- The components that consume these tokens — card tiers, the Prompt, the animation host. Each is its
  own story so a token mistake is caught before three components are built on it.
- The reduce-motion **setting** as a user-facing control; this story provides the scale it drives.
- Dark/light theming variants of the board. The board is one ground; a second variant needs a reason.

## 4. Prerequisites & toolchain

Project baseline. `:core:designsystem` only; Android-side, `:bridge` untouched.

## 5. Design & approach

**Name tokens for meaning, not appearance.** `Highlight.playable` rather than `Green500`; `Zone.
opponentBattlefield` rather than `Grey700`. A name that describes the pixel is a name that has to
change when the pixel does, and the §3.1 vocabulary is the stable thing here.

**The grey scale has a hard requirement, and it is testable.** Zones must be distinguishable *by
value alone* — so adjacent steps need a minimum luminance separation, and the assertion is on
computed contrast rather than on a designer's eye. That is what stops the ramp being quietly
flattened later.

**The information colours have a matching requirement:** each must clear a contrast threshold
against every grey it can appear on, or the signal is unreadable exactly where it matters. Both
checks are cheap to write and are the reason to do the palette as its own story.

**One motion scale, one place.** Durations are `Duration * MotionScale`, with the scale supplied
through a composition local defaulting to 1. Reduce-motion sets it lower; nothing anywhere multiplies
by hand.

## 6. Implementation steps

1. Read §7.4's grey/colour rationale and §3.1's signal table; enumerate the exact signals needing a
   colour before choosing any value.
2. Add the board token file(s) alongside the existing tokens, consumed by nothing yet.
3. Add the contrast assertions.
4. Add the catalog entry showing the grey ramp, the information colours over each grey, and the
   motion durations.

## 7. Testing & verification

- **Proven failing first (standard 1):** the value-separation test must fail against a ramp with two
  adjacent steps collapsed, then pass.
- **Unit:** adjacent grey steps clear the minimum luminance separation; every information colour
  clears its contrast threshold against every grey it may sit on; the motion scale multiplies every
  duration and never reaches zero.
- **Catalog:** the ramp and the signals render in the component catalog. A palette is judged by eye,
  and this is where that happens.
- **The old screens are unchanged**, asserted by their existing tests passing unedited.
- **Eyes-on:** the catalog, in the app — do the greys separate without colour, and does each signal
  read against all of them.

## 8. Acceptance criteria

- [ ] Board greys, information colours, elevation, type and motion tokens exist, named for meaning.
- [ ] Adjacent greys are provably separable by value; each signal colour provably contrasts.
- [ ] One motion scale drives every duration.
- [ ] `MagePalette`/`MageTheme` and every existing screen are untouched.
- [ ] No story, epic or plan-section reference survives in any non-document file, checked without
      filtering by extension.
- [ ] `./gradlew check` passes and the catalog renders the new scales.

## 9. References

- `docs/ui-modernization-plan.md` §7.4 (grey ground, two-layer model), §3.1 (the signal vocabulary),
  §7.3 (motion durations and reduce-motion), §11 Phase 1.
- `core/designsystem/src/main/kotlin/magefree/designsystem/theme/` — the existing tokens this sits
  beside.
