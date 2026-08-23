import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `magefree.kmp.library` — the shared config for multiplatform logic modules (story 0080, EPIC-18).
 *
 * The analog of [AndroidLibraryConventionPlugin] for the modules that carry no Android at all:
 * applies the Kotlin Multiplatform plugin + ktlint, declares the targets, and pins the toolchain, so
 * a module never re-derives them and the set cannot drift between modules.
 *
 * **One target: `jvm()`.** A second target is added when something is actually built for it — a
 * declared target nothing compiles for is exactly the unfalsifiable "portable" claim this epic
 * exists to replace with a build result (`ui-modernization-plan.md` §11 Phase 0). `:bridge` already
 * consumes `:protocol` from a plain Kotlin/JVM project, so the JVM target has a real consumer from
 * day one.
 *
 * **Android consumers resolve the `jvm` variant.** Kotlin's platform-compatibility rules let an
 * `androidJvm` consumer use a `jvm` producer — the same mechanism by which every Android module here
 * already consumes `kotlinx-coroutines-core`. That is why these modules need no `androidTarget()`
 * despite `:app` and eight `:feature`/`:core` modules depending on them.
 *
 * **Tests are JUnit 5 on the JVM target, not `commonTest`.** The modules' existing suites are
 * written against `org.junit.jupiter`, which is JVM-only; moving them to `commonTest` would mean
 * rewriting every assertion onto `kotlin.test` in the same change that ports the build. Story 0080
 * forbids that deliberately — a diff mixing a build conversion with source edits cannot be reviewed
 * for either. `commonTest` becomes worth having when a second target does.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply("org.jlleitschuh.gradle.ktlint")
            }
            extensions.configure<KotlinMultiplatformExtension> {
                jvmToolchain(17)
                jvm {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
            }
            // The JVM target's test task runs JUnit 5, matching what these modules already used as
            // `kotlin("jvm")` projects. Without this the suites compile and silently run nothing.
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }
        }
    }
}
