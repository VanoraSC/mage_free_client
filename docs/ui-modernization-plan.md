# UI/UX Modernization Plan

**Status:** the platform decision (§8) is **committed — Compose Multiplatform**, decided by Pete
on 2026-08-22. The UX system (§7), feature tiering (§6) and roadmap (§11) remain proposals pending
review, and §12 still lists open questions.
**Date:** 2026-08-22.

This plan answers three questions in order, because they depend on each other:

1. **What should the UI actually do?** — researched against MTG Arena and MTG Online, the two
   commercial clients that have solved this, plus what our own server demands that neither of
   them handles.
2. **What UX system do we build to do it?** — the interaction, motion and layout model, stated
   concretely enough to write stories against.
3. **What do we build it in?** — Flutter vs Compose Multiplatform vs the alternatives, decided
   on the actual cost against this codebase, with iOS as a first-class target.

Read alongside [`ux-principles.md`](ux-principles.md) (still valid — this extends it),
[`game-board-requirements.md`](game-board-requirements.md) (the measured, source-verified
behaviour of the server; this plan does not contradict it), [`architecture.md`](architecture.md)
and [`project-plan.md`](project-plan.md).

---

## 1. Where we actually are

An honest inventory, because the plan depends on it.

### 1.1 What exists

| Layer | Modules | Main LOC | Test LOC |
|---|---|---:|---:|
| **Server-side** (unaffected by any client choice) | `:bridge` | 4,681 | 6,675 |
| **Wire contract** (shared bridge↔app) | `:protocol` | 2,411 | 903 |
| **Client logic** (no UI, portable) | `:core:model`, `:core:network`, `:core:cards`, `:core:decks` | 9,584 | 10,437 |
| **Client UI** | `:core:designsystem`, `:feature:*`, `:app` | 17,624 | 12,317 |

The client logic layer is the valuable, hard-won part: the `GameViewMapper`, `GameEventFold`,
`GameClient`/`TableClient` session machinery, deck legality, catalog search. It is ~9.6k lines
backed by ~10.4k lines of tests, most of them recordings of *real bridge output against a real
server*. Story 0076 (transformed-permanent art) took four rounds against a live server to get
right. That is the bug density in this layer, and it is the reason the tests exist.

### 1.2 What the UI is honestly like today

`:feature:game` is a working proof of concept and reads like one:

- **Fixed-height bands.** `VitalsBarHeight = 44.dp`, `StatusRailHeight = 132.dp`,
  `StackStripHeight = 56.dp`, `HandPeekHeight = 64.dp`. The battlefield is a horizontally
  scrolling row inside a fixed band. This is a layout that *never fails*, which is exactly what a
  POC needs and exactly what a real client cannot be.
- **Nothing moves.** There is no animation anywhere on the board. A card going hand → stack →
  battlefield → graveyard simply appears in a different band on the next snapshot. In a
  hidden-information game, motion is not decoration — **it is the only channel that carries
  causality**. This is the single biggest gap between what we have and something people would
  choose to play on.
- **No spatial affordance.** Nothing glows because it is castable. Targeting is a candidate list
  (`CandidateRow`) rather than the board itself lighting up. Combat is a text-labelled
  declaration context, not attackers moving into a red zone.
- **Text-forward, not card-forward.** `BoardCardFace`, `CounterLine`, `zoneCountsLabel()` — the
  board communicates in labels. Art is present but incidental.
- **Portrait-only, phone-only.** No landscape, no tablet, no foldable, no desktop.

None of that is a criticism of the work — the requirements doc was explicit that correctness came
first and the board would be rebuilt. This is that rebuild.

### 1.3 What is genuinely good and must survive

- **The server is authoritative and we already speak it correctly.** No rules engine on device.
- **Every game object has a stable id across snapshots.** This is the precondition for animating
  between snapshots and we already have it. Do not lose it.
- **The prompt model is right in principle** (floating, non-modal, board-interactive prompts stay
  non-modal — §16.2 of the requirements). It just looks like a debug overlay.
- **The bridge is a network service** (WebSocket + JSON). See §8.1 — this is the fact that makes
  iOS possible at all.

---

## 2. The design thesis

> **MTGO's information model. Arena's presentation. Built for a phone first, and a screen second.**

The two commercial clients fail in opposite directions and we can take the good half of each:

- **MTGO tells you the truth.** It always shows exactly what the game is asking, what is on the
  stack, what is being auto-handled, and what your options are. Its Prompt Box is the best single
  idea in either client. It is also visually incoherent and hostile to a newcomer.
- **Arena makes it legible.** Motion, glow, arrows, and spatial grouping mean a player reads the
  board without parsing it. It is also *aggressively* automated in ways that silently take
  decisions away from you, and it cannot express a large fraction of Magic.

