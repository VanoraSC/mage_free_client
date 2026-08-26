import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `magefree.kmp.android.library` — the shared config for a multiplatform `:core:*` module that
 * **still ships on Android**.
 *
 * The difference from [KmpLibraryConventionPlugin] is the second target. `:protocol` and
 * `:core:model` carry no Android at all, so a `jvm()` target is all they need. `:core:cards`
 * (as do `:core:decks` and `:core:network`) has a real Android edge —
 * bundled APK assets, the platform SQLite driver, `Context` — which lives in `androidMain` while the
 * logic above it lives in `commonMain`.
 *
 * **No `useJUnitPlatform()` here, and that is deliberate.** [KmpLibraryConventionPlugin] sets it
 * because those modules' suites are JUnit 5. This module's are JUnit 4 under Robolectric, and
 * switching the runner would not fail loudly — the tests would simply stop being discovered, which
 * is precisely the silent failure the test-count guard exists to catch. Android unit tests
 * default to JUnit 4; leaving the default alone is what keeps them running.
 */
class KmpAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply("com.android.library")
                apply("org.jlleitschuh.gradle.ktlint")
            }
            extensions.configure<KotlinMultiplatformExtension> {
                // every module here now declares `expect` classes — Room's database
                // constructor in `:core:decks`, and the `java.util.concurrent` aliases that let
                // `:core:cards`/`:core:network` keep their real concurrency primitives while naming
                // no `java.*` type in `commonMain`. Expect/actual *classes* are still flagged Beta by
                // the compiler; this is their documented shape, so the warning is turned off once
                // here rather than carried on every build of every target.
                compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

                jvmToolchain(17)
                androidTarget {
                    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
                }
                jvm {
                    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
                }
            }
            extensions.configure<LibraryExtension> {
                compileSdk = 36
                defaultConfig { minSdk = 26 }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                        isReturnDefaultValues = true
                    }
                }
            }
            configureConcurrentFuturesAlignment()
        }
    }
}
