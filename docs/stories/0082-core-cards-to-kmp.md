# 0082 — `:core:cards` to KMP: the catalog, the asset, and the art pipeline

- **Epic:** EPIC-18 — Multiplatform Foundation
- **Depends on:** 0080 (the `magefree.kmp.library` convention plugin), 0081 (Koin — the DI modules
  here are two of the ten, and converting them twice would be waste)

## 1. Objective

Move `:core:cards` off Android. It is the largest piece of Phase 0 and it comes **first among the
`:core:*` conversions**, not last, because the module dependency graph says so (§2).

Three separate problems live in this module: raw `android.database.sqlite` querying, a 14 MB bundled
asset opened through `Context`, and a Coil art pipeline built on the Android/OkHttp generation.

## 2. Context & background

**Dependency order forces this module first — the plan's Phase 0 listed the reverse.** Measured from
the build files:

```
:core:cards  ←  :core:decks  ←  :core:network
```

`:core:decks` has `implementation(project(":core:cards"))`, and `:core:network` has
`api(project(":core:decks"))` — the latter because `TableClient`'s join/submit signatures expose
`magefree.decks.model.Deck`. A KMP module's `commonMain` cannot depend on an Android library, so
`:core:network` cannot be converted while `:core:decks` is still Android, and `:core:decks` cannot
be converted while `:core:cards` is. **The hardest module is therefore first.** `ui-modernization-plan.md`
§11 has been corrected to match; do not re-derive the "cheapest first" order from it.

**The Android surface, enumerated** (every `android.*`/`androidx.*` import in `src/main`):

| File | What it uses | Shape of the fix |
|---|---|---|
| `SqliteCardCatalog.kt` | `android.database.Cursor`, `SQLiteDatabase` | Move to the `androidx.sqlite` driver API |
| `CardCatalogDatabase.kt` | `Context`, `SQLiteDatabase` | Asset source + file path behind a platform interface |
| `CardImageLoader.kt` | `Context`, `android.util.Log` | Coil 3 KMP; logging behind the existing seam |
| `CardArtHttpClient.kt` | `Context`, `PackageManager` | The `User-Agent` (story 0056) becomes a supplied value |
| `CardArtCachePolicy.kt` | `androidx.datastore.preferences.core.*` | Already multiplatform — swap the artifact, not the code |
| `CardArtModule.kt`, `CardCatalogModule.kt` | `Context`, `preferencesDataStore` | Koin modules lose the `Context` parameter |

**The asset extraction is already the right design and is not being redesigned.**
`CardCatalogDatabase` copies `assets/cards.sqlite` once into private storage under a version-stamped
name, adds a `COLLATE NOCASE` index to that private copy, and opens it read-only thereafter. All of
that logic is platform-independent; only two things are Android — *where the bytes come from*
(`AssetManager.open`) and *where they are written* (`Context.filesDir`).

**`CardArtHttpClient`'s `Context` is not incidental.** It reads `PackageManager` to build a
`User-Agent`, which exists because Scryfall rejects OkHttp's default with HTTP 400 and card art had
never loaded anywhere (story 0056). Whatever replaces it must still send a real `User-Agent` — this
is a case where a "cleanup" that drops a parameter silently breaks every image in the app, and the
test that catches it already exists (`CardArtUserAgentTest`).

## 3. Scope

**In scope**
- `:core:cards` applies `magefree.kmp.library` with a `jvm()` target and an `androidTarget()`; the
  Android-only implementations move to `androidMain`.
- `SqliteCardCatalog` and `CardCatalogDatabase` re-expressed over `androidx.sqlite`'s driver API.
- The asset **source** and the writable **directory** behind an interface at the module boundary,
  with the Android implementation in `androidMain` and a JVM one for tests and the desktop build.
- Coil `coil-network-okhttp` → `coil-network-ktor3`; `CardImageLoader` off `Context` and
  `android.util.Log`.

  **The engine is CIO, not Ktor's OkHttp engine, and that was forced rather than chosen.**
  `ktor-client-okhttp:3.5.1` requires OkHttp 5.3.2, which silently upgrades MockWebServer 4.12.0's
  transitive OkHttp and breaks it (`okhttp3.internal.Util` was removed in OkHttp 5) — and
  MockWebServer is not published at 5.3.2 at all, so matching the versions was not available. CIO
  is the better answer regardless: it is pure Kotlin and multiplatform, so this module ends up
  carrying no production HTTP engine that a second target would have to swap.
- `CardArtHttpClient`'s `User-Agent` supplied to the module rather than derived from
  `PackageManager` inside it.
- Both Koin modules moved to common, with the platform pieces provided at the Android edge.

**Out of scope**
- Any change to what search returns, to the catalog schema, or to `tools/card-catalog-generator`.
  This is a port; the query results before and after must be identical.
- The Robolectric tests here — they are story 0085. They must keep passing unchanged in the
  meantime, which is what makes this story's port verifiable at all.
- iOS or any target beyond `jvm()` + `androidTarget()`.

