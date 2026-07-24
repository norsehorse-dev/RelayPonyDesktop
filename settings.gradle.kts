// RelayPony desktop — a SEPARATE Gradle build from the Android app (which is AGP-only and, by a
// deliberate root-build decision, has no kotlin("jvm") module). Standing on its own lets us use a
// plain kotlin("jvm") toolchain with none of the AGP-9 / KGP BaseVariant conflict the root warns
// about, while reusing the exact same portable source (see build.gradle.kts shared source sets).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "relaypony-desktop"
