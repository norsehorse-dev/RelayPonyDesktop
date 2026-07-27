# AUR package (relaypony-bin)

`relaypony-bin` installs the prebuilt Linux desktop app (the same portable tarball
attached to each GitHub release) into `/opt/relaypony`, with a `relaypony` launcher
on `PATH` and a desktop entry. The bundled Java runtime means there is no `jre`/`jdk`
dependency.

## Publishing / updating on the AUR

The AUR repo holds only `PKGBUILD` and `.SRCINFO`; the tarball, `.desktop`, and icon
are fetched from the matching tagged release. To cut a new version:

1. Bump `pkgver` in `PKGBUILD` to the release tag (without the leading `v`). The tag
   must be one whose assets include `RelayPony-linux-x86_64.tar.gz`.
2. Fill in real checksums now that the release assets exist:
   `updpkgsums`
3. Regenerate metadata and test a clean build in a container or clean chroot:
   `makepkg --printsrcinfo > .SRCINFO && makepkg -si`
4. Push to the AUR (needs an AUR account with an SSH key):
   `git clone ssh://aur@aur.archlinux.org/relaypony-bin.git`, copy `PKGBUILD` and
   `.SRCINFO` in, then `git add`, `git commit`, `git push`.
