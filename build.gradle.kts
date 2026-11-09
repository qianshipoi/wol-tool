plugins {
    // AGP 9 ships built-in Kotlin support, so the standalone kotlin-android plugin is not applied.
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
