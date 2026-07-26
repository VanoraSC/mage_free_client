plugins {
    id("magefree.android.library")
}

android {
    namespace = "magefree.network"
}

dependencies {
    // The domain vocabulary this module maps *into*; re-exported (api) because a BridgeClient's
    // signatures are all `:core:model` types.
    api(project(":core:model"))

    // The wire contract. `implementation` (not api) keeps `:protocol` types confined to this
    // module — nothing above `:core:network` can see them.
    implementation(project(":protocol"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