Our server is XMage: it enforces the full rules, asks for everything explicitly, and expects a
client that can express every decision. That means **we cannot take Arena's automation-first
model** — we need MTGO's completeness. But we are on a phone, so we cannot take MTGO's density
either. The synthesis is: *express everything MTGO expresses, present it the way Arena presents
it, and surface only the decision at hand.*

---

## 3. Research: MTG Arena — what to take

Sources in §13. Grouped by what it means for us.

### 3.1 Board presentation (take almost all of this)

| Arena feature | Why it matters | Our position |
|---|---|---|
| Cards animate between zones | Only channel that carries causality | **P0 — take** |
| Playable-now highlight/glow on hand and permanents | Removes "can I do this?" guesswork entirely | **P0 — take** |
| Targeting arrows from source to target, persistent while on stack | Makes the stack readable at a glance | **P0 — take** |
| Attack/block arrows in combat | Combat is two assignment problems (req. §7.4); arrows show the assignment | **P0 — take** |
| Counters rendered on the card face (+1/+1, loyalty, charge) | Board state readable without inspecting | **P0 — take** (0058 has the data) |
| Tapped = rotated 90° | Universal Magic idiom; cheaper to read than a badge | **P0 — take** |
| Life total change animates with a ±N delta | You notice what happened to you | **P1 — take** |
| Stack as a fanned centre pile that expands when large, collapsible | Our stack is a 56dp strip today | **P0 — take** |
| Spotlight/zoom on the object currently resolving | Turns "the log scrolled" into "I saw it" | **P1 — take** |
| Damage numbers float off creatures | Combat math becomes visible | **P2** |
| Phase bar with clickable per-phase stops, colour-coded by whose turn | Stops are a real feature (0063); this is the right control for it | **P0 — take** |
| Rope/turn timer | We have no clock story at all yet | **P1 — decide** (see §12) |
| Keyword reminder text on demand | Huge for less-experienced players | **P1 — take** |
| Gameplay warnings ("you still have untapped mana") | Cheap, high-value guard-rail | **P2 — take, opt-in** |
| Emotes | Constrained, non-toxic communication | **P2** |
| Card styles / alternate art, per-card and per-deck | **Already an explicit EPIC-11 requirement** | **P1 — take** |

### 3.2 Arena's automation model (take the *controls*, reject the defaults)

Arena's gameplay settings: Auto Tap, Auto Order Trigger Abilities, Auto Choose Replacement
Effect, Auto Assign Combat Damage, Show Phases, Keyword Reminders, Gameplay Warnings, plus
Full Control (hold `Ctrl`) and Undo (`Z`) for uncommitted mana actions.

Every one of these has a direct analogue in what our server asks for, and
[`game-board-requirements.md`](game-board-requirements.md) §6.5, §14, §16.5 and §17.1 already
establish that **the server proposes a mana solution** and that **declining a target rewinds
cleanly**. So:

- **Take the toggles.** Auto-tap-with-manual-override, auto-order-identical-triggers,
  auto-assign-damage-among-blockers, all individually switchable.
- **Default them to off, not on.** Our audience is people who chose an XMage client. Arena's
  defaults exist to protect new players from Magic; ours should not take decisions silently.
- **Take Undo.** The rewind semantics are already proven server-side (§17.1). Exposing them as a
  visible **Undo** control is nearly free and is the single best safety net on a touchscreen,
  where misfires are the dominant failure mode.
- **Take Full Control as a *mode*, not a held key.** No `Ctrl` on a phone. A pinned toggle.

### 3.3 Outside the board

- Home hub with one dominant path to play (EPIC-02 already models this).
- Deck manager as art-driven "deck boxes", not a list of strings.
- Deck builder with a **query syntax** (`mana>=3 mana<=5`, `owned:false`, colour identity) *and* a
  filter pane — power users type, everyone else taps. Arena's own builder is widely criticised as
  bare-bones for multi-type filtering and mana-curve visualisation; that is a low bar to clear.
- Mana curve, type counts, and legality as live feedback while editing.
- Text paste import/export (we have this — 0034, 0078).

---

## 4. Research: MTG Online — what to take

### 4.1 The Prompt Box — the most important single idea

MTGO's Prompt Box is "the main source of information on what can or must be done to progress the
game." It highlights when action is required and it is *always in the same place*. Everything the
game asks you flows through it.

We have `PriorityBanner` + `FloatingControls`, which is the same idea, unpolished. **Elevate it to
the organizing element of the board.** One surface, fixed position, thumb-reachable, always
answering "what is being asked of me right now," escalating visually when it is your decision.
Requirements §3.3, §6.2 and §16.2 already point here; this is the commercial proof that it works.

### 4.2 The automation ledger (the thing Arena does *not* have)

