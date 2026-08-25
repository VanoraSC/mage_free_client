pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle provision a JDK matching gradle/gradle-daemon-jvm.properties when no installed one
    // satisfies it. The build container already ships JDK 17, so this only ever fires on a fresh
    // machine — it is the "works on a new checkout" half of the daemon toolchain, not a build-time
    // download in normal use.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
    // Story 0038: host/join/room/spectate tables UI over :core:network's 0037 TableClient, reusing
    // :core:decks for offline deck pick + legality (Android lib; needs the SDK).
    include(":feature:tables")
    // Story 0055: the read-only portrait game board over :core:network's 0052 GameClient/GameState,
    // reusing :feature:cards' Coil-backed art renderer (Android lib; needs the SDK).
    include(":feature:game")
}
