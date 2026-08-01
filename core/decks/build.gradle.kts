plugins {
    id("magefree.android.library")
    // Provides the KSP + Hilt recipe; :core:decks also runs Room's annotation processor through KSP.
    id("magefree.hilt")
    // Story 0033: kotlinx.serialization parses the bundled formats.json legality asset.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "magefree.decks"

    testOptions {
        unitTests {
            // Robolectric drives the real Android SQLite (via Room) on the JVM, so the deck library's
            // persistence + query logic is exercised without a device. The legality checker is pure
            // Kotlin and needs no framework, but shares the same unit-test source set.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Legality checks set/rarity legality by card name across ALL its printings — read-only over the
    // bundled offline catalog. Consumed as a dependency; :core:decks never touches the catalog data.
    implementation(project(":core:cards"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    // ApplicationProvider for the Robolectric-backed in-memory Room persistence tests.
    testImplementation(libs.androidx.test.ext.junit)
}
