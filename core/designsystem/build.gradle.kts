plugins {
    id("magefree.android.library")
    id("magefree.android.compose")
}

android {
    namespace = "magefree.designsystem"
}

dependencies {
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
}
