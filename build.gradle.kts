// Top-level build file. Same toolchain the existing probe builds with on this machine:
// AGP 9.2.1 + Kotlin 2.2.10 (with the Compose compiler plugin) + KSP matching Kotlin.
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.2" apply false
}
