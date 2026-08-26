plugins {
    id("magefree.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

// Multiplatform: the bridge↔app wire contract. Converted with no source changes —
// `:bridge` consumes it from a plain Kotlin/JVM project and `:core:network` from an Android library,
// and both resolve the `jvm` target.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // `api`: the contract's types are `@Serializable` and every consumer needs the
                // serializers on its own compile classpath.
                api(libs.kotlinx.serialization.json)
            }
        }
        // JUnit 5, so the suite lives on the JVM target rather than in `commonTest` — moving it to
        // common would mean rewriting every assertion onto `kotlin.test` in the same change that
        // ports the build. See the convention plugin's KDoc.
        jvmTest {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}
