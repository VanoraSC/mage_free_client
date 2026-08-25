# 0083 — `:core:decks` to KMP: Room, and the small version of the asset problem

- **Epic:** EPIC-18 — Multiplatform Foundation
- **Depends on:** 0082 (`:core:decks` has `implementation(project(":core:cards"))`, so it cannot be
  KMP until that module is), 0081 (Koin), 0080 (the convention plugin)

## 1. Objective

Move `:core:decks` off Android: Room to its multiplatform configuration, and `FormatBundleLoader`
off `AssetManager`.

This is the **small version of the asset problem** story 0082 solves at scale — `formats.json` is a
handful of kilobytes against `cards.sqlite`'s 14 MB — so it is the cheap rehearsal of the same
boundary, on a module whose storage layer is otherwise already portable.

## 2. Context & background

**The Android surface here is smaller than `:core:cards`'s, and it is two things.** Every
`android.*`/`androidx.*` import in `src/main`:

- `androidx.room.*` in `db/DeckDao.kt`, `db/DeckDatabase.kt`, `db/Entities.kt` — **Room itself,
  which is multiplatform from 2.7** (the pinned version is 2.7.1). These annotations do not change;
  the build configuration and the database construction do.
- `android.content.Context` in `di/DeckModule.kt` (Room builder) and
  `internal/FormatBundleLoader.kt` (`AssetManager`).

That is the whole list. There is no raw SQLite here and no platform storage logic beyond those two
files — the deck model, the import/export ports (stories 0034, 0078) and the legality checker are
already pure Kotlin.

**Room's KMP configuration is not just a dependency swap.** It needs:

- **`@ConstructedBy` and an `expect object`.** The Android-only build reached the generated
  `DeckDatabase_Impl` by reflection from `DeckDatabase::class.java`. Common code has no `Class`, so
  Room 2.7 replaces that lookup with an `expect object : RoomDatabaseConstructor<T>` whose `actual`
  KSP generates per target.
- **KSP configured per target** — `kspAndroid` and `kspJvm`, not a single `ksp(...)`. Each target
  gets its own generated implementation; a plain `ksp(...)` configures neither.
- **A `RoomDatabase.Builder` given an explicit database file path.** On Android that builder still
  takes a `Context` alongside the path, so the construction itself stays at the Android edge; what
  moves to common is everything downstream of it.
- **An `androidx.sqlite` driver** — the same driver family story 0082 adopts for the card catalog,
  which is why the two stories should land close together and use the same choice.

The Room **Gradle plugin** is not among them: its job is to hand the processor a `schemaDirectory`,
and `DeckDatabase` sets `exportSchema = false`.

**`formats.json` is bundled and read through `AssetManager`.** Legality is offline by product
decision (EPIC-09, story 0033: *every* deck operation works with no network), so this file must keep
shipping inside the app and must keep being readable with no network and no server. The boundary is
the same one story 0082 introduces for the catalog: a platform-supplied source of bundled bytes.

**`:core:network` is waiting on this.** It has `api(project(":core:decks"))` because `TableClient`'s
join/submit signatures expose `magefree.decks.model.Deck`, so story 0084 cannot start until this
lands.

## 3. Scope

**In scope**
- `:core:decks` applies `magefree.kmp.android.library` — the convention plugin story 0082 added for a
  multiplatform module that still ships on Android, carrying `jvm()` + `androidTarget()`.
- Room configured for KMP: `@ConstructedBy`, per-target KSP, the `androidx.sqlite` driver chosen in
  0082, and `DeckDatabase` opened from an explicit file path.
- `FormatBundleLoader` reading through the bundled-resource interface introduced in 0082 rather than
  `AssetManager` directly.
- `DeckModule` (Koin) in common, with the database path and resource source supplied at the Android
  edge.

**Out of scope**
- Any schema change, migration, or change to what `DeckLegality` decides. Decks already on devices
  must open unchanged — this is a port, and a migration bundled into a port is a data-loss risk
  wearing a refactor's clothes.
