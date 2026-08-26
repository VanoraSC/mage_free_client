import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `magefree.koin` — the fixed Koin recipe for any module that declares or consumes DI bindings
 *. Replaces `magefree.hilt`.
 *
 * **No annotation processor, and that is the point.** Hilt needed KSP, the Hilt Gradle plugin, and
 * `enableAggregatingTask = false` in every consuming module (its legacy javac aggregating task
 * bundles a `kotlin-metadata-jvm` that cannot read Kotlin 2.4 metadata). All of that goes with it.
 * KSP itself stays where it is still earned — Room in `:core:decks`.
 *
 * **`koin-core` only, by default.** It is multiplatform, so a `:core:*` module applying this plugin
 * gains nothing that pins it to Android — which is what lets those modules move
 * without unpicking their DI first. Modules that genuinely need the Android or Compose bindings add
 * them explicitly, so that need stays visible in the module's own build file rather than being
 * granted silently to everything.
 *
 * **What is bought back in exchange for compile-time safety.** Hilt failed the build when a binding
 * was missing; Koin fails at runtime, on whichever screen first asks for it. `koin-test` is added to
 * the test classpath here, unconditionally, so the graph-verification test is always available to
 * the module that declares bindings — the check is not optional infrastructure, it is the
 * replacement for what Hilt was doing.
 */
class KoinConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Every build file lists its library convention plugin (which applies the Kotlin plugin)
            // before `magefree.koin`, so the target shape is already known here. If that order were
            // ever reversed the multiplatform module would take the single-target path and fail to
            // compile `commonMain` against `koin-core` — loudly, at compile time.
            if (pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
                applyMultiplatform()
            } else {
                applySingleTarget()
            }
        }
    }

    /**
     * A single-target module: one `implementation` configuration, and unit tests under `test`.
     *
     * Guarded rather than assumed because Android library/application modules split unit tests
     * across `test` and `testDebug` while a plain Kotlin module has only `test`, and adding to a
     * configuration that does not exist fails configuration.
     */
    private fun Project.applySingleTarget() {
        dependencies {
            add("implementation", platform(libs.findLibrary("koin-bom").get()))
            add("implementation", libs.findLibrary("koin-core").get())
            addTestDependency(this, "testImplementation", platform(libs.findLibrary("koin-bom").get()))
            addTestDependency(this, "testImplementation", libs.findLibrary("koin-test").get())
        }
    }

    /**
     * A multiplatform module: `koin-core` belongs to `commonMain`, not to a per-target configuration
     *.
     *
     * The plain `implementation` configuration a KMP + AGP module still has reaches only the Android
     * target, so a Koin module written in `commonMain` — which is where `:core:decks`' is, and where
     * the remaining `:core:*` conversions are heading — would not compile against it. Putting it in
     * `commonMain` is what the "koin-core only, it is multiplatform" rule above was always for; this
     * is the first module to actually exercise it.
     */
    private fun Project.applyMultiplatform() {
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.named("commonMain") {
                dependencies {
                    implementation(project.dependencies.platform(libs.findLibrary("koin-bom").get()))
                    implementation(libs.findLibrary("koin-core").get())
                }
            }
        }
        // `androidUnitTest` is the KMP name for what a single-target Android module calls `test`.
        dependencies {
            addTestDependency(this, "androidUnitTestImplementation", platform(libs.findLibrary("koin-bom").get()))
            addTestDependency(this, "androidUnitTestImplementation", libs.findLibrary("koin-test").get())
        }
    }

    private fun Project.addTestDependency(
        handler: DependencyHandler,
        configuration: String,
        dependency: Any,
    ) {
        if (configurations.findByName(configuration) != null) {
            handler.add(configuration, dependency)
        }
    }
}
