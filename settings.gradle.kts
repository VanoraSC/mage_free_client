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
        // mavenLocal resolves org.mage:mage-common (+ org.mage:mage), baked into the build image's
        // /root/.m2 (story 0021) and consumed only by :bridge. Declared here because the repo runs
        // FAIL_ON_PROJECT_REPOS, which forbids a project-level repositories {} block; story 0003's
        // "add mavenLocal to the :bridge build" is realized here. It only affects resolution — no
        // module gains an org.mage dependency by its presence.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "mage-free-client"

include(":protocol")
include(":bridge")

// Pure Kotlin/JVM (no Android SDK), so it configures in the JVM-only container too — kept alongside
// :protocol rather than behind the Android guard below.
include(":core:model")

// Android modules require the Android SDK. The JVM/bridge build container has no SDK, so it sets
// MAGE_JVM_ONLY=1 to skip them; host builds include them normally. See docs/build-environment.md.
if (System.getenv("MAGE_JVM_ONLY") != "1") {
    include(":app")
    include(":core:designsystem")
    include(":core:network")
    // Story 0030: bundled XMage card catalog + offline local search (Android lib; needs the SDK).
    include(":core:cards")
    // Story 0033: offline deck model, local deck library (Room) + bundled format-legality (Android lib).
    include(":core:decks")
    include(":feature:connect")
    include(":feature:lobby")
    // Story 0032: card search/browse + full-bleed inspection UI over :core:cards (Android lib).
    include(":feature:cards")
    // Story 0035: deck library + touch-first builder over :core:decks (+ :core:cards art), reusing
    // :feature:cards' search/art (Android lib; needs the SDK).
    include(":feature:decks")
}
