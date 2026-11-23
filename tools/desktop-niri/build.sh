#!/bin/bash
set -euo pipefail
out=${1:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
kde=$here/../desktop-kde
# niri needs no patched compositor (it nests through winit), but it renders via zink, so it
# inherits the KDE pipeline's mesa pin: closure.py/pacman-db.py read ../desktop-kde/pins/mesa.
source "$kde/mesa.env"
mirror=${ALARM_MIRROR:-https://fl.us.mirror.archlinuxarm.org/aarch64}
case $mirror in https://*) ;; *) echo "ALARM_MIRROR must be https" >&2; exit 1 ;; esac
builder=68B3537F39A313B3E574D06777193F152BDBE6A6
fetch() { curl -fsSL --proto '=https' --proto-redir '=https' --retry 6 --retry-delay 5 --retry-all-errors -o "$1" "$2"; }
work=$(mktemp -d); mkdir -p "$out" "$work/db" "$work/root"
pkgs=${PKG_CACHE:-$work/pkgs}; mkdir -p "$pkgs"
trap 'rm -rf "$work"' EXIT

for repo in core extra alarm; do
  fetch "$work/db/$repo.db" "$mirror/$repo/$repo.db"
  mkdir -p "$work/db/x_$repo" && tar -xzf "$work/db/$repo.db" -C "$work/db/x_$repo"
done

python3 "$kde/closure.py" "$work/db" "$here/runtime-packages.txt" "$here/seeds.txt" > "$work/pkglist.txt"
echo "niri: $(wc -l < "$work/pkglist.txt") packages beyond the runtime's own"

while read -r entry sum; do
  file=${entry#*/}
  url=$mirror/$entry
  [ "$file" != "$DESKTOP_MESA_PACKAGE" ] || url=$DESKTOP_MESA_URL
  [ -s "$pkgs/$file" ] || fetch "$pkgs/$file" "$url"
  fetch "$work/pkg.sig" "$url.sig"
  if ! gpgv --status-fd 1 --keyring "$kde/archlinuxarm-builder.gpg" "$work/pkg.sig" "$pkgs/$file" 2>/dev/null \
      | grep -Eq "^\[GNUPG:\] VALIDSIG .* $builder\$"; then
    echo "$file: no valid Arch Linux ARM Build System signature" >&2; rm -f "$pkgs/$file"; exit 1
  fi
  if [ "$(sha256sum "$pkgs/$file" | cut -d' ' -f1)" != "$sum" ]; then
    echo "$file: sha256 differs from the repository database's" >&2; rm -f "$pkgs/$file"; exit 1
  fi
  tar -xf "$pkgs/$file" -C "$work/root" --no-same-owner --no-same-permissions \
    --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
done < "$work/pkglist.txt"

cp -a "$here/overlay/." "$work/root/"

# DankMaterialShell (DMS): upstream's pre-built self-contained arm64 binary. release.yml builds
# dms-distropkg with -tags 'distro_binary withshell', embedding the QML shell and the DCommon
# submodule (bundled Material Symbols/Google Sans/FiraCode fonts, loaded via FontLoader) into one
# CGO_ENABLED=0 Go binary; distro_binary disables update/greeter for the immutable VM pack. The
# quickshell QML runtime comes from the pacman closure, so dms only needs quickshell on PATH.
dms_ver=2.1.2
dms_sha256=6cf71b33e9bade3fa96b30756598ad4f140d11b9925127a45a12911d15e4611f
dms=$work/dms; mkdir -p "$dms"
fetch "$dms/dms.gz" "https://github.com/AvengeMedia/DankMaterialShell/releases/download/v$dms_ver/dms-distropkg-arm64.gz"
[ "$(sha256sum "$dms/dms.gz" | cut -d' ' -f1)" = "$dms_sha256" ] \
  || { echo "dms-distropkg-arm64.gz sha256 mismatch" >&2; exit 1; }
mkdir -p "$work/root/usr/bin"
gunzip -c "$dms/dms.gz" > "$work/root/usr/bin/dms"
chmod 755 "$work/root/usr/bin/dms"

mkdir -p "$work/root/usr/lib/droiddeck"
${CC_AARCH64:-aarch64-linux-gnu-gcc} -shared -fPIC -O2 -Wall -Wextra \
  -o "$work/root/usr/lib/droiddeck/libdroiddeck-root.so" "$kde/root/root.c" -ldl
${STRIP_AARCH64:-aarch64-linux-gnu-strip} --strip-unneeded "$work/root/usr/lib/droiddeck/libdroiddeck-root.so"
python3 "$kde/pacman-db.py" "$work/db" "$pkgs" "$work/pkglist.txt" "$here/runtime-packages.txt" \
  "$here/mirror.txt" "${DESKTOP_NIRI_RELEASE_TAG:-}" "$work/root" "$out/repo"

rm -rf "$work/root/usr/share/doc" "$work/root/usr/share/info" \
       "$work/root/usr/share/gtk-doc" "$work/root/usr/include" "$work/root/usr/lib/pkgconfig" \
       "$work/root/usr/lib/cmake" "$work/root/usr/share/help"
find "$work/root/usr/share/locale" -mindepth 1 -maxdepth 1 -type d ! -name 'en*' -exec rm -rf {} + 2>/dev/null || true
find "$work/root/usr/lib" -name '*.a' -delete
chmod -R u+rwX "$work/root"

sed 's#^[a-z]*/##' "$work/pkglist.txt" > "$out/desktop-niri.packages.txt"
tar -C "$work/root" --zstd -cf "$out/desktop-niri.tar.zst" .
sha256sum "$out/desktop-niri.tar.zst" | awk '{print $1}' > "$out/desktop-niri.sha256"
ls -l "$out/desktop-niri.tar.zst"
