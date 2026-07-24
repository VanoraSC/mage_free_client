# 0009 — Home hub with prominent "Play" entry

- **Epic:** EPIC-02 — App Shell & Navigation
- **Depends on:** 0008
- **Status:** ready

## 1. Objective

Turn the Home destination into the **Arena-style hub**: a screen whose dominant, unmissable,
**thumb-reachable** element is a primary **Play** call-to-action, with secondary entries to the
other parts of the app. Actions are **stubs** (callbacks/no-ops) — the real matchmaking/lobby is
EPIC-06. This story is about the home layout and the primacy of "Play," not the play flow.

## 2. Context & background

- 0008 provides the navigation shell and a `HomePlaceholderScreen` at the Home destination; this
  story replaces that placeholder with the real hub content.
- UX ([`../ux-principles.md`](../ux-principles.md)): **primary actions live in the lower third**
  (thumb reach); progressive disclosure; ≥48dp targets; one coherent visual system. The home hub
  is the single most important "get me into a game" surface — Play must be the visual priority.
- The **Arena** organizing principle borrowed here (no IP): a home screen centered on a large,
  obvious path to play, with lighter secondary navigation around it.

## 3. Scope

**In scope**
- A `HomeScreen` composable: a prominent primary **Play** button/card in the lower third, plus
  secondary entries (e.g. Decks, Profile, Settings, or "continue"/recent placeholders).
- Stateless and preview-able (light + dark previews); state hoisted, events emitted via
  callbacks (`onPlayClick`, `onDecksClick`, …) wired to no-ops/navigation stubs for now.
- A thin `HomeViewModel` **only if** trivial UI state is needed (otherwise keep it stateless); no
  data sources.

**Out of scope**
- Real "play" behavior, game modes, matchmaking, or lobby (EPIC-06/07).
- Connection-status surface (0010) and immersive game (0011).
- The finalized visual design/tokens (EPIC-03) — use the minimal `MageTheme`.

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0007/0008:

- Requires 0008 merged (nav shell + Home destination).
- No new dependencies expected beyond Compose/Material 3 already present.

## 5. Design & approach

```
app/src/main/kotlin/magefree/app/
├── screens/HomeScreen.kt        # the hub (replaces HomePlaceholderScreen)
└── (optional) home/HomeViewModel.kt
```

- **Layout:** a `Scaffold`/`Column` where the visual weight is bottom-anchored — the **Play**
  action sits in the lower third and is the largest, highest-emphasis element (filled/tonal
  button or a large card). Secondary entries are smaller and above/around it. Respect insets and
  the nav chrome from 0008.
- **State/events:** `HomeScreen(state, onPlayClick, onDecksClick, onProfileClick, onSettingsClick)`
  — stateless; the shell passes navigation lambdas. `onPlayClick` is a stub (e.g. logs / TODO
  marker) until EPIC-06.
- **Accessibility:** Play has a clear content description and ≥48dp target; the screen is usable
  one-handed.
- **Previews:** `@Preview` in light and dark.

## 6. Implementation steps

1. Implement `HomeScreen(...)` with the bottom-weighted Play primacy and secondary entries;
   stateless with hoisted callbacks; light + dark previews.
2. Wire it into the Home destination in `MageNavHost`, passing navigation lambdas (Play → stub).
3. (Optional) add a minimal `HomeViewModel` if any UI state is warranted; otherwise omit.
4. Tests: a Compose UI test asserting the Play action exists, is the emphasized element, has a
   content description, and invokes `onPlayClick` when tapped.
5. `./gradlew check` green; `:app:assembleDebug` builds.

## 7. Testing & verification

- **UI test:** the Play CTA is present, labelled, ≥48dp, and its callback fires on tap; secondary
  entries route to their stubs.
- **Hermetic gate:** `./gradlew check` passes; previews render.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] The Home destination shows a hub whose **primary, most-emphasized, thumb-reachable** element
      is the **Play** action, with secondary entries present.
- [ ] `HomeScreen` is stateless/preview-able with hoisted callbacks; Play/secondary events are
      wired to navigation or stubs (no real play behavior).
- [ ] Play has a content description and a ≥48dp target; light + dark previews exist.
- [ ] A UI test verifies Play's presence, prominence, and callback.
- [ ] `./gradlew check` passes hermetically; `:app:assembleDebug` builds.
- [ ] No matchmaking/lobby behavior, connection status, or immersive route were added.

## 9. References

- [`../ux-principles.md`](../ux-principles.md) — primary actions in the lower third; Arena-style home.
- [`AGENTS.md`](../../AGENTS.md) — stateless previewable Composables, accessibility.
- [`0008-navigation-shell-and-top-level-destinations.md`](0008-navigation-shell-and-top-level-destinations.md) — the Home destination this fills.
