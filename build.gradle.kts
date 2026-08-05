// Top-level build file. Plugin versions are declared in the version catalog
// (gradle/libs.versions.toml) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
