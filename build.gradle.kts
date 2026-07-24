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

// --- macOS signing + notarization, opt-in via environment (nothing secret is committed) ---
// Set MACOS_SIGN_IDENTITY plus the three NOTARIZATION_* vars, then `./gradlew notarizeDmg` builds a
// signed, stapled, Gatekeeper-clean .dmg. With them unset (contributors, CI, the Linux .deb),
// `packageDmg` builds an unsigned .dmg exactly as before.
val macSignIdentity: String? = System.getenv("MACOS_SIGN_IDENTITY")
val notaryAppleId = providers.environmentVariable("NOTARIZATION_APPLE_ID")
val notaryPassword = providers.environmentVariable("NOTARIZATION_PASSWORD")
val notaryTeamId = providers.environmentVariable("NOTARIZATION_TEAM_ID")

// RelayPony Desktop is not a rewrite: it compiles the exact wire/session/crypto code the Android
// app ships, plus the age core from AgePony. Those sources are vendored under vendor/ (Apache-2.0).
// Only src/main/kotlin/com/relaypony/desktop is desktop-specific. NsdDiscovery is Android-only.
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
// Native installers: `./gradlew packageDmg` / `notarizeDmg` (macOS) / `packageDeb` (Linux), `packageMsi` (Windows).
compose.desktop {
    application {
        mainClass = "com.relaypony.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "RelayPony"
            packageVersion = "2.0.0"
            description = "Encrypted, direct device-to-device file transfer"
            vendor = "NorseHorse"
            copyright = "Copyright 2026 NorseHorse"
            macOS {
                iconFile.set(project.file("packaging/relaypony.icns"))
                bundleID = "app.relaypony.desktop"
                if (!macSignIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(macSignIdentity)
                    }
                    notarization {
                        appleID.set(notaryAppleId)
                        password.set(notaryPassword)
                    }
                }
            }
            linux {
                iconFile.set(project.file("packaging/relaypony.png"))
                packageName = "relaypony"                              // lowercase for the .deb package id
            }
            windows {
                iconFile.set(project.file("packaging/relaypony.ico"))
                menu = true                                            // Start-menu entry…
                menuGroup = "RelayPony"                                // …grouped under RelayPony
                shortcut = true                                        // and a desktop shortcut
                dirChooser = true                                      // let the user choose the install dir
                // Fixed identity so each new .msi upgrades the previous install in place.
                upgradeUuid = "caae62d9-7cda-4fac-b915-4a407c459e1a"
            }
        }
    }
}

// Keep the CLI's interactive stdin on `./gradlew run` (defensive: no-op if run isn't a JavaExec here).
tasks.matching { it.name == "run" }.configureEach {
    (this as? JavaExec)?.standardInput = System.`in`
}
