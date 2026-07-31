# Implementation Stories (Phase 3)

Each file here is a **self-contained implementation plan for one story**. A story document
carries everything a fresh engineer (human or agent) needs to build, test, and get that one
piece accepted — reading only that document plus the repo it links to.

## Numbering & order

- Files are numbered `NNNN-slug.md` in **global implementation order** — `0001` is built
  first, then `0002`, and so on across all epics. The number is the build sequence, not an
  epic index.
- Each story names the **epic** it serves (`EPIC-01` … see
  [`../project-plan.md`](../project-plan.md)) and the stories it **depends on**.
- A story is only "ready" when its dependencies are merged.

## Workflow (per story)

Follows the repo's [`AGENTS.md`](../../AGENTS.md) git rules:

1. Branch off `main` (e.g. `feature/story-0001-bridge-scaffold`).
2. Implement to the story's design and acceptance criteria.
3. `./gradlew check` (and any integration tests the story names) must pass.
4. Open a PR into `main`; merge when green.

The owning agent is responsible for the whole story — creation through test and acceptance —
against the criteria written in the document.

## Story document template

Every story uses these sections:

1. **Objective** — what this builds and why, in a few sentences.
2. **Context & background** — the essentials to act without hunting: relevant repo docs and
   the specific `../mage` classes/facts involved (with signatures where they matter).
