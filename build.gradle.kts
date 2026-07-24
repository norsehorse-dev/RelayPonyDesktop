import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    kotlin("plugin.compose") version "2.2.10"          // Compose compiler (matches Kotlin)
    id("org.jetbrains.compose") version "1.11.1"       // Compose Multiplatform + native packaging
}

kotlin {
    jvmToolchain(17)
}

// RelayPony Desktop is not a rewrite: it compiles the exact wire/session/crypto code the Android
// app ships, plus the age core from AgePony. Those sources are vendored under vendor/ (Apache-2.0,
// from the RelayPonyAndroid and AgePonyAndroid repos). Only src/main/kotlin/com/relaypony/desktop
// is desktop-specific. NsdDiscovery is the one Android-only file (excluded; desktop uses jmdns).
sourceSets {
    main {
        kotlin {
            srcDir("vendor/agepony-core")
            srcDir("vendor/crypto")
            srcDir("vendor/transport")
            srcDir("vendor/session")
            exclude("**/NsdDiscovery.kt")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)                          // Compose runtime + Skiko for this OS
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")             // agepony-core's crypto engine
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jmdns:jmdns:3.5.9")                            // desktop mDNS (replaces Android NsdManager)
    implementation("com.google.zxing:core:3.5.3")                      // render the pairing QR
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")                          // silence jmdns' SLF4J notice
}

// One binary, two faces: no args opens the GUI (`./gradlew run`), args run the CLI (send/receive/…).
// Native installers: `./gradlew packageDmg` (macOS) / `packageDeb` (Linux) / `packageDistributionForCurrentOS`.
compose.desktop {
    application {
        mainClass = "com.relaypony.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "RelayPony"
            packageVersion = "2.0.0"
            description = "Encrypted, direct device-to-device file transfer"
            vendor = "NorseHorse"
            copyright = "Copyright 2026 NorseHorse"
            macOS {
                iconFile.set(project.file("packaging/relaypony.icns"))
                bundleID = "app.relaypony.desktop"
            }
            linux {
                iconFile.set(project.file("packaging/relaypony.png"))
                packageName = "relaypony"
            }
        }
    }
}

// Keep the CLI's interactive stdin on `./gradlew run` (defensive: no-op if run isn't a JavaExec here).
tasks.matching { it.name == "run" }.configureEach {
    (this as? JavaExec)?.standardInput = System.`in`
}
