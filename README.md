# RelayPony Desktop

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-1f9cf0.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/norsehorse-dev/RelayPonyDesktop?color=1f9cf0)](https://github.com/norsehorse-dev/RelayPonyDesktop/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/norsehorse-dev/RelayPonyDesktop/total?color=1f9cf0)](https://github.com/norsehorse-dev/RelayPonyDesktop/releases)
![Platform](https://img.shields.io/badge/platform-macOS_%7C_Linux-1f9cf0)

Encrypted, direct device-to-device file transfer for **macOS, Linux, and Windows** — the desktop
member of the [RelayPony](https://relaypony.app) family.

RelayPony sends files straight between your devices over the local network, end-to-end encrypted
with the [age](https://age-encryption.org) protocol. No server, no cloud, no account. The desktop
app pairs with the RelayPony iOS and Android apps by QR code and moves files both ways; leave it
running and your computer becomes a drop target for every paired device.

## Not a rewrite

This app compiles the **exact** wire-protocol, session, and age-encryption code that the RelayPony
Android app ships, plus the age core from AgePony. One tested implementation means guaranteed
interop across every device you own, and far less code that could drift. The reused sources are
vendored under [`vendor/`](vendor/) (Apache-2.0, from the RelayPonyAndroid and AgePonyAndroid
repositories); only `com.relaypony.desktop` under [`src/`](src/) is desktop-specific — the Compose
UI, the CLI, jmdns discovery, and the file-backed identity/trust stores.

## Build & run

Requires a JDK 17 or newer.

```sh
./gradlew run                      # launch the GUI
./gradlew run --args="devices"     # CLI: list nearby devices (also: receive, send, pair, selftest)
```

## Native installers

```sh
./gradlew packageDmg               # macOS → build/compose/binaries/main/dmg/RelayPony-2.0.0.dmg
./gradlew packageDeb               # Linux → build/compose/binaries/main/deb/relaypony_2.0.0_*.deb
./gradlew packageDistributionForCurrentOS
```

jpackage builds an installer only for the OS it runs on, so build the `.dmg` on macOS and the
`.deb` on a Linux host. The Java runtime is bundled into each installer — users need no separate JDK.

## Security

Trust is keyed to each device's **age public key**, never its display name. Pairing is
trust-on-first-use over a QR code (with a six-digit verification code), matching the phones. The
transfer is age (X25519 + ChaCha20-Poly1305) end to end. See the RelayPony protocol spec in the
core repositories for the frozen, cipher-agnostic wire format.

## License

Apache-2.0. See [LICENSE](LICENSE).
