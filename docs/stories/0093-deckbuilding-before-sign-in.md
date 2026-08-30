# 0093 — Deckbuilding before sign-in

- **Epic:** EPIC-25 — Deckbuilding (the pre-sign-in mount; the builder rebuild is later)
- **Depends on:** nothing.

## 1. Objective

Mount the deck library and the card browser in the **root** navigation graph and put an entry into
them on the server-list screen, so the one part of the app that needs no server can be used without
one.

## 2. Context & background

**Deckbuilding is already fully offline. It is only mounted as though it were not.** Every deck
operation is served from the device: `:core:decks` is Room-backed local storage, format legality is
parsed from a bundled `assets/formats.json`, and card data comes from `assets/cards.sqlite` read by
`SqliteCardCatalog`. Nothing in `:feature:decks` reads a session, and `:feature:cards` does not
either. Only art fetch and prefetch touch the network.

What makes it feel server-dependent is **where it is mounted**. `AppNavHost` starts on `ConnectRoute`,
and the tabbed shell that owns the Decks tab is entered only on a successful sign-in — so a feature
that never uses a connection sits behind one.

**This is a second mount point, not a change to the entry policy.** The shell is still entered only
with a live session. That invariant is not incidental: allowing the shell in without a session would
make the lobby, tables and the connection strip reachable in a state where none of them can work, and
would bring back the dead Retry control that was deliberately removed. This story adds a *path*; it
relaxes nothing.

**A first launch has no decks**, and that matters more once the builder is reachable before sign-in:
the first thing a new player sees is an empty library. Import already exists — `DeckIO` reads XMage
`.dck`, `.dec`, MTGA and plain text — and it is the answer, because typing sixty card names is not
the intended path from a decklist on the web to a playable deck.

**Checked, and it is not first-class yet.** `LibraryScreen`'s empty state reads *"Create your first
deck, or import one from a file"* but offers exactly one action, **New deck**; import is reachable
only from elsewhere on the screen. So the empty library tells a new player about import and then does
not offer it. `EmptyState` takes a single action, so making import first-class is a small design
change rather than a one-line edit — it is a follow-up, not this story.

## 3. Scope

**In scope**
- `DecksRoute` and `CardsRoute` mounted in the root `AppNavHost`, chrome-free, the way `GameRoute` is.
- An entry into decks on the server-list screen — the first screen a launch shows.
- Back from decks returns to the server list; back from card browse returns to decks.

**Out of scope**
- **The builder rebuild.** Every measured gap in the current builder — the deck and search as one
  screen, a search result knowing what the deck holds, and the rest — is later work in the same epic.
  This story moves where the existing builder is reachable from and changes nothing about it.
- Any change to the connect flow itself, to the shell's entry policy, or to the Decks tab. The tab
  stays: same feature, same route content, two mount points, nothing duplicated.
- Orientation. The new-UI surfaces are landscape; these are the existing portrait screens reached
  from a second place, so nothing requests an orientation.

## 4. Prerequisites & toolchain

Project baseline. Android-only change; `:bridge` is untouched.

## 5. Design & approach

**The root graph gains two destinations, and the server list gains one action.** `AppNavHost` already
renders `GameRoute` and `CatalogRoute` outside the shell chrome, so the pattern exists and this
follows it rather than inventing a third arrangement.

The entry is hoisted the same way `onEnterGame` and `onOpenCatalog` are: `ConnectFlow` takes an
`onOpenDecks` callback and passes it to `ServerListScreen`, so `:feature:connect` needs no knowledge
of the root graph and the navigation tests can drive it without a DI container.

**Card browse comes along**, because the library's "Browse cards" action navigates to it and it reads
the same bundled catalog. Mounting decks without it would make that action dead from the new entry
point — the exact defect class the wiring guards exist to catch.

**Reachability (standard 2).** The new destination is reached from a real control on the first screen
a launch shows, and the guard tests assert it: the wiring guard for the route being registered in the
root graph, and a cold-start reachability test in the same shape as the one that covers the connect
entry. A feature that is built, tested and unreachable is the failure this project has already had
once, and a mount point is exactly where it happens.

## 6. Implementation steps

1. Read `AppNavHost`, `MageNavHost` and `ConnectFlow` to confirm how `GameRoute`/`CatalogRoute` are
   mounted chrome-free and how the shell's own routes are declared.
2. Add the two destinations to the root graph, reusing the same route types the shell uses rather
   than declaring parallel ones.
3. Thread an `onOpenDecks` callback from `AppNavHost` through `ConnectFlow` to `ServerListScreen`.
4. Add the entry control to the server-list screen.
5. Extend the wiring guard and the cold-start reachability test to cover the new path.
6. Check what the empty library offers on a first launch, and record it — the fix, if one is needed,
   is a follow-up rather than this story.

## 7. Testing & verification

- **Proven failing first (standard 1):** the reachability test asserting decks are reachable from the
  server list must fail against the current graph, then pass.
- **Unit:** the wiring guard registers `DecksRoute` and `CardsRoute` in the **root** graph; the
  server-list screen renders the entry control and invokes the hoisted callback.
- **Hermetic Compose (`src/testDebug`):** a cold start lands on the server list, the decks entry is on
  screen, tapping it reaches the library, and Back returns to the server list. This is the gate that
  catches a control that renders, reports a click and does nothing.
- **The Decks tab still works.** The shell path is unchanged and its existing tests must stay green
  without being edited — if they need editing, the change went further than a second mount point.
- **Eyes-on:** yes, and it is the point of the story. A short checklist is in the PR: launch with no
  server configured, open decks from the server list, build or import a deck, browse cards, come
  back, then sign in and confirm the Decks tab is unchanged.

## 8. Acceptance criteria

- [x] Decks and card browse are reachable from the server-list screen, with no session.
- [x] The shell is still entered only with a live session; the entry policy is untouched.
- [x] Back from decks returns to the server list.
- [x] The Decks tab behaves exactly as before, with its tests unedited.
- [x] All six new tests were proven failing before passing.
- [x] `./gradlew check` passes and the APK builds; the launch is the eyes-on step.

## 9. References

- `docs/ui-modernization-plan.md` §7.18 — deckbuilding, and why the mount point is the fault.
- `app/src/main/kotlin/magefree/app/navigation/AppNavHost.kt` — the root graph and its entry policy.
- `app/src/main/kotlin/magefree/app/navigation/MageNavHost.kt` — the shell graph's `DecksRoute` /
  `CardsRoute`.
- `feature/connect/src/main/kotlin/magefree/feature/connect/ConnectFlow.kt` — where the entry hangs.
