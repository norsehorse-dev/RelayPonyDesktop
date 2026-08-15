#!/bin/sh
# Wrap the jpackage launcher in an installed RelayPony app tree so it preloads the system
# libfreetype before the JVM starts. Without this, the bundled runtime's older libfreetype gets
# used by the native GTK file chooser (AWT FileDialog) and the dialog fails to render
# ("error occurred in libfreetype"). FreeType keeps backward ABI compatibility, so the system
# copy is safe for Java2D too. Applies to any format built from the jpackage app image:
# the portable tarball, the AppImage, and (after extraction) the .deb.
#
# Usage: wrap-launcher.sh <app-root>   where <app-root> holds bin/ and lib/app/
set -eu
root="$1"
launcher="$root/bin/RelayPony"
cfg="$root/lib/app/RelayPony.cfg"
[ -f "$launcher" ] || { echo "wrap-launcher: no launcher at $launcher" >&2; exit 1; }
[ -f "$cfg" ] || { echo "wrap-launcher: no cfg at $cfg" >&2; exit 1; }

# The jpackage launcher resolves its config by its own basename, so the renamed binary needs a
# matching .cfg alongside it.
mv "$launcher" "$root/bin/RelayPony.bin"
cp "$cfg" "$root/lib/app/RelayPony.bin.cfg"
cat > "$launcher" <<'WRAP'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
export LD_PRELOAD="libfreetype.so.6${LD_PRELOAD:+:$LD_PRELOAD}"
exec "$HERE/RelayPony.bin" "$@"
WRAP
chmod 0755 "$launcher"
