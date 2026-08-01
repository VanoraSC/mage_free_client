plugins {
    id("magefree.android.library")
    // Story 0030: provides the @Singleton CardCatalog binding backed by the bundled SQLite asset.
    id("magefree.hilt")
}

android {
    namespace = "magefree.cards"

    // The pre-built catalog ships as an APK asset and is copied to the app's files dir on first run
    // (see CardCatalogDatabase). Because it's read via AssetManager.open() (not opened directly as a
    // file), APK deflate is transparent on read, so it is left compressed — that keeps ~14 MB of card
    // data down to ~5.5 MB in the APK, at the cost of a one-time inflate during the first-run copy.

    testOptions {
        unitTests {
            // Robolectric drives the real Android SQLite on the JVM, so :core:cards:check exercises the
            // actual catalog query logic (search ranking, filters, DFC/split lookup) without a device.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
