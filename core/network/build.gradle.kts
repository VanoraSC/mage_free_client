plugins {
    id("magefree.kmp.android.library")
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

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // The domain vocabulary this module maps *into*; re-exported (api) because a
                // BridgeClient's signatures are all `:core:model` types.
                api(project(":core:model"))

                // The wire contract. `implementation` (not api) keeps `:protocol` types confined to
                // this module — nothing above `:core:network` can see them.
                implementation(project(":protocol"))

                // Story 0037: the table client accepts a 0033 domain `Deck` for join/submit and maps
                // it (via 0033's own `Deck.toDeckList()`) onto the wire `DeckList`. `api` (like
                // `:core:model` above) because the `TableClient` ABI exposes `magefree.decks.model.Deck`
                // in its join/submit signatures, so a consumer (0038) resolves that type transitively.
                api(project(":core:decks"))

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                // Story 0084: `okio.IOException` is an `actual typealias` to `java.io.IOException`
                // on the JVM, so `ServerRepository` catches exactly what DataStore throws while
                // `commonMain` names no `java.*` type.
                implementation(libs.okio)

                // Story 0017: server-list persistence (per AGENTS.md — DataStore for prefs). `api`
                // because the module publishes a `DataStore<Preferences>` binding that the consuming
                // graph resolves. Story 0084: `datastore-preferences` is already a multiplatform
                // artifact (it resolves to `-android` and `-jvm` variants), so the port swaps no
                // coordinate here — only where the store is constructed.
                api(libs.androidx.datastore.preferences)
            }
        }

        androidMain {
            dependencies {
                // Story 0084: the platform edge — `androidContext()` for the connectivity observer
                // and the DataStore file.
                implementation(libs.koin.android)
                // Story 0024: ProcessLifecycleOwner for the whole-app foreground/background
                // reconnect hook. Story 0084: declared for the Android target only, because
                // `ProcessAppLifecycleObserver` is the sole reason it is here.
                implementation(libs.androidx.lifecycle.process)
                // Story 0084: OkHttp is an engine choice rather than a portability problem, and
                // swapping it would add an untested variable to a port. It stays, on the target that
                // ships; `KtorBridgeClient` picks up whichever engine is on the classpath.
                implementation(libs.ktor.client.okhttp)
            }
        }

        jvmMain {
            dependencies {
                // Story 0084: the JVM target's engine. The same OkHttp the Android target uses — a
                // JVM host can, and one engine across the module is one set of timeout, redirect and
                // proxy behaviours rather than two.
                implementation(libs.ktor.client.okhttp)
            }
        }

        androidUnitTest {
            dependencies {
                implementation(libs.junit4)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)

                // Story 0046: KtorBridgeClientSignOutTest runs the real client against a throwaway
                // in-process WebSocket bridge, so "sign-out puts a Logout on the wire (and a drop
                // does not)" is asserted on actual frames rather than on a fake's idea of them.
                // Test-only, same pinned Ktor as everywhere.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.websockets)

                // Story 0084: SavedServersSurviveUpgradeTest reads a pre-port DataStore file through
                // the real `networkModule`, which needs a Context for `filesDir`.
                implementation(libs.robolectric)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}