MTGO's auto-yield system is much richer than Arena's, and critically it is **visible and
revocable**:

- Right-click a stack object → *yield to X until end of turn* / *always yield to X* /
  *always yes to X* / *always no to X*, including name-scoped variants
  ("always yield to triggers from cards named X you control").
- A **Yields Alert panel** listing every active yield.
- A single key (`F3`/`5`) that **clears every auto-yield and automated decision**.

This is the correct design for automation and we should copy it wholesale. The number-one failure
mode of automation is *silent* automation — the game does something on your behalf and you cannot
tell what or undo it. Our version:

- **Long-press any stack object or permanent → automation menu** (touch equivalent of right-click).
- **A persistent, one-tap "Automation: 3 active" chip** on the board that opens the ledger.
- **A single "stop automating" control** that clears everything.

### 4.3 Zones, including the one everybody forgets

MTGO's pop-out zones: Graveyard, Exile, **Revealed**, and **Effects** (replacement effects and
emblems awaiting application). That fourth one is genuinely rare and genuinely useful — continuous
and replacement effects are invisible in most clients and they decide games.

We already track known information (0053) and looked-at/revealed identity (0066). Adding a
first-class **Effects & Emblems** zone is a data question for the bridge (does `GameView` expose
it? — needs verification against upstream before it becomes a story) and a small UI question.

### 4.4 Clocks

MTGO runs a **chess clock** per player plus a match timer; running out loses you the match
regardless of board state. We currently have no clock concept anywhere. This is a real gap for
anything resembling competitive play, and it is a **bridge + protocol** question first (does the
server drive timers, or does the client?). Flagged as an open question in §12.

### 4.5 Layout and hotkeys

- MTGO lets you drag grid splitters to resize every panel, and **export/import a settings file**.
- Extensive, **rebindable** hotkeys (`Q` zoom, `E` view face-down, `1`–`8` for priority actions).

On a phone, splitters are the wrong idiom. But if we ship a desktop build (§8.5), both of these
become directly relevant, and hotkeys are the difference between "playable" and "fast" for a
serious player.

---

## 5. What XMage demands that neither client has

Do not lose these to a prettier design. All of it is already measured and documented in
[`game-board-requirements.md`](game-board-requirements.md):

- **Manual mana payment is the baseline**, with the server's own proposed solution offered as the
  fast path (§6.5) — not Arena's opaque auto-tap.
- **`SpecialAction` costs** — convoke, delve, companion (§18, §21.4). These are alternative-cost
  prompts that Arena hides and MTGO exposes clumsily. We have the mechanism traced.
- **Cancel/rollback semantics with cascading rewind** (§16.5, §17.1), including the finding that a
  cancel is *not* pushed to the opponent (§17.4).
- **Simultaneous trigger ordering** (0072) — an explicit ordering UI.
- **Casting is one act, not a series of dialogs** (§6.4) — the organizing principle for prompt
  design and the thing most likely to be broken by a redesign.
- **Graveyard cards need individual identity** (§21.2).
- **Resync restores the outstanding prompt** (0074) — the reconnect path must land you back on the
  same decision, not a blank board.

---

## 6. Consolidated feature list

Tiered by whether it blocks "a real client people would choose."

### P0 — required for the board to stop being a POC

1. **Object-identity animation system.** Every game object tweens between snapshots: zone changes,
   taps, counter changes, entering/leaving. Nothing teleports.
2. **Fluid battlefield layout.** Card size scales to fit the population (with a legibility floor);
   grouping by role; the fan/pile system (0065) doing real work; no fixed-height scroll bands.
3. **Playable-now affordance.** Castable/activatable objects are visually distinct, everywhere.
4. **Spatial targeting.** Legal targets highlight *on the board*; an arrow is drawn from source to
   each chosen target; confirm before submit (§16.4). Candidate lists survive only as the fallback
   for off-board targets.
5. **Combat as spatial assignment.** Attackers move to a red zone; blockers connect with arrows;
   the two assignment problems stay separate (§7.4).
6. **The Prompt as the organizing element.** One authoritative, fixed-position, escalating surface.
7. **Stack as a real, expandable centre pile** with per-object inspection and long-press automation.
8. **Counters, P/T modifications, tap state, and status rendered on the card.**
9. **Phase bar with per-phase, per-player stops** driving 0063's auto-pass.
10. **Undo.** Exposing the rollback the server already supports.
11. **Card inspection v2** — full-bleed, oracle text, *current* modifications, activatable
    abilities, and the DFC flip control (0077).
12. **Adaptive layout** — phone portrait, phone landscape, tablet. One layout system, three
    densities.

### P1 — required for it to be *good*

