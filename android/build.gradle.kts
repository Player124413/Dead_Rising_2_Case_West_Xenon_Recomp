// Plugin versions live here and nowhere else, so that "which AGP built this APK" is one
// line to check and one line to change. Both are pinned rather than ranged: a build that
// picks up a new Android Gradle Plugin on the morning of a release is a build whose
// artifact nobody has tested, and the failure it produces (a differently-stripped native
// library, a different manifest merger answer) is not the kind anybody diagnoses quickly.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
