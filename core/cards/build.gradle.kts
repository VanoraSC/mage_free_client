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

    // Story 0031: on-demand card-art loader/cache. `coil-core` is the ImageLoader (memory+disk cache,
    // request de-dup); `coil-network-okhttp` is the HTTP fetcher that GETs the resolved Scryfall URL
    // and streams it into the disk cache. `api` because the module's Hilt surface hands out a
    // `coil3.ImageLoader` that 0032's UI (with coil-compose) binds `AsyncImage` to.
    api(libs.coil.core)
    implementation(libs.coil.network.okhttp)
    // Story 0031: cache-policy setting persisted the AGENTS.md way (DataStore for prefs).
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    // Story 0056: records the real outgoing art request so the User-Agent can be asserted on the
    // wire, on the loader's *default* client — a test that injected its own Call.Factory would carry
    // whatever headers the test gave it and would prove nothing about the path that ships.
    testImplementation(libs.okhttp.mockwebserver)
}
