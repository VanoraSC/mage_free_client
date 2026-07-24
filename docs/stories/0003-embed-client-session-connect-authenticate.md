# 0003 — Embed the XMage client session & connect/authenticate

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** 0002
- **Status:** ready

## 1. Objective

Make the bridge able to **connect to and authenticate against a real XMage server** by
reusing XMage's own client session library (`org.mage:mage-common`). Implement the
`MageClient` callback sink, build a `Connection`, drive `SessionImpl.connectStart(...)`, and
prove it by fetching the **main room id**. This is JVM-only plumbing — **no WebSocket, no
app-facing protocol, and no view mapping yet** (those are 0004–0006).

## 2. Context & background

The bridge reuses the real client session so it inherits XMage's connect/auth/keepalive/
reconnect and Java-serialization handling correct-by-construction (see
[`../architecture.md`](../architecture.md)). This dependency is **bridge-only**; it must
**never** reach an Android module ([`AGENTS.md`](../../AGENTS.md)).

Verified upstream facts (`../mage`, `org.mage:*:1.4.60`):

- **`mage.interfaces.MageClient`** — the sink `SessionImpl` calls. It `extends CallbackClient`:
  ```java
  interface MageClient extends CallbackClient {
      MageVersion getVersion();
      void connected(String message);
      void disconnected(boolean askToReconnect, boolean keepMySessionActive);
      void showMessage(String message);
      void showError(String message);
  }
  interface CallbackClient {          // mage.interfaces.callback.CallbackClient
      void onNewConnection();
      void onCallback(ClientCallback callback);
  }
  ```
- **`mage.remote.SessionImpl`** — `public SessionImpl(MageClient client)`;
  `public synchronized boolean connectStart(Connection connection)`;
  `public UUID getMainRoomId()` (calls `server.serverGetMainRoomId()` when connected);
  `connectStop(boolean askForReconnect, boolean keepMySessionActive)` to disconnect.
- **`mage.remote.Connection`** — POJO: `setHost`, `setPort`, `setUsername`, `setPassword`,
  `setUserIdStr`, `setUserData(UserData)`. `getURI()` builds the bisocket URI. For a
  non-admin login leave `adminPassword` null.
- **Version handshake is mandatory.** `connectStart` fetches `server.getServerState()` and
  throws `MageVersionException` unless `client.getVersion()` **equals** the server version.
  Because the bridge depends on the **same `mage-common:1.4.60`** the server runs,
  `new MageVersion(SomeBridgeClass.class)` yields the matching version automatically — do
  **not** hand-roll a version.
- **Auth:** with the local server's `authenticationActivated="false"` (from 0002),
  `connectUser` accepts any username without registration. `connectStart` also calls
  `connectSetUserData(...)` for non-admin users — supply a non-null `UserData`
  (`mage.players.net.UserData`; verify the correct factory/defaults) to avoid a null being
  rejected.
- **Dependency source:** 0002's `mvn install` publishes `mage-common` and its transitive
  artifacts (`mage`, jboss-remoting, log4j, …) to the local Maven repo (`~/.m2`). The bridge
  consumes them via `mavenLocal()`.

## 3. Scope

**In scope**
- Add `mavenLocal()` + `org.mage:mage-common:1.4.60` to the `:bridge` build.
- `BridgeMageClient` implementing `MageClient`/`CallbackClient` (captures connected/
  disconnected/messages; for now just logs/records callbacks).
- `XMageConnection` builder and an `XMageSession` wrapper around `SessionImpl` exposing
  `connect()`, `mainRoomId()`, `disconnect()`.
- An env-gated integration test that connects, authenticates, fetches the main room id, and
  disconnects cleanly.

**Out of scope**
- Any WebSocket or app-facing protocol/schema (0004).
- Wiring a per-user session lifecycle over WebSocket (0005).
- Relaying/decoding `ClientCallback` payloads or mapping `mage.view.*` (0006). Here,
  `onCallback` only records/logs that a callback arrived.
- Reconnection tuning beyond what `SessionImpl` does by default.

## 4. Design & approach

```
bridge/src/main/kotlin/magefree/bridge/xmage/
├── BridgeMageClient.kt   # implements MageClient + CallbackClient
├── XMageConnection.kt    # host/port/username/password -> mage.remote.Connection
└── XMageSession.kt       # wraps SessionImpl(client): connect(), mainRoomId(), disconnect()

bridge/src/test/kotlin/magefree/bridge/xmage/
└── ConnectAuthenticateIT.kt   # env-gated (XMAGE_SERVER) end-to-end connect+auth
```

