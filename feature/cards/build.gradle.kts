plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
    id("magefree.hilt")
}

android {
    namespace = "magefree.feature.cards"
}

dependencies {
    // The offline card catalog + on-demand art loader this feature renders (stories 0030/0031), and
    // the design system it renders with (theme + the 0014 card-forward components). Consumed read-only.
    implementation(project(":core:cards"))
    implementation(project(":core:designsystem"))

    // BackHandler for the in-feature inspection overlay (back closes it before leaving the feature).
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

    // Story 0031's loader hands out a Coil ImageLoader; 0032 binds it to AsyncImage here.
    implementation(libs.coil.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Story 0043: the prefetch-size coverage test constructs 0031's CardArtCachePolicyRepository over
    // a no-op DataStore, so the real DefaultArtCacheController (the "download all art" entry point) is
    // the thing under test rather than a stand-in.
    testImplementation(libs.androidx.datastore.preferences)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
