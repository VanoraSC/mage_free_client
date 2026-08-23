plugins {
    id("magefree.android.application")
    id("magefree.android.compose")
    id("magefree.koin")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "magefree.app"

    defaultConfig {
        applicationId = "magefree.app"
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests {
            // Story 0047: the cold-start reachability test drives the real Navigation-Compose graph on
            // the JVM under Robolectric, so `:app:testDebugUnitTest` (a hermetic gate) can prove the
            // connect destination is where a launch lands — no device, no emulator.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    // Story 0017: the real ConnectionStatusSource is backed by :core:network's ConnectionRepository.
    implementation(project(":core:network"))
    // Story 0030: bundles the offline card catalog (SQLite asset + CardCatalog DI) into the app.
    implementation(project(":core:cards"))
    // Story 0033: bundles the offline deck library (Room) + format-legality asset + DI into the app.
    implementation(project(":core:decks"))
    // Story 0047: the connect + sign-in flow (EPIC-04) the root graph starts on. It is the only thing
    // in the app that calls ConnectionRepository.connect(...), so without this dependency the APK can
    // never establish a session and every feature below is wired to data it can never receive.
    implementation(project(":feature:connect"))
    // Story 0029: the lobby browser behind the home "Play" entry.
    implementation(project(":feature:lobby"))
    // Story 0032: the card search/browse + inspection surface behind the Decks "Browse cards" entry.
    implementation(project(":feature:cards"))
    // Story 0035: the deck library + builder hosted on the Decks tab.
    implementation(project(":feature:decks"))
    // Story 0038: host/join/room/spectate tables UI behind the lobby's Join/Watch/Host entries.
    implementation(project(":feature:tables"))
    // Story 0055: the read-only game board the table room's MatchStarting hand-off now opens.
    implementation(project(":feature:game"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.sizeclass)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.koin.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Story 0047: JVM-side Compose + Navigation UI testing for the cold-start reachability test
    // (`src/testDebug`). Scoped to the **debug** unit-test variant because `createComposeRule` needs the
    // host `ComponentActivity` that `compose-ui-test-manifest` contributes, and the convention plugin
    // adds that as a `debugImplementation`. The Compose BOM is applied to `implementation` /
    // `androidTestImplementation` by that same plugin, so this classpath needs it explicitly to keep
    // Compose artifact versions aligned.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.androidx.navigation.testing)
    testDebugImplementation(libs.robolectric)
    // Story 0081: KoinGraphTest resolves every binding in `appModules` — the runtime replacement for
    // Hilt's compile-time missing-binding error. Needs an Android context, hence testDebug.
    testDebugImplementation(platform(libs.koin.bom))
    testDebugImplementation(libs.koin.test)
    testDebugImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
