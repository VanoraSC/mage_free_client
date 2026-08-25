plugins {
    id("magefree.kmp.android.library")
    // Story 0030: provides the CardCatalog binding backed by the bundled SQLite asset.
    id("magefree.koin")
}

android {
    namespace = "magefree.cards"

    // The pre-built catalog ships as an APK asset (src/androidMain/assets) and is copied to the app's
    // files dir on first run (see CardCatalogDatabase). Because it's read via AssetManager.open() (not
    // opened directly as a file), APK deflate is transparent on read, so it is left compressed — that
    // keeps ~14 MB of card data down to ~5.5 MB in the APK, at the cost of a one-time inflate during
    // the first-run copy.
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // Story 0082: the multiplatform SQLite driver API. The concrete driver is supplied by
                // the platform — `AndroidSQLiteDriver` here, `BundledSQLiteDriver` on a JVM target —
                // so only the interface belongs in common. 2.5.0 is what Room 2.7.1 already resolves.
                implementation(libs.androidx.sqlite)

                implementation(libs.kotlinx.coroutines.core)

                // Story 0031: on-demand card-art loader/cache. `coil-core` is the ImageLoader
                // (memory+disk cache, request de-dup); `coil-network-ktor3` is the fetcher that GETs
                // the resolved Scryfall URL and streams it into the disk cache. `api` because the
                // module hands out a `coil3.ImageLoader` that 0032's UI binds `AsyncImage` to.
                api(libs.coil.core)
                implementation(libs.coil.network.ktor3)
                implementation(libs.ktor.client.core)
                // Story 0082: CIO is Ktor's own engine — pure Kotlin and multiplatform, so this
                // module carries no HTTP engine a second target would have to swap.
                implementation(libs.ktor.client.cio)
                // Story 0082: backs the BundledFiles boundary and the catalog's copy-once logic.
                implementation(libs.okio)
                // Story 0031: cache-policy setting persisted the AGENTS.md way (DataStore for prefs).
                // Story 0082: this is DataStore's multiplatform half; the Android
                // `preferencesDataStore` delegate that builds the file lives in androidMain.
                implementation(libs.androidx.datastore.preferences)
            }
        }

        androidMain {
            dependencies {
                // Story 0082: the platform edge — AndroidSQLiteDriver (the same platform SQLite the
                // pre-port `SQLiteDatabase` used, so the APK gains no native library) and
                // `androidContext()` for assets, filesDir and cacheDir.
                implementation(libs.androidx.sqlite.framework)
                implementation(libs.koin.android)
            }
        }

        // Robolectric, JUnit 4, and the real bundled asset — so `check` exercises the actual catalog
        // query logic (search ranking, filters, DFC/split lookup) without a device. Story 0085 moves
        // the parts of this that no longer need Android onto the jvm() target.
        androidUnitTest {
            dependencies {
                implementation(libs.junit4)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.robolectric)
                // Story 0056: records the real outgoing art request so the User-Agent can be asserted
                // on the wire, on the loader's *default* client — a test that injected its own client
                // would carry whatever headers the test gave it and would prove nothing about the
                // path that ships.
                implementation(libs.okhttp.mockwebserver)
                // Story 0082: MockEngine drives the candidate-URL fallback test without a real socket.
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
