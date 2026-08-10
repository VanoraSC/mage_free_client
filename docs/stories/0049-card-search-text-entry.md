# 0049 — Card search text entry

- **Epic:** EPIC-10 — Card Database (defect fix, **blocking playability**)
- **Depends on:** 0032 (card search UI), 0035 (deck builder's add-cards surface), 0048 (the smoke that found it)
- **Status:** ready

## 1. Objective

Make the card search field accept typing. Today **no character ever lands** in "Search cards"
(`:feature:cards`) or "Search cards to add" (`:feature:decks`). You cannot find a card by name, so you
cannot realistically build a legal deck, so you cannot host or join a real constructed table. This is
the top blocker to core playability.

## 2. Context & background

- **Found by 0048's on-device smoke, then reproduced and root-caused independently (2026-08-10).**
  Verified on the emulator: the field takes focus (`mInputShown=true`), `input text "forest"` lands
  nothing, the placeholder never clears, `Showing 0` never changes, and the typed text is absent from
  the view hierarchy. The smoke also reproduced it with `input keyevent` and with taps on the physical
  soft-keyboard keys, so it is not an automation artefact.
- **Isolated:** the **filters work** — tapping `Green` yields `Showing 100` with real cards. The
  bundled catalog, the search pipeline and rendering are all fine. **Only text entry is dead.**
- **Root cause (confirmed in code).** `CardSearchScreen`'s field is fully controlled on the debounced
  state:
  ```kotlin
  OutlinedTextField(value = query /* = uiState.query */, onValueChange = onQueryChange)
  ```
  `onQueryChange` sets the raw `query` flow, but `uiState.query` is produced by
  `debouncedQuery = query.debounce { … }` → `combine(...)` → `_uiState.value`. So a keystroke updates
  the raw flow while Compose recomposes with the **old, unchanged** `uiState.query` — reverting the
  field before the debounce elapses. The field can never accumulate text.
- **Why every test missed it.** The ViewModel tests call `onQueryChange(...)` directly and assert the
  resulting pipeline states — correct, and completely blind to this, because they never render the
  field. There is no test that types into the composable. This is verification standard 2 in a new
  place: *what actually produces `uiState.query` while the user is typing?*
- **Consequence recorded by 0048:** hosting is gated client-side on a format-legal deck, and with name
  search dead the only construction reachable from a cold install is `Limited` (the one preset deferred
  to the server). The smoke can reach `Match starting…` only via that path.

## 3. Scope

**In scope**
- Make the search field accept and display typed text immediately, in **both** places it appears
  (`:feature:cards` search, and the deck builder's add-cards surface in `:feature:decks`).
- Keep the debounce where it belongs: it should throttle **catalog queries**, not the field's own
  displayed value. The usual shape is to hold the text in immediate UI state and debounce only what
  feeds the query pipeline — but choose and document whatever keeps the field responsive and the
  catalog un-hammered.
- Preserve everything 0042 fixed here: the `.catch` **inside** `flatMapLatest` (so a catalog failure
  ends one request rather than killing the pipeline or crashing the process), the error/retry surface,
  and the instant reset on a blank query.
- **A test that types into the rendered composable** and asserts the character appears and results
  update — the coverage whose absence let this ship. A Robolectric/Compose test in the hermetic gate is
  preferred over a device-only androidTest (device tests do not run pre-merge — that is how the
  unmounted connect flow survived).

**Out of scope**
- Search ranking/behaviour changes (0030/0032) beyond making entry work.
- The other defects 0048 found (session-state and lifecycle issues) — separate stories.
- Accessibility semantics (deferred for this effort). If a locator needs a handle, fix the locator.

## 4. Design & approach

- **Reachability (standard 2):** state plainly what produces the field's displayed value on each
  keystroke, and make that path synchronous. A displayed value sourced from a debounced/derived stream
  is the defect.
- Both surfaces should share one corrected pattern rather than diverging; if they already share a
  composable, fix it once and confirm both call sites.

## 5. Implementation steps

1. Reproduce with a rendered-composable test that types a character and fails on today's code
   (standard 1) — capture the failure.
2. Fix the field so displayed text is immediate; debounce only the catalog query.
3. Confirm both surfaces (cards search, deck builder add-cards).
4. Verify 0042's error/retry behaviour and blank-query reset still hold.
5. Gates: `:feature:cards:check`, `:feature:decks:check`, `:app:testDebugUnitTest`, `:app:assembleDebug`.

## 6. Testing & verification

- **Hermetic:** the typing test (proven failing first), plus existing ViewModel/pipeline tests green.
- **Independent (standard 3):** on-device — type a card name in both surfaces and confirm results
  narrow. Then re-run 0048's smoke: its `decks-offline` step should pass its search assertion, and the
  host step should become able to build a real constructed deck rather than only `Limited`.

## 7. Acceptance criteria

- [ ] Typing in "Search cards" and "Search cards to add" displays the text immediately and narrows
      results by name.
- [ ] The debounce still throttles **catalog queries**; typing does not fire one per keystroke.
- [ ] 0042's protections are intact: a catalog failure is an error+retry surface, the pipeline survives
      it, and a blank query resets instantly.
- [ ] A test that **types into the rendered composable** covers this, demonstrated failing on the
      pre-fix code, and lives in the hermetic gate.
- [ ] Verified on-device in both surfaces; 0048's smoke search assertion passes.

## 8. References

- `feature/cards/src/main/kotlin/magefree/feature/cards/{CardSearchScreen,CardSearchViewModel}.kt` — the controlled field and the debounced pipeline.
- [`0048-on-device-smoke-and-wiring-guards.md`](0048-on-device-smoke-and-wiring-guards.md) — the smoke that found it.
- [`0042-deck-and-catalog-robustness.md`](0042-deck-and-catalog-robustness.md) — the `.catch`/retry behaviour that must survive.
