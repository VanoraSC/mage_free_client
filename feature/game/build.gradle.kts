plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
    id("magefree.koin")
}

android {
    namespace = "magefree.feature.game"

    testOptions {
        unitTests {
            // Story 0055: the board's Compose tests render the real screen under Robolectric so every
            // region (and every *empty* region) is covered by the **hermetic** gate rather than only by
            // a device test — device tests do not run pre-merge, which is how an entire epic stayed
            // unmounted (story 0047/0048). Rendering the real screen needs the design-system theme's
            // Android resources on the JVM.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The design system the board renders with (theme tokens + 0014's card components), the app-schema
    // GameClient/GameState this board draws (0052/0054), and 0031's card-art identity type. All three
    // are consumed read-only: nothing here changes them.
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":core:cards"))

    // Story 0055 reuses 0032's Coil-backed `CardArtRenderer` (`rememberCardArtRenderer` / `slotFor`)
    // rather than re-binding Coil to the design system's art slot a second time — exactly as
    // `:feature:decks` reuses it. The dependency is on the renderer seam only; no card-search or
    // inspection surface is pulled into the board.
    implementation(project(":feature:cards"))

    // BackHandler: back collapses the expanded hand before it leaves the board.
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

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // The on-device driver (`src/test/.../live/BoardOpponentDriverIT`) is the *second client* of the
    // two-client harness: it hosts a two-human table and plays one seat while the app on the emulator
    // renders the other. Hosting means submitting a deck, so it needs `:core:decks`' Deck model and
    // `:core:model`'s SkillLevel on the **test** classpath only — production code on this board never
    // sees either. It is env-gated on BRIDGE_URL, so `check` stays hermetic and offline.
    testImplementation(project(":core:decks"))
    testImplementation(project(":core:model"))

    // JVM-side Compose UI testing for the board's rendering tests. Scoped to the **debug** unit-test
    // variant because `createComposeRule` needs the host `ComponentActivity` that
    // `compose-ui-test-manifest` contributes as a `debugImplementation`; the Compose BOM is applied to
    // `implementation`/`androidTestImplementation` by the convention plugin, so this classpath pins it
    // explicitly to keep Compose artifact versions aligned.
    testDebugImplementation(platform(libs.compose.bom))
    testDebugImplementation(libs.compose.ui.test.junit4)
    testDebugImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
