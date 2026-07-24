# Releasing RelayPony Desktop

Each release ships three installers: a signed + notarized **`.dmg`** (macOS), a **`.deb`** (Linux),
and an **`.msi`** (Windows). `jpackage` only builds for the OS it runs on, so each installer is built
on its own platform — or all three automatically via CI (Option A).

The website (relaypony.app/desktop.php) links to the **stable asset names** `RelayPony-macOS.dmg`,
`RelayPony-linux.deb`, and `RelayPony-windows.msi` through `/releases/latest/download/…`, so every
release must attach its assets under exactly those names.

Bump `packageVersion` in `build.gradle.kts` before tagging a new version.

## Option A — CI (recommended)

Push a version tag; GitHub Actions builds and publishes all three installers:

```sh
git tag v2.0.1
git push origin v2.0.1
```

`.github/workflows/release.yml` builds the signed+notarized `.dmg` on a macOS runner, the `.deb` on
an Ubuntu runner, and the `.msi` on a Windows runner, renames them to the stable names, and creates
the GitHub Release. Configure these repository **secrets** first (Settings → Secrets and variables → Actions):

| Secret | What it is |
| --- | --- |
| `MACOS_CERT_P12_BASE64` | Your *Developer ID Application* cert + private key, exported as a `.p12` and base64-encoded (see below) |
| `MACOS_CERT_PASSWORD` | The password you set when exporting the `.p12` |
| `KEYCHAIN_PASSWORD` | Any throwaway string — the password for the temporary CI keychain |
| `MACOS_SIGN_IDENTITY` | The cert's full name, e.g. `Developer ID Application: Kevin Stewart (4AVJZV35G8)` |
| `NOTARIZATION_APPLE_ID` | Your Apple ID email |
| `NOTARIZATION_PASSWORD` | An app-specific password from appleid.apple.com (no trailing spaces) |
| `NOTARIZATION_TEAM_ID` | Your team ID, e.g. `4AVJZV35G8` |

Export the `.p12` once, on the Mac that holds the certificate:

- Keychain Access → **login** → **My Certificates** → right-click the `Developer ID Application`
  certificate → **Export** → save as `cert.p12` with a password (that password is `MACOS_CERT_PASSWORD`).
- `base64 -i cert.p12 | pbcopy` and paste into the `MACOS_CERT_P12_BASE64` secret.

## Option B — manual

### macOS (signed + notarized), on a Mac

```sh
export MACOS_SIGN_IDENTITY="Developer ID Application: Kevin Stewart (4AVJZV35G8)"
export NOTARIZATION_APPLE_ID="you@example.com"
export NOTARIZATION_PASSWORD="xxxx-xxxx-xxxx-xxxx"   # app-specific password, no trailing spaces
export NOTARIZATION_TEAM_ID="4AVJZV35G8"
./gradlew notarizeDmg -Pcompose.desktop.mac.notarization.teamID="$NOTARIZATION_TEAM_ID"
xcrun stapler validate build/compose/binaries/main/dmg/RelayPony-*.dmg
```

Gotchas learned the hard way:

- `MACOS_SIGN_IDENTITY` must be the certificate **name**, not its SHA-1 hash — Compose looks it up by name.
- Only **one** `Developer ID Application` cert may be in the keychain, or codesign reports
  "multiple matching certificates." List them with
  `security find-certificate -a -c "Developer ID Application" -Z | grep "SHA-1 hash"` and delete the
  keyless extras with `security delete-certificate -Z <hash>`.
- The team ID goes through `-Pcompose.desktop.mac.notarization.teamID=…`; the DSL `teamId` property
  does not exist in Compose 1.11.1.

### Linux (`.deb`), on a Linux host

```sh
sudo apt install -y openjdk-17-jdk fakeroot
./gradlew packageDeb
```

### Windows (`.msi`), on a Windows host

Install a JDK 17 and **WiX Toolset 3.x** — jpackage shells out to WiX's `candle.exe`/`light.exe`, and
WiX 4/5 will not work. `choco install wixtoolset` gets 3.14. Then, at the repo root:

```bat
gradlew.bat packageMsi
```

The unsigned installer lands at `build\compose\binaries\main\msi\RelayPony-2.0.0.msi`. It is not
code-signed, so Windows SmartScreen warns on first run — users click **More info -> Run anyway**.
Signing would require a separate Windows code-signing certificate.

### Publish

```sh
cp build/compose/binaries/main/dmg/RelayPony-*.dmg RelayPony-macOS.dmg
cp path/to/relaypony_*_amd64.deb RelayPony-linux.deb
cp path/to/RelayPony-*.msi RelayPony-windows.msi
gh release create v2.0.1 RelayPony-macOS.dmg RelayPony-linux.deb RelayPony-windows.msi \
  --repo norsehorse-dev/RelayPonyDesktop --title "RelayPony Desktop 2.0.1" --generate-notes
```
