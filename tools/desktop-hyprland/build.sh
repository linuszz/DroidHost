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

# Rebuild aquamarine from source with four KGSL/nested fixes (aquamarine-nested.patch). Arch
# aquamarine 0.16.0 assumes a desktop DRM/KMS stack and breaks on the nested Wayland backend over a
# KGSL render node: (1) wl_registry_bind hardcodes versions ignoring the host's advertised v1
# (xdg_wm_base bind 6 poisons the connection) -> clamp every global to min(supported, advertised);
# (2) reopenDRMNode bails on a DRM lease errno other than EINVAL/EOPNOTSUPP -> fall back to opening
# the render node on any lease failure; (3) CGBMAllocator::create hard-fails when DRM_CAP_PRIME is
# not advertised (KGSL/Turnip still exports via gbm_bo_get_fd_for_plane) -> make the check advisory;
# (4) the allocator ctor assigns a possibly-NULL gbm backend name / drmGetDeviceNameFromFd2 result
# to std::string (strlen SEGV) -> guard both. Cross-compile against the just-extracted aarch64
# sysroot (headers still present) and overwrite the Arch package's library; a host
# hyprwayland-scanner generates the protocol bindings. Both use std::format, so they need gcc-14
# (ubuntu-24.04's default gcc-13 libstdc++ lacks <format>).
aq_ver=0.16.0
aq_sha256=42339c3096c9de6582c0d71bc86b7e63334a3faf6a2b917d385ad093caf66c3c
scanner_rev=62e62c1ca23da17612c6890d4ad2064f575643db
aq=$work/aq; mkdir -p "$aq/host"
# aquamarine's CMake generates a header from hwdata's pnp.ids at build time; hwdata is a base
# package absent from the DE closure, so extract it into the sysroot and drop it after the build.
hwdata_desc=$(find "$work/db/x_core" -maxdepth 1 -type d -name 'hwdata-*' | head -1)
hwdata_file=$(awk '/%FILENAME%/{getline; print; exit}' "$hwdata_desc/desc")
hwdata_sum=$(awk '/%SHA256SUM%/{getline; print; exit}' "$hwdata_desc/desc")
fetch "$work/hwdata.pkg" "$mirror/core/$hwdata_file"
[ "$(sha256sum "$work/hwdata.pkg" | cut -d' ' -f1)" = "$hwdata_sum" ] \
  || { echo "hwdata sha256 mismatch" >&2; exit 1; }
tar -xf "$work/hwdata.pkg" -C "$work/root" --no-same-owner --no-same-permissions \
  --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
fetch "$aq/aquamarine.tar.gz" "https://github.com/hyprwm/aquamarine/archive/v$aq_ver.tar.gz"
[ "$(sha256sum "$aq/aquamarine.tar.gz" | cut -d' ' -f1)" = "$aq_sha256" ] \
  || { echo "aquamarine source sha256 mismatch" >&2; exit 1; }
tar -xzf "$aq/aquamarine.tar.gz" -C "$aq"
patch -d "$aq/aquamarine-$aq_ver" -p1 < "$here/aquamarine-nested.patch"
fetch "$aq/scanner.tar.gz" "https://github.com/hyprwm/hyprwayland-scanner/archive/$scanner_rev.tar.gz"
tar -xzf "$aq/scanner.tar.gz" -C "$aq"
cmake -S "$aq/hyprwayland-scanner-$scanner_rev" -B "$aq/scanner-build" -G Ninja \
  -DCMAKE_C_COMPILER=gcc-14 -DCMAKE_CXX_COMPILER=g++-14 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$aq/host"
cmake --build "$aq/scanner-build"
cmake --install "$aq/scanner-build"
cat > "$aq/toolchain.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER aarch64-linux-gnu-gcc-14)
set(CMAKE_CXX_COMPILER aarch64-linux-gnu-g++-14)
set(CMAKE_SYSROOT $work/root)
set(CMAKE_FIND_ROOT_PATH $work/root)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(ENV{PKG_CONFIG_LIBDIR} "$work/root/usr/lib/pkgconfig:$work/root/usr/share/pkgconfig")
set(ENV{PKG_CONFIG_SYSROOT_DIR} "$work/root")
EOF
export PATH="$aq/host/bin:$PATH"
cmake -S "$aq/aquamarine-$aq_ver" -B "$aq/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$aq/toolchain.cmake" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_PREFIX_PATH="$aq/host" \
  -DOpenGL_GL_PREFERENCE=GLVND -DBUILD_TESTING=OFF
# Build only the library target: aquamarine adds its test executables unconditionally, and they
# link base packages (pcre2) plus a newer glibc/libstdc++ than the cross sysroot provides.
cmake --build "$aq/build" --target aquamarine
DESTDIR="$aq/install" cmake --install "$aq/build"
rm -f "$work/root/usr/lib/libaquamarine.so"*
cp -a "$aq/install/usr/lib/libaquamarine.so"* "$work/root/usr/lib/"
rm -rf "$work/root/usr/share/hwdata" "$work/root/usr/share/pkgconfig/hwdata.pc"

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