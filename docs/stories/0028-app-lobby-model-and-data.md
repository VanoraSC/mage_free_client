# 0028 — App lobby model & data

- **Epic:** EPIC-06 — Lobby & Game Browser
- **Depends on:** 0027 (lobby relay & contract), 0016 (`:core:model`/`:core:network`), 0017 (`ConnectionRepository`)
- **Status:** ready

## 1. Objective

Give the app the **domain model and data layer** for the lobby: `:core:model` types for tables,
room users, and game types; a `:core:network` lobby client that requests them over the bridge
(0027's `:protocol` messages) and maps the wire summaries into `:core:model`; and a `LobbyRepository`
that exposes an observable, refreshable snapshot. UI is 0029.

## 2. Context & background

- 0027 defined the bridge request/reply contract (`GetTables`/`TableList` etc.) and confirmed the
  lobby is **request/response** (the app requests + refreshes; there is no server push for the table
  list). This story consumes that contract.
- Reuse the boundary discipline (0016): the wire `:protocol` summaries stay inside `:core:network`'s
  mapper; the app/UI sees only `:core:model` lobby types. `mage.view.*` never reaches the app.
- Follows the `BridgeClient` seam (0016): the lobby request/response rides the **same** authenticated
  session the app already holds via `ConnectionRepository`; a lobby request is meaningful only while
  connected.
- "Fakes are recordings" (AGENTS): a `FakeLobbyClient`/scripted `BridgeClient` replays real
  `:protocol` lobby sequences for tests.

## 3. Scope

**In scope**
- **`:core:model`** lobby types: `LobbyTable` (id, name, host, format/gameType, state, seats summary,
  rated/passworded/limited/tournament flags, skill, createdAt), `RoomUser` (name + minimal status),
  `GameFormat`/`GameType` — pure domain, no wire/Android types.
- **`:core:network`** lobby access: extend `BridgeClient` (or a focused `LobbyClient` over the same
  transport) with `suspend fun tables()/roomUsers()/gameTypes()` performing 0027's request/response;
  DTO(`:protocol` summary)→`:core:model` mappers in the single mapper boundary; a `FakeLobbyClient`.
- **`LobbyRepository`** exposing an observable, **refreshable** snapshot
  (`StateFlow<LobbySnapshot>` = tables + users + types + a load/refresh state), with `refresh()` and
  optional polling cadence (opt-in; default manual/pull-to-refresh). Errors surface as state (an
  error field), not thrown. Gated on an active connection.
- Turbine tests over the fake through load / refresh / empty / error paths, plus the DTO→model
  mapper.

**Out of scope**
- The browser **UI**, filters/sorting presentation (**0029**).
- Any join/create/watch action (**EPIC-07**).
- Finished matches / tournaments / draft (later).
- Live server-push table updates.

## 4. Design & approach

- **`LobbyClient`** (interface + real impl over the `BridgeClient` transport + `FakeLobbyClient`):
  each call sends a `GetX` and awaits the correlated `XList`, mapping summaries → `:core:model`. The
  `:protocol` types stay in the mapper; the interface speaks only `:core:model`.
- **`LobbyRepository`** (`@Singleton`, injected dispatchers/scope): holds a
  `StateFlow<LobbySnapshot>`; `refresh()` fetches tables/users/types (concurrently where sensible),
  reducing into the snapshot; models load/refreshing/loaded/error as state. It does **not** own the
  session — it uses the connected `BridgeClient`; when disconnected it exposes an empty/idle snapshot.
- Keep sorting/filtering out of the repository — expose the raw snapshot; 0029 does presentation.

## 5. Implementation steps

1. Add the `:core:model` lobby types.
2. Add `LobbyClient` (interface + real over `BridgeClient` + `FakeLobbyClient`) with the DTO→model
   mappers; unit-test the mappers.
3. Implement `LobbyRepository` (observable snapshot + `refresh()` + error-as-state), injected.
4. Turbine tests: load→loaded, refresh, empty tables, error path, disconnected→idle — via the fake.
5. `:core:network:check` + `:app:testDebugUnitTest` green (host); `:app:assembleDebug` builds.

## 6. Testing & verification

- **Hermetic gate:** Turbine/state-machine tests over `FakeLobbyClient` for every path; DTO→model
  mapper tests; no live bridge.
- **Live (opt-in):** with the bridge + reference server, `refresh()` yields a non-empty `gameTypes`
  and an empty-but-valid `tables` snapshot.

## 7. Acceptance criteria

- [ ] `:core:model` has pure lobby types (no `:protocol`/`mage.view.*`/Android); `:core:network`
      exposes `tables()/roomUsers()/gameTypes()` mapping 0027's summaries into them.
- [ ] `LobbyRepository` exposes an observable, refreshable `LobbySnapshot` with load/refresh/error
      **as state** (never thrown); meaningful only while connected, idle otherwise.
- [ ] `:protocol` wire summaries stay inside `:core:network`'s mapper — the repository/UI see only
      `:core:model`.
- [ ] Turbine tests cover load/refresh/empty/error/disconnected via the fake;
      `:core:network:check` + `:app:testDebugUnitTest` + `:app:assembleDebug` green; prior suites green.
- [ ] No UI, no join/watch, no filtering/sorting logic here.

## 8. References

- [`0027-lobby-data-relay-and-contract.md`](0027-lobby-data-relay-and-contract.md) — the request/reply contract this consumes.
- [`0016-app-network-layer-and-session-client.md`](0016-app-network-layer-and-session-client.md) — the `BridgeClient` seam + mapper discipline.
- [`0017-connection-repository-and-live-status-wiring.md`](0017-connection-repository-and-live-status-wiring.md) — the connected-session context.
- [`AGENTS.md`](../../AGENTS.md) — module boundaries, error-as-state, fakes-are-recordings.
