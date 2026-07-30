plugins {
    id("magefree.android.library")
    // Story 0017: this module now provides Hilt-injected repositories (ConnectionRepository,
    // ServerRepository) + the BridgeClient/DataStore bindings, so it applies the shared Hilt recipe.
    id("magefree.hilt")
    // Story 0017: ServerRepository serializes its persisted @Serializable DTO to the DataStore. The
    // module already consumed :protocol's generated serializers via ProtocolJson; persisting a local
    // shape needs the serialization compiler plugin applied here too (same pinned Kotlin version).
    alias(libs.plugins.kotlin.serialization)
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
    // Story 0024: ProcessLifecycleOwner for the whole-app foreground/background reconnect hook.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    // Story 0017: server-list persistence (per AGENTS.md — DataStore for prefs). `api` because the
    // module's Hilt surface provides a `DataStore<Preferences>` binding, which the consuming :app must
    // resolve when it aggregates the Hilt component.
    api(libs.androidx.datastore.preferences)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