3. **Scope** — explicit *In scope* / *Out of scope* lists.
4. **Prerequisites & toolchain** — tools and versions the story assumes, stated **explicitly**
   (never "latest"): JDK/Gradle/plugin versions, env vars, and any prior story that must be
   merged. Default to the [Project toolchain baseline](#project-toolchain-baseline) and note
   only the deltas.
5. **Design & approach** — the intended structure: modules, key types, protocol/schema,
   upstream APIs to call, and how correctness is preserved.
6. **Implementation steps** — an ordered, concrete path.
7. **Testing & verification** — unit and (where relevant) integration tests, plus exact
   commands. Correctness is verified against a locally-run XMage server, never invented data.
8. **Acceptance criteria** — a checklist that defines done.
9. **References** — files and docs to read.

## Project toolchain baseline

This is the **locked, authoritative** toolchain. It is internally coherent — a clean
`./gradlew check :app:assembleDebug` builds green across `:bridge` and `:app`. Every version
is pinned in `gradle/libs.versions.toml`; no versions are hard-coded in build files.

> **Guardrail for stories (human or agent):** consume these fixed versions. **Do not** change
> toolchain versions, bump the Gradle wrapper, or add per-module version workarounds inside a
> story. If a build genuinely needs a version change or a new constraint, **stop and flag it**
> for a deliberate, project-wide toolchain decision — never improvise in an isolated story
> (that is how the stack drifts). A story states only its **deltas** (e.g. a new module's
> plugins), never "latest."

### Locked versions

| Area | Pin | Notes |
|------|-----|-------|
| Gradle | wrapper **9.3.1** | always `./gradlew`; requires `junit-platform-launcher` on the test runtime classpath |
| JDK | **17** | `:bridge` uses `jvmToolchain(17)`; ensure `JAVA_HOME` points at a JDK 17 (none may be on `PATH`) |
| Kotlin | **2.4.10** | one version for all modules |
| KSP | **2.3.10** | KSP is **version-independent of Kotlin since 2.3.0** and supports Kotlin 2.2+ (incl. 2.4.10) — this pairing is correct |
| AGP | **8.13.2** | newest AGP that runs on Gradle 9.3.1 with the standard plugin set; AGP 9.x needs Gradle ≥ 9.5 |
| Android SDK | **compileSdk/targetSdk 36**, **minSdk 26** | platforms 35/36 installed locally; SDK wired via `local.properties` (git-ignored) |
| Compose | **BOM 2025.09.01** + Kotlin Compose plugin | Compose libs are BOM-managed (no per-lib versions) |
| Hilt | **2.57.2** | see the aggregation note below |
| Ktor / kotlinx-serialization / coroutines / logback / JUnit5 / ktlint | per catalog | bridge + test stack |

### Standard, deliberate accommodations (not per-story hacks)

- **Hilt uses `hilt { enableAggregatingTask = false }` in every Hilt-consuming module.** This
  routes Hilt aggregation through KSP. Hilt's *legacy* javac aggregating task bundles a
  `kotlin-metadata-jvm` that reads only ≤ Kotlin 2.2 `@Metadata`, so it chokes on our Kotlin
  2.4 metadata; the KSP path handles it. This is a documented, recommended Hilt option — apply
  it consistently, don't re-derive it.
- **`:app` pins `androidx.concurrent:concurrent-futures` 1.2.0 via a `constraints { }` block**
  to align the main runtime (1.1.0 via navigation → profileinstaller) with androidTest
  (1.2.0 via androidx.test:core) under AGP consistent resolution. Any Hilt/test module that
  hits the same split applies the same constraint.

### Module build config: convention plugins

Shared build config lives in **`build-logic/`** (an included build) as `magefree.*` convention
plugins, so a module never re-declares SDK/Java/Kotlin/Compose/Hilt settings and can't drift:

- **`magefree.android.application`** — AGP application + Kotlin + ktlint + the SDK/Java/Kotlin
  baseline + `targetSdk` + the dependency alignment.
- **`magefree.android.library`** — the same, for `:core:*` / `:feature:*` library modules.
- **`magefree.android.compose`** — enables Compose and wires the Compose **BOM** + tooling.
- **`magefree.hilt`** — applies KSP + Hilt, sets `enableAggregatingTask = false`, adds the Hilt deps.

A module's `build.gradle.kts` applies the conventions it needs and declares only its own
dependencies (versions come from the catalog / Compose BOM). **New Android modules MUST apply
these conventions** instead of re-deriving config — that is the structural guarantee behind the
guardrail above.

### Gate
`./gradlew check` (lint + tests) must pass; Android modules also build `assembleDebug`.

### When to revisit (deliberately, project-wide — not in a story)
Moving to AGP 9.x / newer androidx / `compileSdk 37` requires bumping the Gradle wrapper to
≥ 9.5 and re-verifying the whole set together. Track such changes in
[`review-follow-ups.md`](../review-follow-ups.md); do them as their own toolchain pass.

---

## Epic 1 — Bridge & Server Integration

The bridge is the foundation everything else stands on: a headless Kotlin/JVM (Ktor) service
that reuses XMage's own client library (`mage.remote.SessionImpl` / `Mage.Common`) to talk to
a real server, and re-exposes a modern WebSocket + JSON protocol to the app. See
[`../architecture.md`](../architecture.md).

Epic 1 delivers the bridge **foundation and framework** — build, reference server, session,
protocol contract, and the generic relay + mapping/test harness. **Per-feature relays** (lobby
tables, deck submit, game view, …) are implemented within their own epics' stories, built on
this foundation.

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0001 | Bridge module scaffold & health endpoint | — | Gradle multi-project + `:bridge` Ktor app, `/health`, CI `check`. No XMage yet. |
| 0002 | Local XMage server harness (reference environment) | 0001 | Documented, repeatable local `Mage.Server` build/run + an integration-test helper that reaches it. |
| 0003 | Embed XMage client session & connect/authenticate | 0002 | `Mage.Common` as a JVM dependency; `MageClient` sink; `SessionImpl.connectStart`; authenticate and fetch the main room id (JVM only, no WebSocket). |
| 0004 | Bridge↔app protocol contract v1 & schema versioning | 0001 | Versioned JSON envelope, message framing, error model, kotlinx.serialization models, WebSocket endpoint skeleton with a version handshake. |
| 0005 | Session bridge: connect / login / reconnect over WebSocket | 0003, 0004 | WebSocket login wired to a per-session `SessionImpl`; relays connected/failed/disconnected/reconnect state. |
| 0006 | Callback relay & mapping/golden-file test harness | 0005 | Generic `ClientCallback` relay (decompress → map → forward) and server-call plumbing back; the `mage.view.*`→schema mapper boundary + golden-file test infrastructure, proven on one sample push and one relayed call. |

---

## Epic 2 — App Shell & Navigation

The Android app's foundation and the Arena-style information architecture: a home hub with a
prominent path to play, top-level destinations (Home/Play, Decks, Profile/Social, Settings),
an always-visible connection status, and a separate immersive in-game mode. See
[`../project-plan.md`](../project-plan.md) (EPIC-02) and [`../ux-principles.md`](../ux-principles.md).

The full visual system (theme, components) is **EPIC-03**; these stories use a minimal default
Material 3 theme and stub any data (connection state, "play" actions) — real wiring arrives in
EPIC-04/05.

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0007 | Android app module scaffold | — | `:app` Android module (AGP, Compose, Material 3, Hilt, single Activity); builds a debug APK, launches to a placeholder; CI `check`. The Android analog of 0001. |
| 0008 | Navigation shell & top-level destinations | 0007 | Navigation-Compose type-safe routes; Home/Decks/Profile/Settings placeholder destinations; adaptive bottom-bar ↔ nav-rail. |
| 0009 | Home hub with prominent "Play" entry | 0008 | The Arena-style home: a primary, thumb-reachable Play CTA plus entries to other destinations (stub actions). |
| 0010 | Persistent connection-status surface | 0008 | An always-visible connection indicator across the shell, driven by a state holder over a stub source. |
| 0011 | Immersive in-game mode shell | 0008 | The in-game experience as a separate full-screen, edge-to-edge immersive route, distinct from the tabbed shell (placeholder content). |

---

## Epic 3 — Design System & Theming

The original Material 3-based visual system in a shared **`:core:designsystem`** module: theme
(brand color, typography, shape, tokens), the reusable **components** (buttons, list rows,
section chrome, the decision/prompt surface), the **card-forward** components (card tile,
full-bleed card view), and adaptive/accessible foundations. Replaces the minimal placeholder
`MageTheme` from story 0007. See [`../project-plan.md`](../project-plan.md) (EPIC-03) and
[`../ux-principles.md`](../ux-principles.md) ("one coherent visual system," card inspection as a
first-class surface, decisions come to the player).

Components are built as **stateless, parameterized, previewed** shells with placeholder inputs —
real data wiring (card data, connection state, game decisions) stays in the owning epics. This
epic also resolves several logged design-pass items in
[`review-follow-ups.md`](../review-follow-ups.md).

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0012 | Design system module & theme foundation | 0007 | `:core:designsystem` library module; brand color schemes (light/dark), typography, shape, and design tokens; the real `MageTheme` moved here; `:app` migrated off the placeholder. |
| 0013 | Foundational components | 0012 | Reusable non-card Composables: action/button hierarchy, list rows, section chrome (app bar/headers), loading/empty/error states, and the thumb-reachable **decision/prompt surface**. |
| 0014 | Card-forward components | 0012 | The **card tile** and **full-bleed card inspection view** shells (parameterized, placeholder data), with tap-to-peek / long-press inspection patterns. Real card data is EPIC-10. |
| 0015 | Adaptive & accessible foundations + component catalog | 0013, 0014 | Window-size-class layout helpers and a shared inset-ownership convention; dynamic-type / scaling support; a dev **component catalog** screen showcasing everything across light/dark and sizes. |

---

## Epic 4 — Server Connection & Sign-In

The first epic that **wires the app to the real bridge**: choosing/adding a server, signing in
with an XMage account (proxied auth), registering where the server allows it, and seeing live
connection state. See [`../project-plan.md`](../project-plan.md) (EPIC-04) and
[`../architecture.md`](../architecture.md).

> **Status:** implemented and merged. The bridge session (Epic 1 stories 0004 `:protocol` and 0005
> session bridge) it depends on is complete, so the app connects live end-to-end (verified against
> the reference server), in addition to the hermetic `FakeBridgeClient` path.

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0016 | App network layer & session client | 0004 (`:protocol`), 0007 | `:core:model` (connection/session domain) + `:core:network` (a WebSocket bridge client speaking the `:protocol` contract, DTO→domain mappers, and a `FakeBridgeClient`). |
| 0017 | Connection repository & live status wiring | 0016, 0010 | A connection/session repository that maps the bridge `SessionStatus` into the app `ConnectionState` and replaces the **stub** behind story 0010's `ConnectionStatusSource` seam; server-list persistence (DataStore). |
| 0018 | Connect & sign-in UI | 0017, EPIC-03 | `:feature:connect`: server list / add-server, sign-in, and connection-state screens (connecting / connected / auth-failed / version-unsupported), built on the design system. |
| 0019 | Auth, version & network error handling | 0018 | Enriches 0017's seam to carry failure **detail**; distinct auth-failed / version-unsupported (`server=… bridge=…`) / network-timeout surfaces with retry. **Registration permanently deferred** (2026-07-30) — see the story doc. |

---

## Epic 5 — Session Resilience & Notifications

Surviving network drops, rotation, and backgrounding without losing the session, with automatic
reconnection; and (later) push notifications for "it's your turn," invites, and mentions. See
[`../project-plan.md`](../project-plan.md) (EPIC-05).

**Resilience track (planned & built now):**

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0023 | Bridge session hold & resume | 0005, 0004 | The bridge parks a per-client XMage session on an unexpected app-socket drop (kept alive), issues a `resumeId`, and re-attaches a reconnecting app via a `Resume` message — no re-auth. Additive `:protocol` resume messages. |
| 0024 | App reconnect & lifecycle-aware session | 0023, 0016, 0017 | App-side automatic reconnection with resume (no credential re-entry), bounded exponential back-off, and network/lifecycle awareness; survives rotation and backgrounding. |
| 0025 | Resilience UX & recovery surfaces | 0024, 0019, 0010 | Non-destructive reconnecting/restoring indicator (via the 0010 status bar) that preserves context, and a distinct "session lost — sign in again" recovery surface. |

**Notifications track (deferred):** push transport (FCM vs. alternatives), device-token
registration, and "your turn"/invite/mention delivery are **deferred** until after the resilience
track and a push-transport decision — and are partly gated on gameplay (EPIC-11+) to trigger on.
They will be numbered when planned.

---

## Epic 6 — Lobby & Game Browser

Browsing rooms, open tables, and watchable games — the surface behind the home "Play" path — with
players and table settings at a glance and client-side filters/sorting. **Read-only**; joining and
hosting are EPIC-07. See [`../project-plan.md`](../project-plan.md) (EPIC-06).

**Design note:** XMage's lobby is **poll/request-response** (`SessionImpl.getTables/getRoomUsers/
getGameTypes`), not push — so the app requests + refreshes rather than subscribing.

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0027 | Lobby data relay & contract | 0005, 0006, 0004 | Bridge request/response for open tables / room users / game types: additive `:protocol` messages + `mage.view.*`→app-schema mappers (extends 0006's mapper boundary) + `SessionCoordinator` handling. |
| 0028 | App lobby model & data | 0027, 0016, 0017 | `:core:model` lobby types + a `:core:network` `LobbyClient` (DTO→domain) + a `LobbyRepository` exposing an observable, refreshable snapshot (load/refresh/error as state); with a `FakeLobbyClient`. |
| 0029 | Lobby browser UI | 0028, 0018, EPIC-03 | `:feature:lobby`: browse tables (name/host/format/seats/state/flags at a glance) with loading/empty/error/non-destructive-refresh states and client-side filter/sort, behind the shell Play entry. Join deferred to EPIC-07. |

---

## Epic 10 — Card Database, Search & Inspection

Sequenced **before Epic 9 (deck builder) and Epic 7 (join/host)** — the builder searches the catalog,
and joining a table submits a deck the server validates against XMage's card pool. See
[`../project-plan.md`](../project-plan.md) (EPIC-10).

**Decisions (2026-07-31):** card data is **XMage-authoritative** (not Scryfall) and **bundled
on-device**; only **artwork** isn't bundled — it loads **on demand** with a configurable cache
(persistent disk / memory-only) plus an optional user-initiated bulk pre-download, reusing XMage's
image-source URL resolution.

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0030 | Card catalog data & local search | 0020–0022 | A bundled XMage card catalog generated reproducibly from the pinned version (`CardRepository`/`CardInfo`) + a `:core:cards` local `CardCatalog` (offline search/filter/lookup). No artwork. |
| 0031 | Card artwork loading & cache | 0030, EPIC-03 | An on-demand `CardImageLoader` (Coil-backed) resolving XMage image URLs by card identity, with a cache-policy setting (persistent/session-only) + an opt-in bulk pre-download; graceful offline placeholder. |
| 0032 | Card search UI & inspection view | 0030, 0031, 0018, EPIC-03 | `:feature:cards`: search/filter/browse + a full-bleed card inspection view (reusing 0014's card components + 0031 art). In-game inspection deferred to EPIC-11+. |

---

## Build Infrastructure

Containerizing the **heavy / JVM path** (the bridge build, the upstream `../mage` Maven build, and
the reference XMage server) via Docker Compose + a `scripts/dev` helper. Android `:app` stays a
host build. Full design in [`../build-environment.md`](../build-environment.md).

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0020 | Containerized JVM build | — | Base build image (JDK 17 + Maven), a Compose `build` service + cache volumes, and `scripts/dev`; runs `:bridge` in-container. |
| 0021 | Upstream mage build layer | 0020 | A cached image layer that builds `magefree/mage` at a pinned ref and installs `org.mage:mage-common` into the container's Maven repo. |
| 0022 | Reference XMage server container | 0021 | An `xmage-server` Compose service running `Mage.Server` on 17171 (auth off) — realizes story 0002's reference environment. |

## Recommended build order (now)

The story numbers are **identifiers, not a strict sequence** (the app epics shipped ahead of the
bridge). Stories **0001–0022 are complete and merged**:

1. **Build infrastructure:** 0020 → 0021 → 0022. ✅
2. **Bridge (Epic 1):** 0002 (realized by 0022) → 0003 → 0004 → 0005 → 0006, built in-container. ✅
3. **App shell / design system (Epics 2–3):** 0007–0011, 0012–0015. ✅
4. **Epic 4 (connect):** 0016 → 0017 → 0018 → 0019. ✅ (registration deferred)

5. **Epic 5 (resilience track):** 0023 → 0024 → 0025. ✅ (notifications track deferred)
6. **Post-audit hardening:** 0026 (six audit fixes across protocol/resilience). ✅

7. **Epic 6 (lobby browser):** 0027 → 0028 → 0029. ✅

**Next:** **Epic 10 (card database)** — 0030 → 0031 → 0032 — then **Epic 9 (deck builder)**, then
**Epic 7 (join/host tables)**. This reordering (10 → 9 → 7 ahead of Epic 7's original slot) is
deliberate: the deck builder searches the card catalog, and joining a table submits a deck the server
validates against XMage's card pool, so cards and decks come first. Epic 5's notifications track
remains deferred; the other downstream epics (08, 11–17) are not yet broken into stories.