- The deck builder UI (`:feature:decks`) and everything in EPIC-25. Untouched.
- `:core:decks`' Robolectric test (`DeckRepositoryTest`) — story 0085. It must keep passing here.

## 4. Design & approach

**The existing database must open unchanged, and that is the acceptance bar.** A user's deck library
is real data that only exists on their device. The port changes how the database is *constructed*,
not what is in it, so the test that matters is: a database file written by the pre-port code opens,
reads and writes correctly under the ported code. Assert that against a fixture file committed for
the purpose, not against a database the test itself just created — a fresh database proves only that
the new path is self-consistent.

**The database file path is where a port turns into data loss, and it is one line.** The pre-port
builder took the bare name `decks.db` and let Room resolve it under `/data/data/<pkg>/databases/`.
The multiplatform builder takes an absolute path instead — so the Android edge must resolve that same
name through `Context.getDatabasePath(...)`. Anything else (`filesDir`, a cache dir, a name relative
to the process working directory) opens a file that does not exist yet and silently hands the user an
empty deck library. This is the failure the fixture test is pointed at first, before it is made to
pass.

**Reuse 0082's resource boundary rather than inventing a second one.** Two bundled-file mechanisms in
two `:core:*` modules is precisely the drift §9.2 exists to prevent, and `formats.json` and
`cards.sqlite` differ only in size.

**Keep the Room annotations exactly as they are.** They are already multiplatform-compatible; the
temptation to tidy the DAO while the file is open should be resisted for the same reason 0080 forbids
source edits — a diff mixing a build port with logic changes cannot be reviewed for either.

## 5. Verification

- **Standard 1:** the "existing database still opens" test proven failing first, by pointing it at a
  deliberately incompatible construction, then passing.
- **Standard 2 (reachability):** name what supplies the database file path and the bundled-resource
  bytes on Android and on JVM, and confirm each is exercised by a test rather than only declared.
- **Hermetic gate:** `./gradlew check` with the existing `:core:decks` suite unchanged.
- **Live:** deck persistence is the thing that cannot be verified by a fresh install alone — the
  check that matters is an **upgrade** over an existing library.
- **Eyes-on (standard 3) — hand Pete this checklist.**
  1. **Before installing the new build**, note how many decks are in your library and the name of
     one of them.
  2. Install the new build **over** the old one (do not clear app data). Open Decks and confirm every
     deck is still there with the same names and card counts.
  3. Open one of those decks in the builder; confirm its cards, mana curve and legality all render.
  4. Add a card, leave the builder, reopen the deck — confirm the change persisted.
  5. Create a new deck, add a card, and confirm it survives an app restart.
  6. Confirm the legality panel shows a format verdict with the device in **airplane mode** — that is
     the bundled `formats.json` being read through the new boundary.

## 6. Acceptance criteria

- [ ] `:core:decks` is a KMP module; no `android.*` import remains outside `androidMain`.
- [ ] Room runs in its KMP configuration on the same `androidx.sqlite` driver `:core:cards` uses.
- [ ] A database file written by the pre-port code opens and round-trips under the ported code,
      proven by test against a committed fixture.
- [ ] `formats.json` is read through the same bundled-resource boundary as `cards.sqlite`.
- [ ] `./gradlew check` and `:app:assembleDebug` pass; the existing suite is unchanged.
- [ ] Pete has completed the eyes-on checklist, **including the upgrade-over-existing-data step**.

## 7. References

- `core/decks/src/main/kotlin/magefree/decks/internal/db/` — `DeckDatabase`, `DeckDao`, `Entities`.
- `core/decks/src/main/kotlin/magefree/decks/di/DeckModule.kt` — the Room builder's `Context`.
- `core/decks/src/main/kotlin/magefree/decks/internal/FormatBundleLoader.kt` — the `AssetManager` read.
- `docs/stories/0033-deck-model-storage-and-legality.md` — why legality is bundled and offline.
- `docs/ui-modernization-plan.md` §11 Phase 0, §9.2 — the bundled-assets bullet covering both modules.
