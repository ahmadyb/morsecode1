// ────────────────────────────────────────────────────────────────────────────
// Root build script. All plugin versions live in gradle/libs.versions.toml.
// ────────────────────────────────────────────────────────────────────────────
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
}

// ────────────────────────────────────────────────────────────────────────────
// Spec-command compatibility shim.
//
// Section 0 of the spec repeatedly instructs `./gradlew :shared:jvmTest`.
// With a Kotlin Multiplatform `jvm("desktop")` target the generated test task
// is actually `:shared:desktopTest` (the source set is `desktopMain`, exactly
// as the spec's PROJECT STRUCTURE tree requires). Registering this alias lets
// the documented command work verbatim instead of forcing a correction to
// every command in the spec.
// ────────────────────────────────────────────────────────────────────────────
// The :shared:jvmTest alias is registered EAGERLY in shared/build.gradle.kts.
// (A lazy matching-based alias is never created when the task is requested
// directly, which is what broke the CI run.)

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
