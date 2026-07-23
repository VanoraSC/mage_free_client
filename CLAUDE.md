# CLAUDE.md

Project guidance for Claude Code. The canonical engineering standards and agent
instructions live in **[`AGENTS.md`](AGENTS.md)** — read it in full.

@AGENTS.md

## Quick orientation

- **What this is:** a native Android (Kotlin/Compose) client for the XMage MTG engine.
  Feature parity with the XMage Desktop client, but a **from-scratch, mobile-first UX** — do
  not port the Swing UI. See [`README.md`](README.md) and
  [`docs/ux-principles.md`](docs/ux-principles.md).
- **Server is authoritative.** No MTG rules on device. The app is a networked
  view + controller.
- **Read before networking code:** [`docs/architecture.md`](docs/architecture.md) — XMage's
  native JBoss-Remoting transport can't run on Android; we go through a modern bridge.
- **Upstream engine** is at `../mage` — reference only, never an Android dependency.
