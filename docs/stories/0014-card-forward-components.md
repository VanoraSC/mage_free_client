# 0014 — Card-forward components

- **Epic:** EPIC-03 — Design System & Theming
- **Depends on:** 0012
- **Status:** ready

## 1. Objective

Build the design system's **card-forward components** — the **card tile** (a compact,
tappable card representation) and the **full-bleed card inspection view** (a large, readable,
full-detail surface) — plus the **tap-to-peek / long-press** inspection interaction pattern.
These are **parameterized, stateless shells with placeholder inputs**; real card data (oracle
text, art, rulings, in-game modifications) is wired in EPIC-10 and gameplay epics.

## 2. Context & background

- Story 0012 provides the theme + tokens; card components style from them.
- UX direction ([`../ux-principles.md`](../ux-principles.md)):
  - **Card inspection is a first-class, full-bleed surface** — "tapping a card should give a
    large, readable, full-detail view … because the card art thumbnail alone is unreadable at
    phone size."
  - **Card image & static data are large** — components must accept an image slot that the caller
    backs with a real disk-cache strategy (Coil) later; the shell must degrade gracefully while
    loading / when art is absent (placeholder).
  - Hover doesn't exist — provide a **touch equivalent**: tap opens the full view, long-press
    peeks; every action has an obvious tap path.
- These components hold **no data source**; they take a small view model of display fields +
  event lambdas.

## 3. Scope

**In scope** (all in `:core:designsystem`, stateless + previewed light/dark):
- A **`CardTile`** — compact card representation (art slot + name/cost/type overlay or caption),
  tappable, with a loading/placeholder state for missing art; ≥48dp effective target.
- A **`FullCardView`** — full-bleed inspection surface: large art, name, oracle/rules text,
  and slots for "current modifications" and "activatable abilities" (rendered from parameters,
  empty by default); scrollable/readable at phone size, with a clear close/back affordance.
- A defined **image slot** contract (a `@Composable` art slot or an image-model parameter) so the
  caller supplies Coil-backed art later without changing the component.
- The **inspection interaction pattern**: tap → open `FullCardView`; long-press → peek — expressed
  as reusable modifiers/handlers or documented usage, with tap as the guaranteed floor.

**Out of scope**
- Real card data, the card database/search, and actual image loading/caching (Coil wiring) — EPIC-10.
- In-game card state (tapped/attacking/counters) beyond generic parameter slots — gameplay epics.
- The component catalog and adaptive helpers — **0015**.

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0012:

- Requires 0012 merged (`:core:designsystem` + theme/tokens).
- Keep the image dependency **abstract** here (a slot/param) — do **not** add Coil in this story;
  its disk-cache strategy is introduced with real card art in EPIC-10. If a preview needs a
  stand-in, use a solid/placeholder painter, not a network image.

## 5. Design & approach

```
core/designsystem/src/main/kotlin/magefree/designsystem/card/
├── CardDisplay.kt      # small display model: name, cost/type text, an art slot / image key (placeholder-friendly)
├── CardTile.kt         # compact tappable tile; art slot + caption/overlay; loading/placeholder state
├── FullCardView.kt     # full-bleed inspection surface: art + oracle text + modifications/abilities slots + close
└── CardInspect.kt      # tap-to-open / long-press-to-peek interaction helpers (modifier/handlers)
```

- **Image as a slot:** `CardTile`/`FullCardView` take a `art: @Composable (Modifier) -> Unit`
  slot (or an opaque image key + a caller-provided loader) with a built-in placeholder, so the
  design system has **no image-loading dependency**; EPIC-10 supplies a Coil-backed slot.
- **`FullCardView`** is the readable surface: large art region, name/typeline, scrollable oracle
  text, and optional sections for current modifications and activatable abilities (each a
  parameter list, empty by default). A prominent, accessible close/back control.
- **Interaction:** `CardInspect` exposes a `Modifier.cardInspectable(onTap, onLongPressPeek)` (or
  equivalent) so any card surface gets tap-to-open + long-press-to-peek with tap as the floor.
- **Accessibility:** the tile has a content description summarizing the card; the full view is
  navigable and its close control labelled; touch targets ≥48dp.
- Light + dark previews for the tile (with and without art) and the full view (with sample text).

## 6. Implementation steps

1. Define `CardDisplay` (minimal display fields) and the art-slot/placeholder contract.
2. Implement `CardTile` (art slot + caption/overlay + loading/placeholder), tappable.
3. Implement `FullCardView` (art + typeline + scrollable oracle text + modifications/abilities
   slots + close control), readable at phone size.
4. Implement `CardInspect` tap/long-press helpers (tap guaranteed; long-press peek as accelerator).
5. Add light + dark previews covering art-present, art-missing, and a text-heavy card.
6. `./gradlew check` green.

## 7. Testing & verification

- **Hermetic gate:** `./gradlew check` passes (`:core:designsystem` lint + any unit tests); no device.
- **Visual:** tile and full view render in light + dark, including the art-missing placeholder and
  a long-oracle-text card that stays readable/scrollable.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] `CardTile` (compact, tappable, art slot + placeholder) and `FullCardView` (full-bleed,
      readable, close control, modification/ability slots) exist in `:core:designsystem`,
      stateless and token-styled, with light + dark previews.
- [ ] Card art is an abstract **slot/parameter** with a built-in placeholder — **no image-loading
      dependency (no Coil)** is added in this story.
- [ ] A tap-to-open / long-press-to-peek pattern is provided, with tap as the guaranteed path;
      components are accessible (content descriptions, ≥48dp, labelled close).
- [ ] `./gradlew check` passes hermetically; no new deps beyond Compose; `:bridge` untouched.
- [ ] No real card data, database/search, or image caching was added.

## 9. References

- [`../ux-principles.md`](../ux-principles.md) — card inspection as a first-class full-bleed surface; touch equivalents for hover; card art caching.
- [`AGENTS.md`](../../AGENTS.md) — Coil with a real disk-cache strategy (consumed later, EPIC-10), stateless Composables, accessibility.
- [`0012-design-system-module-and-theme-foundation.md`](0012-design-system-module-and-theme-foundation.md) — theme + tokens.
