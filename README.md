# Mage Free Client (Android)

A **standalone, native Android client for XMage** — the open-source Magic: The Gathering
rules engine ([magefree/mage](https://github.com/magefree/mage)).

## Intent

The goal of this project is to **mirror the functionality of the XMage Desktop client**
(`Mage.Client`) — connect to XMage servers, browse/join tables and tournaments, build
decks, and play full rules-enforced games — while **deliberately diverging, heavily, on
the UX**.

The Desktop client is a Swing application designed for large, mouse-driven screens with
dense, always-visible panels. **We are not porting that UI.** A phone is a small,
touch-first, portrait-or-landscape device held in the hands. The interaction model,
information density, navigation, and layout will all be redesigned from scratch for that
context. Where the Desktop client shows everything at once, the mobile client will favor
progressive disclosure, gestures, context-sensitive surfaces, and focus on the current
decision.

> **Principle:** Feature parity with the Desktop client is the target. UI/UX parity is a
> non-goal — in fact, an anti-goal. See [`docs/ux-principles.md`](docs/ux-principles.md).

## Status

🚧 **Pre-implementation / planning.** No app code yet. This repo currently holds the
project intent, the incremental build plan, an architecture analysis of how XMage's client
talks to its server, and the engineering/agent guidelines we'll build under.

**Approach:** goal is behavioral/protocol parity with the desktop client against the server,
wrapped in a phone-native UX. Every feature is verified against a locally-run XMage server;
we don't build UI against invented game states. The feature plan is organized into epics in
[`docs/project-plan.md`](docs/project-plan.md); the foundation is the JVM bridge described in
[`docs/architecture.md`](docs/architecture.md).

## Key architectural challenge (read this first)

XMage's Desktop client and server communicate over **JBoss Remoting 2.5.4 (bisocket
transport) using Java object serialization** of the shared `mage.view.*` view objects,
plus a callback channel for server→client push. This stack targets Java 1.8 and **does not
run on Android** (JBoss Remoting relies on JVM APIs and reflection-based serialization
that Android's runtime does not support).

This means the Android client **cannot speak XMage's native wire protocol directly.** The
integration strategy — most likely a protocol-translating bridge or a modern
(WebSocket/JSON) server endpoint — is analyzed in
[`docs/architecture.md`](docs/architecture.md). Read it before writing any networking code.

## Repository layout (planned)

```
mage_free_client/
├── README.md              # this file — project intent
├── AGENTS.md              # engineering standards & agent instructions (canonical)
├── CLAUDE.md              # imports AGENTS.md for Claude Code
├── docs/
│   ├── project-plan.md    # feature plan — epics & user stories (start here)
│   ├── architecture.md    # XMage integration analysis + transport challenge
│   └── ux-principles.md   # mobile-first UX direction (how we diverge)
└── (bridge/, app/, core/, feature/ modules to come)
```

## Related

- Upstream engine & Desktop client: `../mage` (the cloned `magefree/mage` repo)
- XMage wiki: https://github.com/magefree/mage/wiki
