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

1. Write the story document **and open its issue** (see [Issue tracking](#issue-tracking)).
2. Branch off `main` (e.g. `feature/story-0001-bridge-scaffold`).
3. Implement to the story's design and acceptance criteria, **committing incrementally** (see
   [Verification standards](#verification-standards)).
4. `./gradlew check` (and any integration tests the story names) must pass.
5. For a story with **user-visible behaviour**, an [independent verification pass](#verification-standards)
   runs before merge.
6. Open a PR into `main`; merge when green.
7. The issue closes **after Pete has confirmed the story on a device** — not on merge.

The owning agent is responsible for the whole story — creation through test and acceptance —
against the criteria written in the document.

## Issue tracking

**Applies to the stories written for the UI rebuild** — the epics named in
[`../ui-modernization-plan.md`](../ui-modernization-plan.md) §11. Earlier stories are tracked by the
tables in this document and are **not** backfilled as issues; the tracker is for what is still open,
so filling it with finished work would defeat it.

A story document says *what to build*. The issue says *whether it is still open* — a question the
document cannot answer about itself, and which otherwise requires reading a table.

- **One issue per story**, opened at the same time as the document. Title
  `Story NNNN — <the story's title>`, label `story`, body carrying the objective, the epic it
  serves, and a link to the document. The body does **not** restate the design: that lives in the
  document, and a second copy would go stale.
- **The PR references the issue without a closing keyword** — `Story: #N`, never `Closes #N`.
  GitHub closes a linked issue the instant the PR merges, and merging is precisely not the moment
  the story is done.
- **On merge** the issue gets `needs-verification`, plus a comment naming the merged PR and the
  eyes-on checklist from the story's *Testing & verification* section.
- **Pete closes it**, after confirming the behaviour on a device, with a comment recording what he
  confirmed. If verification fails the issue stays open and the follow-up lands against it.

This makes [verification standard 3](#verification-standards) visible rather than remembered: the
implementer does not get the last word, so the gap between *merged* and *done* is a label on a list
instead of something somebody has to hold in their head.

## Verification standards

These are project-wide rules, not per-story instructions. Each exists because its absence let a
defect ship: every one of the five defects found in the 2026-08 hardening pass had the same shape —
**something that looked complete and silently did nothing**, with green tests.

1. **Prove the test fails first.** Any test for a behavioural fix must be demonstrated failing
   against the unfixed code, then passing after — with both outputs recorded in the PR. A test that
   passes either way is worse than no test, because it manufactures confidence. (Found: an
   interleaving test that passed against the bug because a dispatcher masked the race.)
2. **Reachability check.** For every piece of state the UI reads or gates on, the story must answer
   in writing: **what produces this in production?** Not "what could", not "what does the fake do" —
   the actual production producer. (Found: seats folded from a server push that nothing sent, so the
   host's Start could never enable; a `Logout` message the bridge implements and the app never sends;
   a prefetch warming a different cache key than the UI requests.)
3. **Independent verification for user-visible work.** The agent that implements a story does not get
   the last word on whether it works. A separate pass — cold context, or a live run against the real
   server — answers *"does this actually work end to end?"*, distinct from *"are the units correct?"*.
   Every defect in the hardening pass was found this way; none was found by its implementer. Unit
   tests stay with the implementer (they benefit from implementation knowledge); it is **behavioural
   verification** that must be independent.

   **How the independent pass is done (Pete, 2026-08-14).** Two automated halves, plus a human one:
   - the **hermetic gate**, including Compose tests via Robolectric in `src/testDebug` — device-only
     tests do not run pre-merge, which is how an entire epic once stayed unmounted, and it is what
     caught a control that rendered, reported a click, and did nothing;
   - a **live IT driven through the real ViewModel** against the bridge, so production logic is
     exercised end to end without a screen;
   - **eyes-on, by Pete.** The reviewer hands over a short numbered checklist of what to look at on
     the device; the story is not done until Pete has confirmed it.

   **Do not drive the app's UI programmatically** to satisfy this. `adb input tap` + `uiautomator`
   loops were tried at length and the cost/benefit collapsed: taps landing during recomposition, panel
   geometry shifting between dump and tap, the dump itself disturbing the tap, an install silently
   resetting app data. Installing the APK and confirming it launches is fine; long tap-sequences to
   reach a game state are not. This moves *where* verification effort goes, and relaxes nothing —
   both blocking defects of the 0057/0058 pass were found by eyes on a device while the suites were
   green, which is exactly why the human half stays.
4. **Commit incrementally.** Commit per defect or per coherent step, not once at the end. Long stories
   get interrupted; an interruption should cost minutes, not the whole story.
5. **Unexpectedly absent.** Before relying on an upstream field, confirm something actually **writes**
   to it — not merely that it exists and type-checks. A declared field with a getter and no producer
   looks available at every stage: it compiles, it maps, it round-trips, and it silently carries
   nothing forever. (Found 2026-08-13: `GameView.opponentHands` is declared with a getter and is
   **never populated anywhere in XMage** — the only two references in the whole codebase are the
   declaration and the getter. A board feature had been specified on top of it.) The check is cheap:
   grep the upstream for writes, and confirm live that the field is non-empty in the situation that
   should populate it. This is the mirror of standard 2 — reachability asks *what produces this state
   in our code*; this asks *does the source we are reading from ever produce anything at all*.

A fake that behaves differently from production is a defect in the fake, not a convenience — fix the
double, not the test that depends on it.

### Investigations are instrumented, not inferred

Some stories are a question before they are a feature: *what does the server actually send during a
real game?* Those are answered by **instrumenting the code and playing a real game** — add
diagnostic logging aimed at the specific question, hand over a build, Pete plays the game that
provokes the behaviour, and the logs come back to be read. The story is written after that, from
what the logs say.

Reading `../mage` establishes what the server **can** send; only a live game establishes what it
**does**. That gap is standard 5's whole subject: `GameView.opponentHands` is declared, typed,
mapped and written to by nothing, and it looks identical to a working field at every stage that is
not a running game.

Two working rules:

- **The instrumentation is its own step with its own hand-off**, not something folded into the
  implementation commit. `diag: log every GameStateCache prompt observation and answer` is the shape.
- **The logging is removed or demoted once the question is answered.** A diagnostic that outlives
  its question is noise in the next one.

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
   upstream APIs to call, and how correctness is preserved. For any state the UI reads or gates on,
   state the **reachability check** (standard 2): what *produces* that state in production.
6. **Implementation steps** — an ordered, concrete path.
7. **Testing & verification** — unit and (where relevant) integration tests, plus exact
   commands. Correctness is verified against a locally-run XMage server, never invented data.
   Name which tests must be **proven failing first** (standard 1), and — for user-visible work —
   what the **independent verification** pass checks (standard 3), including the **eyes-on checklist**
   handed to Pete.
8. **Acceptance criteria** — a checklist that defines done.
9. **References** — files and docs to read.

Sections 5 and 7 carry the [Verification standards](#verification-standards); a story that gates UI
on state with no named production producer is not ready to implement.

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
| JDK | **17** | Three JVMs are involved and only the last two are pinned. The **launcher** is whatever `java` the `gradlew` script finds (`JAVA_HOME`, else `PATH`) and may be any modern JDK. The **daemon** is pinned to 17 by `gradle/gradle-daemon-jvm.properties`, so the CLI and the IDE share one daemon instead of starting two. **Compilation** is pinned by `jvmToolchain(17)` and AGP's `compileOptions`. `JAVA_HOME` is therefore not required when a JDK is on `PATH` |
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

## Epic 9 — Deck Management & Building

The deck library + touch-first builder, then **Epic 7 (join/host)** works end-to-end. See
[`../project-plan.md`](../project-plan.md) (EPIC-09).

**Decisions (2026-08-01, Pete):** **every** deck operation — view, create, construct, sideboard,
legality — works with **no network connection**; the *only* networked thing is artwork, and its
download is initiated **from the deck builder** per the chosen cache policy (scoped to the deck's
cards). Format-legality data is **bundled** (generated from XMage like the card catalog).

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0033 | Deck model, storage & legality data | 0030, 0020–0022 | `:core:decks` deck model (↔ `DeckCardLists`), a local offline `DeckRepository`, bundled format-legality data (generated from XMage) + an offline `DeckLegality` checker. |
| 0034 | Deck import & export | 0033, 0030 | Ported XMage `.dck`/plain-text/`.dec` (± MTGA) import/export over the deck model, resolving names via the catalog; offline; shareable file/text. |
| 0035 | Deck library & builder UI | 0033, 0034, 0032, 0018, EPIC-03 | `:feature:decks`: offline library (CRUD/favorite/import) + touch-first builder (search→add/remove, sideboard, mana curve, live legality) + a deck-scoped art download from the builder. |

---

## Epic 7 — Hosting & Joining Tables

Creating a table, joining an open table (submitting a deck), readying up, starting the match, and
spectating — the surface the lobby (Epic 6) and deck builder (Epic 9) feed into. Ends at
**match-start**; in-game play is EPIC-11. See [`../project-plan.md`](../project-plan.md) (EPIC-07).

**Design note:** XMage's table actions are `SessionImpl.createTable/joinTable/submitDeck/leaveTable/
startMatch/watchTable` (+ pushed table/game-start callbacks). Joining **submits a deck** (0033's
`Deck ↔ DeckCardLists`-equivalent). The same protocol→client→feature layering as Epics 6/10/9; the
`mage.*` boundary stays in the bridge.

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0036 | Table actions: protocol & bridge relay | 0027, 0023, 0004–0006 | `:protocol` table-action messages (create/join/submit/update/leave/remove/start/watch) + `CreateTableOptions`/`DeckList` + pushed table/seat/construct/match-starting events; a `:bridge` `TableRelay` + `CallbackMapper` cases dispatching to `SessionImpl`. No UI; `mage.*` stays in the bridge. |
| 0037 | Table client & session API | 0036, 0028, 0023–0025, 0033 | `:core:network` `TableClient` (the eight verbs → `Result`) + an observable app-schema `TableState` (seats/phase + `MatchStarting`) folded from 0036's events, seeded from create/join; maps a 0033 `Deck` to the wire; re-syncs across resume. No UI. |
| 0038 | Host & join tables UI | 0037, 0033/0035, 0029, 0018, EPIC-03 | `:feature:tables`: host (options→create), join (pick a saved deck + legality → submit), a table room (seats/ready/start/leave over `observeTable`), and spectate; enables the lobby's deferred Join + a Host entry. Ends at a "match starting" hand-off (EPIC-11). |
| 0039 | Live table-action coverage | 0036, 0022 | An env-gated `TableRelayIT` driving a real create → seat AI → seat self → start → `MatchStarting` round trip against the reference server, plus a typed-decline case. ✅ |
| 0040 | Table seat state | 0036, 0037, 0027 | **Defect fix:** the room's seats come from `SeatView` via a targeted `GetTable` (nothing sends the `SeatUpdated` the fold expects), and start is gated on the server's own `READY_TO_START`. |
| 0041 | Host seating flow | 0040, 0037, 0033/0035 | **Defect fix:** hosting seats the configured AI players and the host (each submitting a deck, deck picked offline with legality), removing the table if any step fails — mirroring upstream's `NewTableDialog`. |
| 0059 | Deck submission: offer it only where it works | 0036, 0037, 0038 | **Defect fix.** The room's "Submit your deck" is declined every time and reports only "the server declined the action". Upstream's `TableController` makes `updateDeck` a **no-op** outside `SIDEBOARDING`/`CONSTRUCTING`, and routes `submitDeck` to a null tournament outside sideboarding — these are sideboarding verbs offered during waiting. Also: the decline reason exists but arrives on a channel the bridge does not correlate. |
| 0060 | Table types come from the server | 0036, 0037, 0038 | **Defect fix.** The host form hardcodes 7 deck types; the reference server advertises **52** (plugin-loaded, named in server config), omitting `Constructed - Freeform Unlimited` and every Commander/Brawl/Block format. `SessionImpl` already exposes `getDeckTypes`/`getGameTypes`/`getPlayerTypes` — the lists are simply never asked for. |

---

## Audit follow-ups (2026-08-07)

A fresh-context audit of Epics 6/7/9/10, run alongside the first **live** end-to-end smoke against the
reference server, produced 14 findings. The two Epic 7 defects became [0040](0040-table-seat-state.md)
/ [0041](0041-host-seating-flow.md); one (an unbounded phantom-seat append) was folded into 0040. The
rest are grouped here by theme. **Every finding listed below was verified against the merged code**
before being written up.

| Story | Title | Covers | What it fixes |
|-------|-------|--------|---------------|
| 0042 | Deck & catalog robustness | builder / catalog / legality | A lost-update race that silently drops added cards; a catalog read failure that crashes the app and wedges search; a latent throw for an unbundled format; and the leading-wildcard full scan run per card name on every builder tap. |
| 0043 | Artwork pipeline fixes | `:core:cards` art | Bulk pre-download warms LARGE while the grid requests SMALL (so "download all art" leaves thumbnails blank offline); the documented `resolve()` fallback is never used; a prefetch failure crashes the app and leaves progress stuck. |
| 0044 | Correctness & doc hygiene | lobby / options / fakes / docs | An unguarded `refreshJob` that can resurrect stale tables after a disconnect; silent loss of an unsupported match time limit (and a KDoc claiming losslessness); stale "deferred to EPIC-07" comments; a fake that diverges from production and a test whose name overpromises. |
| 0045 | App↔bridge live integration | `:core:network` + Compose | **Foundation gap:** every live test stops at `:bridge`, so the whole app-side stack (`KtorBridgeClient`, request correlation, `SessionRelay`, reconnect, `LobbyClient`, `TableClient`) has only ever run against its own fakes. Adds a runnable `bridge` Compose service and env-gated tests driving the **real** client through connect → lobby → host → seat → ready → start. ✅ |
| 0046 | Sign-out sends Logout | `:core:network` | **Found by 0045:** `:protocol` defines `Logout` and the bridge implements it, but the app has **zero** references — `disconnect()` just closes the socket, so the bridge cannot tell a deliberate sign-out from a dropped connection and parks the upstream session (plus its username) for the full resume TTL. ✅ |
| 0047 | Mount the connect flow | `:app` + `:feature:connect` | **Found by 0046's reachability check:** `:feature:connect` is not a dependency of `:app` and is never mounted; nothing in `:app` calls `connect(...)`. A running APK **cannot establish a session at all**, so every mounted feature (lobby/cards/decks/tables) is wired to data it can never receive. Requires on-device verification. |

---

## Epic 11 — In-Game Play

The actual game. Everything built so far funnels into `MatchStarting`, which today opens nothing.

**Shape of the upstream contract:** XMage's in-game protocol is *"the server asks a typed question,
the client answers with a primitive"* — `GAME_*` callbacks carry a `GameClientMessage` (a full
`GameView` **snapshot** plus what is being asked), and the client replies with
`sendPlayerUUID/Boolean/Integer/String/ManaType` or a `PlayerAction`. Two consequences shape the
whole epic: state is a **snapshot, not a delta**, and **the server owns the rules** —
`GameView.canPlayObjects` says what is legally playable, so we never reimplement Magic.

**Sequencing (Pete, 2026-08-11): data first.** The data layers land and are proven against a real
game *before* any board is drawn — the order that worked for Epic 7, and the reason 0045 caught
defects fakes could not. UI scope is deliberately **not** committed yet; it will be decided once a
real game's state is reaching the app and we can see what the board actually needs.

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0051 | Game protocol & bridge relay | 0036, 0006 | `:protocol` game state/events/typed prompts/replies + a `:bridge` `GameRelay` and per-callback mappers. `mage.*` stays in the bridge. No app change. |
| 0052 | Game client & state | 0051, 0037, 0045 | `:core:network` `GameClient` + observable app-schema `GameState` and current prompt, folded from 0051's events; re-syncs across resume. **Live-proven against a real game before any UI.** No UI. |
| 0054 | Bridge game-state cache & query | 0051, 0052, 0023 | **Required for initial release, before the board UI.** Upstream has no "get game" verb and re-joining does not resync, so the board is push-only and a reconnecting client is blind until something happens. The bridge sees every snapshot and snapshots are complete, so it caches the latest **per session, per game** and answers a query with it. |
| 0055 | Board rendering (read-only) | 0052, 0054, 0031, 0014 | Portrait board rendering a live `GameState` — both battlefields, peek-and-expand hand, stack/phase/turn, player vitals, explicit priority — with **real card art**. No interaction. |
| 0056 | Card art: send a User-Agent | 0031, 0043 | **App-wide defect.** `CardImageLoader` sends no `User-Agent`, and Scryfall rejects OkHttp's default with HTTP 400 — so card art has **never** loaded, anywhere: browser, deck builder, the deck-scoped offline download, and the board. One line, plus a test that inspects the real outgoing request. |
| 0057 | Board interaction: casting, targeting, cancel | 0055, 0056, 0052, 0054 | Floating controls (never modals) + visibility toggle, tap-select-confirm, targeting with per-pick sends and a confirm, mana by tapping lands, cascading cancel, manual priority through a single pass-policy seam. Unblocks what 0055 could not verify live. |
| 0058 | Creature status and counters | 0051, 0052, 0055 | **Defect + missing data.** The board renders `0/0 · Summoning sick` under a land, because P/T and summoning sickness are drawn unconditionally — and the bridge drops `cardTypes`, `isCreature()` and `counters` from `CardView` entirely. Creature-ness is game state, not printing (Earthbend, Ensoul Artifact, crewed Vehicles), so the server's own answer must be carried and rendered, along with counters. |
| 0061 | Combat: declaring attackers and blockers | 0057, 0058, 0055 | Combat cannot be played today: a declaration is projected as ordinary priority with `pickableObjectIds` empty, so the board offers "attack with everything or nothing" and **no way to block**. `playable` is empty during a declaration — the creatures come only from `possibleAttackers`/`possibleBlockers`, which the bridge already carries. Pairing questions are upstream's own (`selectDefender` / `Select attacker to block`), asked only when ambiguous, and arrive as ordinary target prompts 0057 already answers. |
| 0062 | Alternative costs: convoke, delve | 0057 | **Defect fix, traced from `../mage`.** `GameState.specialActionsAvailable` is mapped end to end and never read — the board has no way to trigger a `SpecialAction` (convoke, delve), so decks using them are unplayable. No new prompt kind needed: the fix is a board affordance wired to the existing field, then live verification. |
| 0063 | Priority: stops and configurable auto-pass | 0057, 0061 | **Mechanism traced from `../mage`'s desktop client** (§14.3): six explicit skip-to-X actions, the full stop-settings matrix (global + per-phase-step × your/opponent turn), and hold-priority — entirely client-local, no protocol change. **Scope: full mechanism parity with upstream** (Pete), not a narrowed v1 — all six skip actions, the complete stop matrix, hold-priority, through the existing `PassPolicy` seam. Touch-first presentation of that mechanism on a phone is a separate, still-undesigned pass. |
| 0064 | Between-games sideboard | 0036, 0037, 0033, 0059, 0057 | **Full-match gap, and a genuine `:protocol`/`:bridge` fix — corrects requirements §12.1's original claim.** `SideboardPrompt`'s deck payload is dropped by `SideboardMapper` (upstream's `TableClientMessage.getDeck()` is never read), so today's board has no way to build a sideboard screen at all. Adds the payload, then the timed swap screen itself, reusing 0037's existing `submitDeck`/`updateDeck` verbs and 0033's deck model/legality. |
| 0065 | Battlefield stacking | 0055, 0057, 0058, 0061 | **Board-space feature, no protocol change.** Every permanent renders full-size in a scrolling row today, so a battlefield with many of the same land/token costs as much space as one with ten different spells. Groups same-name permanents into a compact pile **only when every rendered field matches** (tapped, damage, counters, summoning sickness, combat state, pick-eligibility) — a correctness requirement, not a preference, so a pile never hides what the server's per-object state actually distinguishes. Fans up to 3 members, caps at 3 with a count badge beyond that. |
| 0066 | Card identity: graveyard, companion, looked-at | 0051, 0052, 0055 | **Defect fix, traced from `../mage`.** `PlayerView.graveyard` is a full `CardsView`, sent always (graveyard is public info) — our bridge reads only its size. `playable` already includes graveyard-castable spells (Flashback, Escape, …), so they render as `"Unnamed candidate N"` today, not broken, just unnamed. Folds in `companion`/`lookedAt`, already flagged separately in requirements §11.3. |
| 0053 | Bridge known-information tracking | the board increment | **Post-initial-release.** Remembering information the player has already been shown (reveals, look-at, scry) so they need not. Distinct from 0054: that caches the latest snapshot verbatim, this accumulates knowledge over time — where **invalidation** is the hard part. |
| 0067 | Commander format support | 0051, 0052, 0055, 0066 | **Parked — a future increment (Pete, 2026-08-15), not the first playable board.** Fully traced and specified so nothing needs re-deriving when picked up: `PlayerView.commandList` (commander/emblem/dungeon/plane) is mapped nowhere today — Commander cannot be played at all. Tax is automatic server-side and needs no board work once casting from the zone works; commander damage is a genuine upstream data constraint (not exposed structurally anywhere, even on desktop) requiring a product decision (§21.3a) before any damage UI is built. |
| 0069 | Rejoin a table whose game already started | 0055, 0057 | **Defect found live (Pete, 2026-08-16).** The only path into `GameBoardNavRoute` is a one-shot `MatchStarting` push; a client that leaves the table room after the match starts (or relaunches) has no way back in. `mage.view.TableView.getGames()` already carries the current game id on every table read — a read `observeTable` already performs unconditionally on room open — so the fix carries that id through instead of adding a new read. |
| 0070 | Game-state cache survives session churn | 0054, 0069 | **Defect found live (Pete, 2026-08-16).** A rejoined game can show a confidently wrong board (e.g. "waiting on opponent" against an AI that already acted). `GameStateCache` (0054) is cleared on every session eviction, and XMage gives a rejoining seated player no way to resync (`GameController.join`/`watch`, read from source) — so any bridge-session churn (network hiccup, TTL, sign-out), not just a park, strands the board on a stale-but-plausible snapshot. Moves the cache from per-session to per-username ownership in `SessionRegistry`, so a new session for the same person inherits the last known state instead of starting blank. |
| 0072 | Ordering simultaneous triggered abilities is unplayable | 0057 | **Defect found live (Pete, 2026-08-16).** Two of the viewer's own triggers going on the stack at once produces an unplayable prompt: tapping a candidate does nothing, and both candidates are labeled the literal string "ability". Root cause, traced to `../mage`: `GameController`'s `PICK_ABILITY` overload always sends `targets = null` (the app only treats `targetIds` as tappable, so it stays empty), and upstream's own `AbilityView` hardcodes `name = "Ability"` for the ordinary case (the real source name is in the unexposed `getSourceCard()`, not read by our mapper). |
| 0074 | A resync must be able to restore the outstanding prompt, not just the board | 0054, 0070, 0071 | **Defect found live (Pete, 2026-08-16), immediately after 0071 merged.** Leaving during the mulligan decision and rejoining left the player stuck — mulligan is asked before priority exists, so 0071's priority-only fallback correctly doesn't apply. Root cause traced deeper: `GamePrompted` already carries both `state` and `prompt`, but `GameStateCache.observe()` discards the prompt half, and `GameStateSnapshot` has nowhere to carry one anyway — so a resync could restore the board but never what the server is still waiting to be told, for *any* prompt type. Wires the prompt through `GameStateCache` → `GameStateSnapshot` → `GameViewMapper.apply`, making 0071's fix a harmless special case of the general one. |
| 0075 | An optional target prompt needs a real "Done" with zero picks | 0057 | **Defect found live (Pete, 2026-08-17).** Activating Gideon, Battle-Forged's +2 ("up to one target creature an opponent controls") against an opponent with no creatures left the board offering only a "Cancel this cast" button — confusing and mislabeled, since nothing is being cast and the ability is already committed. Root cause: `controlsFor` only shows the server's own `Done` right-button `if (hasPicked)`, but an optional (`required = false`) target prompt is answerable with zero picks by definition, from the moment it arrives. |
| 0076 | A transformed permanent shows its front-face art forever | 0055, 0043 | **Defect found live (Pete, 2026-08-17), with a same-day follow-up.** Kytheon, Hero of Akros transformed into Gideon, Battle-Forged (confirmed server-side by its own +2/+1/0 abilities) but the board kept showing Kytheon's art — the name was already correct, only the art stayed on the front face. `toCardUi()` hardcodes `CardArtFace.FRONT` for every rendered card; upstream's own `PermanentView.transformed` field is dead code (commented out at the pinned ref). Fix threads upstream's own `CardView.getAlternateName()` through all four layers — non-null exactly when a `PermanentView`'s live name differs from its original name. **Follow-up defect the same day:** an *untransformed* Kytheon in hand also showed Gideon's art — a plain `CardView` (any non-permanent zone) sets the same field unconditionally for any transformable card, to its other face's name, regardless of which face is showing; only `PermanentView`'s constructor overwrites it into the correct signal. Fixed by gating the bridge's read on `card is PermanentView`. |
| 0077 | Manual "flip" control to peek at a DFC's other face | 0055, 0076 | **Feature request (Pete, 2026-08-17), mid-0076.** The tapped-card detail overlay gains a Flip button for any double-faced/modal-double-faced card, toggling its shown art/name between front and back — a local viewing choice that never touches the server or the card's actual live face elsewhere on the board. Flippability and the back-face name come from the bundled catalog (`CardFaces.doubleFaced`/`modalDoubleFaced`/`secondSideName`, story 0030), looked up by the card's front-face name (`alternateName ?: name`, reusing 0076's signal) since the catalog only ever indexes a DFC under its front face. |
| 0078 | MTGGoldfish text import puts the whole deck in the sideboard | 0042 | **Defect found live (Pete, 2026-08-20).** An MTGGoldfish plain-text export (`About` / `Name <deck>` / blank / `Deck` / … / blank / `Sideboard` / …) put all 75 cards in the sideboard. Root cause, traced into both our port and upstream's `TxtDeckImporter`: the blank-line-switches-to-sideboard rule is one-directional (confirmed by reading `readLine` directly — `sideboard` is only ever set, never reset), and upstream's own header pre-scan only disables it for `//sideboard`/`SB:` marks, not a bare `Sideboard` header — so the blank line after the `About`/`Name` preamble latches the whole rest of the file, `Deck` section included. Fixed by giving `TextFormat` the same explicit `Deck`/`Mainboard`/`Sideboard`/`Commander`/`Maybeboard` header recognition [MtgaFormat] already has for its own format, plus skipping `About`/`Name …` as metadata instead of parsing them as bogus 1-count cards. |
| 0079 | A library search offers the whole library, not the legal fetches | 0057, 0072 | **Defect found live (Pete, 2026-08-20).** Activating Marsh Flats let the ability go on the stack, but the resulting picker offered no way to select a card. Root cause, traced into upstream directly: `TargetCardInLibrary`/`HumanPlayer.chooseTarget` send `cardsView1` as the *entire remaining library* with `targets = null`, while the real, narrow legal set lives only in `options["possibleTargets"]` — a different shape from story 0072's `PICK_ABILITY` (also `targets = null`, but there the candidate set genuinely *is* the answer set). The bridge's existing 0072 fallback treated both shapes the same, offering the whole library as if every card were fetchable. Fixed by preferring `possibleTargets` over the whole-`cardsView1` fallback when present, and narrowing the board's candidate-card panel to what is actually pickable rather than showing the prompt's raw card list. |

---

## EPIC-18 — Multiplatform Foundation

The first epic of the UI rebuild, and it runs **before** the UI work rather than after it: the
epic's cost scales with how much code exists when it happens, and Phases 1–4 are about to add a
great deal. See [`../ui-modernization-plan.md`](../ui-modernization-plan.md) §9 and §11 Phase 0.

**The UI does not move.** On Android, Compose Multiplatform *is* Jetpack Compose — this epic is
`:core:*`, `:protocol` and DI. Nothing in `:feature:*` or `:app` changes except its DI annotations.

**The `:core:*` order is fixed by the module graph, not by cost.**
`:core:cards` ← `:core:decks` ← `:core:network`, because a KMP module's `commonMain` cannot depend
on an Android library. That puts the hardest module first. Each of these stories is tracked by a
GitHub issue (see [Issue tracking](#issue-tracking)).

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0080 | KMP build foundation: `:protocol` and `:core:model` | — | The `magefree.kmp.library` convention plugin, and the two already-clean modules converted to KMP with a `jvm()` target. **No Kotlin source changes** — the story exists to prove a converted module still resolves from `:bridge` (JVM), `:app` and every Android library consumer before anything hard depends on it. |
| 0081 | Dependency injection: Hilt to Koin | 0080 | The one genuine multiplatform blocker, removed. 34 files across 10 modules: 10 `@Module @InstallIn` classes, 14 `@HiltViewModel`s, 18 `hiltViewModel()` call sites, one `@HiltAndroidApp`, one `@AndroidEntryPoint`, one `EntryPointAccessors` site. **Hilt fails at compile time and Koin fails at runtime**, so a module-graph verification test lands with it or the conversion is unverified. No Hilt test infrastructure exists, which lowers the risk. |
| 0082 | `:core:cards` to KMP | 0081 | The largest piece, and **first** among the `:core:*` conversions by dependency order. `SqliteCardCatalog` off `SQLiteDatabase`/`Cursor` onto the `androidx.sqlite` driver; the 14 MB bundled asset behind a platform boundary; Coil onto `coil-network-ktor3` without losing story 0056's `User-Agent`. Correctness is checkable by equality — same queries, same rows. |
| 0083 | `:core:decks` to KMP | 0082 | Room in its KMP configuration on the same driver, and `FormatBundleLoader` off `AssetManager` through 0082's resource boundary. The acceptance bar is an **upgrade over an existing deck library**, not a fresh install — a user's decks exist only on their device. |
| 0084 | `:core:network` to KMP | 0083 | Three Android-coupled files. Both observers are already behind interfaces and move to `androidMain` unchanged; DataStore construction comes off the `Context` delegate. Corrects §9.2, which recorded debt here that does not exist: `ServerRepository` takes a `DataStore`, not a `Context`. Saved servers must survive the upgrade. |
| 0085 | Robolectric out of the logic modules | 0082, 0083, 0084 | The `:core:*` logic suites move onto the `jvm()` target — 264 tests, which `./gradlew check` runs, and which is what makes the portability claim check itself rather than resting on a grep. Widened in flight to take `:core:network`'s 216 with it. Three tests stay on Android because the Android edge is their subject, as do the 5 Compose tests — they are the hermetic gate. |

**EPIC-23 does not wait for this.** Its work is bridge-side (already JVM) plus `:protocol` data
classes (already clean), and several of its items improve the current UI on their own.

## EPIC-23 — Game Information We Do Not Yet Map

Six fields the server sends on every snapshot and the bridge drops on the floor. See
[`../ui-modernization-plan.md`](../ui-modernization-plan.md) §7.4, §7.5, §7.13, §7.14, §7.15.

**It runs before what needs it, and it does not wait for anything.** The board epics — EPIC-19
(motion and presentation), EPIC-13 (targeting and combat), EPIC-11 (zone browser, stack, vitals) —
all render data that has to exist first. The work here is bridge-side (already JVM) plus `:protocol`
data classes (already clean), so it never depended on EPIC-18.

**Every story is the same shape, and it is the cheapest shape there is:** a correct upstream field,
threaded through unchanged. Story 0076's `transformed` and story 0058's `cardTypes` are the
precedent — no computation, no interpretation, no rules logic in the client. The server already
decided; the bridge just stopped listening.

**No story here renders anything**, so none carries an eyes-on checklist. What each one carries
instead is a **live check against the reference XMage server** (story 0022's `xmage-server`
service): a fixture proves the mapper reads a field, only a live game proves the server populates it
on the path we read. Where a case cannot be reached live without a contrived deck, the PR says so
rather than passing a fixture off as live coverage.

They touch the same two files (`GameMessages.kt`, `GameViewMapper.kt`), so **land them in order** —
the dependencies below are about merge conflicts, not about compilation. Each is tracked by a GitHub
issue (see [Issue tracking](#issue-tracking)).

| Story | Title | Depends on | What it delivers |
|-------|-------|------------|------------------|
| 0086 ([#140](https://github.com/VanoraSC/mage_free_client/issues/140)) | Spell and ability targets on the wire | — | `CardView.targets` → `GameCardView.targets`. The one piece of genuinely new data the stack needs, and what §3.1's targeting arrows draw. Threaded all the way to `GameState.GameCard`, because a field that stops at `:protocol` has not reached the app. Upstream de-duplicates through a `LinkedHashSet` and resolves through `game.getObject`, so a target that is itself a spell arrives the same way a permanent does — one flat id list. |
| 0087 ([#141](https://github.com/VanoraSC/mage_free_client/issues/141)) | Attachments in both directions | 0086 | `PermanentView.attachments`, `attachedToPermanent`, `attachedControllerDiffers`. The bridge maps only `attachedTo`, so a host cannot know what it carries and every permanent would have to scan the battlefield to find out. `attachedControllerDiffers` is §7.4's *"real and easily-missed board state"* — your Aura on their creature. Also the input §7.4's piling rule needs: a permanent carrying an attachment never piles. |
| 0088 ([#142](https://github.com/VanoraSC/mage_free_client/issues/142)) | Player counters and designations | 0087 | `PlayerView.counters`, `monarch`, `initiative`, `designationNames`. **Poison is a win condition and the app cannot see it.** Counters reuse the existing `GameCounterView` rather than inventing a second counter shape, and the kind stays a string because the set is open. |
| 0089 ([#143](https://github.com/VanoraSC/mage_free_client/issues/143)) | Command objects: emblems, commanders, dungeons, planes | 0088 | `PlayerView.commandList`, dropped entirely today. A polymorphic list of four upstream view types behind one `CommandObjectView` interface that already carries everything needed — id, name, rules, and a printing to resolve art by. Modelled as one flat type plus a kind code with `CardTypeCode`'s tolerant serializer, because they differ in what they *are*, not in what the app reads. |
| 0090 ([#144](https://github.com/VanoraSC/mage_free_client/issues/144)) | Zone contents: graveyard and exile cards, not just counts | 0089 | `PlayerView.graveyard` / `exile` are `CardsView`; the bridge takes `.size` and discards the cards, which makes §7.13's zone browser unimplementable. Keeps the counts, preserves upstream ordering, and **measures the snapshot size delta** — §10 says measure real payloads before deciding anything about deltas, and a late-game graveyard is the largest thing this adds. |
| 0091 ([#145](https://github.com/VanoraSC/mage_free_client/issues/145)) | Token and copy identity | 0090 | `CardView.isToken` and `mageObjectType`. A token and a card look identical and behave differently, and §7.4's piling rule depends on telling them apart. Both fields, because `mageObjectType` answers copy-versus-original off the same read. |

**What this epic deliberately does not do.** Every "out of scope" here is a rendering surface —
targeting arrows, the vitals overlay, the zone browser, token treatment. That is the point: this epic
makes the data exist so those epics can be about design rather than about plumbing.

## Known issues (accepted, not scheduled)

Deliberately logged rather than fixed. Each is bounded, self-healing, and has no user-visible effect;
the fix would cost more risk than the defect does. Revisit only if the impact changes.

| Issue | Observed | Why it is accepted |
|-------|----------|--------------------|
| **Sign-out sometimes parks instead of evicting.** Story 0046's `Logout` can lose the race with the socket close that follows — most reliably on a freshly-resumed socket — so the bridge sees a bare close and parks the session instead of tearing it down immediately. | 2026-08-11, during the lead's on-device smoke run: `11:29:54 Resumed …` → `11:30:47 Parked …` (that `Parked` was a sign-out). All nine sessions in the run did evict. | The session still goes away at the resume TTL (~60 s) and **nothing is orphaned**, so the cost is bounded and self-healing with no user-visible effect. Closing the race properly means reworking the teardown handshake, risking 0023/0024's park+resume — behaviour that is correct and load-bearing — for no observable gain. Noted in `KtorBridgeClient.signOut()`. |

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

8. **Epic 10 (card database):** 0030 → 0031 → 0032. ✅

9. **Epic 9 (deck builder):** 0033 → 0034 → 0035. ✅ (all deck ops offline; only art is networked)

10. **Epic 7 (hosting & joining tables):** 0036 → 0037 → 0038, with 0040 + 0041 fixing it end to end;
    **0059 + 0060 outstanding** (two defects found while hosting by hand during 0057). ⚠️

11. **Epic 11 (in-game play):** 0051 → 0052 → 0054 → 0055 → 0057 → 0058 → 0061 built;
    **0062 + 0063 + 0064 + 0065 + 0066 specified**. ⚠️ **0067 (Commander) parked** — a future increment.

**Current state — a game is playable from the app, including combat.** Hosting works end to end, and
the board renders a live game and answers the server's prompts: a full turn has been played on-device
against an AI — mulligan, land, cast, cancel, recast, targeting, mana, resolution, priority and turn
advance. Combat (0061) is built: attackers and blockers are declared by tapping, with the server's
pairing questions answered the same way. **A full best-of-N match is not yet playable** — see 0064.

**Known gaps toward "a full game/match is playable end to end."** A 2026-08-15 pass through `../mage`
source (not guesswork) found three concrete gaps and closed one open question:
- **0062** (convoke/delve) — a confirmed defect: the board has no way to pay these costs at all today.
- **0063** (stops/auto-pass) — manual-only priority (§9.1/§14.1) works but is tedious over a full game;
  the desktop mechanism it should eventually match is now traced and specified (§14.3).
- **0064** (between-games sideboard) — the one piece of "a full **match**," not just one game: today's
  board has no story, no UI, and (unlike 0062/0063) a genuine **`:protocol`/`:bridge` gap** — requirements
  §12.1's original claim that the deck payload already crossed the bridge was checked against source and
  is wrong; `SideboardMapper` drops it. A match that reaches game 2 has nothing to sideboard with.
- Combat damage among multiple blockers/trample (requirements §19) is **not** an open design
  question — it is the same `GetMultiAmount` prompt already proven live for Forked Bolt's damage
  division. Needs a short live combat probe to confirm, not new design.
- **0065** (battlefield stacking) — a board-space feature (lands/tokens crowding the battlefield), no
  protocol change; see requirements §20.
- **0066** (graveyard/companion/looked-at card identity) — a confirmed defect: `playable` already
  includes graveyard-castable spells (Flashback and its relatives), but they render as
  `"Unnamed candidate N"` because the bridge only reads the graveyard's *size*, not its cards.

A **2026-08-15 systematic review of alternative/activation costs, Commander, and companion**
(requirements §21) found every mechanic checked already routes through the existing prompt set — no new
protocol work needed there — except the graveyard-identity gap above (0066) and the whole Commander
format, which is **parked as story 0067** for a future increment (Pete's call, not in the first
playable board) but fully traced and specified so it needs no re-deriving when picked up.

Two Epic 7 defects found while hosting by hand are specified as **0059** (deck submission offered in
states where upstream ignores it) and **0060** (the host form hardcodes 7 of the server's 52 deck
types). Neither blocks play: a match starts because `joinTable` binds the host's deck at creation.

**Beyond that:** **EPIC-08** (tournaments / draft / sealed) is the remaining unstarted
branch. The 10 → 9 → 7 ordering was deliberate: cards and decks came first. Epic 5's notifications
track remains deferred; the downstream epics (08, 11–17) are not yet broken into stories.
