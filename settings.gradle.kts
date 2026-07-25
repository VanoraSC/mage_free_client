pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mage-free-client"

include(":protocol")
include(":bridge")

// Android modules require the Android SDK. The JVM/bridge build container has no SDK, so it sets
// MAGE_JVM_ONLY=1 to skip them; host builds include them normally. See docs/build-environment.md.
if (System.getenv("MAGE_JVM_ONLY") != "1") {
    include(":app")
    include(":core:designsystem")
}
