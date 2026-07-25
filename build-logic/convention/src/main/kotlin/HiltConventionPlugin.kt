import dagger.hilt.android.plugin.HiltExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `magefree.hilt` — the fixed Hilt recipe for any Hilt-consuming module. Applies KSP + the Hilt
 * plugin, sets `enableAggregatingTask = false` (route aggregation through KSP; see the toolchain
 * baseline for why), and adds the Hilt runtime + KSP compiler. One place, applied consistently.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }
            extensions.configure<HiltExtension> {
                enableAggregatingTask = false
            }
            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
