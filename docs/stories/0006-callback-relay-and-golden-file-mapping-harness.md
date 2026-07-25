# 0006 — Callback relay & mapping / golden-file test harness

- **Epic:** EPIC-01 — Bridge & Server Integration
- **Depends on:** 0005
- **Status:** ready

## 1. Objective

Complete the Epic 1 foundation: a **generic relay** of server→client pushes
(`ClientCallback`) that decompresses, **maps `mage.view.*` → app schema**, and forwards over
the WebSocket; the **client→server request/response plumbing** back; and the
**`mage.view.*`→schema mapper boundary** plus the **golden-file test infrastructure** that
guards it. Proven end-to-end on **one sample push** (a chat message) and **one relayed call**
(server info). Per-feature mappers (lobby, deck, game) are added by their own epics on top of
this machinery.

## 2. Context & background

- This builds the "single coupling surface + golden-file drift detector" described in
  [`../architecture.md` → Keeping in sync with upstream](../architecture.md). The mapper is
  the **only** place `mage.view.*` shapes are read; everything above the mapper (and the whole
  app) sees only the `:protocol` schema.
- Server pushes arrive via `BridgeMageClient.onCallback(ClientCallback)` (0003/0005). The
  payload is **compressed until decompressed**: call `clientCallback.decompressData()` **before**
  `getData()` (which then returns a `mage.view.*` object whose type depends on
  `clientCallback.getMethod()` — a `ClientCallbackMethod`).
- Sample push grounding (`../mage`): `ClientCallbackMethod.CHATMESSAGE` → data is
  `mage.view.ChatMessage` (fields: `username`, `time: Date`, `message`, `color: MessageColor`,
  `messageType: MessageType {TALK, WHISPER_FROM, WHISPER_TO, …}`; public constructors and
  getters — so it can be built in-memory for hermetic tests).
- Sample call grounding: `SessionImpl.getMainRoomId(): UUID`, `getServerState()` (has the
  server version), `getRoomChatId(roomId): Optional<UUID>`, `joinChat(chatId)`,
  `sendChatMessage(chatId, text)`.
- Reuse 0005's **channel/flow hand-off**: mapped messages are pushed onto the same per-session
  outbound stream; never send to the socket from a remoting thread.
- This story **extends** the 0004 sealed protocol types (no forking).

## 3. Scope

**In scope**
- The generic relay pipeline: `onCallback` → `decompressData()` → dispatch by
  `ClientCallbackMethod` → mapper → `:protocol` message → outbound stream.
- A `mapping` boundary (`ViewMapper`/`CallbackMapper`) with the **chat** mapping as the sample;
  unmapped methods are logged and dropped (downstream epics register more).
- Client→server request/response plumbing, demonstrated by `GetServerInfo` → `ServerInfo`
  (correlated via `requestId`). Game `sendPlayer*` calls reuse this pattern later.
- The `:protocol` additions: `ChatEvent`, `GetServerInfo`, `ServerInfo`.
- **Golden-file test harness**: hermetic mapper tests (in-memory `mage.view.*` → asserted
  golden JSON, with a regenerate switch) plus an env-gated live test that maps a **real**
  server-produced callback and asserts the same golden.

**Out of scope**
- The full chat feature/UX (EPIC-16) — chat here is only the mapper/relay *sample*.
- Lobby/table/deck/game mappers and messages (their epics), and any game `sendPlayer*`
  decisions.
