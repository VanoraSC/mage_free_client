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
        // Story 0081 (EPIC-18): Koin replaced Hilt, so there is no annotation processor and no
        // per-module aggregation workaround left to configure.
        register("koin") {
            id = "magefree.koin"
            implementationClass = "KoinConventionPlugin"
        }
        // Story 0080 (EPIC-18): the multiplatform logic modules — `:protocol`, `:core:model`, and
        // `:core:cards`/`:core:decks`/`:core:network` as stories 0082-0084 convert them.
        register("kmpLibrary") {
            id = "magefree.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
    }
}
