# 0025 — Resilience UX & recovery surfaces

- **Epic:** EPIC-05 — Session Resilience & Notifications
- **Depends on:** 0024 (app reconnect & resume), 0019 (error surfaces), 0010 (status bar)
- **Status:** ready

## 1. Objective

Make reconnection **legible and non-destructive**: a reconnecting player sees a clear, unobtrusive
"reconnecting… / restoring your session" indicator that **preserves context** instead of being
thrown back to the sign-in form, and a distinct, actionable "session lost — sign in again" surface
only when recovery has truly failed. This completes the resilience track (bridge hold 0023 + app
reconnect 0024 + this UX).

## 2. Context & background

- Story 0019 gave `:feature:connect` a `ConnectPhase` taxonomy (`Connecting`, `Reconnecting`,
  `AuthFailed`, `VersionUnsupported`, `Network`) and design-system surfaces. Story 0024 now drives
  `Reconnecting` for real (with resume) and distinguishes a resume-in-progress. This story renders
  those states well and adds a **`Restoring`** distinction (reconnected, re-attaching the session).
- Story 0010's global `ConnectionStatusBar` (real since 0017) already shows connection state
  app-wide — the natural home for a persistent, non-blocking "reconnecting" indicator. This story
  reuses/extends it rather than inventing a parallel surface.
- **Non-destructive is the point** ([`../ux-principles.md`](../ux-principles.md), "the connection is
  the product"): a transient drop must not discard what the player was doing. The pattern set here is
  the same one the future in-game board (EPIC-11+) will need when a drop happens mid-game.

## 3. Scope

**In scope**
- A **non-modal "reconnecting…/restoring" indicator** (design-system component; reuse/extend the
  0010 `ConnectionStatusBar`) that appears on `Reconnecting`/`Restoring` and clears on `Connected`,
  **without** navigating away or clearing the current screen's state.
- A distinct **`Restoring`** phase surfaced from 0024's resume-in-progress (reconnected socket,
  re-attaching the held session) vs. plain `Reconnecting` (still trying to reach the bridge).
- A terminal **"session lost"** recovery surface (only after 0024's ret/resume budget is exhausted or
  a `ResumeRejected` → fresh-login is required): clear copy + a re-authenticate CTA, via the
  design-system error/`DecisionPrompt` surfaces — reusing 0019's patterns.
- Wire these into `:feature:connect`'s sign-in flow and confirm the global status bar reflects
  reconnect/restore/lost. Stateless, previewable Composables (light + dark).
- ViewModel tests over the phase→surface mapping and Compose previews for each state.

**Out of scope**
- The reconnect/resume mechanics (**0024**) and bridge hold (**0023**).
- In-game board–specific resilience overlays (**deferred to EPIC-11+**, when the board exists — this
  story only establishes the reusable pattern in the connect/shell context).
- Push notifications (later EPIC-05 slice).

## 4. Design & approach

- **Phase model:** extend the feature's `ConnectPhase` (or its `ConnectionStatus` projection) with
  `Restoring`, mapped from 0024's resume-in-progress signal; keep `Reconnecting` for pre-resume
  ret/retry. A terminal `SessionLost` maps from 0024's exhausted/resume-rejected outcome.
- **Global indicator:** extend the 0010 `ConnectionStatusBar` to render a subtle animated
  "Reconnecting… / Restoring your session" strip on those phases (it already observes the real
  connection via 0017); it must not intercept input or alter navigation.
- **Session-lost surface:** in `:feature:connect`, a design-system error surface with a re-authenticate
  action (routes back to sign-in with the last server pre-selected). Distinct copy from
  `AuthFailed`/`VersionUnsupported`/`Network`.
- Reuse `MageTheme`, `DecisionPrompt`, `StateViews`, and the status-bar chrome — no hand-rolled
  styles.

## 5. Implementation steps

1. Add `Restoring` (and a terminal `SessionLost`) to the feature phase model; map them from 0024's
   reconnect/resume/exhausted signals.
2. Extend the 0010 `ConnectionStatusBar` with a non-blocking reconnecting/restoring indicator that
   preserves context (no nav, no state loss).
3. Add the "session lost → re-authenticate" surface (design system), routing back to sign-in.
4. Previews (light + dark) for reconnecting / restoring / session-lost; ViewModel tests for the
   phase→surface mapping and that a transient drop never navigates away.
5. `:feature:connect:check` + `:app:testDebugUnitTest` green; `:app:assembleDebug` builds; 0010's
   existing status-bar test still passes.

## 6. Testing & verification

- **Hermetic gate:** ViewModel/state tests over reconnecting/restoring/session-lost mapping with fake
  repositories; assert a transient `Reconnecting`/`Restoring` **preserves** the current UI state and
  never routes to the form; `SessionLost` routes to re-auth. Compose previews compile.
- **Live (opt-in):** with 0023/0024 wired, a real network blip shows the reconnecting→restoring→
  connected indicator without losing the screen; a beyond-TTL drop shows session-lost → re-auth.

## 7. Acceptance criteria

- [ ] A transient drop shows a **non-destructive** reconnecting/restoring indicator (via the global
      status bar) and **preserves context** — the player is not thrown back to the form.
- [ ] `Restoring` (resume re-attaching) is visually distinct from `Reconnecting` (still reaching the
      bridge) and from a fresh `Connecting`.
- [ ] Only after recovery truly fails (budget exhausted / resume rejected) does a distinct
      **"session lost — sign in again"** surface appear, with a re-authenticate CTA.
- [ ] Built on the design system (status-bar chrome, `DecisionPrompt`, state views); 0010's status
      bar reflects reconnect/restore/lost; its existing test still passes.
- [ ] ViewModel tests + previews cover every state; `:feature:connect:check` +
      `:app:testDebugUnitTest` + `:app:assembleDebug` green.
- [ ] No reconnect mechanics, bridge changes, in-game overlays, or notifications here.

## 8. References

- [`0024-app-reconnect-and-lifecycle-session.md`](0024-app-reconnect-and-lifecycle-session.md) — the reconnect/resume states this renders.
- [`0019-registration-and-auth-error-handling.md`](0019-registration-and-auth-error-handling.md) — the `ConnectPhase` taxonomy + error surfaces this extends.
- [`0010-persistent-connection-status-surface.md`](0010-persistent-connection-status-surface.md) — the global `ConnectionStatusBar` this reuses.
- [`../ux-principles.md`](../ux-principles.md) — non-destructive recovery; the connection is the product.