- `GameView` delta/diff strategy (open question #7 in [`../architecture.md`](../architecture.md)).

## 4. Design & approach

**Protocol additions** (`:protocol`, extend the sealed types):

```kotlin
@Serializable @SerialName("chat_event")
data class ChatEvent(
    val text: String,
    val username: String?,
    val timestampEpochMs: Long,
    val kind: ChatKind,               // TALK, WHISPER_IN, WHISPER_OUT, GAME, STATUS, ...
    val requestId: String? = null,
) : ServerMessage
enum class ChatKind { TALK, WHISPER_IN, WHISPER_OUT, GAME, STATUS, USER }

@Serializable @SerialName("get_server_info")
data class GetServerInfo(val requestId: String? = null) : ClientMessage

@Serializable @SerialName("server_info")
data class ServerInfo(val serverVersion: String, val mainRoomId: String?, val requestId: String? = null) : ServerMessage
```

**Bridge structure**

```
bridge/src/main/kotlin/magefree/bridge/
├── mapping/
│   ├── CallbackMapper.kt     # ClientCallback -> ServerMessage? (dispatch by method)
│   └── ChatMessageMapper.kt  # mage.view.ChatMessage -> ChatEvent
├── session/
│   └── (relay wired into XMageUpstreamSession/SessionCoordinator from 0005)
bridge/src/test/kotlin/magefree/bridge/mapping/
├── GoldenFiles.kt            # assertMatchesGolden(name, actualJson); UPDATE_GOLDEN regenerate
├── ChatMessageMapperTest.kt  # hermetic: build ChatMessage -> assert golden
└── CallbackRelayIT.kt        # env-gated: real CHATMESSAGE -> map -> assert same golden
bridge/src/test/resources/golden/mapping/
└── chat_talk.json            # committed expected output
```

- **`CallbackMapper.map(cb: ClientCallback): ServerMessage?`**:
  1. `cb.decompressData()`.
  2. `when (cb.method) { CHATMESSAGE -> ChatMessageMapper.map(cb.data as ChatMessage); else -> null }`.
  3. `null` → the relay logs "unmapped callback: <method>" and drops it (downstream epics add
     cases). Never throw on an unmapped method.
- **`ChatMessageMapper`**: `ChatMessage` → `ChatEvent` (`text=message`, `username`,
  `timestampEpochMs=time.time`, `kind` from `MessageType`). Deterministic and pure.
- **Relay wiring** (extends 0005): `BridgeMageClient.onCallback` → `CallbackMapper.map` →
  push non-null result onto the per-session outbound flow → `sendSerialized`. Keep the
  decompress+map work off the socket thread; use the existing channel hand-off.
- **Request/response** (`SessionCoordinator`, extends 0005): on `GetServerInfo`, call the
  upstream (`getServerState().version`, `getMainRoomId()`), reply
  `ServerInfo(serverVersion, mainRoomId, requestId = req.requestId)`. This is the generic
  client→server call shape; later `sendPlayer*`-style stories follow it.

**Golden-file harness** (the drift detector):
- **Hermetic tier (default gate):** construct `mage.view.*` inputs in-memory (we depend on
  `mage-common`), run the mapper, serialize with `ProtocolJson.json`, and assert equality with
  a committed golden file. `UPDATE_GOLDEN=1` regenerates goldens intentionally. If an upstream
  field/shape changes, constructing the view or the assertion breaks **loudly at the mapper** —
  exactly the intended blast radius.
- **Live tier (opt-in, `XMAGE_SERVER`):** `CallbackRelayIT` logs in (0005), gets the main room
  chat (`getMainRoomId`→`getRoomChatId`→`joinChat`), `sendChatMessage(...)`, receives the real
  `CHATMESSAGE` callback, maps it, and asserts it matches the **same** golden — proving the
  in-memory fixtures match reality. Use fixed/normalized fields (e.g., stub the timestamp) so
  the golden stays deterministic.

## 5. Implementation steps

1. Add `ChatEvent`/`ChatKind`, `GetServerInfo`, `ServerInfo` to `:protocol`; extend round-trip
   tests.
2. Implement `ChatMessageMapper` and `CallbackMapper` (decompress + dispatch + drop-unmapped).
3. Wire the relay into the 0005 session path: `onCallback` → map → per-session outbound flow;
   ensure the work runs off the socket thread via the existing channel.
4. Handle `GetServerInfo` in `SessionCoordinator`, replying `ServerInfo` correlated by `requestId`.
5. Build the golden harness (`GoldenFiles.assertMatchesGolden` + `UPDATE_GOLDEN`), add
   `chat_talk.json`, and write `ChatMessageMapperTest` (hermetic).
6. Write `CallbackRelayIT` (env-gated) doing the real chat round-trip and asserting the same golden.
7. `./scripts/dev gradle check` green (hermetic mapper + protocol tests run; live test skipped without env var).

## 6. Testing & verification

- **Hermetic (default gate):** protocol round-trips; `ChatMessageMapperTest` maps an in-memory
  `ChatMessage` and matches `chat_talk.json`; a WS `testApplication` flow shows `GetServerInfo`
  → `ServerInfo`. No XMage server needed.
- **Live (opt-in):**
  ```bash
  ./scripts/dev up xmage-server
  XMAGE_SERVER=xmage-server:17171 ./scripts/dev gradle :bridge:test --tests '*CallbackRelayIT'
  ```
  A real chat message flows server → `SessionImpl` → relay → mapper → WebSocket and matches the
  committed golden.

## 7. Acceptance criteria

- [ ] `CallbackMapper` decompresses and dispatches by `ClientCallbackMethod`; the `CHATMESSAGE`
      case maps `mage.view.ChatMessage` → `ChatEvent`; unmapped methods are logged and dropped
      (never throw).
- [ ] Relayed pushes reach the socket via the 0005 channel hand-off (no remoting-thread sends);
      an app client over `/v1/session` receives a `ChatEvent` for a real chat message.
- [ ] `GetServerInfo` → `ServerInfo` works, correlated by `requestId`, sourcing version + main
      room id from the upstream.
- [ ] The golden-file harness exists: `ChatMessageMapperTest` matches `chat_talk.json`
      hermetically; `UPDATE_GOLDEN=1` regenerates; `CallbackRelayIT` maps a **real** callback to
      the same golden (env-gated, skipped by default).
- [ ] `mage.view.*` types appear **only** inside `magefree.bridge.mapping`; `:protocol` and the
      rest of the bridge see only app-schema types.
- [ ] `./scripts/dev gradle check` passes and stays hermetic; the live test is opt-in via `XMAGE_SERVER`.

## 8. References

- `../mage/Mage.Common/src/main/java/mage/interfaces/callback/ClientCallback.java` &
  `ClientCallbackMethod.java` — push envelope, `decompressData()`, method enum.
- `../mage/Mage.Common/src/main/java/mage/view/ChatMessage.java` — the sample view type.
- `../mage/Mage.Common/src/main/java/mage/remote/SessionImpl.java` — `getMainRoomId`,
  `getRoomChatId`, `joinChat`, `sendChatMessage`, `getServerState`.
- [`../architecture.md`](../architecture.md) — mapper boundary + golden-file drift detection.
- [`0005-session-bridge-connect-login-reconnect.md`](0005-session-bridge-connect-login-reconnect.md) — the session path and channel hand-off this extends.
