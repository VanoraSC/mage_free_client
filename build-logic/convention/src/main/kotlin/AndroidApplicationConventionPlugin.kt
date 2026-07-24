import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `magefree.android.application` — the shared config for the Android application module.
 * Applies AGP application + Kotlin Android + ktlint, and the toolchain baseline (SDK/Java/Kotlin,
 * targetSdk, dependency alignment). Modules apply this instead of re-declaring any of it.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
                apply("org.jlleitschuh.gradle.ktlint")
            }
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig {
                    targetSdk = 36
                }
            }
            configureConcurrentFuturesAlignment()
        }
    }
}
