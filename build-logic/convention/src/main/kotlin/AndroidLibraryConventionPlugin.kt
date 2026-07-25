import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `magefree.android.library` — the shared config for Android library modules (`:core:*`,
 * `:feature:*`). Applies AGP library + Kotlin Android + ktlint and the toolchain baseline. This is
 * what stops each new module from re-deriving `compileSdk`/`minSdk`/Java/Kotlin settings.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
                apply("org.jlleitschuh.gradle.ktlint")
            }
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
            }
            configureConcurrentFuturesAlignment()
        }
    }
}
