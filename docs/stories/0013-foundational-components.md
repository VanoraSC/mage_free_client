# 0013 — Foundational components

- **Epic:** EPIC-03 — Design System & Theming
- **Depends on:** 0012
- **Status:** ready

## 1. Objective

Build the reusable **non-card UI components** of the design system in `:core:designsystem`: the
action/button hierarchy, list rows, section chrome (top app bar / section headers),
loading/empty/error states, and — central to the mobile UX — the **decision/prompt surface** (a
clear, thumb-reachable affordance for surfacing a required choice). All are **stateless,
parameterized, preview-able** shells; feature screens adopt them as they are built.

## 2. Context & background

- Story 0012 provides the theme + tokens these components style themselves from (no magic numbers
  — use the tokens).
- UX direction ([`../ux-principles.md`](../ux-principles.md)):
  - **Decisions come to the player** — a required choice must be an "unmissable, thumb-reachable
    prompt, not a small dialog the player has to hunt for." The **decision/prompt surface** here
    is the reusable primitive for that (used later by the game decision loop, EPIC-13).
  - Touch-first, ≥48dp targets, content descriptions; one coherent system.
- These components carry **no data or business logic** — they take content + event lambdas.

## 3. Scope

**In scope** (all in `:core:designsystem`, stateless + previewed light/dark):
- **Action hierarchy** — primary/secondary/tertiary button styles (thin wrappers over M3 buttons
  applying tokens + a consistent emphasis scale), including an icon+label variant.
- **List rows** — a standard list-item row (leading/trailing slots, title/supporting text) sized
  for touch.
- **Section chrome** — a top app bar wrapper and section headers.
- **State surfaces** — reusable loading, empty, and error components.
- **Decision/prompt surface** — the thumb-reachable prompt primitive: a bottom-anchored surface
  that presents a title/message and a set of choice actions, with a clear default focus and an
  accessible description; parameterized by content + `onChoice` lambdas.

**Out of scope**
- Card tile / full-bleed card view — **0014**.
- Adaptive-layout helpers, dynamic-type tuning, the component catalog — **0015**.
- Wiring any component to real data or navigation (owning epics do that; e.g. the game decision
  loop wires the prompt surface in EPIC-13).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0012:

- Requires 0012 merged (`:core:designsystem` + theme/tokens).
- No new dependencies expected beyond Compose/Material 3 already in the module.

## 5. Design & approach

```
core/designsystem/src/main/kotlin/magefree/designsystem/component/
├── Buttons.kt          # MagePrimaryButton / MageSecondaryButton / MageTextButton (+ icon variant)
├── ListRow.kt          # MageListRow (leading/trailing slots, title/supporting)
├── SectionChrome.kt    # MageTopAppBar wrapper + SectionHeader
├── StateViews.kt       # LoadingState / EmptyState / ErrorState (message + optional retry action)
└── prompt/
    └── DecisionPrompt.kt   # the thumb-reachable prompt surface + a DecisionPromptChoice model
```

- Every component styles itself from the **0012 theme + tokens** (colors from `colorScheme`,
  spacing/elevation/shape from tokens) — no hard-coded colors/dimensions.
- **`DecisionPrompt`**: a stateless composable taking a `title`, optional `message`, and a list of
  choices (`DecisionPromptChoice(label, contentDescription, emphasis)`) plus an `onChoice` lambda.
  It anchors to the lower area (thumb reach), gives the primary choice clear emphasis, and sets a
  merged content description so it is announced as one prompt. This is the primitive EPIC-13's
  targeting/choices UI renders into.
- **Accessibility**: content descriptions on interactive elements; ≥48dp targets; don't rely on
  color alone for state (pair color with text/icon).
- Each component ships **light + dark previews** covering its key states.

## 6. Implementation steps

1. Implement the button wrappers with a consistent emphasis scale over M3 buttons + tokens.
2. Implement `MageListRow` and the section chrome (app bar wrapper, section header).
3. Implement `LoadingState` / `EmptyState` / `ErrorState`.
4. Implement `DecisionPrompt` + `DecisionPromptChoice`, thumb-anchored and accessible.
5. Add light + dark previews for every component and its main states.
6. Add small unit/logic tests where there is pure logic (e.g. choice mapping); UI verified via
   previews (and opt-in instrumented tests if warranted).
7. `./gradlew check` green.

## 7. Testing & verification

- **Hermetic gate:** `./gradlew check` passes (`:core:designsystem` lint + any unit tests); no device.
- **Visual:** every component renders in light + dark previews across its states; the decision
  prompt reads as one accessible, thumb-reachable surface.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] `:core:designsystem` gains the action hierarchy, list row, section chrome, and state surfaces —
      all stateless, token-styled (no hard-coded colors/dimensions), with light + dark previews.
- [ ] A **`DecisionPrompt`** surface exists: thumb-reachable, clear primary emphasis, content +
      `onChoice` hoisted, accessible (merged description, ≥48dp) — the reusable prompt primitive.
- [ ] Components have content descriptions and ≥48dp targets; state is never color-only.
- [ ] `./gradlew check` passes hermetically; new deps (if any) pinned in the catalog; `:bridge` untouched.
- [ ] No card components, catalog, or adaptive helpers were added; no component wired to real data.

## 9. References

- [`../ux-principles.md`](../ux-principles.md) — decisions come to the player (the prompt surface), touch-first.
- [`AGENTS.md`](../../AGENTS.md) — stateless previewable Composables, accessibility.
- [`0012-design-system-module-and-theme-foundation.md`](0012-design-system-module-and-theme-foundation.md) — theme + tokens these components consume.
