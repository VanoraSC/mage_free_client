plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
}

android {
    namespace = "magefree.designsystem"

    testOptions {
        unitTests {
            // The catalog test renders real composables under Robolectric, so the hermetic gate needs
            // Android resources (the theme) on the JVM.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.sizeclass)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.animation.core)

    testImplementation(libs.junit4)

    // JVM-side Compose UI testing, so a component that fails to compose is caught by the hermetic gate
    // rather than on a device. Scoped to the **debug** unit-test variant because `createComposeRule`
    // needs the host `ComponentActivity` that `compose-ui-test-manifest` contributes as a
    // `debugImplementation`; the Compose BOM is applied to `implementation`/`androidTestImplementation`
    // only, so this classpath pins it.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.robolectric)
}
