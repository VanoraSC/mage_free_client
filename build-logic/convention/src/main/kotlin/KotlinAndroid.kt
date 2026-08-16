import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** The project's single version catalog, for convention plugins to read pinned coordinates. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * The shared Android + Kotlin baseline for every Android module (application or library):
 * `compileSdk`/`minSdk`, Java 17, and Kotlin `jvmTarget` 17. Keeping this in one place is why a
 * module never re-declares SDK/Java/Kotlin versions — see the Project toolchain baseline.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = 36
        // AGP 9 dropped CommonExtension's generic type parameters, and with them the lambda-block
        // sugar (`defaultConfig { }`, `compileOptions { }`) that came from the old app/library-typed
        // generics — that sugar survives only on the concrete ApplicationExtension/LibraryExtension
        // subtypes now. configureKotlinAndroid is shared across both, so it configures the plain
        // property instead.
        defaultConfig.apply {
            minSdk = 26
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    extensions.getByType<KotlinAndroidProjectExtension>().apply {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

/**
 * Align `androidx.concurrent:concurrent-futures` across a module's classpaths: the main runtime
 * pulls 1.1.0 transitively (navigation → profileinstaller) while androidTest needs 1.2.0, and AGP
 * consistent resolution otherwise conflicts. Applied to every Android module for consistency.
 */
internal fun Project.configureConcurrentFuturesAlignment() {
    dependencies.constraints.add(
        "implementation",
        libs.findLibrary("androidx-concurrent-futures").get(),
    )
}
