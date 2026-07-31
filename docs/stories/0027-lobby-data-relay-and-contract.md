# 0027 — Lobby data relay & contract

- **Epic:** EPIC-06 — Lobby & Game Browser
- **Depends on:** 0005 (session bridge), 0006 (callback relay & mapper boundary), 0004 (`:protocol`)
- **Status:** ready

## 1. Objective

Give the bridge a **request/response lobby surface**: on the app's request it fetches the current
open tables, room users, and available game types from the pinned XMage server via `SessionImpl`,
maps the `mage.view.*` lobby shapes to app-schema `:protocol` messages at the single mapper
boundary, and replies. This is the bridge/contract half of the lobby browser; the app model +
network is 0028 and the UI is 0029.

## 2. Context & background

- **Browsing is poll/request-response in XMage** (verified via `javap` on `mage-common:1.4.60`):
  the lobby is read by calling `SessionImpl` methods, not by a server push. Relevant surface:
  - `getMainRoomId(): UUID` — the lobby room (already used by 0006's `ServerInfo`).
  - `getTables(roomId): Collection<mage.view.TableView>` — the open/active tables.
  - `getRoomUsers(roomId): Collection<mage.view.RoomUsersView>` — who's in the room.
  - `getGameTypes(): List<mage.view.GameTypeView>` — the available formats.
  - (`getFinishedMatches(roomId): Collection<MatchView>` exists too — **out of scope** here.)
  There is **no** table-list *push* `ClientCallbackMethod`, so the app **polls** (request +
  pull-to-refresh / periodic refresh), rather than subscribing. A live server-push refresh could be
  a later enhancement; request/response matches the server and is sufficient for browse.
- `mage.view.TableView` is rich: `tableId`, `tableName`, `controllerName` (host), `gameType`,
  `deckType`, `tableState` (`mage.constants.TableState`), `seats` (`List<SeatView>`), `seatsInfo`,
  `isTournament`, `skillLevel`, `isRated`, `isPassworded`, `isLimited`, `createTime`,
  `spectatorsAllowed`, `additionalInfoShort/Full` — the "settings at a glance" EPIC-06 wants.
- This **extends 0006's mapper boundary**: `mage.view.*` lobby types are read **only** inside
  `magefree.bridge.mapping`, and mapped to `:protocol` app-schema before leaving `:core:network`'s
  peer. This story adds the *bridge*-side mappers + request handling; the app-side mappers are 0028.
- Request/response follows 0006's `GetServerInfo`→`ServerInfo` shape (correlated by `requestId`),
  driven by the bound session in `SessionCoordinator`.

## 3. Scope

**In scope**
- **`:protocol` additions** (additive, extend the sealed types): request messages
  `GetTables`/`GetRoomUsers`/`GetGameTypes` (client) and reply messages `TableList`/`RoomUserList`/
  `GameTypeList` (server), plus the app-schema payload shapes they carry (e.g. `TableSummary`,
  `RoomUserSummary`, `GameTypeSummary`) with the browse-relevant `TableView` fields. Correlated by
  `requestId`.
- **Bridge mappers** in `magefree.bridge.mapping`: `TableView`→`TableSummary`,
  `RoomUsersView`→`RoomUserSummary`, `GameTypeView`→`GameTypeSummary` — pure, deterministic, the
  only place these `mage.view.*` types are read.
- **`XMageSession`/`UpstreamSession` additions**: `tables()`, `roomUsers()`, `gameTypes()` wrapping
  the blocking `SessionImpl` calls on `Dispatchers.IO` (against the session's main room id).
- **`SessionCoordinator` request handling**: on `GetTables`/`GetRoomUsers`/`GetGameTypes`, call the
  upstream, map, and reply with the correlated list message (empty list if not connected).
- Hermetic tests (fake upstream + mapper golden/round-trip) + an env-gated live IT (browse the
  reference server — the tables list is legitimately empty, so assert a successful, well-formed
  empty reply + non-error, plus `gameTypes` non-empty).

**Out of scope**
- The app-side domain model, network client, and mappers (**0028**) and the browser UI (**0029**).
- Any **join / create / watch / spectate** action (`joinTable`/`watchTable`/`createTable`) — those
  mutate state and belong to **EPIC-07**. This story is **read-only**.
- Finished-matches / tournament / draft browsing, and filtering/sorting (client-side, **0029**).
- Live server-push refresh of the table list (possible later enhancement).

## 4. Design & approach

```
protocol/src/main/kotlin/magefree/protocol/
└── LobbyMessages.kt          # Get{Tables,RoomUsers,GameTypes} (client) + {Table,RoomUser,GameType}List (server) + summaries
bridge/src/main/kotlin/magefree/bridge/mapping/
├── TableMapper.kt            # mage.view.TableView -> TableSummary
├── RoomUserMapper.kt         # mage.view.RoomUsersView -> RoomUserSummary
└── GameTypeMapper.kt         # mage.view.GameTypeView -> GameTypeSummary
bridge/src/main/kotlin/magefree/bridge/{session,xmage}/
└── (XMageSession/UpstreamSession + SessionCoordinator lobby request handling)
```

- Mappers are pure and covered by the golden-file/round-trip harness (0006) with in-memory
  `mage.view.*` fixtures. Map only the browse-relevant fields; normalise non-deterministic ones
  (e.g. `createTime`) for golden stability.
- `SessionCoordinator` handles each request against the **bound** session's main room id; a request
  with no bound/connected session replies an empty list (or a `ProtocolError`, pick one and
  document). Blocking `SessionImpl` calls run on `Dispatchers.IO`; results reach the socket via the
  existing outbound stream (no remoting-thread sends).

## 5. Implementation steps

1. Add the request/reply/summariy messages to `:protocol`; extend round-trip tests.
2. Implement the three bridge mappers (+ golden/round-trip tests with in-memory `TableView` etc.).
3. Add `tables()`/`roomUsers()`/`gameTypes()` to `XMageSession`/`UpstreamSession` (IO dispatcher).
4. Handle the three requests in `SessionCoordinator`, replying correlated list messages.
5. Hermetic tests (fake upstream scripts table/user/type lists → correct reply mapping).
6. `./scripts/dev gradle :bridge:check :protocol:check` green.
7. **Live:** against the reference server, `GetGameTypes` returns a non-empty list and `GetTables`
   returns a well-formed empty list (no error) — a `LobbyRelayIT`.

## 6. Testing & verification

- **Hermetic gate:** mapper tests (in-memory `mage.view.*` → asserted app-schema) + a WS
  `testApplication` flow: `GetTables`/`GetRoomUsers`/`GetGameTypes` → correct correlated replies via
  a fake upstream. `./scripts/dev gradle check` stays hermetic.
- **Live (opt-in, `XMAGE_SERVER`):** `LobbyRelayIT` logs in and browses the real main room —
  `gameTypes` non-empty, `tables` an empty-but-valid list.

## 7. Acceptance criteria

- [ ] `:protocol` gains the lobby request/reply/summary messages by **extending** the sealed types
      (round-trips pass); existing messages/`ProtocolJson`/`ProtocolVersion` untouched.
- [ ] `mage.view.TableView`/`RoomUsersView`/`GameTypeView` are read **only** in
      `magefree.bridge.mapping`; mapped summaries carry the browse-relevant fields.
- [ ] `GetTables`/`GetRoomUsers`/`GetGameTypes` reply correlated list messages sourced from the
      bound session; blocking calls run on `Dispatchers.IO`; no remoting-thread sends.
- [ ] Hermetic mapper + coordinator tests pass; the live `LobbyRelayIT` is env-gated and skipped by
      default; `./scripts/dev gradle :bridge:check :protocol:check` green.
- [ ] Read-only: no join/create/watch, no app-side model/UI here.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — `getTables`, `getRoomUsers`, `getGameTypes`, `getMainRoomId`.
- `../mage/Mage.Common/src/main/java/mage/view/TableView.java` — the browse payload.
- [`0006-callback-relay-and-golden-file-mapping-harness.md`](0006-callback-relay-and-golden-file-mapping-harness.md) — the mapper boundary + golden harness this extends.
- [`0005-session-bridge-connect-login-reconnect.md`](0005-session-bridge-connect-login-reconnect.md) — `SessionCoordinator` request/response.
