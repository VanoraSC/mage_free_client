# 0044 — Correctness & doc hygiene

- **Epic:** Cross-cutting (defect fixes from the 2026-08-07 audit)
- **Depends on:** 0028/0029 (lobby data + UI), 0036/0037 (table options + fake), 0038 (nav/lobby wiring)
- **Status:** ready

## 1. Objective

Clear the audit's remaining smaller findings: an **unguarded field mutated from two threads** in the
lobby repository, **silent value loss** in the table-options mappers (plus a KDoc that claims the
mapping is lossless when it isn't), **stale in-code docs** that still call shipped work deferred, and
**two test-quality holes** — a fake that diverges from production and a test whose name promises far
more than its body checks.

## 2. Context & background

All verified directly against the merged code.

- **A. `LobbyRepository.refreshJob` is unguarded** (`core/network/.../LobbyRepository.kt:56, 64, 78,
  82-83`). It is a plain `private var Job?`, written by `refresh()` (UI thread) and read+written by the
  `connectionState.collect` block on an IO-dispatcher application scope — neither `@Volatile` nor
  guarded. **Failure (narrow window):** a pull-to-refresh is in flight when the socket drops; the
  disconnect handler cancels a stale/`null` reference and resets the snapshot, then the surviving
  refresh completes and writes `Loaded` with tables — leaving the lobby showing a populated list while
  the connection is `Disconnected`, contradicting the class's own documented invariant.
- **B. Silent loss in the options mappers.**
  `MatchOptionsMapper.timeLimitOf`/`bufferTimeOf` (`bridge/.../MatchOptionsMapper.kt:74-79`) do
  `entries.firstOrNull { it.prioritySecs == seconds } ?: NONE`, so **any** value not exactly equal to
  an upstream enum's seconds silently becomes **no clock**, and the create still reports success.
  Latent today (both fields are pinned at `0` with no setter) but a trap for whoever adds the field.
  Separately, `core/network/.../table/OptionsMapper.kt:12-13` claims the mapping is *"total and
  lossless"* while mapping `SkillLevel.Unknown → CASUAL` — the doc is simply wrong.
- **C. Stale in-code docs** (project rule: docs reflect **current state**):
  `feature/lobby/.../TableRow.kt:32` — *"(joining is deferred to EPIC-07)"*; and
  `app/.../navigation/MageNavHost.kt:112` — *"a table is still EPIC-07"*, which is contradicted a few
  lines below in the same file. Join/host shipped in 0038.
- **D. Test-quality holes.**
  `core/network/.../fake/FakeTableClient.kt` — `observeTable` **ignores its `seed`** and never emits
  it, while production `DefaultTableClient.observeTable` emits the seed first. Tests therefore see
  `isLoading` behavior that production does not have, and a regression in seed emission would go
  unnoticed. `core/cards/src/test/.../CardCatalogBundleTest.kt` — the test named *"every printing
  points at a real card (no orphans in a sample)"* only asserts that `Island` has a non-empty
  printings list and is a Land; it verifies **nothing** about orphaned printings.

## 3. Scope

**In scope**
- **A** — make the refresh/disconnect interaction safe: `@Volatile` (or funnel both paths through one
  actor/`Mutex`) **and** re-check the connection state before the terminal snapshot write, so a
  late-completing refresh cannot resurrect data after a disconnect. A test that exercises the
  interleaving.
- **B** — stop silently discarding a requested time limit: map to the nearest supported value and
  report the coercion, or reject unsupported values as a typed failure (pick one, document it); and
  correct the `OptionsMapper` KDoc to state the `Unknown → CASUAL` fallback honestly.
- **C** — update the two stale comments to current state (no history narration).
- **D** — make `FakeTableClient.observeTable` emit the `seed` exactly as production does, and either
  implement the orphan-integrity check the bundle test's name promises
  (`SELECT COUNT(*) FROM printing p LEFT JOIN card c ON p.card_id = c.id WHERE c.id IS NULL` ⇒ 0) or
  rename it to what it actually asserts. Prefer implementing it.

**Out of scope**
- Deck/catalog defects — **0042**; artwork — **0043**; Epic 7's seat/host defects — **0040/0041**.
- Adding a host time-limit control (that is a feature; this story only makes the mapping honest).

## 4. Design & approach

- **A**: prefer a single owner for the refresh lifecycle over sprinkling `@Volatile`; the guard that
  actually matters is the state re-check before the terminal write.
- **B**: silent coercion to "no clock" is the dangerous default — surface it either way.
- **D**: a fake that behaves unlike production is a *source* of false confidence; this is the same
  class of problem that hid Epic 7's seat defect, so fix the divergence rather than the test.

## 5. Implementation steps

1. Guard `refreshJob` + re-check connection state before the terminal snapshot write; interleaving test (A).
2. Time-limit mapping (coerce-and-report or typed failure) + corrected `OptionsMapper` KDoc (B).
3. Update the two stale comments (C).
4. `FakeTableClient` emits the seed; implement (or rename) the bundle orphan check (D).
5. `:core:network:check`, `:core:cards:check`, `:feature:lobby:check`, `:bridge:check` (in-container),
   `:app:testDebugUnitTest`, `:app:assembleDebug` green.

## 6. Testing & verification

- **Hermetic:** an interleaving test for A (refresh in flight → disconnect → refresh completes ⇒ the
  snapshot stays empty/disconnected); mapper tests for B (an unsupported value is coerced-and-reported
  or rejected, never silently `NONE`); a seed-emission test for the fake; the real orphan-integrity
  query in the bundle test.
- No live server needed (the `:bridge` mapper test is hermetic).

## 7. Acceptance criteria

- [ ] A refresh completing **after** a disconnect can no longer leave the lobby showing stale tables;
      the interleaving is covered by a test.
- [ ] An unsupported match time limit is never silently turned into "no clock" — it is coerced with a
      reported adjustment or rejected as a typed failure; the `OptionsMapper` KDoc matches reality.
- [ ] No in-code comment still describes shipped join/host behavior as deferred.
- [ ] `FakeTableClient.observeTable` emits its `seed` exactly as production does; the bundle test
      either performs the orphan-integrity check its name promises or is renamed to match.
- [ ] All prior suites green.

## 8. References

- The 2026-08-07 audit (findings A/#8, B/#10, C/#13, D/#14), each verified against the merged code.
- [`0040-table-seat-state.md`](0040-table-seat-state.md) — the same fake-fidelity lesson that hid Epic 7's seat defect.
