# Architecture: talking to XMage from Android

This document captures what we currently understand about how the XMage Desktop client
communicates with an XMage server, and what that implies for a native Android client. It
is based on a **light structural survey** of the upstream repo (`../mage`), not a deep code
read. Treat specifics as "verify before relying on."

## How the Desktop client talks to the server (upstream)

- **Transport:** [JBoss Remoting](https://jbossremoting.jboss.org/) `2.5.4.SP5`, using the
  **bisocket** transport (`org.jboss.remoting.transport.bisocket`). Bisocket gives a
  server→client callback channel over what is otherwise a client-initiated socket.
- **Serialization:** Java object serialization (JBoss Serialization). Objects on the wire
  are the shared, `Serializable` DTOs in the **`Mage.Common`** module — primarily the
  `mage.view.*` package (`GameView`, `PlayerView`, `PermanentView`, `CardsView`,
  `TableView`, `GameClientMessage`, etc.). Some payloads are gzip-compressed
  (`mage.remote.traffic.ZippedObject`).
- **Server API surface:** `mage.interfaces.MageServer` (~40+ RMI-style methods) — e.g.
  `connectUser`, `roomGetAllTables`, `roomCreateTable`, `roomJoinTable`, `deckSubmit`,
  `matchStart`, `gameGetView`, `sendPlayerBoolean/Integer/String/UUID`, `sendPlayerAction`,
  `chatSendMessage`, draft/tournament calls. This is effectively the entire client-visible
  server contract.
- **Server→client push:** `mage.interfaces.callback.ClientCallback` carries
  `{ method: ClientCallbackMethod, objectId: UUID, data: Object }`. The client implements a
  callback handler (`CallbackClientImpl` in `Mage.Client`) that reacts to game updates,
  chat, prompts, etc. This is how the server drives the game: it pushes a new `GameView`
  and a prompt, the client renders it and calls a `sendPlayer*` method back.
- **Client session logic:** `mage.remote.SessionImpl` (in `Mage.Common`) wraps all of the
  above — connect/reconnect, ping/keepalive, the callback registration, background
  "RemotingTask" execution.
- **Target runtime:** Java 1.8.

### The gameplay loop, conceptually

1. Client connects, authenticates, gets the main room id.
2. Client lists tables/tournaments, joins or creates one, submits a deck (`DeckCardLists`).
3. Match starts; server sends `ClientCallback`s with `GameView` snapshots + prompts.
4. Client renders the view and, when it's the player's turn/decision, returns a choice via
   `sendPlayer*` / `sendPlayerAction`.
5. Repeat until the game/match ends.

The important mental model: **the server is authoritative and stateful; the client is a
renderer + input device.** All rules enforcement lives server-side. That is good news for
Android — we do not reimplement any MTG rules — but it means the client is only as useful
as its connection to a server.

## Why the native protocol won't work on Android

**JBoss Remoting 2.5.4 (circa 2010) does not run on Android.** It depends on JVM/J2SE
facilities and reflection-heavy Java serialization semantics that Android's ART runtime
does not fully provide, and it is a heavyweight, effectively unmaintained stack. Even
setting that aside, Java-serialization interop would force the Android client to carry the
exact `mage.view.*`/`Mage.Common` classes (and matching `serialVersionUID`s) — dragging in
a large, AWT/Swing-adjacent dependency graph and coupling us tightly to upstream's internal
DTOs.

So: **the Android client will not speak XMage's native wire protocol directly.**

## Integration strategy

**Committed: Option A** — a protocol-translating bridge that reuses XMage's own client
library (`mage.remote.SessionImpl` / `Mage.Common`) headless on a JVM and re-exposes
WebSocket + JSON to Android. The first integration work validates it in practice; Option B
is the documented fallback if a hard blocker appears, and Option C stays ruled out. Options
B and C are retained below for context.

### Option A — Protocol-translating bridge (committed)

Stand up a small **gateway service** that speaks JBoss Remoting to a real XMage server on
one side (reusing `Mage.Common`/`mage.remote.SessionImpl` as a library on the JVM, where it
already works) and exposes a **modern protocol** (WebSocket + JSON or Protobuf) to the
Android client on the other.

- ✅ Android stays fully modern; no legacy transport on-device.
- ✅ We control the mobile-facing schema; decouples us from upstream DTO churn.
- ✅ Can target any self-hosted or public XMage server the bridge is **version-matched** to
  (see [Versioning & upstream updates](#versioning--upstream-updates)).
- ⚠️ Adds a hop and an extra deployable to run/host.
- ⚠️ Bridge must faithfully map the callback/`sendPlayer*` loop.

### Option B — Modern endpoint inside a forked server

Add a WebSocket/gRPC endpoint directly to `Mage.Server` alongside the existing remoting
listener.

- ✅ No extra hop.
- ⚠️ Requires forking/maintaining server changes against a fast-moving upstream.
- ⚠️ Only works against servers running our fork.

### Option C — Reimplement the bisocket + Java-serialization protocol on Android

Hand-roll the wire protocol.

- ❌ Extremely brittle, high effort, tightly coupled to upstream serialization details.
- ❌ Realistically infeasible/maintenance nightmare. Documented only to be explicitly ruled
  out.

## Implications for the Android app

- **No rules engine on device.** Do not port `Mage` core. The app is a networked
  view/controller.
- **Design to an abstraction, not to `mage.view.*`.** Define our own mobile-facing domain
  models in a `:core:model` module and map the server representation into them at the
  network boundary. This keeps UI code independent of whatever the bridge/server sends and
  lets the UX diverge freely.
- **The connection is the product.** Reconnection, latency, background/foreground
  transitions, and "it's your turn" push notifications matter far more on mobile than on
  desktop. Treat them as first-class from day one.
- **Card images & static data** (card metadata, set symbols) are large. Plan a caching
  strategy (on-device cache + CDN) rather than bundling.

## Keeping in sync with upstream

The `mage.view.*` → app-schema mapping is our only real coupling surface. We keep it
correct and low-maintenance with:

- **Golden-file mapper tests.** Recorded real `mage.view.*` payloads → asserted app-schema
  output, regenerated from a captured corpus of real games. This is the primary defense
  against upstream drift: a changed view shape fails loudly at the mapper.
- **Versioned schema contract.** The bridge↔app JSON schema is a first-class, versioned
  artifact in this repo — the shared source of truth both sides build against.
- **CI.** `./gradlew check` for the app; the bridge test suite; an integration job that runs
  a local XMage server for AI-vs-AI games to exercise the callback loop.
- **Blast-radius discipline.** Nothing above `:core:network` (app) or the mapper (bridge)
  knows the wire/view shapes. Upstream changes are absorbed at those two boundaries only.

## Versioning & upstream updates

XMage enforces **exact-version lockstep** between client and server: `SessionImpl.connectStart`
rejects the connection unless the client's `MageVersion` equals the server's, and the
Java-serialized `mage.view.*` DTOs are not cross-version safe. The bridge embeds
`mage-common`, so it *is* an XMage client bound by this rule — if the server's version differs
from the bridge's, the bridge cannot connect at all (no partial/degraded mode).

Three version axes, which Option A deliberately decouples:

- **Server version** — its cadence; the bridge must match it exactly.
- **Bridge's embedded `mage-common`** — ours; rebuilt to match the server.
- **Bridge↔app schema** — ours; kept stable so the app rarely moves.

The Android app never sees `mage.view.*` or the version gate — it speaks the schema. So a
server update is normally a **bridge-only** rebuild/redeploy, and because the bridge is
infrastructure we run, one redeploy restores every app user at once (no per-user client
update, unlike the desktop client). The golden-file mapping tests are the detector for
whether an upstream change leaked into the app schema or was absorbed in the mapper.

**Current posture: a self-hosted, version-pinned server.** We run our own XMage server at a
version we choose, so the bridge/client is always version-matched and we upgrade on our own
schedule. Version skew happens only when *we* decide to upgrade — never imposed by a third
party. Targeting arbitrary public servers (which ship ~weekly and would put the bridge on a
constant catch-up treadmill) is out of scope for now.

**Design consequence:** the bridge treats a version mismatch as a first-class, legible state
(server on X, bridge supports Y) rather than a generic connection error, and a server upgrade
is a hard cutover — reconnection does not paper over it.

## Decisions

Committed:

1. **Integration** → Option A: a protocol-translating bridge that reuses `SessionImpl`.
   Works against stock servers; no server fork.
2. **Repo layout** → monorepo. Bridge and app share this repo and version their contract
   together.
3. **Bridge runtime** → Kotlin/JVM (Ktor), linking the Java `Mage.Common` library.
4. **Wire format** → JSON first; a Protobuf swap stays optional later.
5. **Auth** → proxy XMage's own account auth through the bridge; our own layer only if a
   real need appears.
6. **Target-server posture** → a self-hosted, version-pinned server we operate, so the
   bridge/client version is always under our control (see
   [Versioning & upstream updates](#versioning--upstream-updates)).

Open:

7. How much of `GameView` does a phone actually need per frame, and how do we diff/delta it
   to keep payloads small on mobile data?