- **`BridgeMageClient`**:
  - `getVersion()` → `MageVersion(BridgeMageClient::class.java)`.
  - `connected(msg)` / `disconnected(...)` → update an exposed state (e.g. a `@Volatile`
    flag or a callback) and log.
  - `showMessage` / `showError` → log.
  - `onNewConnection()` → log.
  - `onCallback(cb)` → record count / last method and log; **no decoding yet**. (Note:
    `ClientCallback.getData()` is compressed until `decompressData()` — leave that for 0006.)
- **`XMageConnection.build(host, port, username, password)`** → a `Connection` with
  `userIdStr = UUID.randomUUID().toString()` and a default `UserData`.
- **`XMageSession`**: constructs `SessionImpl(bridgeMageClient)`; `connect(connection)` calls
  `connectStart` on `Dispatchers.IO` and returns success/failure; `mainRoomId()` returns
  `session.getMainRoomId()`; `disconnect()` calls `connectStop(false, false)`. Blocking
  remoting calls must run off the main/event threads (structured concurrency;
  `Dispatchers.IO`).
- Keep this a plain library surface for now (no Ktor route). 0005 puts it behind the
  WebSocket, one `XMageSession` per connected app client.

## 5. Implementation steps

1. Add `repositories { mavenLocal() }` and `implementation("org.mage:mage-common:1.4.60")` to
   `bridge/build.gradle.kts` (pin the version via the catalog). Confirm it resolves after
   0002's `mvn install`.
2. Implement `BridgeMageClient` (all `MageClient`/`CallbackClient` methods; version from
   `mage-common`).
3. Implement `XMageConnection.build(...)` (set host/port/username/password/userIdStr/UserData).
4. Implement `XMageSession` (construct `SessionImpl`, `connect`/`mainRoomId`/`disconnect`,
   IO dispatcher).
5. Write `ConnectAuthenticateIT` (env-gated on `XMAGE_SERVER`): parse target via 0002's
   `XMageServerTarget`; connect with a random username; assert `connect()` succeeded, `MageClient.connected(...)`
   fired, and `mainRoomId()` is non-null; then `disconnect()` and assert disconnected.
6. Verify `./gradlew check` passes with the IT **skipped** (no env var).
7. Manually verify against a running local server (0002 script) with
   `XMAGE_SERVER=localhost:17171`.

## 6. Testing & verification

- **Hermetic gate:** `./gradlew check` passes; `ConnectAuthenticateIT` is *skipped* when
  `XMAGE_SERVER` is unset.
- **Live (opt-in):**
  ```bash
  ./scripts/xmage-server/run-local-server.sh        # from 0002; wait for "Started"
  XMAGE_SERVER=localhost:17171 ./gradlew :bridge:test --tests '*ConnectAuthenticateIT'
  ```
  Connects, authenticates (auth disabled locally), returns a non-null main room id, disconnects.

## 7. Acceptance criteria

- [ ] `:bridge` compiles against `org.mage:mage-common:1.4.60` resolved from `mavenLocal()`;
      the version is pinned in the catalog.
- [ ] `BridgeMageClient` implements the full `MageClient`/`CallbackClient` surface and reports
      a `MageVersion` derived from `mage-common` (no hand-rolled version).
- [ ] Against a running local server, the IT connects, `connected(...)` fires, and
      `mainRoomId()` returns a non-null `UUID`; `disconnect()` leaves the session cleanly closed.
- [ ] The IT is env-gated and **skipped** when `XMAGE_SERVER` is unset; `./gradlew check`
      stays green and hermetic.
- [ ] No decoding of callback payloads and no `mage.view.*` mapping is done here.
- [ ] The `org.mage` dependency exists **only** in `:bridge`; no Android module references it.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/interfaces/MageClient.java` and
  `.../interfaces/callback/CallbackClient.java` — the sink to implement.
- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — `connectStart`,
  `getMainRoomId`, `connectStop`.
- `../mage/Mage.Common/src/main/java/mage/remote/Connection.java` — connection POJO.
- `../mage/Mage.Common/src/main/java/mage/utils/MageVersion.java` — version constructor.
- [`0002-local-xmage-server-harness.md`](0002-local-xmage-server-harness.md) — server + `XMageServerTarget`.
- [`../architecture.md`](../architecture.md) — why reuse of `SessionImpl` is the correctness anchor.
