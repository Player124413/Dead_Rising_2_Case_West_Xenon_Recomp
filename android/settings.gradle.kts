// The Android app. See docs/android-port-plan.md for the whole port and android/README.md
// for how to build this.
//
// It is a Gradle project in its own directory rather than a module of some larger build
// because it is the only thing here that needs Gradle: the runtime is CMake, on every
// platform, and the one Android-specific job Gradle has is to package what CMake produced.
// externalNativeBuild below points straight at ../runtime/CMakeLists.txt — the same file
// the desktop build uses, with the same source list, so "does it build for Android" and
// "does it build" stay one question.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS rather than the default: a repository declared in a module's
    // build file is a second place dependencies can come from, and this project has exactly
    // one answer to "where do artifacts come from". It has no runtime dependencies at all —
    // see app/build.gradle.kts for why that is deliberate.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CaseWestAndroid"
include(":app")
