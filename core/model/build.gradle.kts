plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
}

// Pure Kotlin/JVM: the app's connection/session domain types. Deliberately dependency-free — no
// Android SDK and no `:protocol` wire types, so it configures in the JVM-only container too and
// stays the single, wire-agnostic vocabulary everything above `:core:network` speaks.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
