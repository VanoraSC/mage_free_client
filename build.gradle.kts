// Root build: declares shared plugin versions via the version catalog. No application
// logic lives here — modules (currently only :bridge) apply the plugins they need.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}
