plugins {
    id("magefree.kmp.library")
}

// Multiplatform: the app's connection/session domain types. Deliberately
// dependency-free — no Android SDK and no `:protocol` wire types, so it configures in the JVM-only
// container too and stays the single, wire-agnostic vocabulary everything above `:core:network`
// speaks. The `jvm()` target comes from the convention plugin; SDK/Java/Kotlin settings are not
// re-declared here.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // Intentionally empty. This module's dependency-freeness is a property worth
                // keeping, not an accident — see the KDoc on `magefree.model`.
            }
        }
    }
}
