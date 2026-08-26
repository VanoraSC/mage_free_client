plugins {
    `kotlin-dsl`
}

group = "magefree.buildlogic"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // The plugin artifacts are compileOnly: the convention plugins configure their DSL types but
    // the plugins themselves are applied by id (resolved via the root project's plugin declarations).
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "magefree.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "magefree.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "magefree.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        // Koin is the DI container, so there is no annotation processor and no
        // per-module aggregation workaround left to configure.
        register("koin") {
            id = "magefree.koin"
            implementationClass = "KoinConventionPlugin"
        }
        // The multiplatform logic modules — `:protocol`, `:core:model`, and
        // `:core:cards`, `:core:decks` and `:core:network`.
        register("kmpLibrary") {
            id = "magefree.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        // a multiplatform `:core:*` module that still ships on Android, so it carries an
        // `androidTarget()` alongside `jvm()` and an `androidMain` source set for the platform edge.
        register("kmpAndroidLibrary") {
            id = "magefree.kmp.android.library"
            implementationClass = "KmpAndroidLibraryConventionPlugin"
        }
    }
}
