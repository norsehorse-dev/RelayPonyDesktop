# RelayPony Desktop

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-1f9cf0.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/norsehorse-dev/RelayPonyDesktop?color=1f9cf0)](https://github.com/norsehorse-dev/RelayPonyDesktop/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/norsehorse-dev/RelayPonyDesktop/total?color=1f9cf0)](https://github.com/norsehorse-dev/RelayPonyDesktop/releases)
![Platform](https://img.shields.io/badge/platform-macOS_%7C_Linux-1f9cf0)

Encrypted, direct device-to-device file transfer for **macOS, Linux, and Windows** — the desktop
member of the [RelayPony](https://relaypony.app) family.

RelayPony sends files straight between your devices, end-to-end encrypted with the
[age](https://age-encryption.org) protocol: over the local network when they share one, and over the
internet (peer to peer, with a relay that only sees ciphertext as the fallback) when they don't. No
cloud storage, no account. The desktop
app pairs with the RelayPony iOS and Android apps by QR code and moves files both ways; leave it
running and your computer becomes a drop target for every paired device.

## Not a rewrite

This app compiles the **exact** protocol code the RelayPony Android app ships: its crypto, transport
and session modules, the age core from AgePony and the PonyDirect internet transport, all taken
straight from the pinned [RelayPonyAndroid](https://github.com/norsehorse-dev/RelayPonyAndroid)
submodule. One tested implementation means guaranteed interop across every device you own, and no
copy that can drift. Only `com.relaypony.desktop` under [`src/`](src/) is desktop-specific: the
Compose UI, the CLI, jmdns discovery, and the file-backed identity, trust and settings stores.

## Build & run

Requires a JDK 17 or newer and [rustup](https://rustup.rs): the build compiles the word-code
pairing core (RelayPonyPake, Rust) for your OS, using the toolchain the crate pins. Clone with the
submodules:

```sh
git clone --recursive https://github.com/norsehorse-dev/RelayPonyDesktop.git
./gradlew run                      # launch the GUI
./gradlew run --args="pair"        # CLI: pair with another device (also: devices, receive, send, doctor, selftest)
```

An existing clone needs `git submodule update --init --recursive`. To build against another
RelayPonyAndroid checkout, pass `-Prelaypony.androidDir=/path/to/RelayPonyAndroid`.

## Native installers

```sh
./gradlew packageDmg               # macOS → build/compose/binaries/main/dmg/RelayPony-4.0.0.dmg
./gradlew packageDeb               # Linux → build/compose/binaries/main/deb/relaypony_4.0.0_*.deb
./gradlew packageDistributionForCurrentOS
```

jpackage builds an installer only for the OS it runs on, so build the `.dmg` on macOS and the
`.deb` on a Linux host. The Java runtime is bundled into each installer — users need no separate JDK.

## Security

Trust is keyed to each device's **age public key**, never its display name. Pairing is mutual: one
device scans the other's QR code and both confirm the same eight-digit code, matching the phones.
Only paired devices can send here unless you allow others in Settings, and each send carries a
signed HELLO so another device can't pose as a paired one. The
transfer is age (X25519 + ChaCha20-Poly1305) end to end. See the RelayPony protocol spec in the
core repositories for the frozen, cipher-agnostic wire format.

## License

Apache-2.0. See [LICENSE](LICENSE).
