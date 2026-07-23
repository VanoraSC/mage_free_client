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
4. **Design & approach** — the intended structure: modules, key types, protocol/schema,
   upstream APIs to call, and how correctness is preserved.
5. **Implementation steps** — an ordered, concrete path.
6. **Testing & verification** — unit and (where relevant) integration tests, plus exact
   commands. Correctness is verified against a locally-run XMage server, never invented data.
7. **Acceptance criteria** — a checklist that defines done.
8. **References** — files and docs to read.

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

Downstream epics continue the numbering from `0007`.
