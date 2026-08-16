import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `magefree.android.compose` — enables Jetpack Compose for an Android module (application or
 * library). Applies the Kotlin Compose plugin, turns on `buildFeatures.compose`, and wires the
 * Compose **BOM** (main + androidTest) plus debug tooling. Compose modules then declare only the
 * specific Compose libraries they use — versions come from the BOM, so they can't drift.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            // The "android" extension's concrete type differs (application vs library), so configure
            // via whichever is present rather than the CommonExtension supertype.
            //
            // AGP 9 dropped CommonExtension's generic type parameters, and with them the
            // `buildFeatures { }` lambda-block sugar — configured via the plain property instead.
            val configure: (CommonExtension) -> Unit = { commonExtension ->
                commonExtension.buildFeatures.apply {
                    compose = true
                }
                dependencies {
                    val bom = platform(libs.findLibrary("compose-bom").get())
                    add("implementation", bom)
                    add("androidTestImplementation", bom)
                    add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
                    add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())
                }
            }

            when {
                pluginManager.hasPlugin("com.android.application") ->
                    extensions.configure<ApplicationExtension> { configure(this) }
                pluginManager.hasPlugin("com.android.library") ->
                    extensions.configure<LibraryExtension> { configure(this) }
            }
        }
    }
}
