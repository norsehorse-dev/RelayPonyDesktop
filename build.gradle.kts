import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

// RelayPony Desktop is not a rewrite: it compiles the Android app's protocol core straight from a
// RelayPonyAndroid checkout (crypto, transport and session, plus the AgePony age core and the
// PonyDirect WAN transport that Android pins as its own submodules). One source of truth, so the
// desktop can't drift from the phones. Only src/main/kotlin/com/relaypony/desktop is desktop-specific.
//
// The pinned RelayPonyAndroid submodule is the default, which is what CI and release builds use.
// To build against another checkout (a sibling with unpushed work):
//     ./gradlew -Prelaypony.androidDir=/path/to/RelayPonyAndroid run
// or set RELAYPONY_ANDROID_DIR. NsdDiscovery is Android-only (jmdns replaces it here).
val androidDir: File = run {
    val override = providers.gradleProperty("relaypony.androidDir").orNull
        ?: System.getenv("RELAYPONY_ANDROID_DIR")?.takeIf { it.isNotBlank() }
    val dir = if (override != null) file(override.replaceFirst("~", System.getProperty("user.home"))) else file("RelayPonyAndroid")
    if (!dir.resolve("session/src/main/kotlin").isDirectory) {
        throw GradleException(
            "RelayPonyAndroid not found at ${dir.path}. Run git submodule update --init --recursive, " +
                "or point -Prelaypony.androidDir at a RelayPonyAndroid checkout."
        )
    }
    if (override != null) logger.warn("RelayPony Desktop: compiling the Android core from ${dir.canonicalPath}, not the pinned submodule.")
    dir
}

sourceSets {
    main {
        kotlin {
            srcDir(androidDir.resolve("AgePonyAndroid/agepony-core/src/main/kotlin"))
            srcDir(androidDir.resolve("PonyDirect-Kotlin/ponydirect/src/main/kotlin"))
            srcDir(androidDir.resolve("crypto/src/main/kotlin"))
            srcDir(androidDir.resolve("transport/src/main/kotlin"))
            srcDir(androidDir.resolve("session/src/main/kotlin"))
            // Word codes: only WordCodePairing from the Android pake module. Its UniFFI bindings are
            // gitignored there (scripts/build-pake.sh makes them), so desktop generates its own below.
            srcDir(androidDir.resolve("pake/src/main/kotlin/com"))
            srcDir(layout.buildDirectory.dir("pake-kotlin"))
            exclude("**/NsdDiscovery.kt")
        }
        resources.srcDir(layout.buildDirectory.dir("pake-resources"))
        resources.srcDir(layout.buildDirectory.dir("android-strings"))
    }
}

