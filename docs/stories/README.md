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

Established by story 0001; a story states only its **deltas** from this and pins exact
versions (avoid "latest" — it is ambiguous and non-reproducible).

- **JDK 17** — the `:bridge` toolchain is `jvmToolchain(17)`. Ensure a JDK 17 is installed and
  `JAVA_HOME` points at it; Gradle needs a JVM to launch and none may be on `PATH`.
- **Gradle via the committed wrapper** (currently **9.3.1**) — always `./gradlew`, never a
  local Gradle. Note: Gradle 9 requires `junit-platform-launcher` on the test runtime classpath.
- **Kotlin / Ktor / kotlinx-serialization / logback / JUnit 5 / ktlint** — all pinned in
  `gradle/libs.versions.toml`; no hard-coded versions in build files.
- **Gate:** `./gradlew check` (lint + tests) must pass.

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

Downstream epics continue the numbering from `0016`.
