plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
    id("magefree.hilt")
}

android {
    namespace = "magefree.feature.decks"

    testOptions {
        unitTests {
            // Story 0049: the add-cards typing test renders the real composable under Robolectric, so
            // the hermetic gate needs Android resources (the design-system theme) on the JVM.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The offline deck library/legality/import-export (0033/0034), the offline card catalog +
    // on-demand art loader/prefetch (0030/0031), and the design system (theme + 0014 card components).
    implementation(project(":core:decks"))
    implementation(project(":core:cards"))
    implementation(project(":core:designsystem"))
    // Story 0035 reuses 0032's search ViewModel, card-art renderer, inspection surface, and the art
    // cache-policy controller/sheet rather than re-implementing card search/rendering.
    implementation(project(":feature:cards"))

    // BackHandler for the in-feature builder/inspection overlays (back closes them before leaving).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Story 0049: JVM-side Compose UI testing for the builder's own search field, so both surfaces are
    // covered by the hermetic gate rather than only on a device. Scoped to the **debug** unit-test
    // variant because `createComposeRule` needs the host `ComponentActivity` that
    // `compose-ui-test-manifest` contributes as a `debugImplementation`; the Compose BOM is applied to
    // `implementation`/`androidTestImplementation` only, so this classpath pins it explicitly.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
