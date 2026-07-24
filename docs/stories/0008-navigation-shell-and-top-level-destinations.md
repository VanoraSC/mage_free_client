# 0008 — Navigation shell & top-level destinations

- **Epic:** EPIC-02 — App Shell & Navigation
- **Depends on:** 0007
- **Status:** ready

## 1. Objective

Replace the placeholder root with a **navigation shell**: a Navigation-Compose host with
**type-safe routes** and the app's **top-level destinations** — Home/Play, Decks,
Profile/Social, Settings — as placeholder screens, reachable through an **adaptive** bottom
navigation bar (compact) ↔ navigation rail (medium/expanded). Content of each destination is a
stub; this story is about the shell and navigation, not the screens.

## 2. Context & background

- 0007 established `:app`, `MageTheme`, and `AppRoot` (the placeholder this story replaces with
  the nav host).
- Stack ([`AGENTS.md`](../../AGENTS.md)): **Navigation-Compose with type-safe routes** and
  adaptive layouts (phone ↔ tablet/foldable). Use Material 3 window size classes to switch
  between bottom bar and rail.
- UX ([`../ux-principles.md`](../ux-principles.md)): thumb-reachable primary navigation (bottom
  on phones), ≥48dp targets, content descriptions on nav items, one coherent shell.
- The **Arena-style** IA has a small set of top-level destinations with Home/Play foremost.

## 3. Scope

**In scope**
- A `NavHost` with type-safe routes (kotlinx.serialization route types) for the four top-level
  destinations, each a placeholder composable.
- A `TopLevelDestination` model (route, label, icon, content description) driving the nav UI.
- Adaptive chrome: `NavigationBar` at compact width, `NavigationRail` at medium/expanded, via
  `WindowSizeClass`.
- Correct selected-state and single-top / state-restoring navigation between top-level tabs.

**Out of scope**
- Home hub content and the Play CTA (0009).
- Connection-status surface (0010) and immersive game route (0011).
- Any real screen content, ViewModels, or data.
- Nested/detail navigation within a destination (added by the owning feature epics).

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](README.md#project-toolchain-baseline) and 0007:

- Requires 0007 merged (the `:app` module, Compose, `MageTheme`).
- Add to the catalog (pin current stable): `androidx.navigation:navigation-compose`,
  `androidx.compose.material3:material3-window-size-class` (or the `material3-adaptive*`
  artifacts), and `androidx.compose.material:material-icons-extended` if icons are needed.
- Android SDK + (for instrumented tests) an emulator/device, as in 0007.

## 5. Design & approach

```
app/src/main/kotlin/magefree/app/
├── navigation/
│   ├── TopLevelDestination.kt   # enum/sealed: route + label + icon + contentDescription
│   ├── MageNavHost.kt           # NavHost with type-safe routes -> placeholder screens
│   └── AppShell.kt              # Scaffold: adaptive NavigationBar/NavigationRail + NavHost
├── screens/                     # placeholder destination composables
│   ├── HomePlaceholderScreen.kt
│   ├── DecksPlaceholderScreen.kt
│   ├── ProfilePlaceholderScreen.kt
│   └── SettingsPlaceholderScreen.kt
└── AppRoot.kt                   # now hosts AppShell() (replaces the 0007 placeholder)
```

- **Routes:** `@Serializable` route objects (e.g. `data object HomeRoute`, `DecksRoute`,
  `ProfileRoute`, `SettingsRoute`) used with type-safe `composable<HomeRoute> { … }` and
  `navController.navigate(HomeRoute)`.
- **`TopLevelDestination`:** ordered list mapping each route to its label, selected/unselected
  icons, and a content description (accessibility). The nav UI iterates this list.
- **`AppShell`:** a `Scaffold`; compute `WindowSizeClass`; at `Compact` width render a bottom
  `NavigationBar`, otherwise a `NavigationRail` beside the content. Tab selection navigates with
  `launchSingleTop = true`, `restoreState = true`, and `popUpTo(startDestination) { saveState = true }`
  so tab state is preserved. Track the current destination from the back stack to set selection.
- `AppRoot` becomes `MageTheme { AppShell() }`.

## 6. Implementation steps

1. Add navigation + window-size-class (and icons if needed) to the catalog; add deps to `:app`.
2. Define the `@Serializable` routes and `TopLevelDestination` list.
3. Build `MageNavHost` (type-safe `composable<Route>` entries → placeholder screens).
4. Build `AppShell` with the adaptive bottom-bar/rail and correct selection + state handling;
   point `AppRoot` at it.
5. Add the four placeholder screens (each shows its name; ≥48dp, labelled).
6. Tests: a Compose UI test (instrumented or Robolectric if configured) that taps each
   destination and asserts the right placeholder shows and selection updates; a unit test for the
   `TopLevelDestination` list (routes unique, all have labels + content descriptions).
7. `./gradlew check` green; `:app:assembleDebug` builds.

## 7. Testing & verification

- **Hermetic gate:** unit test on the destination model; `./gradlew check` passes with no device.
- **UI (opt-in on device, or Robolectric if the project adopts it):** navigating between the four
  destinations shows the correct placeholder and updates the selected indicator; tab state is
  restored when returning to a tab.

```bash
./gradlew check
```

## 8. Acceptance criteria

- [ ] A `NavHost` with **type-safe** routes hosts four top-level destinations
      (Home/Play, Decks, Profile/Social, Settings) as placeholder screens.
- [ ] Navigation uses a bottom `NavigationBar` at compact width and a `NavigationRail` at
      medium/expanded, chosen via `WindowSizeClass`.
- [ ] Tab switching is single-top and restores per-tab state; the selected item reflects the
      current destination.
- [ ] Every nav item has a label and content description; targets are ≥48dp.
- [ ] `./gradlew check` passes hermetically; `:app:assembleDebug` builds; new deps pinned in the catalog.
- [ ] No home-hub content, connection status, immersive route, or real screen data were added.

## 9. References

- [`AGENTS.md`](../../AGENTS.md) — Navigation-Compose type-safe routes, adaptive layouts, accessibility.
- [`../ux-principles.md`](../ux-principles.md) — thumb-reachable nav, coherent shell.
- [`0007-android-app-module-scaffold.md`](0007-android-app-module-scaffold.md) — the module + `AppRoot` seam this replaces.
