plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
    id("magefree.koin")
}

android {
    namespace = "magefree.feature.tables"

    testOptions {
        unitTests {
            // The room test renders the real composable under Robolectric, so the hermetic gate needs
            // Android resources (the design-system theme) on the JVM.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The design system this feature renders with; the domain vocabulary (SkillLevel) it speaks; the
    // table TableClient (+ TableState/CreateTableOptions) it drives; and the fully-offline deck library +
    // legality reused for the deck pick. Depends on `:core:decks` directly — never `:feature:decks`
    // — to keep the deck pick/legality offline without feature→feature coupling.
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:decks"))

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
    // The scriptable table FakeTableClient lives in `:core:network`'s main source set; the hermetic
    // ViewModel tests here drive it directly (no bridge, no `:protocol`).
    //
    // One test (`TableRoomSeatSeamTest`) instead drives the room through the *real*
    // TableClient over a fake BridgeClient, so it can prove the room's seats are actually reachable from
    // a bridge reply — the blind spot that let the seat defect ship green. Scripting those replies means
    // naming wire messages, so `:protocol` is on the **test** classpath only: production code in this
    // module still never sees a wire type (`:core:network` keeps `:protocol` as `implementation`).
    testImplementation(project(":protocol"))

    // JVM-side Compose UI testing for the table room, so a surface that should not exist — or one that
    // renders and does nothing — is caught by the hermetic gate rather than only on a device. Scoped to
    // the **debug** unit-test variant because `createComposeRule` needs the host `ComponentActivity`
    // that `compose-ui-test-manifest` contributes as a `debugImplementation`; the Compose BOM is
    // applied to `implementation`/`androidTestImplementation` only, so this classpath pins it.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
