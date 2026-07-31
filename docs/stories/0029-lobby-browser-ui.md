# 0029 — Lobby browser UI

- **Epic:** EPIC-06 — Lobby & Game Browser
- **Depends on:** 0028 (lobby model & data), 0018 (connect flow / feature module pattern), EPIC-03 (design system)
- **Status:** ready

## 1. Objective

Build the **`:feature:lobby`** module: the screen behind the home "Play" path where a connected
player browses open tables (and watchable games), sees players and table settings at a glance, and
filters/sorts to find something to play or watch. Read-only browse — joining/hosting is EPIC-07.

## 2. Context & background

- 0028 provides `LobbyRepository` (observable, refreshable `LobbySnapshot`: tables, users, game
  types + load/refresh/error state). This story is the UI over it — MVVM with immutable UI state.
- Design system (EPIC-03): `MageTheme`, list rows, section chrome, state views (loading/empty/error),
  buttons; **no hand-rolled styles**. UX (`../ux-principles.md`): legible at a glance,
  thumb-reachable, ≥48dp; the connection is the product, so reflect connection/refresh state clearly.
- Reaches the lobby from the shell's **Play** entry (0009 home hub); a table row's tap target is
  **detail/preview** here — the join action is EPIC-07 (show a disabled/"coming soon" affordance or
  route to a read-only detail, documented).
- Filtering/sorting is **client-side** over the snapshot (the server returns the full list).

## 3. Scope

**In scope** (all in `:feature:lobby`, MVVM, immutable `StateFlow<UiState>`):
- **Table list**: each row shows name, host, format/gameType, seats (filled/total), state, and the
  key flags (rated / passworded / limited / tournament) at a glance; loading / empty ("no open
  tables") / error (with retry) states via the design system.
- **Refresh**: pull-to-refresh (and/or a refresh action) driving `LobbyRepository.refresh()`;
  reflect refreshing state without clearing the current list (non-destructive).
- **Filter/sort**: client-side filters (e.g. format, hide-passworded, hide-full) and sort (e.g. by
  created time / players), applied over the snapshot; a clear "showing N of M" and reset.
- **Players at a glance**: a room-users count/summary (full roster detail can be minimal here).
- ViewModel exposing `StateFlow<LobbyUiState>`; stateless, previewable Composables (light + dark);
  ViewModel tests over load/refresh/filter/sort/empty/error via a fake repository.

**Out of scope**
- **Join / create / host / watch / spectate** actions and deck submission (**EPIC-07**) — this
  screen is read-only; the join affordance is disabled/deferred and documented.
- Table **detail** beyond the at-a-glance row (or a minimal read-only detail — keep it light).
- Finished matches / tournament / draft browsing.
- Wiring a live auto-refresh loop (manual/pull-to-refresh is the baseline; periodic refresh optional
  and off by default).

## 4. Design & approach

```
feature/lobby/
├── LobbyViewModel.kt (+ LobbyUiState, filter/sort model)   # observes LobbyRepository; client-side filter/sort
├── LobbyScreen.kt                                          # table list + refresh + filter/sort controls + states
├── TableRow.kt                                             # one TableSummary row (design-system list row)
└── LobbyFilters.kt (or inline)                             # filter/sort UI + state
```

- `LobbyUiState` = the filtered/sorted tables + counts + room-users summary + a
  loading/refreshing/error flag + the active filter/sort. The ViewModel maps `LobbySnapshot` +
  filter/sort → `LobbyUiState`; screens are stateless and previewable.
- Loading/empty/error use the design-system state views; refresh is non-destructive (keep the list,
  show a refresh indicator). Reuse `DecisionPrompt`/error surfaces for the error+retry path.
- Reachable from the shell's Play entry; the join action is a disabled/"next" affordance (EPIC-07).

## 5. Implementation steps

1. Create `:feature:lobby` (conventions: `magefree.android.library` + `.compose` + `.hilt`; depends
   on `:core:model`, the lobby repository, `:core:designsystem`); register in `settings.gradle.kts`
   (inside the Android guard).
2. `LobbyViewModel` + `LobbyUiState` over `LobbyRepository`; client-side filter/sort.
3. `LobbyScreen` + `TableRow` + filter/sort controls; loading/empty/error/refresh states via the
   design system; light+dark previews.
4. Wire the shell's Play entry to the lobby (minimal nav; keep 0008/0009 shell behavior intact).
5. ViewModel tests (Turbine) over load/refresh/filter/sort/empty/error via a fake repository; Compose
   previews compile.
6. `:feature:lobby:check` + `:app:testDebugUnitTest` green (host); `:app:assembleDebug` builds.

## 6. Testing & verification

- **Hermetic gate:** ViewModel tests for the snapshot→UiState mapping, filter/sort, and
  load/refresh/empty/error via a fake repository; `./gradlew check` green with no live bridge.
- **Live (opt-in):** with 0027/0028 wired and the reference server, the lobby shows game types and
  an empty-tables state, and pull-to-refresh works.

## 7. Acceptance criteria

- [ ] A connected player browses open tables with name/host/format/seats/state + rated/passworded/
      limited/tournament flags at a glance, built on the design system.
- [ ] Loading / empty / error(+retry) / non-destructive refresh states are distinct and legible;
      pull-to-refresh drives `LobbyRepository.refresh()`.
- [ ] Client-side filter + sort work over the snapshot, with a clear count and reset.
- [ ] Reachable from the shell Play entry; the **join** action is deferred/disabled (EPIC-07) — no
      join/watch here; 0008/0009 shell behavior unchanged.
- [ ] ViewModel tests + previews cover every state; `:feature:lobby:check` + `:app:testDebugUnitTest`
      + `:app:assembleDebug` green; prior suites green.

## 8. References

- [`0028-app-lobby-model-and-data.md`](0028-app-lobby-model-and-data.md) — the `LobbyRepository` this renders.
- [`0018-connect-and-sign-in-ui.md`](0018-connect-and-sign-in-ui.md) — the feature-module + design-system pattern to mirror.
- [`0009-home-hub-with-prominent-play-entry.md`](0009-home-hub-with-prominent-play-entry.md) — the Play entry this sits behind.
- [`../ux-principles.md`](../ux-principles.md) — legible at a glance; non-destructive refresh.
