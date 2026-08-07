# 0042 — Deck & catalog robustness

- **Epic:** EPIC-09 / EPIC-10 (defect fixes from the 2026-08-07 audit)
- **Depends on:** 0030 (catalog), 0033 (deck model/legality), 0034 (import), 0035 (builder)
- **Status:** ready

## 1. Objective

Fix four verified defects on the deck/catalog path: a **lost-update race** that silently discards
cards the user added, an **unhandled catalog failure** that crashes the app, a **latent throw** in the
legality checker, and the **full-table scan per card name** that makes live legality the dominant cost
of every builder tap.

## 2. Context & background

All four were found by an independent audit and **verified directly against the merged code**.

- **A. Lost update in `addCard`** (`feature/decks/.../builder/BuilderViewModel.kt:130-139`). The
  method snapshots `deck` *before* `viewModelScope.launch`, then **suspends** on
  `catalog.card(cardId)` (SQLite on the IO dispatcher), then applies the mutation to the **stale**
  snapshot. `changeQuantity`/`removeRow` are genuinely safe — they compute the next state
  synchronously on `Dispatchers.Main.immediate` — so `addCard` is the only read-suspend-write gap.
  **Failure:** tap the same card twice within the lookup latency → both coroutines start from deck v0,
  both write v0+1 → **two taps yield one copy**, and the truncated deck is persisted. Adding to main
  then sideboard quickly loses the main copy.
- **B. Catalog failure crashes the app** (`feature/cards/.../CardSearchViewModel.kt:229-241, 277-291`;
  same shape in `feature/decks/.../library/LibraryViewModel.kt` and
  `feature/tables/.../join/JoinTableViewModel.kt`). `catalog.search`/`filter` are called inside a
  `flow { }` that is `launchIn(viewModelScope)` with no error handling. `viewModelScope` has a
  `SupervisorJob` but **no `CoroutineExceptionHandler`**, so a throw reaches the default handler →
  process crash, and the `combine`/`flatMapLatest` pipeline is terminated permanently.
  **Failure:** `CardCatalogDatabase.copyIfNeeded` copies a ~14 MB asset into `filesDir` on first use;
  on a device with no free storage `copyTo` throws `IOException` → the app crashes on the first
  keystroke in card search. The same path reaches the join flow via `DeckLegality`.
- **C. Latent throw for an unbundled format** (`core/decks/.../internal/DefaultDeckLegality.kt:55-58`).
  `requireNotNull(...)` throws if a `DeckFormat` key is missing from `formats.json`. Verified the
  shipped bundle contains all six keys, so it **cannot fire today** — but it is called from
  `JoinTableViewModel.selectDeck` inside a bare `launch`, so the first bundle/enum drift becomes a
  crash instead of a degraded check.
- **D. A full scan per card name** (`DefaultDeckLegality.kt:120`, `DefaultDeckIO.kt:56`,
  `BuilderViewModel.kt:235` → `SqliteCardCatalog.search`, which runs
  `SELECT id, name FROM card WHERE name LIKE ? ESCAPE '\'`). `exactByName` is implemented as a
  substring `search` filtered in Kotlin: a leading-wildcard predicate no index can serve, materialising
  every match, then a second `IN (…)` query plus a printings query.
  **Failure (performance):** `BuilderViewModel.rebuild` runs after **every** edit and calls `resolve`
  per entry plus `legality.check`, which calls `exactByName` per distinct name — ~20 full scans of a
  ~32k-row table (×3 queries each) on every "+"/"−" tap; `DeckIO.import` does the same per line.

## 3. Scope

**In scope**
- **A** — make builder mutations atomic: re-read the authoritative deck **inside** the coroutine after
  any suspension, and serialise `read → transform → save` (a `Mutex` or single-threaded confinement)
  so concurrent taps cannot interleave. Cover the *other* suspending mutations too if any exist.
- **B** — the catalog-backed flows must not let a failure escape: catch at the flow boundary and
  surface a user-visible error state (search, deck library, join deck-pick) instead of crashing, and
  keep the pipeline alive so a later query still works.
- **C** — an unknown/unbundled format returns a structured "unknown format" result rather than throwing.
- **D** — add an **exact-name** catalog lookup backed by an indexable predicate
  (`WHERE name = ? COLLATE NOCASE`, or a batched `IN (…)` for a whole deck) and use it from all three
  call sites. Add/confirm an index on `card.name`. Keep the existing substring `search` for the
  user-facing search box.
- Tests: a concurrency test that fails on the current `addCard` (two rapid adds ⇒ two copies); an
  error-injection test per VM proving a catalog failure surfaces as state and does not crash or wedge
  the pipeline; an unknown-format legality test; and a test asserting the exact-name path is used
  (and a benchmark-ish assertion or query-count check if cheap).

**Out of scope**
- Artwork pipeline defects — **0043**. Lobby/table/options/doc hygiene — **0044**.
- Any change to the bundled assets themselves or to the generators.
- New deck features; UI redesign.

## 4. Design & approach

- **A**: the ViewModel owns the deck; every mutation goes through one serialised path that re-reads
  current state after suspension. Prefer confinement over a lock if it reads cleaner.
- **B**: fail *soft* — the catalog is a bundled read-only asset; a failure is environmental (storage),
  so the right UX is an error state with retry, never a crash.
- **D**: `exactByName` is a *different query* from user search; give it its own catalog method rather
  than post-filtering a substring search.

## 5. Implementation steps

1. Serialise builder mutations; add the failing-then-passing concurrency test (A).
2. Guard the catalog-backed flows + error states in the three VMs (B).
3. Structured unknown-format result (C).
4. Exact-name catalog lookup + index; switch the three call sites (D).
5. `:core:cards:check`, `:core:decks:check`, `:feature:decks:check`, `:feature:cards:check`,
   `:feature:tables:check`, `:app:testDebugUnitTest`, `:app:assembleDebug` green.

## 6. Testing & verification

- **Hermetic:** the concurrency test must **fail against today's `addCard`** and pass after the fix —
  demonstrate this explicitly. Error-injection via a fake catalog that throws. Unknown-format case.
  Exact-name usage asserted at each call site.
- No live server needed.

## 7. Acceptance criteria

- [ ] Rapid repeated adds never lose a copy; deck mutations are atomic across the catalog suspension,
      proven by a test that fails on the pre-fix code.
- [ ] A catalog read failure surfaces as an **error state** in card search, the deck library, and the
      join deck-pick — no crash, and the pipeline still serves later queries.
- [ ] An unknown/unbundled format yields a structured result, not a throw.
- [ ] Exact-name resolution uses an **indexable** query (no leading-wildcard scan) at all three call
      sites; the user-facing substring search is unchanged.
- [ ] All prior suites green.

## 8. References

- The 2026-08-07 audit (findings A/#3, B/#4, C/#12, D/#11), each verified against the merged code.
- [`0035-deck-library-and-builder-ui.md`](0035-deck-library-and-builder-ui.md), [`0033-deck-model-storage-and-legality.md`](0033-deck-model-storage-and-legality.md), [`0030-card-catalog-data-and-local-search.md`](0030-card-catalog-data-and-local-search.md).
