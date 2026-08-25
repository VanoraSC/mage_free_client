plugins {
    id("magefree.kmp.android.library")
    id("magefree.koin")
    // Story 0081: KSP used to arrive with the Hilt convention plugin. Hilt is gone, but Room's
    // compiler still needs it — so this module, now the only one running an annotation processor at
    // all, applies KSP explicitly rather than inheriting it from a DI recipe that no longer has one.
    alias(libs.plugins.ksp)
    // Story 0033: kotlinx.serialization parses the bundled formats.json legality asset.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "magefree.decks"
}

// Story 0083: KSP writes Room's generated `DeckDatabase_Impl`/`DeckDao_Impl` into the `androidDebug`
// and `jvmMain` *Kotlin* source sets, and ktlint derives its tasks from those under the multiplatform
// layout (an Android-only module's ktlint tasks come from `android.sourceSets`, which never saw
// them — which is why this module linted clean before the port and not after). Generated code is not
// this repo's to format.
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter { exclude { it.file.path.contains("${File.separator}build${File.separator}") } }
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // Legality checks set/rarity legality by card name across ALL its printings —
                // read-only over the bundled offline catalog. Consumed as a dependency; :core:decks
                // never touches the catalog data. Story 0083 adds a second reason: `BundledFiles`,
                // the bundled-resource boundary formats.json is now read through, is declared there.
                implementation(project(":core:cards"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // Story 0083: room-runtime is the multiplatform artifact from 2.7. room-ktx is gone
                // with it — it existed for `withTransaction`, which this module never called, and
                // coroutine/Flow query support is in room-runtime itself on the KMP configuration.
                implementation(libs.room.runtime)
                // Story 0083: FormatBundleLoader reads okio Sources off the BundledFiles boundary.
                implementation(libs.okio)
            }
        }

        androidMain {
            dependencies {
                // Story 0083: the platform edge — AndroidSQLiteDriver (the same platform SQLite the
                // pre-port open helper used, so the APK gains no native library) and
                // `androidContext()` for the database path.
                implementation(libs.androidx.sqlite.framework)
                implementation(libs.koin.android)
            }
        }

        /**
         * Story 0085: the deck-persistence suite runs here, on the `jvm()` target, over Room's
         * multiplatform runtime and `BundledSQLiteDriver` — no Android runtime, no Robolectric. That
         * is what makes it a portability check that fires on every commit.
         */
        jvmTest {
            dependencies {
                implementation(libs.junit4)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.androidx.sqlite.bundled)
            }
        }

        // What is left needs Robolectric for a real reason: `ExistingDeckDatabaseTest` and
        // `FormatBundleLoaderTest` test the Android edge itself — `getDatabasePath`,
        // `AndroidSQLiteDriver`, APK assets — so they stay on Android by design (story 0085 §2).
        androidUnitTest {
            dependencies {
                implementation(libs.junit4)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.robolectric)
                // ApplicationProvider for the Robolectric-backed Room persistence tests.
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}

dependencies {
    // Story 0083: Room's processor runs per Kotlin target, not once for the module — each target
    // gets its own generated `DeckDatabase_Impl` and its own `actual DeckDatabaseConstructor`. A
    // plain `ksp(...)` would configure neither.
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
