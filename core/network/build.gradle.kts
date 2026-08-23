plugins {
    id("magefree.android.library")
    // Story 0017: this module provides the repositories (ConnectionRepository, ServerRepository) and
    // the BridgeClient/DataStore bindings, so it applies the shared DI recipe. Koin since 0081.
    id("magefree.koin")
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

    // Story 0037: the table client accepts a 0033 domain `Deck` for join/submit and maps it (via
    // 0033's own `Deck.toDeckList()`) onto the wire `DeckList`. `api` (like `:core:model` above) because
    // the `TableClient` ABI exposes `magefree.decks.model.Deck` in its join/submit signatures, so a
    // consumer (0038) resolves that type transitively.
    api(project(":core:decks"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Story 0081: `androidContext()` for the connectivity observer and the DataStore file. This is
    // the module's remaining Android tie on the DI side; story 0084 removes it.
    implementation(libs.koin.android)
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

    // Story 0046: KtorBridgeClientSignOutTest runs the real client against a throwaway in-process
    // WebSocket bridge, so "sign-out puts a Logout on the wire (and a drop does not)" is asserted on
    // actual frames rather than on a fake's idea of them. Test-only, same pinned Ktor as everywhere.
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.websockets)
}