// ---- Word-code pairing's Rust core (RelayPonyPake, pinned inside RelayPonyAndroid) ----
// Built for the OS doing the build and packed into the jar under JNA's platform folder
// (darwin-aarch64, linux-x86-64, win32-x86-64, ...), where the UniFFI bindings find it at run time.
// Each installer is built on its own OS, so each carries its own library. The toolchain is pinned by
// the crate's rust-toolchain.toml; paths are remapped so two machines produce the same bytes.
val pakeDir: File = androidDir.resolve("RelayPonyPake")
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = when (val a = System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    "amd64", "x86_64" -> "x86-64"
    else -> a
}
val jnaPrefix = when {
    hostOs.contains("mac") -> "darwin-$hostArch"
    hostOs.contains("win") -> "win32-$hostArch"
    else -> "linux-$hostArch"
}
val pakeLibName = when {
    hostOs.contains("mac") -> "librelaypony_pake_ffi.dylib"
    hostOs.contains("win") -> "relaypony_pake_ffi.dll"
    else -> "librelaypony_pake_ffi.so"
}
val pakeTargetDir: File = layout.buildDirectory.dir("pake-target").get().asFile
val pakeOutDir: File = layout.buildDirectory.dir("pake-resources/$jnaPrefix").get().asFile
val cargoBin = (System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo") + "/bin"

val buildPake = tasks.register<Exec>("buildPake") {
    description = "Builds RelayPonyPake's pake-ffi for this OS and stages it for JNA."
    if (!pakeDir.resolve("crates/pake-ffi").isDirectory) {
        throw GradleException("RelayPonyPake not found in ${pakeDir.path}. Run git submodule update --init --recursive.")
    }
    workingDir = pakeDir
    commandLine("cargo", "build", "--release", "--locked", "-p", "pake-ffi", "--target-dir", pakeTargetDir.path)
    environment("PATH", cargoBin + File.pathSeparator + (System.getenv("PATH") ?: ""))
    environment(
        "RUSTFLAGS",
        "--remap-path-prefix=${pakeDir.canonicalPath}=/relaypony-pake --remap-path-prefix=${System.getProperty("user.home")}=/home",
    )
    outputs.file(pakeOutDir.resolve(pakeLibName))
    outputs.upToDateWhen { false }                                       // cargo decides what is stale
    doLast {
        val built = pakeTargetDir.resolve("release/$pakeLibName")
        if (!built.isFile) throw GradleException("cargo finished but ${built.path} is missing")
        pakeOutDir.mkdirs()
        Files.copy(built.toPath(), pakeOutDir.resolve(pakeLibName).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
// UniFFI Kotlin bindings for pake-ffi. uniffi-bindgen reads metadata the release profile strips, so
// they come from a host debug build of the same crate, as in Android's scripts/build-pake.sh.
val pakeKotlinDir: File = layout.buildDirectory.dir("pake-kotlin").get().asFile
val pakeRustFlags =
    "--remap-path-prefix=${pakeDir.canonicalPath}=/relaypony-pake --remap-path-prefix=${System.getProperty("user.home")}=/home"
val pakeHostDebug = tasks.register<Exec>("pakeHostDebug") {
    description = "Builds a debug pake-ffi for this OS, for uniffi-bindgen to read."
    workingDir = pakeDir
    commandLine("cargo", "build", "--locked", "-p", "pake-ffi", "--target-dir", pakeTargetDir.path)
    environment("PATH", cargoBin + File.pathSeparator + (System.getenv("PATH") ?: ""))
    environment("RUSTFLAGS", pakeRustFlags)
    outputs.upToDateWhen { false }
}
val pakeBindings = tasks.register<Exec>("pakeBindings") {
    description = "Generates the UniFFI Kotlin bindings for pake-ffi into build/pake-kotlin."
    dependsOn(pakeHostDebug)
    workingDir = pakeDir
    commandLine(
        "cargo", "run", "-q", "--locked", "-p", "pake-ffi", "--features", "cli", "--bin", "uniffi-bindgen",
        "--target-dir", pakeTargetDir.path, "--",
        "generate", "--library", pakeTargetDir.resolve("debug/$pakeLibName").path,
        "--language", "kotlin", "--no-format", "--out-dir", pakeKotlinDir.path,
    )
    environment("PATH", cargoBin + File.pathSeparator + (System.getenv("PATH") ?: ""))
    environment("RUSTFLAGS", pakeRustFlags)
    outputs.dir(pakeKotlinDir)
    outputs.upToDateWhen { false }
    doFirst { pakeKotlinDir.deleteRecursively(); pakeKotlinDir.mkdirs() }
    doLast {
        val kt = pakeKotlinDir.resolve("uniffi/relaypony_pake_ffi/relaypony_pake_ffi.kt")
        if (!kt.isFile) throw GradleException("uniffi-bindgen finished but ${kt.path} is missing")
    }
}
tasks.named("compileKotlin") { dependsOn(pakeBindings) }

// The Android app's translated strings, read at run time underneath the desktop's own (see I18n.kt).
val copyAndroidStrings = tasks.register<Sync>("copyAndroidStrings") {
    from(androidDir.resolve("app/src/main/res")) { include("values*/strings.xml") }
    into(layout.buildDirectory.dir("android-strings/strings/android"))
}
tasks.named("processResources") { dependsOn(buildPake, copyAndroidStrings) }

dependencies {
    implementation(compose.desktop.currentOs)                          // Compose runtime + Skiko for this OS
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")             // agepony-core's crypto engine
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.json:json:20240303")                           // org.json, which Android ships built in
    implementation("net.java.dev.jna:jna:5.19.1")                      // loads the word-code Rust core (UniFFI)
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
            packageVersion = "4.0.0"
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