13. **Automation ledger** (§4.2) — long-press menus, active-automation chip, clear-all.
14. **Auto-tap / auto-order / auto-assign toggles**, defaulted off.
15. **Full Control mode** as a pinned toggle.
16. **Effects & Emblems zone** (pending bridge data verification).
17. **Life-total deltas, resolution spotlight, keyword reminders.**
18. **Alternate art selection** per card and per deck (explicit EPIC-11 ask).
19. **Art prefetch at match start** — we know both decklists; warm the cache before turn one.
20. **Deck builder v2** — query syntax + filter pane, live curve/legality, art-driven deck boxes.
21. **Home hub, lobby, and tables rebuilt on the new design system.**
22. **Game log / history panel** with the server's own text.
23. **Clocks**, if we decide the server drives them (§12).

### P2 — polish and reach

24. Damage number floats; attack/block animation beats.
25. Gameplay warnings (opt-in).
26. Emotes.
27. Sound design and haptics.
28. Spectating on the new board (EPIC-15), including both players' hidden information.
29. Desktop build with hotkeys and resizable panels.
30. Replays.
31. Accessibility pass — *explicitly deferred per standing direction*, but the design system should
    not actively make it harder (semantic labels on components as we build them costs nothing).

---

## 7. The UX system we will build

Concrete enough to write stories against.

### 7.1 Interaction model

Unchanged in principle from [`ux-principles.md`](ux-principles.md) — **taps are the floor,
gestures are accelerators** — with the touch vocabulary made explicit and used consistently
everywhere:

| Gesture | Meaning | Everywhere |
|---|---|---|
| **Tap** | Select / act on this object | Yes |
| **Long-press** | Inspect *and* offer this object's automation + options menu | Yes |
| **Drag** | Accelerator only: play a card, assign an attacker to a defender | Always has a tap path |
| **Swipe up from hand edge** | Expand hand | Hand only |
| **Back** | Cancel the innermost thing (collapse hand → cancel targeting → cancel cast) | Yes |

The one rule that matters: **long-press is inspection, everywhere, on every card-like object, in
every screen.** Today that is inconsistent across the app.

### 7.2 The Prompt

One component, one position, three escalation states:

- **Idle** — states whose priority it is and what phase we are in. Low contrast.
- **Asking** — the server wants a decision. High contrast, thumb-reachable actions, states the
  question in the server's own words (cleaned of markup, as `stripServerMarkup` already does).
- **Board-interactive** — the decision requires touching the board (targets, attackers, blockers).
  The Prompt shrinks to a header + progress ("2 of 3 targets") + Confirm/Cancel, and **never
  blocks the board** (requirements §6.2, §16.2).

Casting stays **one act** (§6.4): choose card → the Prompt walks cost/target/mode steps in place,
with Cancel rewinding the whole act, never a chain of stacked dialogs.

### 7.3 Motion & object identity

This is the load-bearing new subsystem, so it gets designed rather than improvised.

- Every renderable game object has a **stable id** (we already have this) and a **single owning
  layout slot** per snapshot.
- The board is hosted in one shared coordinate space. When a snapshot changes an object's slot,
  the object **animates from its previous measured position to its new one** rather than being
  destroyed and recreated. In Compose this is `LookaheadScope` + shared-element/`Modifier.animateBounds`;
  in Flutter it is a `Hero`-like custom controller. Either way it is *the framework's* job, not ours.
- **Motion durations encode meaning, not taste.** Zone moves ~250 ms, taps ~150 ms, counter changes
  ~120 ms, resolution spotlight ~400 ms hold. A player must be able to follow a five-trigger chain
  without it feeling like a slideshow — so a **"reduce motion / fast" setting is P1, not P2**, and
  it must shorten durations rather than remove the animation (removing it removes the information).
- **Snapshots may arrive faster than animations finish.** The animation host must handle
  interruption by retargeting in flight, never by queueing. Queueing desynchronises the board from
  the server and that is unacceptable.

### 7.4 Board layout

Replace fixed bands with a **constraint-driven, three-density layout**:

- **Regions** (opponent battlefield, your battlefield, stack, hand, prompt, vitals) get
  *proportional* space with minimums, not fixed dp.
- **Card size is derived** from the widest populated row, floored at a legibility minimum; below
  the floor, the fan/pile system (0065) collapses duplicates and the row scrolls.
- **Phase-aware emphasis**: during combat, battlefields expand and hand contracts; during your
  main phase, hand expands. This is requirements §3 ("the board is a focus view") made mechanical.
- **Three densities** — compact (phone portrait), medium (phone landscape / small tablet),
  expanded (tablet / desktop) — chosen by window size class, sharing one component set.

### 7.5 Card rendering tiers

Three fidelity tiers, one component family, so a card looks like itself everywhere:

| Tier | Where | What it shows | Art |
|---|---|---|---|
| **Token** | Battlefield, stack piles | Name, P/T, counters, tap state, status | Downsampled, cropped to the art box |
| **Tile** | Hand, zone browsers, deck lists | Name, cost, type line, P/T | Downsampled full card |
| **Full** | Inspection, mulligan, sideboard | Oracle text, current modifications, activatable abilities, flip control | Full resolution |

Only the **Full** tier ever loads full-resolution art. This matters a lot for memory and for the
first-turn experience on a phone.

### 7.6 Automation ledger

- Long-press any object → menu including yield/always-yes/always-no scoped to that object *and* to
  its name.
- A persistent **"Automating N"** chip; tapping opens a list with per-entry revoke.
- One **Stop automating everything** action.
- Automation never applies to a decision that is not reversible without a matching entry visible in
  the ledger.

### 7.7 Outside the board

The same design system, applied: home hub, lobby/tables, deck library and builder, card search,
settings. These are lower risk and mostly a re-skin plus the P1 deck-builder work — but they are
what the app looks like before anyone reaches a game, so they are not optional.

---

## 8. The platform decision

### 8.1 The constraint that unlocks everything

**The bridge is a network service, not a library.** It runs on a JVM, embeds `mage-common`, speaks
JBoss Remoting to the XMage server on one side, and exposes **WebSocket + JSON** on the other
([`architecture.md`](architecture.md), Option A). Nothing about it runs on the device.

Two consequences, both large:

1. **iOS needs no JVM.** An iOS client only has to open a WebSocket and parse JSON. There is no
   Kotlin/Native-vs-JVM problem, no `mage-common` on device, no serialization interop. **iOS is a
   client-side-only project.**
2. **The framework choice is genuinely open.** Any framework that can do WebSockets and JSON can be
   our client. Flutter is not architecturally blocked. The decision is purely about cost and fit.

### 8.2 Framing the cost correctly

The UI is being rewritten either way — that is the entire point of this document. So the honest
question is **not** "how much UI code do we throw away." It is:

> **Does the rest of the client get rewritten too?**

| | Compose Multiplatform | Flutter | React Native | KMP + native UI (Compose + SwiftUI) |
|---|---|---|---|---|
| UI rewrite (wanted anyway) | ~17.6k LOC | ~17.6k LOC | ~17.6k LOC | ~17.6k × **2** |
| Client logic rewrite (pure cost) | **0** | ~9.6k LOC | ~9.6k LOC | 0 |
| Logic tests rewrite (pure cost) | **0** | ~10.4k LOC | ~10.4k LOC | 0 |
| Protocol types re-derived | **0** | ~2.4k LOC in Dart | ~2.4k LOC in TS | 0 |
| Language count | 1 (Kotlin) | 2 (Dart + Kotlin for bridge) | 2 (TS + Kotlin) | 3 (Kotlin, Swift, Kotlin) |
| Desktop target | Free | Free | Poor | No |

**Flutter's pure cost is ~22,400 lines of re-derivation with zero user-visible benefit** — and it
is the *highest-bug-density* code in the project. Story 0076 needed four rounds against a live
server. Story 0079, 0074, 0072, 0066 — all of those are logic-layer corrections found by playing
real games. Rewriting that layer in Dart does not carry the fixes across; it re-opens the search
for every one of them, against a server we have to be live-connected to in order to find them.

### 8.3 The dependency check — the decisive detail

Every third-party library this client already uses is **already Kotlin Multiplatform**:

| Dependency | In use | KMP status |
|---|---|---|
| Ktor client 3.5.1 | `:core:network` | ✅ Multiplatform — OkHttp engine on Android, **NSURLSession (Darwin) on iOS** |
| kotlinx-serialization 1.11 | protocol + network | ✅ Multiplatform |
| kotlinx-coroutines 1.10 | everywhere | ✅ Multiplatform |
| Coil 3.1.0 | `:core:cards` art loader | ✅ **Multiplatform since 3.0** — Android, iOS, JVM, JS, Wasm; one `AsyncImage` in `commonMain` |
| Room 2.7.1 | `:core:decks` | ✅ **Multiplatform since 2.7** — `@Entity`/`@Dao`/`@Database` in `commonMain` |
| DataStore 1.1.7 | prefs | ✅ Multiplatform |
| Lifecycle / ViewModel 2.9 | `:feature:*` | ✅ Multiplatform |
| Navigation | `:app` | ✅ CMP navigation available |
| Material 3 / Compose Foundation | design system | ✅ Compose Multiplatform |
| **Hilt** | DI, every module | ❌ **Android-only — the one real port** (→ Koin, or a hand-written graph) |
| **Robolectric** | `:core:cards`, `:core:decks`, `:feature:game` tests | ⚠️ Android-only — those tests move to common/JVM equivalents |

