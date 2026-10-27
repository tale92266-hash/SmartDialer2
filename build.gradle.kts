// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Compose compiler plugin for Kotlin 2.2+ integration; version must match the Kotlin plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
