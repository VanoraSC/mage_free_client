plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
    id("magefree.koin")
}

android {
    namespace = "magefree.feature.cards"

    testOptions {
        unitTests {
            // the search field's typing test renders the real composable under Robolectric,
            // so the hermetic gate needs Android resources (the design-system theme) on the JVM.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The offline card catalog + on-demand art loader this feature renders, and
    // the design system it renders with (theme + the card-forward components). Consumed read-only.
    implementation(project(":core:cards"))
    implementation(project(":core:designsystem"))

    // BackHandler for the in-feature inspection overlay (back closes it before leaving the feature).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.koin.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.core)

    // the loader hands out a Coil ImageLoader; card-browse binds it to AsyncImage here.
    implementation(libs.coil.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // the prefetch-size coverage test constructs the CardArtCachePolicyRepository over
    // a no-op DataStore, so the real DefaultArtCacheController (the "download all art" entry point) is
    // the thing under test rather than a stand-in.
    testImplementation(libs.androidx.datastore.preferences)

    // JVM-side Compose UI testing, so "does the field accept typing?" is answered by the
    // hermetic gate rather than only on a device (device tests do not run pre-merge — that is how a
    // fully-controlled, never-updating field shipped). Scoped to the **debug** unit-test variant
    // because `createComposeRule` needs the host `ComponentActivity` that `compose-ui-test-manifest`
    // contributes, and the compose convention plugin adds that as a `debugImplementation`. The Compose
    // BOM is applied to `implementation`/`androidTestImplementation` by that same plugin, so this
    // classpath needs it explicitly to keep Compose artifact versions aligned.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
