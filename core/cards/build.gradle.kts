plugins {
    id("magefree.kmp.android.library")
    // provides the CardCatalog binding backed by the bundled SQLite asset.
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
                // the multiplatform SQLite driver API. The concrete driver is supplied by
                // the platform — `AndroidSQLiteDriver` here, `BundledSQLiteDriver` on a JVM target —
                // so only the interface belongs in common. 2.5.0 is what Room 2.7.1 already resolves.
                implementation(libs.androidx.sqlite)

                implementation(libs.kotlinx.coroutines.core)

                // on-demand card-art loader/cache. `coil-core` is the ImageLoader
                // (memory+disk cache, request de-dup); `coil-network-ktor3` is the fetcher that GETs
                // the resolved Scryfall URL and streams it into the disk cache. `api` because the
                // module hands out a `coil3.ImageLoader` that 0032's UI binds `AsyncImage` to.
                api(libs.coil.core)
                implementation(libs.coil.network.ktor3)
                implementation(libs.ktor.client.core)
                // CIO is Ktor's own engine — pure Kotlin and multiplatform, so this
                // module carries no HTTP engine a second target would have to swap.
                implementation(libs.ktor.client.cio)
                // backs the BundledFiles boundary and the catalog's copy-once logic.
                implementation(libs.okio)
                // cache-policy setting persisted the AGENTS.md way (DataStore for prefs).
                // this is DataStore's multiplatform half; the Android
                // `preferencesDataStore` delegate that builds the file lives in androidMain.
                implementation(libs.androidx.datastore.preferences)
            }
        }

        androidMain {
            dependencies {
                // the platform edge — AndroidSQLiteDriver (the same platform SQLite the
                // pre-port `SQLiteDatabase` used, so the APK gains no native library) and
                // `androidContext()` for assets, filesDir and cacheDir.
                implementation(libs.androidx.sqlite.framework)
                implementation(libs.koin.android)
            }
        }

        jvmMain {
            dependencies {
                // the JVM platform edge — `JvmBundledFiles` reads classpath resources,
                // and `BundledSQLiteDriver` carries its own SQLite so the host does not have to
                // provide one (`sqlite-framework` wraps the *platform's*, which a JVM host lacks).
                implementation(libs.androidx.sqlite.bundled)
            }
        }

        /**
         * the catalog and art suites run here, on the `jvm()` target, with no Android
         * runtime involved. That is what makes them a portability check that fires on every commit
         * rather than a second copy of the Android tests.
         *
         * `src/androidMain/assets` is wired in as a resource directory rather than the 14 MB
         * `cards.sqlite` being copied: on the JVM a classpath resource *is* the APK asset, so the
         * same file answers `BundledFiles.openBundled("cards.sqlite")` on both targets and there is
         * only ever one copy of it in the repository.
         */
        jvmTest {
            resources.srcDir("src/androidMain/assets")
            dependencies {
                implementation(libs.junit4)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.androidx.sqlite.bundled)
                // records the real outgoing art request so the User-Agent can be asserted
                // on the wire, on the loader's *default* client — a test that injected its own client
                // would carry whatever headers the test gave it and would prove nothing about the
                // path that ships.
                implementation(libs.okhttp.mockwebserver)
                // MockEngine drives the candidate-URL fallback test without a real socket.
                implementation(libs.ktor.client.mock)
            }
        }

        // **no Robolectric here any more** — everything that needed an Android runtime
        // moved to `jvmTest`, and this module has no Android-edge test left to justify keeping it.
        // What remains (`ArtDownloadManager*`, `XMageImageSourceTest`) is pure Kotlin over fakes.
        androidUnitTest {
            dependencies {
                implementation(libs.junit4)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}
