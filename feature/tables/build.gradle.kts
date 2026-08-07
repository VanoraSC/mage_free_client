plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
    id("magefree.hilt")
}

android {
    namespace = "magefree.feature.tables"
}

dependencies {
    // The design system this feature renders with; the domain vocabulary (SkillLevel) it speaks; the
    // 0037 TableClient (+ TableState/CreateTableOptions) it drives; and the fully-offline deck library +
    // legality (0033) reused for the deck pick. Depends on `:core:decks` directly — never `:feature:decks`
    // — to keep the deck pick/legality offline without feature→feature coupling.
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:decks"))

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
    // The scriptable 0037 FakeTableClient lives in `:core:network`'s main source set; the hermetic
    // ViewModel tests here drive it directly (no bridge, no `:protocol`).

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