That is a remarkably clean bill of health. The multiplatform port is **mostly build-system work
plus a DI swap**, not a rewrite. We did not plan for this — it fell out of choosing modern
libraries — but it is a large, real asset and it would be strange to discard it.

Compose Multiplatform itself is **stable on Android, iOS and Desktop** (iOS went stable in 1.8.0,
May 2025) with Web/Wasm in beta, and has substantial production adoption.

### 8.4 The fair case for Flutter

Stating it properly, because it is not a weak case:

- **Animation and custom rendering are Flutter's home turf.** Impeller gives predictable frame
  timing; the implicit-animation and `Hero` APIs are mature; Rive/Lottie integration is excellent.
  For a board full of moving cards, that is exactly the workload.
- **Faster iteration.** Flutter's hot reload is better than CMP's iOS loop today.
- **Bigger ecosystem for game-like UI**, more examples, more hiring reach.
- **One rendering path on both platforms**, so "looks identical on iOS" is not a question.
- CMP on iOS still has real rough edges: text input/IME fidelity, accessibility, scroll physics
  matching UIKit, native interop friction, and binary size from bundling Skiko.

The counter is that our board is **not** a game engine. It is a few dozen card widgets, tweened
positions, some arrows, and glow. Compose's `Animatable`, `LookaheadScope`, shared-element
transitions and `graphicsLayer` are sufficient for that, and CMP's rendering path is Skia — the
same engine lineage Flutter used until Impeller. The animation argument is real but it is not worth
~22k lines of re-derived, bug-prone logic.

### 8.5 Decision — committed 2026-08-22

> **Compose Multiplatform**, with Android + iOS + **Desktop** targets. Not Flutter.

Three reasons, in order of weight:

1. **It does not re-open solved problems.** The ~9.6k lines of client logic and ~10.4k lines of
   tests that encode everything we have learned from live play survive untouched.
2. **The port is unusually cheap** because every dependency is already multiplatform. Hilt is the
   only genuine swap.
3. **Desktop comes free, and it is a development accelerator.** A desktop build of the board runs
   against the bridge with no emulator, no APK install, no `adb`. Given the standing directions
   that Pete builds and deploys APKs himself and that we do not drive screens programmatically,
   a desktop target is the fastest possible path to *eyes on the board* — for both of us. It also
   happens to be what a serious XMage player actually wants.

This was originally written as a recommendation gated on a decision-spike. **Pete decided in favour
of CMP on 2026-08-22 without the gate**, so the spike is no longer a decision instrument. The
residual risk it was meant to retire — can CMP hit frame budget on a populated board, and is the
iOS build story as advertised — is real and still needs retiring, so Phase 1 is sequenced to prove
it **vertically and early** rather than in a throwaway (see §11).

Flutter remains the documented fallback if that vertical proof fails badly, but the bar for
reopening this is a measured failure, not a preference.

### 8.6 Explicitly rejected

- **React Native.** Same logic-rewrite cost as Flutter, weaker story for a heavily animated
  board, and would need `react-native-skia` to do what CMP and Flutter do natively.
- **Native twice (Compose + SwiftUI).** Best possible platform fidelity, but it doubles the UI
  work permanently. For a project with one person directing, that is the wrong trade.

---

## 9. Cross-platform and iOS concerns

Design decisions to make **now**, so the iOS port later is mechanical rather than a redesign.

### 9.1 Hard prerequisite: transport security

