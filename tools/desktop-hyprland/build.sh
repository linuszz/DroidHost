#!/bin/bash
set -euo pipefail
out=${1:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
kde=$here/../desktop-kde
# Hyprland nests through aquamarine's Wayland backend and renders via GBM-on-renderD0 + zink/Turnip,
# so it inherits the KDE pipeline's mesa pin: closure.py/pacman-db.py read ../desktop-kde/pins/mesa.
# hyprland/aquamarine/hyprcursor and the whole Hyprland suite live in Arch extra (Arch builder
# signed), so the closure resolves them from core/extra/alarm exactly like niri — no external repo.
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
echo "hyprland: $(wc -l < "$work/pkglist.txt") packages beyond the runtime's own"

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

# Shared pacman wrappers/hooks and sudo/su come from the KDE overlay: cp -a on the Linux builder
# preserves their exec bits and symlinks (sudoedit -> sudo, masked hooks -> /dev/null). The Hyprland
# overlay then adds only the DE-specific files (hyprland.conf, skel, kwin-compositor=opengl).
mkdir -p "$work/root/usr/share/droiddeck" "$work/root/usr/local/bin"
cp -a "$kde/overlay/usr/share/droiddeck/pacman" "$work/root/usr/share/droiddeck/"
cp -a "$kde/overlay/usr/local/bin/pacman" "$kde/overlay/usr/local/bin/su" \
      "$kde/overlay/usr/local/bin/sudo" "$kde/overlay/usr/local/bin/sudoedit" "$work/root/usr/local/bin/"
cp -a "$here/overlay/." "$work/root/"
mkdir -p "$work/root/usr/lib/droiddeck"
${CC_AARCH64:-aarch64-linux-gnu-gcc} -shared -fPIC -O2 -Wall -Wextra \
  -o "$work/root/usr/lib/droiddeck/libdroiddeck-root.so" "$kde/root/root.c" -ldl
${STRIP_AARCH64:-aarch64-linux-gnu-strip} --strip-unneeded "$work/root/usr/lib/droiddeck/libdroiddeck-root.so"
python3 "$kde/pacman-db.py" "$work/db" "$pkgs" "$work/pkglist.txt" "$here/runtime-packages.txt" \
  "$here/mirror.txt" "${DESKTOP_HYPR_RELEASE_TAG:-}" "$work/root" "$out/repo"

rm -rf "$work/root/usr/share/doc" "$work/root/usr/share/info" \
       "$work/root/usr/share/gtk-doc" "$work/root/usr/include" "$work/root/usr/lib/pkgconfig" \
       "$work/root/usr/lib/cmake" "$work/root/usr/share/help"
find "$work/root/usr/share/locale" -mindepth 1 -maxdepth 1 -type d ! -name 'en*' -exec rm -rf {} + 2>/dev/null || true
find "$work/root/usr/lib" -name '*.a' -delete
chmod -R u+rwX "$work/root"

sed 's#^[a-z]*/##' "$work/pkglist.txt" > "$out/desktop-hyprland.packages.txt"
tar -C "$work/root" --zstd -cf "$out/desktop-hyprland.tar.zst" .
sha256sum "$out/desktop-hyprland.tar.zst" | awk '{print $1}' > "$out/desktop-hyprland.sha256"
ls -l "$out/desktop-hyprland.tar.zst"