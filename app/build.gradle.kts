plugins {
    id("magefree.android.application")
    id("magefree.android.compose")
    id("magefree.hilt")
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
}

dependencies {
    implementation(project(":core:designsystem"))
    // Story 0017: the real ConnectionStatusSource is backed by :core:network's ConnectionRepository.
    implementation(project(":core:network"))
    // Story 0030: bundles the offline card catalog (SQLite asset + CardCatalog DI) into the app.
    implementation(project(":core:cards"))
    // Story 0033: bundles the offline deck library (Room) + format-legality asset + DI into the app.
    implementation(project(":core:decks"))
    // Story 0029: the lobby browser behind the home "Play" entry.
    implementation(project(":feature:lobby"))
    // Story 0032: the card search/browse + inspection surface behind the Decks "Browse cards" entry.
    implementation(project(":feature:cards"))
    // Story 0035: the deck library + builder hosted on the Decks tab.
    implementation(project(":feature:decks"))

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
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