## 4. Design & approach

**One SQLite story for the whole repo.** Room 2.7 is multiplatform and rides on
`androidx.sqlite`'s `SQLiteDriver`/`SQLiteConnection`/`SQLiteStatement`. `:core:decks` (story 0083)
already uses Room, so choosing the same driver family here means the two modules share one SQLite
dependency and one set of behaviours rather than each inventing its own.

**The driver is a parameter, not a choice made in the module.** Android passes
`AndroidSQLiteDriver` — the platform's own SQLite, the same engine `SQLiteDatabase` used, so the APK
gains no native library and query behaviour is unchanged on the platform that ships. A JVM/desktop
target passes `BundledSQLiteDriver`, which carries its own SQLite so the host does not have to
provide one. Everything else about opening the file, and every query, is identical either way.

**`EXPLAIN QUERY PLAN` is not available through the driver**, and two tests depend on it.
`AndroidSQLiteStatement` splits into a SELECT variant and an "other" variant; an EXPLAIN lands in
the latter, `step()` returns false immediately, and no rows come back. Those two tests are the only
thing proving the NOCASE index is *used* rather than merely present (story 0042 defect D), so they
read the plan through `SQLiteDatabase` against the same prepared file. A query plan is a property of
SQLite and of the file, not of the API wrapper — and `AndroidSQLiteDriver` is that same SQLite.

**`OPEN_READONLY` cannot be expressed.** `SQLiteDriver.open(fileName)` takes no flags. Nothing
writes to the catalog and only `SELECT`s are issued, so behaviour is unchanged; what is lost is
SQLite refusing a write if one were ever added by mistake. Recorded rather than discovered later.

**Identical results are the acceptance bar, and they are testable exactly.** The catalog is
immutable and bundled, so the same query against the same asset must return the same rows before and
after. Capture the current output of a fixed set of queries against the shipped asset, then assert
the ported implementation reproduces it — this is a port whose correctness is checkable by equality,
which is rare and worth exploiting. `CardCatalogFixtureTest`, `CardCatalogExactNameTest` and
`CardCatalogBundleTest` already cover parts of this.

**The NOCASE index must survive.** `LOCAL_REVISION` exists so a change to `prepare()` re-lays
existing copies. Changing the driver changes `prepare()`, so **bump `LOCAL_REVISION`** — otherwise an
already-installed device keeps a copy built by the old path and the mismatch is invisible until an
exact-name lookup gets slow or wrong.

## 5. Verification

- **Standard 1:** the query-equivalence tests must be shown failing against a deliberately altered
  port (e.g. a dropped `ORDER BY`), then passing. A port test that passes against a broken port is
  worthless.
- **Standard 2 (reachability):** for the asset path and the writable directory, name what supplies
  each on Android and on JVM, and confirm both are actually exercised by a test — not just declared.
- **Standard 5 (unexpectedly absent):** confirm the `User-Agent` is non-empty on the real outgoing
  request after the change, not merely that the parameter exists. `CardArtUserAgentTest` inspects
  the real request; keep it doing that.
- **Hermetic gate:** `./gradlew check`, with the existing `:core:cards` suite unchanged.
- **Live:** card art must load. Story 0056's whole subject was art that had never loaded anywhere
  while every test was green.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. Fresh install (or clear app data). Open card search and type a card name — confirm results
     appear.
  2. Confirm card **art** renders in the search results, not placeholders.
  3. Open a card's inspection view; confirm the full-size art loads.
  4. Search for a card by exact name in a case you would not normally type (e.g. `lightning bolt`).
     Confirm it is found — that is the NOCASE index doing its job.
  5. Open a deck in the builder and confirm the cards in it show art.

## 6. Acceptance criteria

- [ ] `:core:cards` is a KMP module; no `android.*` import remains outside `androidMain`.
- [ ] The same queries return the same rows as before the port, proven by test.
- [ ] `LOCAL_REVISION` is bumped and a device with an old copy re-lays it.
- [ ] The art pipeline runs on `coil-network-ktor3` and still sends a real `User-Agent`.
- [ ] `./gradlew check` and `:app:assembleDebug` pass; the existing suite is unchanged.
- [ ] Pete has completed the eyes-on checklist and art loaded in all three places.

## 7. References

- `core/cards/src/main/kotlin/magefree/cards/internal/CardCatalogDatabase.kt` — the asset copy,
  `ASSET_VERSION`/`LOCAL_REVISION`, and the NOCASE index.
- `core/cards/src/main/kotlin/magefree/cards/internal/SqliteCardCatalog.kt` — the queries to port.
- `core/cards/src/main/kotlin/magefree/cards/art/CardArtHttpClient.kt` — the `User-Agent` story 0056
  added, and why the `Context` there is load-bearing.
- `docs/stories/0056-card-art-user-agent.md` — what breaks silently if the header is lost.
- `docs/ui-modernization-plan.md` §11 Phase 0, §9.2 — the module's recorded debt.
