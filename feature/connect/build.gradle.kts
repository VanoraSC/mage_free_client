plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
    id("magefree.koin")
}

android {
    namespace = "magefree.feature.connect"

    testOptions {
        unitTests {
            // The server-list test renders the real composable under Robolectric, so the hermetic
            // gate needs Android resources (the design-system theme) on the JVM.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The design system this feature renders with (theme + components); the domain vocabulary it
    // speaks; and the repositories it drives. `:core:network` re-exports `:core:model` (api), but
    // the feature depends on it explicitly for clarity and to survive that ever changing.
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.koin.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // JVM-side Compose UI testing for the server list, so a control that renders but does nothing is
    // caught by the hermetic gate rather than only on a device. Scoped to the **debug** unit-test
    // variant because `createComposeRule` needs the host `ComponentActivity` that
    // `compose-ui-test-manifest` contributes as a `debugImplementation`; the Compose BOM is applied to
    // `implementation`/`androidTestImplementation` only, so this classpath pins it explicitly.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
