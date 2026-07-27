# Releasing RelayPony Desktop

Each release ships installers for every platform: a signed + notarized **`.dmg`** (macOS); a **`.deb`**,
a portable **`.tar.gz`**, and an **`.AppImage`** (Linux); and an **`.msi`** (Windows). CI builds and
publishes the Linux and Windows assets on every tag; the macOS `.dmg` is notarized on a Mac and
uploaded to the release. An AUR package (`relaypony-bin`) tracks the Linux tarball; see below.

The website (relaypony.app/desktop.php) links to the **stable asset names** `RelayPony-macOS.dmg`,
`RelayPony-linux.deb`, `RelayPony-linux-x86_64.tar.gz`, `RelayPony-x86_64.AppImage`, and
`RelayPony-windows.msi` through `/releases/latest/download/…`, so every release must attach its assets
under exactly those names.

Bump `packageVersion` in `build.gradle.kts` before tagging a new version.

## Cutting a release

### 1. CI builds Linux + Windows and publishes (no secrets required)

Push a version tag. `.github/workflows/release.yml` builds the `.deb`, portable `.tar.gz`, and
`.AppImage` on an Ubuntu runner and the `.msi` on a Windows runner, then creates the GitHub Release
with those four assets. CI never signs or notarizes, so no repository secrets are needed.

```sh
git tag v2.0.2
git push origin v2.0.2
```

### 2. Notarize the macOS dmg on a Mac and upload it

CI does not build the `.dmg`. Notarize it locally and upload it to the release CI just created.
`source ~/.pgpony-release-env` first — that file exports `MACOS_SIGN_IDENTITY` (the certificate's full
name) and the three `NOTARIZATION_*` values, shared across the Pony-family apps.

```sh
source ~/.pgpony-release-env
./gradlew notarizeDmg -Pcompose.desktop.mac.notarization.teamID="$NOTARIZATION_TEAM_ID"
xcrun stapler validate build/compose/binaries/main/dmg/RelayPony-*.dmg
cp build/compose/binaries/main/dmg/RelayPony-*.dmg RelayPony-macOS.dmg
gh release upload v2.0.2 RelayPony-macOS.dmg --repo norsehorse-dev/RelayPonyDesktop
```

The release must exist before `gh release upload`, so run this once CI has published. Until the `.dmg`
is up, the site's macOS download link 404s — notarization takes a few minutes on Apple's side, so you
can start it while the tag build runs and upload the moment the release appears. `stapler validate`
printing "The validate action worked!" confirms it is signed, notarized, and stapled.

macOS notarization gotchas learned the hard way:

- `MACOS_SIGN_IDENTITY` must be the certificate **name**, not its SHA-1 hash — Compose looks it up by name.
- Only **one** `Developer ID Application` cert may be in the keychain, or codesign reports
  "multiple matching certificates." List them with
  `security find-certificate -a -c "Developer ID Application" -Z | grep "SHA-1 hash"` and delete the
  keyless extras with `security delete-certificate -Z <hash>`.
- The team ID goes through `-Pcompose.desktop.mac.notarization.teamID=…`; the DSL `teamId` property
  does not exist in Compose 1.11.1.

### 3. Publish the AUR package (Arch)

`packaging/aur/PKGBUILD` builds `relaypony-bin` from the release tarball, so a release carrying
`RelayPony-linux-x86_64.tar.gz` must exist first. Then bump `pkgver`, run `updpkgsums`, regenerate
`.SRCINFO`, and push to the AUR — full steps in `packaging/aur/README.md`.

## Building any installer by hand

Each installer can also be built directly on its own OS, e.g. to test before tagging.

### Linux (`.deb`, `.tar.gz`, `.AppImage`)

```sh
sudo apt install -y openjdk-17-jdk fakeroot desktop-file-utils
./gradlew packageDeb createDistributable
tar -czf RelayPony-linux-x86_64.tar.gz -C build/compose/binaries/main/app RelayPony
```

For the AppImage, assemble an AppDir from that app image and run `appimagetool`:

```sh
APPDIR=RelayPony.AppDir
rm -rf "$APPDIR"; mkdir -p "$APPDIR"
cp -r build/compose/binaries/main/app/RelayPony/* "$APPDIR/"
cp packaging/appimage/AppRun "$APPDIR/AppRun"; chmod +x "$APPDIR/AppRun"
cp packaging/appimage/relaypony.desktop "$APPDIR/relaypony.desktop"
cp packaging/relaypony.png "$APPDIR/relaypony.png"
wget -q https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x appimagetool-x86_64.AppImage
ARCH=x86_64 ./appimagetool-x86_64.AppImage --appimage-extract-and-run "$APPDIR" RelayPony-x86_64.AppImage
```

### Windows (`.msi`)

Install a JDK 17 and **WiX Toolset 3.x** — jpackage shells out to WiX's `candle.exe`/`light.exe`, and
WiX 4/5 will not work. `choco install wixtoolset` gets 3.14. Then, at the repo root:

```bat
gradlew.bat packageMsi
```

The unsigned installer lands at `build\compose\binaries\main\msi\RelayPony-<version>.msi`. It is not
code-signed, so Windows SmartScreen warns on first run — users click **More info -> Run anyway**.

### Publishing entirely by hand (no CI)

If you build all five on their own machines, attach them under the stable names in one shot:

```sh
gh release create v2.0.2 \
  RelayPony-macOS.dmg RelayPony-linux.deb RelayPony-linux-x86_64.tar.gz \
  RelayPony-x86_64.AppImage RelayPony-windows.msi \
  --repo norsehorse-dev/RelayPonyDesktop --title "RelayPony Desktop 2.0.2" --generate-notes
```