iOS App Transport Security requires HTTPS/WSS by default. Our bridge currently serves plain
WebSocket to a LAN address. **Story 0068's TLS/nginx work is not optional for iOS — it is a hard
prerequisite**, and it should be re-scoped with that in mind. (That branch is currently unmerged
and untouched pending Pete's direction; this plan does not change that.)

Related: the WSL portproxy problem (bridge reachable only on loopback, needing an IP-specific
`netsh portproxy` that breaks on DHCP change) is a *development* blocker that will bite twice as
hard with a second device platform. Worth solving properly as part of the same work.

### 9.2 Design constraints that keep iOS cheap

- **No Android-shaped navigation.** No hardware back as the only path — every "back" affordance
  must exist on screen. Our Back-cancels-innermost rule (§7.1) needs a visible equivalent.
- **Safe areas and gesture zones as first-class layout inputs.** The iPhone home indicator sits
  exactly where the "thumb-reachable primary actions" live. `:core:designsystem/layout/Insets.kt`
  already exists — it must be the *only* place insets are handled.
- **Platform-idiomatic where it is cheap, ours where it matters.** The board is entirely ours on
  both platforms. Settings lists, sheets and scroll physics should feel native — CMP's Material
  components will not, so budget for a small platform-adaptive component set.
- **No platform APIs in shared code.** Everything device-specific behind `expect`/`actual`:
  storage paths, notifications, secure credential storage, share sheets, haptics.

### 9.3 Platform work that is genuinely separate

- **Push notifications** — FCM (Android) vs APNs (iOS). "It's your turn" (EPIC-05) needs a
  **bridge-side push service**, which does not exist yet and is a backend story, not a UI one.
- **Background socket behaviour** — iOS suspends sockets aggressively. The reconnect/resync path
  (0024, 0070, 0074) is already good; it needs iOS-specific testing, not redesign.
- **The bundled card catalog** (~14 MB SQLite asset, `AssetManager`-based on Android) needs a
  multiplatform resource story.
- **Coil's network layer** must move from `coil-network-okhttp` to `coil-network-ktor3` so it works
  on both.

### 9.4 The risk that is not technical

**App Store review.** An unofficial Magic client that displays Wizards' card names and hotlinked
card art has a materially different risk profile inside Apple's review process than it does as a
sideloaded APK. This is a Pete decision, not an engineering one, and it should be made *before*
we spend on the iOS target — see §12.

---

## 10. Performance engineering

The board is the only performance-sensitive surface. Targets and how we hit them.

**Targets:** 60 fps sustained on the board on a mid-range Android device, 120 fps where the display
allows; board interactive within 1 s of the first snapshot; no frame drop when a snapshot arrives
mid-animation.

1. **Snapshot payload size.** [`architecture.md`](architecture.md) open question #7 —
   "how much of `GameView` does a phone actually need per frame, and how do we diff/delta it?" — is
   still open and now matters. Measure real payloads first; only then decide on deltas. **Measure
   before optimizing** is the rule here.
2. **Recomposition scoping.** The board must not recompose wholesale per snapshot. Stable keys per
   object id, `derivedStateOf` for computed board facts, and a fold that produces
   *structurally-shared* state so unchanged objects are `equals`-identical across snapshots. Our
   `GameEventFold` already folds events into state — this is a property to assert in tests, not
   hope for.
3. **Art pipeline.** Two decoded sizes (board token, full inspection), never one. Prefetch both
   decklists' art at match start — we know them. Explicit memory and disk cache budgets. This is
   the difference between a smooth first turn and a stuttering one.
4. **Animation host cost.** One shared layout pass, not per-card animation coordinators.
   Interruption retargets in flight (§7.3).
5. **A performance test gate.** Frame-timing assertions on a scripted board sequence (a recorded
   game replayed through the fold) so regressions are caught pre-merge, consistent with how we
   already gate correctness hermetically.

---

## 11. Roadmap

Phased so that **the Android app keeps working at every step** and each phase is independently
valuable.

### Phase 0 — ~~Decide~~ *(closed: decided 2026-08-22, CMP)*

Originally a decision-spike. Its risk-retirement purpose is folded into Phase 1a below, which
proves the whole vertical — including iOS and a real animated board frame — before the broad
mechanical port begins.

### Phase 1 — Multiplatform the core (no UI change)

Convert `:protocol`, `:core:model`, `:core:network`, `:core:cards`, `:core:decks` to KMP.
Hilt → Koin. Coil → `coil-network-ktor3`. Room → KMP. Robolectric tests → common/JVM.
**Success criterion: the existing Android app is behaviourally identical and every existing test
still passes.** Highest value, lowest risk, mostly mechanical.

Sequenced **narrow-and-deep first, wide second**, so nothing large is ported before the platform
is proven end to end:

- **Phase 1a — the vertical proof.** Take the two smallest, dependency-free modules —
  `:core:model` (389 LOC, zero dependencies, pure Kotlin) and `:protocol` (2,411 LOC, only
  kotlinx-serialization) — to KMP, and stand up an Android + iOS + desktop CMP app target that
  renders **one recorded real board snapshot** with card art (Coil 3) and one zone-move animation.
  This is simultaneously Phase 1's first step and the risk retirement Phase 0 was for.

  **Measure and report before continuing:** frame time on a populated board, iOS binary size,
  build/iteration time on each target, and the actual friction of the Hilt→Koin swap. A bad result
  here costs two small modules, not the port.

- **Phase 1b — the wide port.** `:core:cards`, `:core:decks`, `:core:network`, and the DI swap
  across the app. Only after 1a reports clean.

### Phase 2 — Design system v2

New tokens (colour, type, elevation, motion), the three-tier card component family (§7.5), the
Prompt component (§7.2), and — the new subsystem — the **object-identity animation host** (§7.3),
built and tested standalone before any board depends on it.

### Phase 3 — The board

**Pete-led design session first, per standing direction.** P0 items 1–12. This is the largest
phase and should be broken into stories only after that session, against this document's §7.

### Phase 4 — The rest of the app

Home, lobby, tables, deck library and builder v2, card search, settings — rebuilt on the Phase 2
system. P1 items 18–22.

### Phase 5 — iOS

Nav shell, `expect`/`actual` platform layer, safe-area pass, push service (bridge-side), TLS
prerequisite (§9.1). Gated on the §12 App Store decision.

### Phase 6 — Desktop and polish

Desktop target with hotkeys and resizable panels (§4.5), P2 items.

### Proposed new epics

The existing epics stay; these are added or amended:

- **EPIC-18 — Multiplatform Foundation.** Phase 1. Shared client core across Android/iOS/desktop.
- **EPIC-19 — Motion & Board Presentation.** The animation host, card tiers, layout system.
  Amends EPIC-11 rather than replacing it.
- **EPIC-20 — Automation Ledger.** Extends EPIC-12 with MTGO-style visible, revocable automation.
- **EPIC-21 — iOS Client.** Phase 5.
- **EPIC-22 — Desktop Client.** Phase 6.

---

## 12. Open questions — Pete's calls

These change the plan and I am not deciding them:

1. ~~**Framework — confirm the spike gate.**~~ **Answered 2026-08-22: Compose Multiplatform, no
   gate.** The spike's risk-retirement role is folded into Phase 1a (§11).
2. **App Store risk (§9.4).** Is an iOS App Store release actually the goal, or is iOS for personal
   / TestFlight / sideload use? This changes how much the Phase 5 investment is worth and whether
   the art-hotlinking approach needs to change at all.
3. **Clocks (§4.4).** Do we want per-player chess clocks? First question is whether the *server*
   drives them — that needs verifying in upstream source before it is a story, and it is a
   bridge/protocol change, not a UI one.
4. **Automation defaults.** I have proposed defaulting every automation toggle **off** (§3.2) on
   the grounds that our audience chose an XMage client. Confirm, or say which should default on.
5. **Effects & Emblems zone (§4.3).** Worth verifying against upstream `GameView` before it becomes
   a story — want that investigated now or deferred?
6. **Desktop target.** I am recommending it partly as a *development* accelerator (§8.5). Do you
   want it treated as a real shipping target, or purely a dev tool?
7. **Story 0068 (TLS/nginx).** It is a hard prerequisite for iOS (§9.1) and it is sitting unmerged
   with no PR. Standing direction is to ask before touching it — asking.

---

## 13. Sources

MTG Arena:
- [All 15 Settings in MTG Arena Explained — Draftsim](https://draftsim.com/mtg-arena-settings/)
- [Arena Hot Keys and Interface Guide — MTG Arena Zone](https://mtgazone.com/arena-hot-keys-and-interface-guide-simplify-your-game-with-these-easy-tricks/)
- [MTG Arena Hot Keys and Using the Interface — Flipside Gaming](https://flipsidegaming.com/blogs/magic-blog/mtg-arena-hot-keys-and-using-the-interface)
- [All MTG Arena Keyboard Shortcuts — MTGA Assistant](https://mtgaassistant.net/Article/All-MTG-Arena-Keyboard-Shortcuts)
- [MTG Arena Hidden Advanced deck builder options — Aetherhub](https://aetherhub.com/Article/MTG-Arena-Hidden-Advanced-deck-builder-options)
- [Magic Arena Deck Building Guide — Grand Screen](https://grand-screen.com/tcg/mtg/magic-arena-deck-builder-guide/)

MTG Online:
- [Gameplay: Tips and Tricks — mtgo.com](https://www.mtgo.com/getting-started/getting-started-tips-tricks)
- [Gameplay: Duels & Solitaire — mtgo.com](https://www.mtgo.com/getting-started/getting-started-gameplay)
- [Gameplay: Multiplayer & Commander — mtgo.com](https://www.mtgo.com/getting-started/getting-started-multiplayer)
- [Magic Online: Hotkeys — Wizards support](http://wizards.custhelp.com/app/answers/detail/a_id/646/~/magic-online-hotkeys)

Platform:
- [Compose Multiplatform — kotlinlang.org](https://kotlinlang.org/compose-multiplatform/)
- [Multiplatform image loading: Coil 3.0 — Cash App Code Blog](https://code.cash.app/multiplatform-image-loading)
- [Coil — GitHub](https://github.com/coil-kt/coil)
- [Using Jetpack Room in Kotlin Multiplatform shared code — John O'Reilly](https://johnoreilly.dev/posts/jetpack_room_kmp/)
- [Kotlin Multiplatform — Android Developers](https://developer.android.com/kotlin/multiplatform)
- [Flutter vs. Kotlin Multiplatform: 2026 Architecture Guide — Shorebird](https://shorebird.dev/blog/flutter-vs-kotlin-multiplatform)
- [Kotlin Multiplatform vs. Flutter: What to choose in 2026 — Volpis](https://volpis.com/blog/kotlin-multiplatform-vs-flutter/)
